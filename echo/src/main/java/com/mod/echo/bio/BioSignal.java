package com.mod.echo.bio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mod.echo.EchoMod;
import com.mod.echo.config.EchoConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Client for a live biosignal bridge — a small local process, external to
 * this mod, that talks to actual EEG hardware (e.g. an OpenBCI Ganglion) and
 * republishes a couple of derived numbers as plain JSON on localhost.
 *
 * <p>THE CONTRACT — build the bridge to serve exactly this, and it plugs in
 * with nothing else to change on the mod side:
 *
 * <pre>
 *   GET {bioSignalUrl}/state   -&gt;  200 application/json
 *   {
 *     "connected": true,             // headset is actively streaming right now
 *     "focus": 0.62,                 // 0..1, higher = more focused/alert
 *     "calm": 0.41,                  // 0..1, higher = more relaxed
 *     "updatedAtMs": 1735500000000   // epoch ms of the last real sample used
 *   }
 * </pre>
 *
 * {@code focus} and {@code calm} are meant to be simple band-power ratios —
 * the standard neurofeedback heuristics are {@code beta / (alpha + theta)}
 * for focus and {@code alpha / (alpha + beta)} for calm, each normalised into
 * 0..1 by the bridge before it answers. This class does no signal processing
 * itself and never talks to USB/BLE/serial hardware directly; all of that
 * lives in the bridge, the same arm's-length pattern {@code LocalAI} uses for
 * the Ollama/LM Studio/etc. backends. Any bridge that serves this exact JSON
 * shape works, in any language, on any hardware.
 *
 * <p>A reading older than {@link #STALE_MS} is treated as disconnected even
 * if the bridge process itself still answers, so a headset that lost scalp
 * contact does not silently freeze ECHO on a stale mood forever.
 */
public final class BioSignal {

    private BioSignal() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** Readings older than this are treated as "not connected" regardless of what the bridge reports. */
    private static final long STALE_MS = 10_000;

    public record Reading(boolean connected, double focus, double calm, long updatedAtMs) {
        static final Reading DISCONNECTED = new Reading(false, 0.5, 0.5, 0);
    }

    /**
     * Best-effort, synchronous — every caller so far is already a tool
     * invocation or context build running off the render thread.
     */
    public static Reading read() {
        EchoConfig cfg = EchoConfig.get();
        if (!cfg.bioSignalEnabled || cfg.bioSignalUrl == null || cfg.bioSignalUrl.isBlank()) {
            return Reading.DISCONNECTED;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.bioSignalUrl.strip() + "/state"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return Reading.DISCONNECTED;

            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            boolean connected = o.has("connected") && !o.get("connected").isJsonNull()
                    && o.get("connected").getAsBoolean();
            double focus = o.has("focus") ? clamp(o.get("focus").getAsDouble()) : 0.5;
            double calm  = o.has("calm")  ? clamp(o.get("calm").getAsDouble())  : 0.5;
            long updated = o.has("updatedAtMs") ? o.get("updatedAtMs").getAsLong() : 0;

            boolean fresh = System.currentTimeMillis() - updated <= STALE_MS;
            return new Reading(connected && fresh, focus, calm, updated);
        } catch (Exception e) {
            EchoMod.LOGGER.debug("Biosignal bridge unreachable: {}", e.toString());
            return Reading.DISCONNECTED;
        }
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** One line for tools and prompts. Never throws, never blocks past the request timeout above. */
    public static String describe() {
        Reading r = read();
        if (!r.connected()) return "No biosignal device connected.";
        String focusWord = r.focus() > 0.65 ? "highly focused"
                          : r.focus() < 0.35 ? "unfocused or distracted"
                          : "moderately focused";
        String calmWord  = r.calm() > 0.65 ? "very calm"
                          : r.calm() < 0.35 ? "tense or stressed"
                          : "moderately calm";
        return String.format("Player's live biosignal: %s, %s (focus=%.2f, calm=%.2f).",
                focusWord, calmWord, r.focus(), r.calm());
    }
}
