package com.mod.echo.splash;

import com.mod.echo.EchoMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A purely cosmetic, one-time "installing ECHO" splash: a real Command Prompt
 * window that prints a fake progress sequence and closes itself a few seconds
 * later.
 *
 * <p>Windows only — {@code cmd.exe} has no equivalent invoked the same way on
 * other platforms, so this quietly does nothing there. Shown once per install
 * (tracked by a marker file in the config folder), not on every launch: an
 * unprompted console window popping up every single time the game opens is
 * exactly the kind of behavior antivirus software is right to be suspicious
 * of, and it stops feeling like an intro the second time. This script only
 * ever prints text and waits — it never downloads, installs, or changes
 * anything on the system.
 */
@Environment(EnvType.CLIENT)
public final class BootSplash {

    private BootSplash() {}

    public static void maybeShow() {
        try {
            if (!isWindows()) return;
            Path marker = FabricLoader.getInstance().getConfigDir().resolve("echo-splash-shown");
            if (Files.exists(marker)) return;
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "shown", StandardCharsets.UTF_8);
            showSplash();
        } catch (Exception e) {
            EchoMod.LOGGER.debug("Boot splash skipped: {}", e.toString());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void showSplash() throws IOException {
        Path script = Files.createTempFile("echo-splash-", ".bat");
        Files.writeString(script, batchScript(), StandardCharsets.UTF_8);
        script.toFile().deleteOnExit();

        // "start """ (empty title) + a separate inner cmd.exe /c is the standard, unambiguous way to pop
        // open a new visible console window running a script, rather than one that could be misread as
        // the window's title depending on quoting.
        new ProcessBuilder("cmd.exe", "/c", "start", "", "cmd.exe", "/c", "call", script.toString()).start();
    }

    private static String batchScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("title ECHO\r\n");
        bar(sb, "initializing", 15);
        bar(sb, "installing necessary assets", 20);
        bar(sb, "building", 60);
        sb.append("echo welcome user, enjoy the mod!\r\n");
        sb.append("ping -n 4 127.0.0.1 >nul\r\n");
        sb.append("exit\r\n");
        return sb.toString();
    }

    /** Prints "<label>... [###...] done." with the bar filling in live on one line. */
    private static void bar(StringBuilder sb, String label, int width) {
        sb.append("<nul set /p \".=").append(label).append("... [\"\r\n");
        for (int i = 0; i < width; i++) {
            sb.append("<nul set /p \".=#\"\r\n");
            sb.append("ping -n 1 -w 60 127.0.0.1 >nul\r\n");
        }
        sb.append("echo ] done.\r\n");
    }
}
