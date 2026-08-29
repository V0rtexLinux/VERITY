package com.mod.echo.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mod.echo.EchoMod;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Persistent, human-editable configuration for ECHO.
 *
 * Stored as {@code config/echo.json}.  Every field has a sane default, so a
 * missing or partially written file never stops the mod from loading — unknown
 * keys are ignored and missing keys fall back to the defaults below.
 */
public final class EchoConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("echo.json");

    private static EchoConfig instance;

    // ------------------------------------------------------------------ //
    //  AI backend                                                          //
    // ------------------------------------------------------------------ //

    /** Base URL of the local inference server. Empty = auto-detect. */
    public String aiBaseUrl = "";
    /** Model id to use. Empty = pick the best tool-capable model that is installed. */
    public String aiModel = "";
    /** Seconds to wait for a completion before giving up. */
    public int aiTimeoutSeconds = 120;
    /** Sampling temperature. */
    public double aiTemperature = 0.4;
    /** Maximum consecutive tool round-trips inside one request. */
    public int aiMaxToolRounds = 6;
    /** Number of chat turns kept in the rolling context window. */
    public int aiHistorySize = 24;
    /** Try to start a local backend automatically if none is reachable. */
    public boolean aiAutoStartBackend = true;
    /** Download the recommended model automatically when it is missing. */
    public boolean aiAutoPullModel = true;

    // ------------------------------------------------------------------ //
    //  Assistant behaviour                                                 //
    // ------------------------------------------------------------------ //

    /** Words that wake ECHO up in chat, comma separated. */
    public String wakeWords = "hey echo,ei echo,echo";
    /** Reply language: "auto", "en" or "pt". */
    public String language = "auto";
    /** Personality: "friendly", "concise", "teacher" or "pro". */
    public String personality = "friendly";
    /** Let ECHO run world-changing tools (build, give, teleport, commands). */
    public boolean allowWorldTools = true;
    /** Let ECHO run raw server commands. Requires allowWorldTools. */
    public boolean allowRawCommands = false;
    /** Show the small status overlay in the corner of the HUD. */
    public boolean showHud = true;
    /** Enable the offline voice listener (needs Vosk in the mods folder). */
    public boolean voiceEnabled = true;
    /** Spawn the floating companion orb next to the player. */
    public boolean companionEnabled = true;
    /** Let ECHO look things up on the internet (DuckDuckGo/Wikipedia, no key needed) for
     *  questions outside Minecraft or outside what it already knows. */
    public boolean webSearchEnabled = true;

    // ------------------------------------------------------------------ //
    //  Settings auto-tuner                                                 //
    // ------------------------------------------------------------------ //

    /** Let ECHO adjust Minecraft's video/gameplay options. */
    public boolean settingsTunerEnabled = true;
    /** Re-tune automatically when joining a world or server. */
    public boolean settingsTunerAutoOnJoin = false;
    /** Ask before applying, instead of applying straight away. */
    public boolean settingsTunerConfirm = true;
    /** Target frame rate the tuner aims for. */
    public int settingsTunerTargetFps = 60;

    // ------------------------------------------------------------------ //
    //  Loading / saving                                                    //
    // ------------------------------------------------------------------ //

    public static EchoConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static synchronized void load() {
        EchoConfig cfg = new EchoConfig();
        try {
            if (Files.exists(FILE)) {
                String json = Files.readString(FILE, StandardCharsets.UTF_8);
                EchoConfig parsed = GSON.fromJson(json, EchoConfig.class);
                if (parsed != null) cfg = parsed;
                EchoMod.LOGGER.info("Loaded configuration from {}", FILE);
            } else {
                EchoMod.LOGGER.info("No configuration found, writing defaults to {}", FILE);
            }
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Could not read echo.json ({}), falling back to defaults.", e.getMessage());
        }
        cfg.clamp();
        instance = cfg;
        save();
    }

    public static synchronized void save() {
        if (instance == null) return;
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Could not write echo.json: {}", e.getMessage());
        }
    }

    /** Keep every numeric field inside a range that cannot break the mod. */
    private void clamp() {
        aiTimeoutSeconds = Math.max(10, Math.min(600, aiTimeoutSeconds));
        aiTemperature    = Math.max(0.0, Math.min(2.0, aiTemperature));
        aiMaxToolRounds  = Math.max(1, Math.min(16, aiMaxToolRounds));
        aiHistorySize    = Math.max(4, Math.min(200, aiHistorySize));
        settingsTunerTargetFps = Math.max(20, Math.min(480, settingsTunerTargetFps));
        if (wakeWords == null || wakeWords.isBlank()) wakeWords = "hey echo,ei echo,echo";
        if (language == null || language.isBlank())   language = "auto";
        if (personality == null || personality.isBlank()) personality = "friendly";
        if (aiBaseUrl == null) aiBaseUrl = "";
        if (aiModel == null)   aiModel = "";
        // Raw command execution is meaningless without the world tools it builds on.
        if (!allowWorldTools) allowRawCommands = false;
    }

    /** Wake words as a lower-case array, longest first so "hey echo" wins over "echo". */
    public String[] wakeWordList() {
        String[] raw = wakeWords.toLowerCase(Locale.ROOT).split(",");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String w : raw) {
            String t = w.strip();
            if (!t.isEmpty()) out.add(t);
        }
        out.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return out.toArray(new String[0]);
    }

    /** Apply a single {@code key=value} edit at runtime. Returns a human-readable result. */
    public String applyEdit(String key, String value) {
        String k = key.toLowerCase(Locale.ROOT).replace("-", "_").replace(".", "_");
        try {
            switch (k) {
                case "model", "ai_model"          -> aiModel = value;
                case "base_url", "ai_base_url"    -> aiBaseUrl = value;
                case "temperature"                -> aiTemperature = Double.parseDouble(value);
                case "timeout"                    -> aiTimeoutSeconds = Integer.parseInt(value);
                case "history"                    -> aiHistorySize = Integer.parseInt(value);
                case "language", "lang"           -> language = value;
                case "personality"                -> personality = value;
                case "wake_words"                 -> wakeWords = value;
                case "hud", "show_hud"            -> showHud = parseBool(value);
                case "voice", "voice_enabled"     -> voiceEnabled = parseBool(value);
                case "companion"                  -> companionEnabled = parseBool(value);
                case "web_search"                 -> webSearchEnabled = parseBool(value);
                case "world_tools"                -> allowWorldTools = parseBool(value);
                case "raw_commands"               -> allowRawCommands = parseBool(value);
                case "tuner"                      -> settingsTunerEnabled = parseBool(value);
                case "tuner_auto"                 -> settingsTunerAutoOnJoin = parseBool(value);
                case "tuner_confirm"              -> settingsTunerConfirm = parseBool(value);
                case "target_fps"                 -> settingsTunerTargetFps = Integer.parseInt(value);
                default -> {
                    return "Unknown setting '" + key + "'.";
                }
            }
        } catch (NumberFormatException e) {
            return "'" + value + "' is not a valid number for " + key + ".";
        }
        clamp();
        save();
        return key + " = " + value;
    }

    private static boolean parseBool(String v) {
        String s = v.strip().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("on") || s.equals("yes") || s.equals("1") || s.equals("sim");
    }

    /** A compact multi-line summary used by the {@code echo config} chat command. */
    public String summary() {
        JsonObject o = GSON.toJsonTree(this).getAsJsonObject();
        StringBuilder sb = new StringBuilder();
        for (String key : o.keySet()) {
            sb.append("  ").append(key).append(" = ").append(o.get(key).toString()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
