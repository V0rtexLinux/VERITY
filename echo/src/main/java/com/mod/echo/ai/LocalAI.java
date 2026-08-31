package com.mod.echo.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mod.echo.EchoMod;
import com.mod.echo.config.EchoConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ECHO's language-model client.
 *
 * <h2>Why this is not "just Ollama"</h2>
 * The old implementation hard-coded one Ollama URL, one small model, and never
 * sent a tool schema at all — it asked the model to emit a {@code [TOOL:...]}
 * tag in prose and then regex-matched it, which small models get wrong
 * constantly.  This client instead:
 *
 * <ul>
 *   <li>probes every local inference server people actually run (Ollama,
 *       LM&nbsp;Studio, llama.cpp's own server, Jan, KoboldCpp) and uses
 *       whichever one answers,</li>
 *   <li>speaks the OpenAI-compatible {@code /v1/chat/completions} dialect where
 *       it is available and Ollama's native {@code /api/chat} otherwise — both
 *       with a real {@code tools} array, so the model returns structured
 *       {@code tool_calls} instead of text ECHO has to guess at,</li>
 *   <li>picks the strongest tool-calling model that fits the machine (see
 *       {@link ModelCatalog}) rather than a fixed 2B model,</li>
 *   <li>needs no API key and never talks to a remote service.</li>
 * </ul>
 */
public final class LocalAI {

    private LocalAI() {}

    // ------------------------------------------------------------------ //
    //  Backend description                                                 //
    // ------------------------------------------------------------------ //

    /** Wire format a detected backend speaks. */
    public enum Dialect {
        /** POST {base}/v1/chat/completions — LM Studio, llama.cpp, Jan, KoboldCpp, Ollama >= 0.4. */
        OPENAI,
        /** POST {base}/api/chat — Ollama's own richer endpoint. */
        OLLAMA
    }

    /**
     * @param name    display name of the runtime
     * @param baseUrl root URL, no trailing slash
     * @param dialect which wire format to use
     */
    public record Backend(String name, String baseUrl, Dialect dialect) {}

    /** Candidates probed in order. Ollama first: it is by far the most common. */
    private static final List<Backend> CANDIDATES = List.of(
            new Backend("Ollama",    "http://127.0.0.1:11434", Dialect.OLLAMA),
            new Backend("LM Studio", "http://127.0.0.1:1234",  Dialect.OPENAI),
            new Backend("llama.cpp", "http://127.0.0.1:8080",  Dialect.OPENAI),
            new Backend("Jan",       "http://127.0.0.1:1337",  Dialect.OPENAI),
            new Backend("KoboldCpp", "http://127.0.0.1:5001",  Dialect.OPENAI),
            new Backend("Ollama",    "http://localhost:11434", Dialect.OLLAMA)
    );

    // ------------------------------------------------------------------ //
    //  State                                                              //
    // ------------------------------------------------------------------ //

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static final AtomicBoolean initialising = new AtomicBoolean(false);

    private static volatile Backend backend    = null;
    private static volatile String  model      = "";
    private static volatile boolean ready      = false;
    private static volatile String  lastError  = "";
    private static Process spawnedBackend      = null;

    // ------------------------------------------------------------------ //
    //  Result of one completion                                           //
    // ------------------------------------------------------------------ //

    /** One tool the model asked ECHO to run. */
    public record ToolCall(String id, String name, JsonObject arguments) {}

    /**
     * A single assistant turn.
     *
     * @param content   prose the model produced (may be empty when it only called tools)
     * @param toolCalls structured tool calls, empty when the model just answered
     * @param error     non-empty when the request failed outright
     */
    public record Completion(String content, List<ToolCall> toolCalls, String error) {
        public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
        public boolean failed()       { return error != null && !error.isEmpty(); }

        public static Completion of(String content, List<ToolCall> calls) {
            return new Completion(content == null ? "" : content,
                    calls == null ? List.of() : calls, "");
        }
        public static Completion error(String message) {
            return new Completion("", List.of(), message);
        }
    }

    // ------------------------------------------------------------------ //
    //  Initialisation                                                     //
    // ------------------------------------------------------------------ //

    /**
     * Detect a backend, choose a model, and optionally download it.
     * Safe to call repeatedly — concurrent calls collapse into one.
     */
    public static CompletableFuture<Boolean> initialize() {
        if (ready) return CompletableFuture.completedFuture(true);
        if (!initialising.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ready);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doInitialize();
            } finally {
                initialising.set(false);
            }
        });
    }

    private static boolean doInitialize() {
        EchoConfig cfg = EchoConfig.get();
        EchoMod.LOGGER.info("Looking for a local language-model backend...");

        backend = detectBackend(cfg.aiBaseUrl);

        if (backend == null && cfg.aiAutoStartBackend) {
            EchoMod.LOGGER.info("No backend answered. Trying to start Ollama...");
            if (tryStartOllama()) {
                backend = detectBackend(cfg.aiBaseUrl);
            }
        }

        if (backend == null) {
            ready = false;
            lastError = "no local AI backend is running";
            EchoMod.LOGGER.warn("No local AI backend found. {}", setupHelp());
            return false;
        }

        EchoMod.LOGGER.info("Using {} at {} ({} dialect).",
                backend.name(), backend.baseUrl(), backend.dialect());

        List<String> installed = listModelsSync();
        long ramGb = systemRamGb();

        if (!cfg.aiModel.isBlank() && (installed.isEmpty() || installed.contains(cfg.aiModel))) {
            model = cfg.aiModel;
            EchoMod.LOGGER.info("Model pinned by config: {}", model);
        } else {
            if (!cfg.aiModel.isBlank()) {
                EchoMod.LOGGER.warn("Configured model '{}' is not installed anymore — picking another one.",
                        cfg.aiModel);
                cfg.aiModel = "";
            }
            model = ModelCatalog.pickInstalled(installed, ramGb);
        }

        if (model == null || model.isBlank()) {
            ModelCatalog.Entry want = ModelCatalog.recommendedFor(ramGb);
            if (cfg.aiAutoPullModel && backend.dialect() == Dialect.OLLAMA) {
                EchoMod.LOGGER.info("No model installed. Downloading {} ({}). This happens once.",
                        want.id(), want.note());
                if (pullModelSync(want.id())) {
                    model = want.id();
                } 
            }
            if (model == null || model.isBlank()) {
                ready = false;
                lastError = "no model installed — run: ollama pull " + want.id();
                EchoMod.LOGGER.warn("No usable model. Run: ollama pull {}", want.id());
                return false;
            }
        }

        ready = true;
        lastError = "";
        EchoMod.LOGGER.info("AI ready — model '{}' ({}), {} GB RAM detected.",
                model, ModelCatalog.describe(model), ramGb);
        return true;
    }

    /** Probe the configured URL first, then every well-known local port. */
    private static Backend detectBackend(String configuredBaseUrl) {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            String base = stripTrailingSlash(configuredBaseUrl);
            // Trust the user's URL, but work out which dialect it speaks.
            if (probe(base + "/api/tags")) return new Backend("configured", base, Dialect.OLLAMA);
            if (probe(base + "/v1/models")) return new Backend("configured", base, Dialect.OPENAI);
            EchoMod.LOGGER.warn("Configured aiBaseUrl '{}' did not answer.", configuredBaseUrl);
        }
        for (Backend c : CANDIDATES) {
            String probeUrl = c.dialect() == Dialect.OLLAMA
                    ? c.baseUrl() + "/api/tags"
                    : c.baseUrl() + "/v1/models";
            if (probe(probeUrl)) return c;
        }
        return null;
    }

    private static boolean probe(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() >= 200 && res.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ //
    //  Model listing / pulling                                            //
    // ------------------------------------------------------------------ //

    public static CompletableFuture<List<String>> listModels() {
        return CompletableFuture.supplyAsync(LocalAI::listModelsSync);
    }

    private static List<String> listModelsSync() {
        List<String> out = new ArrayList<>();
        if (backend == null) return out;
        try {
            String url = backend.dialect() == Dialect.OLLAMA
                    ? backend.baseUrl() + "/api/tags"
                    : backend.baseUrl() + "/v1/models";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return out;

            JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
            JsonArray arr = body.has("models") ? body.getAsJsonArray("models")
                          : body.has("data")   ? body.getAsJsonArray("data")
                          : new JsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String name = o.has("name") ? o.get("name").getAsString()
                            : o.has("id")   ? o.get("id").getAsString()
                            : null;
                if (name != null && !name.isBlank()) out.add(name);
            }
        } catch (Exception e) {
            EchoMod.LOGGER.debug("Could not list models: {}", e.getMessage());
        }
        return out;
    }

    /** Blocking model download. Only meaningful on Ollama; other backends manage their own. */
    private static boolean pullModelSync(String modelId) {
        if (backend == null || backend.dialect() != Dialect.OLLAMA) return false;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", modelId);
            body.addProperty("stream", false);

            HttpRequest req = HttpRequest.newBuilder(URI.create(backend.baseUrl() + "/api/pull"))
                    .timeout(Duration.ofMinutes(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            boolean okStatus = res.statusCode() / 100 == 2;
            if (okStatus) EchoMod.LOGGER.info("Downloaded model {}.", modelId);
            else EchoMod.LOGGER.warn("Model download failed ({}): {}", res.statusCode(), res.body());
            return okStatus;
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Model download failed: {}", e.getMessage());
            return false;
        }
    }

    /** Ask the backend to download a model, reporting the outcome asynchronously. */
    public static CompletableFuture<String> pullModel(String modelId) {
        return CompletableFuture.supplyAsync(() -> {
            if (backend == null) return "No backend is running.";
            if (backend.dialect() != Dialect.OLLAMA) {
                return backend.name() + " manages its own models — download " + modelId + " in its own UI.";
            }
            return pullModelSync(modelId)
                    ? "Downloaded " + modelId + "."
                    : "Could not download " + modelId + ".";
        });
    }

    // ------------------------------------------------------------------ //
    //  Chat                                                               //
    // ------------------------------------------------------------------ //

    /**
     * Run one completion.
     *
     * @param messages conversation so far, each a {@code {role, content, ...}} object
     * @param tools    tool schemas in OpenAI function format, or {@code null} for a plain answer
     */
    public static CompletableFuture<Completion> chat(List<JsonObject> messages, List<JsonObject> tools) {
        return CompletableFuture.supplyAsync(() -> chatSync(messages, tools));
    }

    private static Completion chatSync(List<JsonObject> messages, List<JsonObject> tools) {
        if (!ready && !doInitialize()) {
            return Completion.error(lastError.isEmpty() ? "AI backend unavailable" : lastError);
        }
        EchoConfig cfg = EchoConfig.get();

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", GSON.toJsonTree(messages));
        body.addProperty("stream", false);

        if (tools != null && !tools.isEmpty()) {
            JsonArray toolArray = new JsonArray();
            for (JsonObject t : tools) toolArray.add(t);
            body.add("tools", toolArray);
        }

        String url;
        if (backend.dialect() == Dialect.OLLAMA) {
            url = backend.baseUrl() + "/api/chat";
            JsonObject options = new JsonObject();
            options.addProperty("temperature", cfg.aiTemperature);
            body.add("options", options);
        } else {
            url = backend.baseUrl() + "/v1/chat/completions";
            body.addProperty("temperature", cfg.aiTemperature);
        }

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(cfg.aiTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    // Some OpenAI-compatible servers insist on a bearer token even
                    // though they never check it. No real key is ever involved.
                    .header("Authorization", "Bearer local")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();

            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() / 100 != 2) {
                String detail = describeHttpFailure(res.statusCode(), res.body());
                EchoMod.LOGGER.warn("Completion failed ({}): {}", res.statusCode(), res.body());
                return Completion.error(detail);
            }
            return backend.dialect() == Dialect.OLLAMA
                    ? parseOllama(res.body())
                    : parseOpenAi(res.body());

        } catch (java.net.http.HttpTimeoutException e) {
            return Completion.error("the model took longer than " + cfg.aiTimeoutSeconds
                    + "s to answer — try a smaller model");
        } catch (Exception e) {
            ready = false; // force a re-probe on the next request
            EchoMod.LOGGER.warn("Completion error: {}", e.toString());
            return Completion.error("could not reach the local AI backend (" + e.getMessage() + ")");
        }
    }

    private static String describeHttpFailure(int status, String body) {
        if (status == 404) return "model '" + model + "' is not installed on " + backend.name();
        if (status == 400 && body != null && body.toLowerCase(Locale.ROOT).contains("tool")) {
            return "model '" + model + "' does not support tool calling — pick one from the ECHO catalogue";
        }
        if (status == 500) return backend.name() + " could not load model '" + model + "'";
        return backend.name() + " returned HTTP " + status;
    }

    // ------------------------------------------------------------------ //
    //  Response parsing                                                   //
    // ------------------------------------------------------------------ //

    /** Ollama: {@code {"message": {"content": "...", "tool_calls": [...]}}} */
    private static Completion parseOllama(String raw) {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        if (!root.has("message") || !root.get("message").isJsonObject()) {
            return Completion.of(raw, List.of());
        }
        JsonObject msg = root.getAsJsonObject("message");
        String content = optString(msg, "content");
        List<ToolCall> calls = new ArrayList<>();

        if (msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) {
            int i = 0;
            for (JsonElement el : msg.getAsJsonArray("tool_calls")) {
                if (!el.isJsonObject()) continue;
                JsonObject fn = el.getAsJsonObject().has("function")
                        ? el.getAsJsonObject().getAsJsonObject("function")
                        : el.getAsJsonObject();
                String name = optString(fn, "name");
                if (name.isEmpty()) continue;
                calls.add(new ToolCall("call_" + (i++), name, readArguments(fn.get("arguments"))));
            }
        }
        return Completion.of(content, calls);
    }

    /** OpenAI: {@code {"choices": [{"message": {"content": "...", "tool_calls": [...]}}]}} */
    private static Completion parseOpenAi(String raw) {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        if (!root.has("choices") || root.getAsJsonArray("choices").size() == 0) {
            return Completion.of(raw, List.of());
        }
        JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
        if (!choice.has("message") || !choice.get("message").isJsonObject()) {
            return Completion.of("", List.of());
        }
        JsonObject msg = choice.getAsJsonObject("message");
        String content = optString(msg, "content");
        List<ToolCall> calls = new ArrayList<>();

        if (msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) {
            int i = 0;
            for (JsonElement el : msg.getAsJsonArray("tool_calls")) {
                if (!el.isJsonObject()) continue;
                JsonObject call = el.getAsJsonObject();
                if (!call.has("function") || !call.get("function").isJsonObject()) continue;
                JsonObject fn = call.getAsJsonObject("function");
                String name = optString(fn, "name");
                if (name.isEmpty()) continue;
                String id = call.has("id") ? call.get("id").getAsString() : "call_" + (i);
                i++;
                calls.add(new ToolCall(id, name, readArguments(fn.get("arguments"))));
            }
        }
        return Completion.of(content, calls);
    }

    /**
     * Tool arguments arrive either as a nested object (Ollama) or as a JSON
     * string (OpenAI).  Small models sometimes produce slightly malformed JSON,
     * so a parse failure yields an empty object rather than an exception —
     * the tool then falls back on its own defaults.
     */
    private static JsonObject readArguments(JsonElement args) {
        try {
            if (args == null || args.isJsonNull()) return new JsonObject();
            if (args.isJsonObject()) return args.getAsJsonObject();
            if (args.isJsonPrimitive()) {
                String s = args.getAsString().trim();
                if (s.isEmpty()) return new JsonObject();
                JsonElement parsed = JsonParser.parseString(s);
                if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            }
        } catch (Exception e) {
            EchoMod.LOGGER.debug("Malformed tool arguments, using defaults: {}", args);
        }
        return new JsonObject();
    }

    private static String optString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        JsonElement el = o.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    // ------------------------------------------------------------------ //
    //  Starting a backend                                                 //
    // ------------------------------------------------------------------ //

    /** Try to launch {@code ollama serve} if the binary is on this machine. */
    private static boolean tryStartOllama() {
        String exe = findOllamaBinary();
        if (exe == null) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(exe, "serve");
            pb.redirectErrorStream(true);
            spawnedBackend = pb.start();

            Thread drain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(spawnedBackend.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        EchoMod.LOGGER.debug("[ollama] {}", line);
                    }
                } catch (Exception ignored) {
                    // The stream closes when the process exits; nothing to report.
                }
            }, "echo-ollama-log");
            drain.setDaemon(true);
            drain.start();

            // Give it up to ~20s to bind its port.
            for (int i = 0; i < 20; i++) {
                if (probe("http://127.0.0.1:11434/api/tags")) return true;
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Could not start Ollama: {}", e.getMessage());
        }
        return false;
    }

    private static String findOllamaBinary() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", "");
        List<String> candidates = new ArrayList<>();
        if (os.contains("win")) {
            candidates.add(home + "\\AppData\\Local\\Programs\\Ollama\\ollama.exe");
            candidates.add("C:\\Program Files\\Ollama\\ollama.exe");
        } else if (os.contains("mac")) {
            candidates.add("/usr/local/bin/ollama");
            candidates.add("/opt/homebrew/bin/ollama");
            candidates.add("/Applications/Ollama.app/Contents/Resources/ollama");
        } else {
            candidates.add("/usr/local/bin/ollama");
            candidates.add("/usr/bin/ollama");
            candidates.add("/snap/bin/ollama");
        }
        for (String c : candidates) {
            if (new java.io.File(c).isFile()) return c;
        }
        return commandExists("ollama") ? "ollama" : null;
    }

    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ //
    //  Status / lifecycle                                                 //
    // ------------------------------------------------------------------ //

    public static boolean isReady()          { return ready; }
    /** Which wire format the active backend speaks; OpenAI-shaped until one is found. */
    public static Dialect getDialect()       { return backend == null ? Dialect.OPENAI : backend.dialect(); }
    public static String  getModel()         { return model; }
    public static String  getBackendName()   { return backend == null ? "none" : backend.name(); }
    public static String  getBaseUrl()       { return backend == null ? "-" : backend.baseUrl(); }
    public static String  getLastError()     { return lastError; }

    /**
     * Switch model at runtime; the change is remembered in the config file.
     *
     * <p>Validated against the backend's own installed list first — blindly
     * accepting an unpulled or misspelled model id used to leave ECHO stuck
     * failing every single message afterwards with a backend-side "could not
     * load model" error, since nothing ever pointed back at the bad model id
     * itself. The previously working model is left in place until the new one
     * is confirmed to actually exist.
     */
    public static String setModel(String modelId) {
        if (modelId == null || modelId.isBlank()) return "Give me a model name.";
        String id = modelId.trim();

        List<String> installed = listModelsSync();
        if (!installed.isEmpty() && !installed.contains(id)) {
            return "'" + id + "' isn't installed, so switching to it would just break every message afterwards. "
                    + "Run \"ollama pull " + id + "\" first, or check \"echo models\" for what's already here.";
        }

        model = id;
        EchoConfig.get().aiModel = model;
        EchoConfig.save();
        ready = true;
        lastError = "";
        return "Now using " + model + " (" + ModelCatalog.describe(model) + ").";
    }

    /** Estimated usable system memory in whole gigabytes. */
    public static long systemRamGb() {
        // Reflection keeps this compiling and running on any JVM, with or without
        // the com.sun.management extensions.
        for (String getter : new String[]{"getTotalMemorySize", "getTotalPhysicalMemorySize"}) {
            try {
                Object bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                java.lang.reflect.Method m = bean.getClass().getMethod(getter);
                m.setAccessible(true);
                long bytes = ((Number) m.invoke(bean)).longValue();
                if (bytes > 0) return Math.max(2, bytes / (1024L * 1024L * 1024L));
            } catch (Exception ignored) {
                // Not available on this JVM — try the next name, then the heap fallback.
            }
        }
        // Fall back to the heap ceiling, which is a lower bound on real memory.
        long heap = Runtime.getRuntime().maxMemory() / (1024L * 1024L * 1024L);
        return Math.max(4, heap * 2);
    }

    public static String statusReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("backend: ").append(getBackendName()).append(" @ ").append(getBaseUrl()).append('\n');
        sb.append("model:   ").append(model.isBlank() ? "(none)" : model).append('\n');
        sb.append("about:   ").append(ModelCatalog.describe(model)).append('\n');
        sb.append("ready:   ").append(ready);
        if (!lastError.isEmpty()) sb.append('\n').append("problem: ").append(lastError);
        return sb.toString();
    }

    /** Instructions shown when nothing local is running. */
    public static String setupHelp() {
        long ram = systemRamGb();
        ModelCatalog.Entry rec = ModelCatalog.recommendedFor(ram);
        return """
               ECHO runs entirely on your own machine — no account, no API key.
                 1. Install Ollama from https://ollama.com/download
                 2. Run: ollama pull %s
                 3. Ollama starts on its own; ECHO finds it automatically.
               LM Studio, llama.cpp's server, Jan and KoboldCpp also work —
               start any of them and ECHO will detect it.
               (Recommended for your %d GB of RAM: %s — %s)"""
                .formatted(rec.id(), ram, rec.id(), rec.note());
    }

    public static void shutdown() {
        ready = false;
        if (spawnedBackend != null && spawnedBackend.isAlive()) {
            EchoMod.LOGGER.info("Stopping the AI backend ECHO started.");
            spawnedBackend.destroy();
            try {
                if (!spawnedBackend.waitFor(5, TimeUnit.SECONDS)) spawnedBackend.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            spawnedBackend = null;
        }
    }

    private static String stripTrailingSlash(String s) {
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
