package com.mod.echo.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One callable tool: its name, its description, its JSON-Schema parameters and
 * the code that runs it.
 *
 * The schema is emitted in the OpenAI function-calling shape, which both the
 * OpenAI-compatible backends and Ollama's native endpoint accept, so the same
 * definition drives every backend {@link LocalAI} can talk to.
 */
public final class ToolSpec<C> {

    /**
     * What a tool actually does.
     *
     * @param <C> the execution context — a server player plus its server on the
     *            logical server, the {@code Minecraft} instance on the client
     */
    @FunctionalInterface
    public interface Body<C> {
        /** Run the tool and return a short factual result for the model to read. */
        String run(C context, JsonObject arguments) throws Exception;
    }

    public final String name;
    public final String description;
    public final JsonObject parameters;
    public final Body<C> body;

    public ToolSpec(String name, String description, JsonObject parameters, Body<C> body) {
        this.name        = name;
        this.description = description;
        this.parameters  = parameters;
        this.body        = body;
    }

    /** Run the tool, turning any exception into a message the model can act on. */
    public String invoke(C context, JsonObject arguments) {
        try {
            String result = body.run(context, arguments == null ? new JsonObject() : arguments);
            return result == null || result.isBlank() ? "Done." : result;
        } catch (Exception e) {
            return "Tool '" + name + "' failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Serialise to the {@code {"type":"function","function":{...}}} envelope. */
    public JsonObject toSchema() {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("description", description);
        fn.add("parameters", parameters);

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("type", "function");
        wrapper.add("function", fn);
        return wrapper;
    }

    // ------------------------------------------------------------------ //
    //  Schema builder                                                      //
    // ------------------------------------------------------------------ //

    /** Fluent JSON-Schema builder for a tool's parameter object. */
    public static final class Schema {

        private final Map<String, JsonObject> props = new LinkedHashMap<>();
        private final JsonArray required = new JsonArray();

        public static Schema none() {
            return new Schema();
        }

        public static Schema of() {
            return new Schema();
        }

        public Schema str(String key, String description) {
            return add(key, "string", description, false, null);
        }

        public Schema str(String key, String description, String... allowedValues) {
            return add(key, "string", description, false, allowedValues);
        }

        public Schema requiredStr(String key, String description) {
            return add(key, "string", description, true, null);
        }

        public Schema requiredStr(String key, String description, String... allowedValues) {
            return add(key, "string", description, true, allowedValues);
        }

        public Schema integer(String key, String description) {
            return add(key, "integer", description, false, null);
        }

        public Schema requiredInteger(String key, String description) {
            return add(key, "integer", description, true, null);
        }

        public Schema number(String key, String description) {
            return add(key, "number", description, false, null);
        }

        public Schema requiredNumber(String key, String description) {
            return add(key, "number", description, true, null);
        }

        public Schema bool(String key, String description) {
            return add(key, "boolean", description, false, null);
        }

        private Schema add(String key, String type, String description,
                           boolean isRequired, String[] allowedValues) {
            JsonObject p = new JsonObject();
            p.addProperty("type", type);
            p.addProperty("description", description);
            if (allowedValues != null && allowedValues.length > 0) {
                JsonArray en = new JsonArray();
                for (String v : allowedValues) en.add(v);
                p.add("enum", en);
            }
            props.put(key, p);
            if (isRequired) required.add(key);
            return this;
        }

        public JsonObject build() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject properties = new JsonObject();
            props.forEach(properties::add);
            schema.add("properties", properties);
            // Some backends reject an empty "required" array, so only add it when used.
            if (required.size() > 0) schema.add("required", required);
            return schema;
        }
    }

    // ------------------------------------------------------------------ //
    //  Argument readers                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Read a string argument.  Models occasionally send a number or a boolean
     * where a string was asked for, so anything primitive is accepted and
     * converted rather than rejected.
     */
    public static String str(JsonObject args, String key, String fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return args.get(key).getAsString();
        } catch (Exception e) {
            return args.get(key).toString();
        }
    }

    public static int integer(JsonObject args, String key, int fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return args.get(key).getAsInt();
        } catch (Exception e) {
            // Models like to answer "12 blocks"; pull the first number out of it.
            return firstInt(args.get(key).toString(), fallback);
        }
    }

    public static double number(JsonObject args, String key, double fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return args.get(key).getAsDouble();
        } catch (Exception e) {
            return firstInt(args.get(key).toString(), (int) fallback);
        }
    }

    public static boolean bool(JsonObject args, String key, boolean fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return args.get(key).getAsBoolean();
        } catch (Exception e) {
            String s = args.get(key).toString().toLowerCase();
            return s.contains("true") || s.contains("yes") || s.contains("on") || s.contains("1");
        }
    }

    /** Clamp an integer argument into a safe range. */
    public static int clampedInt(JsonObject args, String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, integer(args, key, fallback)));
    }

    private static int firstInt(String text, int fallback) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+").matcher(text);
        return m.find() ? Integer.parseInt(m.group()) : fallback;
    }
}
