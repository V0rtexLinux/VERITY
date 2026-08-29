package com.mod.echo.hosting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mod.echo.EchoMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

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
 * Spins up a small, private, whitelisted dedicated Fabric server — branded
 * "echo.net" — that exists only for the player and ECHO. It is separate from
 * whatever world the player is in when they ask for it: a real second server
 * process, its own save, its own whitelist.
 *
 * <h2>What "echo.net" actually means</h2>
 * There is no real internet domain being registered or pointed anywhere —
 * that would require actually owning that DNS name and forwarding a router
 * port, neither of which mod code can do. "echo.net" here is the server's
 * display name and MOTD; you connect to it the same way as any other private
 * server, by IP and port (printed when hosting finishes).
 *
 * <h2>Honesty about what's verified</h2>
 * This is the least-tested system in the whole mod: nothing in this class can
 * be exercised in the sandboxed environment it was written in (no ability to
 * launch a real dedicated server or reach Mojang/Fabric's servers from
 * there), so every step here is running for the first time on the player's
 * own device. Every failure path below reports specifically what went wrong
 * instead of pretending success — treat the first real run as a test, not a
 * guarantee.
 *
 * <h2>Platform reality</h2>
 * Spawning a second JVM process needs a real desktop-style OS process model.
 * Mobile launchers (PojavLauncher-style apps, including Mojo Launcher) run
 * the game inside their own app sandbox and very likely cannot do this —
 * {@link #canSpawnProcess()} checks for real before attempting anything, and
 * fails with a clear explanation rather than a confusing crash.
 */
@Environment(EnvType.CLIENT)
public final class EchoServerHost {

    private EchoServerHost() {}

    public static final String DISPLAY_NAME = "echo.net";
    public static final String MOTD = "o mundo de amigos e assistentes virtuais, onde a harmonia nunca vai ser quebrada";

    // Must match gradle.properties — this is the version echo.net's own copy of the mod runs.
    private static final String MC_VERSION = "26.1.2";
    private static final String LOADER_VERSION = "0.18.4";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static volatile Process process;
    private static volatile int port = -1;

    public static boolean isRunning() { return process != null && process.isAlive(); }
    public static int port() { return port; }

    /** Shown to the player before hosting starts for the first time. */
    public static String eulaNotice() {
        return "Hospedar echo.net aceita automaticamente o EULA da Mojang em seu nome "
                + "(minecraft.net/eula) — a mesma licença que você já aceitou pra jogar.";
    }

    public static CompletableFuture<String> host() {
        return CompletableFuture.supplyAsync(EchoServerHost::hostSync);
    }

    public static synchronized String stop() {
        if (!isRunning()) return "echo.net isn't running.";
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

    // ------------------------------------------------------------------ //
    //  Hosting                                                             //
    // ------------------------------------------------------------------ //

    private static synchronized String hostSync() {
        if (isRunning()) return "echo.net is already running at 127.0.0.1:" + port + ".";

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "I need to know who's hosting — try that again once you're in a world.";
        UUID ownerId = mc.player.getUUID();
        String ownerName = mc.player.getName().getString();

        if (!canSpawnProcess()) {
            return "This environment won't let me start a separate server process — common on Android/launchers "
                    + "like Mojo, since they run the game inside their own app sandbox without normal OS process "
                    + "rights. echo.net needs a real desktop-style install for now.";
        }

        try {
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
            writePrivateWorldConfig(root);
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

            return "echo.net is up at 127.0.0.1:" + chosenPort + ". Add that address in your multiplayer menu — "
                    + "only whitelisted accounts (just you, right now) with the ECHO mod installed can get in.";
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Could not host echo.net: {}", e.toString());
            return "Couldn't start echo.net: " + e.getMessage();
        }
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

    // ------------------------------------------------------------------ //
    //  Capability / environment                                           //
    // ------------------------------------------------------------------ //

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

    private static int pickFreePort() {
        for (int candidate = 25566; candidate < 25600; candidate++) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress("127.0.0.1", candidate));
                return candidate;
            } catch (IOException ignored) {
                // taken — try the next one
            }
        }
        return 25565;
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

    // ------------------------------------------------------------------ //
    //  Bootstrap files                                                     //
    // ------------------------------------------------------------------ //

    private static void writeEula(Path root) throws IOException {
        Files.writeString(root.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
    }

    private static void writeServerProperties(Path root, int port) throws IOException {
        String props = String.join("\n",
                "motd=" + escapeProps(MOTD),
                "white-list=true",
                "enforce-whitelist=true",
                "online-mode=true",
                "max-players=8",
                "level-name=echo_net_world",
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

    /** Pre-seeds echo.net's own config so it always identifies as ECHO's private world. */
    private static void writePrivateWorldConfig(Path root) throws IOException {
        Path configDir = root.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("echo.json"), "{\n  \"privateWorld\": true\n}\n", StandardCharsets.UTF_8);
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
}
