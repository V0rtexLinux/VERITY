package com.mod.echo.ai;

import com.mod.echo.config.EchoConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime personality and verbosity controls.
 *
 * This replaces the old self-modification engine, which existed to escalate a
 * horror narrative.  ECHO's version does something useful instead: it lets the
 * player (or ECHO itself, through a tool) change how the assistant talks —
 * tone, length, proactivity, whether it explains its reasoning — and every
 * change is written straight into the system prompt on the next turn.
 */
public final class PersonalityEngine {

    private PersonalityEngine() {}

    /** Parameters ECHO understands, with their allowed values. */
    private static final Map<String, List<String>> ALLOWED = new LinkedHashMap<>();
    static {
        ALLOWED.put("tone",        List.of("friendly", "neutral", "cheerful", "dry", "professional"));
        ALLOWED.put("verbosity",   List.of("terse", "short", "normal", "detailed"));
        ALLOWED.put("proactivity", List.of("off", "low", "normal", "high"));
        ALLOWED.put("teaching",    List.of("off", "on"));
        ALLOWED.put("emoji",       List.of("off", "on"));
        ALLOWED.put("language",    List.of("auto", "en", "pt"));
        ALLOWED.put("confirm",     List.of("never", "risky", "always"));
    }

    private static final Map<String, String> STATE = new LinkedHashMap<>();
    static { reset(); }

    private static final int MAX_LOG = 20;
    private static final Deque<String> LOG = new ArrayDeque<>();

    public static synchronized void reset() {
        STATE.clear();
        STATE.put("tone",        "friendly");
        STATE.put("verbosity",   "short");
        STATE.put("proactivity", "normal");
        STATE.put("teaching",    "on");
        STATE.put("emoji",       "off");
        STATE.put("language",    "auto");
        STATE.put("confirm",     "risky");
        applyConfigPreset(EchoConfig.get().personality);
    }

    /** Map the coarse config-level personality onto the fine-grained parameters. */
    public static synchronized void applyConfigPreset(String preset) {
        switch (preset == null ? "" : preset.toLowerCase(Locale.ROOT)) {
            case "concise" -> {
                STATE.put("tone", "neutral");
                STATE.put("verbosity", "terse");
                STATE.put("teaching", "off");
            }
            case "teacher" -> {
                STATE.put("tone", "friendly");
                STATE.put("verbosity", "detailed");
                STATE.put("teaching", "on");
            }
            case "pro" -> {
                STATE.put("tone", "professional");
                STATE.put("verbosity", "short");
                STATE.put("teaching", "off");
                STATE.put("confirm", "never");
            }
            default -> { /* "friendly" — the defaults above already describe it. */ }
        }
    }

    /**
     * Change one parameter.
     *
     * @return a short confirmation, or an explanation of why the change was rejected
     */
    public static synchronized String set(String parameter, String value) {
        if (parameter == null || parameter.isBlank()) return "Which setting should I change?";
        String key = parameter.strip().toLowerCase(Locale.ROOT);
        if (!ALLOWED.containsKey(key)) {
            return "I don't have a '" + parameter + "' setting. I know: " + String.join(", ", ALLOWED.keySet()) + ".";
        }
        String val = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        List<String> options = ALLOWED.get(key);
        if (!options.contains(val)) {
            return key + " can be: " + String.join(", ", options) + ".";
        }
        String previous = STATE.put(key, val);
        if (key.equals("language")) {
            EchoConfig.get().language = val;
            EchoConfig.save();
        }
        log(key + ": " + previous + " -> " + val);
        return "Set " + key + " to " + val + ".";
    }

    public static synchronized String get(String key) {
        return STATE.getOrDefault(key == null ? "" : key.toLowerCase(Locale.ROOT), "");
    }

    public static synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(STATE);
    }

    public static synchronized List<String> changeLog() {
        return List.copyOf(LOG);
    }

    private static void log(String entry) {
        LOG.addLast(entry);
        while (LOG.size() > MAX_LOG) LOG.removeFirst();
    }

    // ------------------------------------------------------------------ //
    //  Prompt fragment                                                     //
    // ------------------------------------------------------------------ //

    /** The block of instructions injected into every system prompt. */
    public static synchronized String promptSection() {
        StringBuilder sb = new StringBuilder("HOW TO SPEAK\n");

        sb.append("- Tone: ").append(switch (STATE.get("tone")) {
            case "neutral"      -> "plain and matter-of-fact.";
            case "cheerful"     -> "warm and upbeat, but never cloying.";
            case "dry"          -> "dry and understated.";
            case "professional" -> "precise and businesslike.";
            default             -> "friendly and encouraging.";
        }).append('\n');

        sb.append("- Length: ").append(switch (STATE.get("verbosity")) {
            case "terse"    -> "one short sentence whenever that is enough.";
            case "normal"   -> "two or three sentences.";
            case "detailed" -> "explain properly, but stay under eight lines.";
            default         -> "one or two sentences; never pad an answer.";
        }).append('\n');

        sb.append("- Follow-ups: ").append(switch (STATE.get("proactivity")) {
            case "off"  -> "answer exactly what was asked and stop.";
            case "low"  -> "only mention something extra if it prevents a mistake.";
            case "high" -> "actively suggest the obvious next step.";
            default     -> "add one useful suggestion when it genuinely helps.";
        }).append('\n');

        if ("on".equals(STATE.get("teaching"))) {
            sb.append("- Say briefly *why*, not just *what*, when the reason is not obvious.\n");
        }
        if ("off".equals(STATE.get("emoji"))) {
            sb.append("- Do not use emoji.\n");
        }
        sb.append("- Confirmation: ").append(switch (STATE.get("confirm")) {
            case "never"  -> "never ask permission; just do it.";
            case "always" -> "describe what you are about to do and wait for a yes.";
            default       -> "act immediately for read-only or easily undone actions; "
                           + "for anything that changes the world in a big way, say what you will do first.";
        }).append('\n');

        String lang = STATE.get("language");
        sb.append("- Language: ").append(switch (lang) {
            case "en" -> "always answer in English.";
            case "pt" -> "responde sempre em portugues.";
            default   -> "answer in whatever language the player wrote in.";
        });
        return sb.toString();
    }

    /** Multi-line summary for the {@code echo personality} chat command. */
    public static synchronized String summary() {
        StringBuilder sb = new StringBuilder();
        STATE.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v)
                .append("   (").append(String.join(" / ", ALLOWED.get(k))).append(")\n"));
        return sb.toString().stripTrailing();
    }
}
