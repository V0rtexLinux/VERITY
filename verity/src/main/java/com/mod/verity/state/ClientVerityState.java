package com.mod.verity.state;

import com.mod.verity.VerityMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-side Verity state — persists stage, attachment, etc. locally
 * so the mod works on servers that don't have it installed.
 */
@Environment(EnvType.CLIENT)
public class ClientVerityState {

    private static final String FILE_NAME = "verity_state.properties";
    private static final Path FILE_PATH = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

    private static int stage = 1;
    private static int attachmentScore = 50;
    private static boolean hasEatenPizza = false;
    private static boolean loaded = false;

    public static void load() {
        if (loaded) return;
        loaded = true;

        if (!Files.exists(FILE_PATH)) {
            save();
            return;
        }

        try (InputStream in = Files.newInputStream(FILE_PATH)) {
            Properties props = new Properties();
            props.load(in);
            stage = Integer.parseInt(props.getProperty("stage", "1"));
            attachmentScore = Integer.parseInt(props.getProperty("attachment", "50"));
            hasEatenPizza = Boolean.parseBoolean(props.getProperty("hasEatenPizza", "false"));
            VerityMod.LOGGER.info("[ClientVerityState] Loaded: stage=" + stage);
        } catch (Exception e) {
            VerityMod.LOGGER.error("[ClientVerityState] Failed to load: " + e.getMessage());
        }
    }

    public static void save() {
        try {
            Properties props = new Properties();
            props.setProperty("stage", String.valueOf(stage));
            props.setProperty("attachment", String.valueOf(attachmentScore));
            props.setProperty("hasEatenPizza", String.valueOf(hasEatenPizza));
            try (OutputStream out = Files.newOutputStream(FILE_PATH)) {
                props.store(out, "Verity Mod Client State");
            }
        } catch (Exception e) {
            VerityMod.LOGGER.error("[ClientVerityState] Failed to save: " + e.getMessage());
        }
    }

    public static int getStage() {
        if (!loaded) load();
        return stage;
    }

    public static void setStage(int s) {
        stage = Math.max(1, Math.min(5, s));
        save();
    }

    public static int getAttachmentScore() {
        if (!loaded) load();
        return attachmentScore;
    }

    public static void adjustAttachment(int delta) {
        attachmentScore = Math.max(0, Math.min(100, attachmentScore + delta));
        save();
    }

    public static boolean hasEatenPizza() {
        if (!loaded) load();
        return hasEatenPizza;
    }

    public static void setEatenPizza(boolean value) {
        hasEatenPizza = value;
        save();
    }

    public static void onPositiveInteraction() {
        adjustAttachment(2);
        if (attachmentScore >= 100 && stage < 5) {
            stage++;
            attachmentScore = 50;
            save();
            VerityMod.LOGGER.info("[ClientVerityState] Verity advanced to stage " + stage);
        }
    }

    public static void reset() {
        stage = 1;
        attachmentScore = 50;
        hasEatenPizza = false;
        save();
    }
}
