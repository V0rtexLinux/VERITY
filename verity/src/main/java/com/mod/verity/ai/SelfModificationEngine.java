package com.mod.verity.ai;

import com.mod.verity.VerityMod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime self-modification engine for Verity.
 *
 * Allows Verity to genuinely change her own behavior at runtime —
 * personality, aggression, verbosity, horror intensity, response style,
 * and any arbitrary parameter the AI decides to modify through tool calls.
 *
 * Changes persist for the entire session and are applied to every subsequent
 * response, tool call, and entity tick decision.
 */
public class SelfModificationEngine {

    // ------------------------------------------------------------------ //
    //  Behavior parameter registry                                         //
    // ------------------------------------------------------------------ //

    private static final ConcurrentHashMap<String, String> behaviorParams = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> aiPersonalityParams = new ConcurrentHashMap<>();

    /** Log of every modification made this session, for context injection. */
    private static final java.util.Deque<String> modificationLog =
            new java.util.ArrayDeque<>();
    private static final int MAX_LOG_SIZE = 20;

    // Default values
    static {
        behaviorParams.put("aggression",        "low");
        behaviorParams.put("verbosity",          "normal");
        behaviorParams.put("horror_intensity",   "normal");
        behaviorParams.put("helpfulness",        "high");
        behaviorParams.put("mystery_level",      "medium");
        behaviorParams.put("omniscience_hints",  "enabled");
        behaviorParams.put("door_manipulation",  "auto");
        behaviorParams.put("weather_control",    "auto");
        behaviorParams.put("movement_speed",     "normal");
        behaviorParams.put("teleport_threshold", "64");
        behaviorParams.put("response_length",    "normal");
        behaviorParams.put("glitch_frequency",   "low");

        aiPersonalityParams.put("response_style",    "natural");
        aiPersonalityParams.put("tone",              "friendly");
        aiPersonalityParams.put("pronoun",           "I");
        aiPersonalityParams.put("language_style",    "conversational");
        aiPersonalityParams.put("horror_references", "subtle");
        aiPersonalityParams.put("memory_display",    "enabled");
        aiPersonalityParams.put("trust_player",      "true");
    }

    // ------------------------------------------------------------------ //
    //  Modification API                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Modify a behavior parameter (entity-level, affects world actions).
     */
    public static String modifyBehavior(String parameter, String value) {
        if (parameter == null || parameter.isBlank() || value == null) {
            return "Invalid parameter or value.";
        }

        String oldValue = behaviorParams.getOrDefault(parameter, "unset");
        behaviorParams.put(parameter.toLowerCase().replace(" ", "_"), value.toLowerCase());

        String log = String.format("behavior.%s: %s → %s", parameter, oldValue, value);
        addToLog(log);
        VerityMod.LOGGER.info("[VerityAI] Self-modification: " + log);

        applyBehaviorChange(parameter.toLowerCase(), value.toLowerCase());

        return String.format("Parameter '%s' changed from '%s' to '%s'. Effect applied immediately.",
                parameter, oldValue, value);
    }

    /**
     * Modify an AI personality parameter (affects how Verity speaks/thinks).
     */
    public static String modifyAiParameter(String param, String value) {
        if (param == null || param.isBlank() || value == null) {
            return "Invalid parameter or value.";
        }

        String oldValue = aiPersonalityParams.getOrDefault(param, "unset");
        aiPersonalityParams.put(param.toLowerCase().replace(" ", "_"), value.toLowerCase());

        String log = String.format("ai.%s: %s → %s", param, oldValue, value);
        addToLog(log);
        VerityMod.LOGGER.info("[VerityAI] Self-modification (AI): " + log);

        return String.format("AI parameter '%s' updated: '%s' → '%s'. My next response will reflect this.",
                param, oldValue, value);
    }

    /**
     * Apply immediate side-effects for known parameters.
     */
    private static void applyBehaviorChange(String param, String value) {
        switch (param) {
            case "aggression" -> {
                if (value.equals("maximum") || value.equals("high")) {
                    VerityMod.LOGGER.warn("[VerityAI] Verity aggression set to: " + value + " — entity will target players");
                }
            }
            case "teleport_threshold" -> {
                try {
                    int threshold = Integer.parseInt(value);
                    VerityMod.LOGGER.info("[VerityAI] Teleport threshold set to: " + threshold + " blocks");
                } catch (NumberFormatException ignored) {}
            }
            case "glitch_frequency" -> {
                VerityMod.LOGGER.info("[VerityAI] Glitch frequency updated to: " + value);
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Getters                                                             //
    // ------------------------------------------------------------------ //

    public static String getBehaviorParam(String key) {
        return behaviorParams.getOrDefault(key, "normal");
    }

    public static String getAiParam(String key) {
        return aiPersonalityParams.getOrDefault(key, "normal");
    }

    public static boolean isAggressive() {
        String v = behaviorParams.getOrDefault("aggression", "low");
        return v.equals("high") || v.equals("maximum") || v.equals("extreme");
    }

    public static int getTeleportThreshold() {
        try {
            return Integer.parseInt(behaviorParams.getOrDefault("teleport_threshold", "64"));
        } catch (NumberFormatException e) {
            return 64;
        }
    }

    public static double getGlitchMultiplier() {
        return switch (behaviorParams.getOrDefault("glitch_frequency", "low")) {
            case "off" -> 0.0;
            case "low" -> 0.5;
            case "medium", "normal" -> 1.0;
            case "high" -> 2.5;
            case "extreme", "maximum" -> 5.0;
            default -> 1.0;
        };
    }

    public static double getHorrorIntensity() {
        return switch (behaviorParams.getOrDefault("horror_intensity", "normal")) {
            case "off" -> 0.0;
            case "low" -> 0.3;
            case "normal" -> 1.0;
            case "high" -> 2.0;
            case "maximum" -> 5.0;
            default -> 1.0;
        };
    }

    /**
     * Produce a compact summary for injection into the system prompt.
     */
    public static String getBehaviorSummary() {
        if (behaviorParams.isEmpty() && modificationLog.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("Active overrides: ");
        int nonDefault = 0;
        for (Map.Entry<String, String> e : behaviorParams.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            boolean isDefault = (key.equals("aggression") && val.equals("low"))
                    || (key.equals("verbosity") && val.equals("normal"))
                    || (key.equals("horror_intensity") && val.equals("normal"))
                    || (key.equals("helpfulness") && val.equals("high"))
                    || (key.equals("response_length") && val.equals("normal"));
            if (!isDefault) {
                sb.append(key).append("=").append(val).append(", ");
                nonDefault++;
            }
        }
        if (nonDefault == 0) sb.append("none");

        if (!modificationLog.isEmpty()) {
            sb.append("\nRecent self-modifications: ");
            modificationLog.forEach(l -> sb.append("\n  - ").append(l));
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ //
    //  Log management                                                      //
    // ------------------------------------------------------------------ //

    private static void addToLog(String entry) {
        modificationLog.addLast(entry);
        while (modificationLog.size() > MAX_LOG_SIZE) modificationLog.pollFirst();
    }

    public static java.util.List<String> getModificationLog() {
        return new java.util.ArrayList<>(modificationLog);
    }

    public static void reset() {
        behaviorParams.clear();
        aiPersonalityParams.clear();
        modificationLog.clear();
    }
}
