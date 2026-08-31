package com.mod.echo.hosting;

import com.mod.echo.EchoMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives an already-installed playit.gg agent for players behind carrier-grade
 * NAT (very common in Brazil and several other countries) — no port forwarding
 * exists that fixes CGNAT, since the ISP itself never gives the router a real
 * public IP. playit works around that with an outbound-only tunnel instead.
 *
 * <h2>Why this only drives an existing install</h2>
 * Downloading and silently running a third-party binary from inside a
 * Minecraft mod is the same category of problem as auto-installing a VPN
 * client would have been: it looks like malware to antivirus software, and
 * there is no way to verify a "current, official" download URL from here that
 * will still be correct on the player's own machine later. This only ever
 * spawns a {@code playit} binary the player chose to install themselves
 * (e.g. via {@code winget install DevelopedMethods.playit}) — the same trust
 * boundary as spawning {@code java} already crosses, not a new one.
 *
 * <h2>What's genuinely automated vs. not</h2>
 * playit's own claim step (visiting a one-time URL in a browser to link the
 * agent to an account) is an intentional anti-abuse measure and isn't meant
 * to be skippable — this surfaces that URL from the agent's own log the
 * moment it appears instead of making the player watch a console window.
 * Actually pointing a tunnel at ECHO's port, though, is done once through
 * playit's own web dashboard: their tunnel-configuration format isn't
 * documented clearly enough anywhere this could verify to risk guessing at
 * it — better to say so plainly than to ship a config file that silently
 * does nothing.
 */
public final class PlayitBridge {

    private PlayitBridge() {}

    private static final Pattern CLAIM_URL = Pattern.compile("https://playit\\.gg/claim/[A-Za-z0-9\\-_]+");

    private static volatile Process process;

    public static boolean isRunning() { return process != null && process.isAlive(); }

    public static boolean isInstalled() {
        try {
            Process p = new ProcessBuilder("playit", "--version").redirectErrorStream(true).start();
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

    /**
     * Starts the agent and watches its own log for the one-time claim URL, if this is a
     * fresh install. Returns a message for the player — never throws.
     */
    public static String start(int localPort) {
        if (isRunning()) return "playit is already running.";
        if (!isInstalled()) {
            return "playit isn't installed. Install it yourself first (e.g. \"winget install "
                    + "DevelopedMethods.playit\" on Windows, or see playit.gg/download), then say "
                    + "\"echo host\" again — I'll take it from there.";
        }
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("echo-server").resolve("playit");
            Files.createDirectories(dir);
            Path log = dir.resolve("playit.log");

            ProcessBuilder pb = new ProcessBuilder("playit")
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            process = pb.start();

            String claimUrl = watchForClaimUrl(log);
            String portHint = " Once claimed, add a TCP tunnel to 127.0.0.1:" + localPort
                    + " in your playit.gg dashboard to actually reach echo.net through it.";

            if (claimUrl != null) {
                return "playit needs a one-time claim — open " + claimUrl + " in your browser to link it "
                        + "to a playit.gg account." + portHint;
            }
            return "playit is running (already claimed on a previous run, most likely)." + portHint;
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

    /** Polls the agent's own log briefly for the claim URL it prints on a fresh install. */
    private static String watchForClaimUrl(Path log) {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) return null;
            try {
                if (Files.exists(log)) {
                    String text = Files.readString(log, StandardCharsets.UTF_8);
                    Matcher m = CLAIM_URL.matcher(text);
                    if (m.find()) return m.group();
                }
                Thread.sleep(500);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
