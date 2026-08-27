package com.mod.echo.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ranked catalogue of local, no-API-key language models that ECHO can drive.
 *
 * Every entry here is a fully local model (Ollama / llama.cpp / LM Studio) with
 * *native* function-calling support, which is what the assistant needs: ECHO
 * hands the model a JSON tool schema and expects structured {@code tool_calls}
 * back, not prose it has to guess at.
 *
 * The list is ordered from best to worst.  {@link #bestFor(long)} narrows it to
 * the models that actually fit in the machine's memory, and
 * {@link #pickInstalled(List, long)} picks the highest ranked one the user has
 * already downloaded so nothing is fetched unnecessarily.
 */
public final class ModelCatalog {

    private ModelCatalog() {}

    /**
     * @param id           model id as the backend knows it
     * @param minRamGb     RAM needed to run it comfortably
     * @param toolQuality  0-100, how reliably it emits well-formed tool calls
     * @param note         short human-readable description
     */
    public record Entry(String id, int minRamGb, int toolQuality, String note) {}

    /** Best-to-worst. Tool quality is the primary sort key, size the tie-breaker. */
    private static final List<Entry> CATALOG = List.of(
            new Entry("qwen3:30b-a3b",   32, 97, "Qwen3 MoE — the strongest local tool caller, only 3B active params"),
            new Entry("qwen3:14b",       16, 95, "Qwen3 14B — excellent tool calling, great quality/size balance"),
            new Entry("qwen2.5:14b",     16, 92, "Qwen2.5 14B — very reliable structured output"),
            new Entry("llama3.3:70b",    48, 92, "Llama 3.3 70B — top quality if the machine can hold it"),
            new Entry("qwen3:8b",        10, 90, "Qwen3 8B — the sweet spot for most gaming PCs"),
            new Entry("mistral-nemo:12b", 12, 88, "Mistral Nemo — strong function calling, 128k context"),
            new Entry("llama3.1:8b",      10, 86, "Llama 3.1 8B — solid, widely available tool caller"),
            new Entry("qwen2.5:7b",        8, 85, "Qwen2.5 7B — dependable on modest hardware"),
            new Entry("qwen3:4b",          6, 80, "Qwen3 4B — still calls tools correctly on low-RAM machines"),
            new Entry("llama3.2:3b",       6, 72, "Llama 3.2 3B — light fallback with basic tool support"),
            new Entry("qwen3:1.7b",        4, 62, "Qwen3 1.7B — last resort for very small machines")
    );

    public static List<Entry> all() {
        return CATALOG;
    }

    /** Every catalogue entry that fits in {@code ramGb} of system memory. */
    public static List<Entry> bestFor(long ramGb) {
        List<Entry> fits = new ArrayList<>();
        for (Entry e : CATALOG) {
            if (e.minRamGb() <= ramGb) fits.add(e);
        }
        if (fits.isEmpty()) fits.add(CATALOG.get(CATALOG.size() - 1));
        return fits;
    }

    /** The single model ECHO recommends downloading for a machine with {@code ramGb}. */
    public static Entry recommendedFor(long ramGb) {
        return bestFor(ramGb).get(0);
    }

    /**
     * Choose the best already-installed model.
     *
     * @param installed model ids reported by the backend (may carry {@code :latest} suffixes)
     * @param ramGb     available system memory, used to avoid picking something that will swap
     * @return the chosen model id, or {@code null} when nothing installed is usable
     */
    public static String pickInstalled(List<String> installed, long ramGb) {
        if (installed == null || installed.isEmpty()) return null;

        // 1. Exact catalogue matches that fit in memory, best first.
        for (Entry e : bestFor(ramGb)) {
            for (String have : installed) {
                if (matches(have, e.id())) return have;
            }
        }
        // 2. Catalogue matches that do not fit — better than nothing, the backend
        //    will page it in and simply run slower.
        for (Entry e : CATALOG) {
            for (String have : installed) {
                if (matches(have, e.id())) return have;
            }
        }
        // 3. Anything that looks like a known tool-calling family.
        for (String have : installed) {
            String h = have.toLowerCase(Locale.ROOT);
            if (h.startsWith("qwen") || h.startsWith("llama3") || h.startsWith("mistral")
                    || h.startsWith("hermes") || h.startsWith("command-r") || h.startsWith("firefunction")) {
                return have;
            }
        }
        // 4. Give up gracefully and use whatever the user has installed.
        return installed.get(0);
    }

    /** True when an installed id refers to the same model as a catalogue id. */
    private static boolean matches(String installedId, String catalogId) {
        String a = normalise(installedId);
        String b = normalise(catalogId);
        return a.equals(b);
    }

    private static String normalise(String id) {
        String s = id.toLowerCase(Locale.ROOT).trim();
        if (s.endsWith(":latest")) s = s.substring(0, s.length() - ":latest".length());
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        return s;
    }

    /** Human-readable description of a model id, or a generic line when unknown. */
    public static String describe(String modelId) {
        if (modelId == null || modelId.isBlank()) return "no model selected";
        for (Entry e : CATALOG) {
            if (matches(modelId, e.id())) return e.note();
        }
        return "custom local model";
    }
}
