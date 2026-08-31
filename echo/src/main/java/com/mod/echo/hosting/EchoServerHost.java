package com.mod.echo.hosting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mod.echo.EchoMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Opens "echo.net" — ECHO's own private, whitelisted world — permanently
 * whenever the platform allows it: a real separate dedicated server process,
 * with its own save, that keeps running after the player leaves or closes
 * the game entirely.
 *
 * <h2>The fallback, and why it exists</h2>
 * Spawning a second JVM process needs a real desktop-style OS process model.
 * Mobile launchers (PojavLauncher-style apps, including Mojo Launcher) run
 * the game inside their own app sandbox and very likely cannot do this.
 * {@link #canSpawnProcess()} checks for real before attempting anything; when
 * it fails, this falls back to opening the player's current session to LAN
 * instead (the same "echo.net" name and mod-only gate) — strictly worse
 * (it dies the moment the player leaves the world, exactly like vanilla's own
 * "Open to LAN" always has), but still usable rather than a dead end. The
 * very first time that fallback runs, creating the save itself needs one
 * click through Minecraft's own "Create New World" screen — recreating that
 * screen's internal setup by hand from mod code would mean guessing several
 * interlocking, version-sensitive internal constructors with no way to
 * verify any of them; delegating to the real screen sidesteps that risk
 * entirely. Every later "echo host" reopens it with zero clicks.
 *
 * <h2>What "echo.net" means</h2>
 * A display name (MOTD / level name) — not a real internet domain. That
 * would mean actually owning that DNS name and forwarding a router port,
 * neither of which mod code can do. You still connect by IP.
 *
 * <h2>Honesty about what's verified</h2>
 * None of this can be exercised in the sandboxed environment it was written
 * in (no ability to launch a real dedicated server or spawn a second
 * process from there), but the client-side calls (IntegratedServer,
 * WorldOpenFlows#openWorld, CreateWorldScreen#openFresh, publishServer) are
 * checked against real, currently-published Fabric mod source targeting
 * this exact Minecraft version — not a guess. The dedicated-server bootstrap
 * (Fabric Meta's server-jar endpoint, the config files it writes) is still
 * running for the first time on the player's own device. Every failure path
 * reports specifically what went wrong instead of pretending success.
 */
@Environment(EnvType.CLIENT)
public final class EchoServerHost {

    private EchoServerHost() {}

    // Must match gradle.properties — this is the version echo.net's own copy of the mod runs.
    private static final String MC_VERSION = "26.1.2";
    private static final String LOADER_VERSION = "0.18.4";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static volatile Process process;
    private static volatile int port = -1;

    public static boolean isRunning() { return process != null && process.isAlive(); }

    /** Shown to the player before hosting starts. Only strictly needed when a real server jar gets downloaded
     *  (the dedicated path), but shown either way since there's no way to know in advance which path will run. */
    public static String eulaNotice() {
        return "Hospedar echo.net aceita automaticamente o EULA da Mojang em seu nome "
                + "(minecraft.net/eula) — a mesma licença que você já aceitou pra jogar.";
    }

    private record Attempt(String dedicatedResult, String fallbackNotice) {}

    public static CompletableFuture<String> host() {
        if (isRunning()) {
            return CompletableFuture.completedFuture("echo.net is already running at 127.0.0.1:" + port + ".");
        }

        return CompletableFuture.supplyAsync(EchoServerHost::attemptDedicated)
                .thenCompose(attempt -> attempt.dedicatedResult() != null
                        ? CompletableFuture.completedFuture(attempt.dedicatedResult())
                        : fallBackToLan(attempt.fallbackNotice()));
    }

    public static String stop() {
        if (isRunning()) {
            process.destroy();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) process.destroyForcibly();
            process = null;
            port = -1;
            return "Stopped echo.net.";
        }

        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null && EchoPrivateWorld.is(server) && server.isPublished()) {
            return "echo.net is running in LAN mode, which has no way to close it separately — "
                    + "leave the world (Save and Quit to Title) to stop it.";
        }
        return "echo.net isn't running.";
    }

    // ------------------------------------------------------------------ //
    //  Primary: a real, separate, permanent dedicated server               //
    // ------------------------------------------------------------------ //

    private static synchronized Attempt attemptDedicated() {
        if (!canSpawnProcess()) {
            return new Attempt(null,
                    "This environment can't start a separate server process — common on Android/launchers like "
                            + "Mojo, since they run the game inside their own app sandbox with no normal OS "
                            + "process rights.");
        }
        try {
            return new Attempt(hostDedicatedSync(), null);
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Could not start a dedicated echo.net: {}", e.toString());
            return new Attempt(null, "Couldn't start a separate echo.net process (" + e.getMessage() + ").");
        }
    }

    private static String hostDedicatedSync() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "I need to know who's hosting — try that again once you're in a world.";
        UUID ownerId = mc.player.getUUID();
        String ownerName = mc.player.getName().getString();

        Path root = FabricLoader.getInstance().getGameDir().resolve("echo-server");
        Files.createDirectories(root);

        Path launchJar = root.resolve("fabric-server-launch.jar");
        if (Files.notExists(launchJar) || Files.size(launchJar) == 0) {
            EchoMod.LOGGER.info("Downloading echo.net's server launcher (first time only)...");
            downloadServerLauncher(launchJar);
        }

        int chosenPort = pickFreePort();
        writeEula(root);
        writeServerProperties(root, chosenPort);
        writeWhitelistAndOps(root, ownerId, ownerName);
        copyServerMods(root);

        ProcessBuilder pb = new ProcessBuilder(javaBinary(), "-Xmx2G", "-jar", "fabric-server-launch.jar", "nogui")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(root.resolve("echo-host.log").toFile());
        process = pb.start();
        port = chosenPort;

        if (!waitForPort("127.0.0.1", chosenPort, Duration.ofSeconds(120))) {
            return "echo.net didn't come up in time — check echo-server/echo-host.log and "
                    + "echo-server/logs/latest.log for what went wrong.";
        }

        return "echo.net is up on port " + chosenPort + " and stays running even after you log off. "
                + "On this PC, join at 127.0.0.1:" + chosenPort + ". To join from outside your network with a "
                + "real address — like a domain instead of an IP — forward port " + chosenPort + " (TCP) to this "
                + "PC on your router, then point that domain's DNS at your public IP; neither of those is "
                + "something I can do from inside the game. Only whitelisted accounts with the ECHO mod "
                + "installed can get in either way.";
    }

    /**
     * The Fabric Meta "server/jar" endpoint is the same one the official Fabric
     * installer uses for "generate a server" — it hands back a ready-to-run
     * launcher that downloads the matching Mojang server jar itself on first
     * boot, so this never has to resolve that URL by hand.
     */
    private static void downloadServerLauncher(Path dest) throws IOException, InterruptedException {
        String installerVersion = latestFabricInstallerVersion();
        String url = "https://meta.fabricmc.net/v2/versions/loader/" + MC_VERSION + "/" + LOADER_VERSION
                + "/" + installerVersion + "/server/jar";
        downloadTo(url, dest);
    }

    private static String latestFabricInstallerVersion() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://meta.fabricmc.net/v2/versions/installer"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IOException("Fabric meta returned HTTP " + res.statusCode());
        JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
        if (arr.isEmpty()) throw new IOException("Fabric meta listed no installer versions.");
        return arr.get(0).getAsJsonObject().get("version").getAsString();
    }

    private static void downloadTo(String url, Path dest) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<Path> res = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (res.statusCode() / 100 != 2) {
            Files.deleteIfExists(dest);
            throw new IOException("download failed with HTTP " + res.statusCode() + " from " + url);
        }
    }

    private static boolean canSpawnProcess() {
        try {
            Process p = new ProcessBuilder(javaBinary(), "-version").redirectErrorStream(true).start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String javaBinary() {
        String home = System.getProperty("java.home");
        if (home == null || home.isBlank()) return "java";
        Path candidate = Path.of(home, "bin", isWindows() ? "java.exe" : "java");
        return Files.isExecutable(candidate) ? candidate.toString() : "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Prefers Minecraft's real default port. That matters beyond convention here:
     * anyone port-forwarding this on a router, or pointing a real domain at it with a
     * plain A record (no SRV record), needs a fixed, known port — 25565 is the one every
     * "how to host a Minecraft server" guide and every client assumes when none is given.
     */
    private static int pickFreePort() {
        if (isPortFree(25565)) return 25565;
        for (int candidate = 25566; candidate < 25600; candidate++) {
            if (isPortFree(candidate)) return candidate;
        }
        return 25565;
    }

    private static boolean isPortFree(int candidate) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("0.0.0.0", candidate));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean waitForPort(String host, int port, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) return false; // the process exited before ever opening the port
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 1000);
                return true;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static void writeEula(Path root) throws IOException {
        Files.writeString(root.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
    }

    private static void writeServerProperties(Path root, int port) throws IOException {
        String props = String.join("\n",
                "motd=" + escapeProps(EchoPrivateWorld.MOTD),
                "white-list=true",
                "enforce-whitelist=true",
                "online-mode=true",
                "max-players=8",
                "level-name=" + EchoPrivateWorld.LEVEL_NAME,
                "server-port=" + port,
                "spawn-protection=0",
                "allow-flight=true",
                "");
        Files.writeString(root.resolve("server.properties"), props, StandardCharsets.UTF_8);
    }

    private static void writeWhitelistAndOps(Path root, UUID uuid, String name) throws IOException {
        String safeName = name.replace("\"", "");
        Files.writeString(root.resolve("whitelist.json"),
                "[{\"uuid\":\"" + uuid + "\",\"name\":\"" + safeName + "\"}]", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("ops.json"),
                "[{\"uuid\":\"" + uuid + "\",\"name\":\"" + safeName
                        + "\",\"level\":4,\"bypassesPlayerLimit\":false}]", StandardCharsets.UTF_8);
    }

    /** Copies every non-client-only mod jar so echo.net runs with the same mods as the client. */
    private static void copyServerMods(Path root) throws IOException {
        Path destMods = root.resolve("mods");
        Files.createDirectories(destMods);
        Path clientMods = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.isDirectory(clientMods)) return;

        try (Stream<Path> jars = Files.list(clientMods)) {
            for (Path jar : jars.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                if (isClientOnlyMod(jar)) continue;
                Files.copy(jar, destMods.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static boolean isClientOnlyMod(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            ZipEntry entry = jf.getEntry("fabric.mod.json");
            if (entry == null) return false; // not a fabric mod (a plain library) — safe to include
            String text;
            try (var in = jf.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonObject meta = JsonParser.parseString(text).getAsJsonObject();
            return meta.has("environment") && "client".equalsIgnoreCase(meta.get("environment").getAsString());
        } catch (Exception e) {
            return false; // unreadable — safer to include it than silently drop something needed
        }
    }

    private static String escapeProps(String s) {
        return s.replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=");
    }

    // ------------------------------------------------------------------ //
    //  Fallback: LAN, tied to the current session                         //
    // ------------------------------------------------------------------ //

    private static CompletableFuture<String> fallBackToLan(String notice) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                result.complete(notice + " Opening echo.net as a LAN world instead — it will only stay up "
                        + "while you're in it.\n" + hostLanOnClientThread(mc));
            } catch (Exception e) {
                EchoMod.LOGGER.warn("LAN fallback for echo.net also failed: {}", e.toString());
                result.complete(notice + " The LAN fallback also failed: " + e.getMessage());
            }
        });
        return result;
    }

    private static String hostLanOnClientThread(Minecraft mc) throws Exception {
        IntegratedServer current = mc.getSingleplayerServer();
        if (mc.hasSingleplayerServer() && current != null && EchoPrivateWorld.is(current)) {
            return publishToLan(mc);
        }

        Screen previous = mc.screen;
        if (mc.getLevelSource().levelExists(EchoPrivateWorld.LEVEL_NAME)) {
            mc.createWorldOpenFlows().openWorld(EchoPrivateWorld.LEVEL_NAME, () -> mc.setScreen(previous));
            return "Opening echo.net...";
        }

        CreateWorldScreen.openFresh(mc, () -> mc.setScreen(previous));
        return "Preciso de um clique seu, só essa primeira vez: no menu que abriu, coloque o nome do mundo "
                + "exatamente como \"" + EchoPrivateWorld.LEVEL_NAME + "\" e clique em Criar Novo Mundo — assim "
                + "que ele carregar eu já abro pro LAN e travo pra só quem tem o mod, sozinho.";
    }

    /** Called automatically once echo.net (LAN mode) finishes loading — see EchoModClient's join hook. */
    public static String publishToLan(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return "echo.net isn't the active world.";
        }
        if (server.isPublished()) {
            return "echo.net is already open to LAN.";
        }
        boolean ok = server.publishServer(GameType.SURVIVAL, false, findFreeLanPort());
        if (!ok) {
            return "Couldn't open echo.net to LAN — check the log for what blocked it.";
        }
        return "echo.net is open to LAN — it should show up automatically in your multiplayer server list. "
                + "Only accounts with the ECHO mod installed can actually get in. This stops the moment you "
                + "leave the world.";
    }

    private static int findFreeLanPort() {
        int candidate = pickFreePort();
        return candidate == 25565 ? 0 : candidate; // 0 lets vanilla pick if every candidate above was taken
    }
}
