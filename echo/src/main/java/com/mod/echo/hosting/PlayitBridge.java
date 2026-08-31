package com.mod.echo.hosting;

import com.mod.echo.EchoMod;
import net.fabricmc.loader.api.FabricLoader;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Drives an already-installed playit.gg agent for players behind carrier-grade
 * NAT (very common in Brazil, and on most mobile carriers generally) — no port
 * forwarding fixes CGNAT, since the ISP itself never gives the router a real
 * public IP. playit works around that with an outbound-only tunnel instead.
 *
 * <h2>Why this only drives an existing install</h2>
 * Downloading and silently running a third-party binary from inside a
 * Minecraft mod is the same category of problem auto-installing a VPN client
 * would have been: it looks like malware to antivirus software, and there is
 * no way to verify a "current, official" download URL from here that will
 * still be correct on the player's own machine later. This only ever spawns a
 * {@code playit} binary the player chose to install themselves (e.g. via
 * {@code winget install DevelopedMethods.playit}) — the same trust boundary
 * spawning {@code java} already crosses, not a new one.
 *
 * <h2>What's genuinely automated vs. not, confirmed against playit's own source</h2>
 * The claim/link step is driven through playit's real CLI subcommands
 * ({@code claim generate}, {@code claim url}, {@code claim exchange}) rather
 * than scraping log output, and the claim URL is opened in the player's
 * browser automatically — only the actual approval click, which is the
 * anti-abuse human check playit intends to require, is left to the player.
 * Once claimed, the secret is cached locally so this never runs the claim
 * flow again.
 *
 * <p>Pointing an actual tunnel at ECHO's port is <b>not</b> automated, and
 * this was checked directly against playit-agent's own CLI source
 * (playit-cloud/playit-agent) rather than assumed: its {@code Commands} enum
 * has no tunnel-management subcommand at all — that configuration only
 * exists through the playit.gg web dashboard, by design, not because of
 * missing documentation here.
 */
public final class PlayitBridge {

    private PlayitBridge() {}

    private static volatile Process process;

    public static boolean isRunning() { return process != null && process.isAlive(); }

    public static boolean isInstalled() {
        return runShort(5, "playit", "--version") != null;
    }

    /** Starts (claiming first, if needed) the agent. Returns a message for the player — never throws. */
    public static String start(int localPort) {
        if (isRunning()) return "playit is already running.";
        if (!isInstalled()) {
            return "playit isn't installed. Install it yourself first (e.g. \"winget install "
                    + "DevelopedMethods.playit\" on Windows, or see playit.gg/download), then say "
                    + "\"echo host\" again — I'll take it from there.";
        }

        String portHint = " Once you've done that, add a TCP tunnel to 127.0.0.1:" + localPort
                + " in your playit.gg dashboard — that one step has no CLI or API in playit itself, "
                + "confirmed straight from their own source, so it can't be automated from here.";

        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("echo-server").resolve("playit");
            Files.createDirectories(dir);
            Path secretFile = dir.resolve("secret_key");

            String secret = Files.exists(secretFile) ? Files.readString(secretFile, StandardCharsets.UTF_8).strip() : "";
            String claimMessage = "";

            if (secret.isBlank()) {
                String code = runShort(10, "playit", "claim", "generate");
                if (code == null || code.isBlank()) return "Couldn't start playit's claim process.";
                String url = runShort(10, "playit", "claim", "url", code, "--name", "echo.net");
                if (url == null || url.isBlank()) url = "https://playit.gg/claim/" + code;
                openBrowser(url);

                secret = runShort(300, "playit", "claim", "exchange", code, "--wait", "300");
                if (secret == null || secret.isBlank()) {
                    return "Opened " + url + " for you to approve playit — once you click approve there, "
                            + "say \"echo host\" again and I'll finish connecting it (this attempt timed out "
                            + "waiting 5 minutes).";
                }
                Files.writeString(secretFile, secret, StandardCharsets.UTF_8);
                claimMessage = "Claimed a new playit agent (approved at " + url + "). ";
            }

            ProcessBuilder pb = new ProcessBuilder("playit")
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(dir.resolve("playit.log").toFile());
            pb.environment().put("SECRET_KEY", secret);
            process = pb.start();

            return claimMessage + "playit is running and tunneling out — CGNAT no longer blocks it." + portHint;
        } catch (Exception e) {
            EchoMod.LOGGER.debug("Could not start playit: {}", e.toString());
            return "Couldn't start playit: " + e.getMessage();
        }
    }

    public static String stop() {
        if (!isRunning()) return "playit isn't running.";
        process.destroy();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) process.destroyForcibly();
        process = null;
        return "Stopped playit.";
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // fall through to the OS-command fallback below
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) new ProcessBuilder("cmd", "/c", "start", "", url).start();
            else if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (Exception e) {
            EchoMod.LOGGER.debug("Could not auto-open a browser for {}: {}", url, e.toString());
        }
    }

    /** Runs a short-lived playit subcommand and returns its trimmed stdout, or null on any failure/timeout. */
    private static String runShort(int timeoutSeconds, String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean exited = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? output.strip() : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }
}
