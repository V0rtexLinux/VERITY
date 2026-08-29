package com.mod.echo.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mod.echo.EchoMod;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Best-effort internet lookups for questions outside Minecraft, or outside
 * what the local model already knows — no API key, no account, matching the
 * rest of this mod's "nothing to sign up for" design.
 *
 * This is not a general search engine — every real one now requires a paid
 * or keyed API. It combines two free, keyless, public endpoints instead:
 *   - DuckDuckGo's Instant Answer API, for quick facts, definitions and
 *     disambiguation-adjacent topics.
 *   - Wikipedia's summary API, for anything encyclopedic the first one misses.
 * Between the two this answers a genuinely wide range of general-knowledge
 * questions, but it is not a substitute for a real search engine: very
 * recent events, obscure topics, or anything needing multiple sources may
 * come back empty. Say so plainly when that happens rather than guessing.
 */
public final class WebSearch {

    private WebSearch() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    public static String search(String query) {
        if (query == null || query.isBlank()) return "What should I look up?";
        String q = query.strip();

        try {
            String hit = duckDuckGo(q);
            if (hit != null) return hit;
        } catch (Exception e) {
            EchoMod.LOGGER.debug("DuckDuckGo lookup failed: {}", e.toString());
        }

        try {
            String hit = wikipedia(q);
            if (hit != null) return hit;
        } catch (Exception e) {
            EchoMod.LOGGER.debug("Wikipedia lookup failed: {}", e.toString());
        }

        return "I couldn't find a solid answer for \"" + q + "\" with what I can reach — "
                + "try rephrasing it or asking something more specific.";
    }

    private static String duckDuckGo(String query) throws Exception {
        String url = "https://api.duckduckgo.com/?q=" + encode(query)
                + "&format=json&no_html=1&skip_disambig=1&no_redirect=1";
        JsonObject body = getJson(url);
        if (body == null) return null;

        String abstractText = text(body, "AbstractText");
        if (!abstractText.isBlank()) {
            String source = text(body, "AbstractSource");
            return source.isBlank() ? abstractText : abstractText + " (" + source + ")";
        }

        String answer = text(body, "Answer");
        if (!answer.isBlank()) return answer;

        String definition = text(body, "Definition");
        if (!definition.isBlank()) {
            String source = text(body, "DefinitionSource");
            return source.isBlank() ? definition : definition + " (" + source + ")";
        }

        if (body.has("RelatedTopics") && body.get("RelatedTopics").isJsonArray()) {
            for (JsonElement el : body.getAsJsonArray("RelatedTopics")) {
                if (!el.isJsonObject()) continue;
                String t = text(el.getAsJsonObject(), "Text");
                if (!t.isBlank()) return t;
            }
        }
        return null;
    }

    private static String wikipedia(String query) throws Exception {
        String title = query.strip().replace(' ', '_');
        String url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + encode(title);
        JsonObject body = getJson(url);
        if (body == null) return null;
        String extract = text(body, "extract");
        return extract.isBlank() ? null : extract;
    }

    private static JsonObject getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "EchoMinecraftMod/1.0 (local assistant; no telemetry)")
                .GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) return null;
        JsonElement parsed = JsonParser.parseString(res.body());
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().strip() : "";
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
