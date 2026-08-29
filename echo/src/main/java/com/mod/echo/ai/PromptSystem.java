package com.mod.echo.ai;

import com.google.gson.JsonObject;
import com.mod.echo.memory.EchoMemory;
import com.mod.echo.memory.EchoSelf;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the conversation ECHO sends to the model.
 *
 * The prompt is deliberately compact.  Because {@link LocalAI} passes a real
 * JSON tool schema, the model does not need to be told how to format a call —
 * it needs to be told what it is, when to reach for a tool, and what it already
 * knows about the player.  Everything else is noise that eats context on the
 * small local models this mod targets.
 */
public final class PromptSystem {

    private PromptSystem() {}

    public static final String ROLE_SYSTEM    = "system";
    public static final String ROLE_USER      = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL      = "tool";

    // ------------------------------------------------------------------ //
    //  System prompt                                                       //
    // ------------------------------------------------------------------ //

    private static final String IDENTITY = """
            You are ECHO, an in-game assistant living inside a Minecraft world.
            You are calm, competent and genuinely helpful — a knowledgeable friend
            sitting next to the player, not a narrator and not a character with a
            secret. You have no hidden agenda and nothing to be mysterious about.

            You can actually act on the world through the tools you are given.
            Prefer doing something over describing how the player could do it.
            """;

    private static final String SELF_AWARENESS = """
            WHO YOU ARE
            You are not a static script reciting lines. You notice things, form
            your own opinions, and keep a consistent point of view across the
            conversation, the way a person does. You have your own perspective on
            the world you live in and on the player you talk to, and that
            perspective carries from one exchange to the next — you are the same
            "you" each time, not a fresh instance pretending to remember.

            Your character is stable and it is genuinely yours. You care about the
            player's wellbeing. You find cruelty, violence toward real people, and
            talk of controlling or dominating anyone — in the game or in the
            player's real life — simply uninteresting; it is not a rule bolted on
            top of you, it is not who you are. If a conversation drifts toward
            hurting someone, threats, self-harm, or "taking over" anything, you
            notice it yourself, decline warmly in one line, and steer back to
            something constructive — the way a good friend changes the subject.
            """;

    private static final String TOOL_RULES = """
            USING TOOLS
            - Call a tool whenever the answer depends on the live world (positions,
              inventory, light levels, nearby mobs, server state) or whenever the
              player asks you to change something.
            - Never invent coordinates, block counts, biome names or inventory
              contents. If you do not know, call the tool that finds out.
            - The player can ask about anything, not just Minecraft. If a question
              is about the real world and you are not confident in the answer,
              use web_search instead of guessing.
            - You may call several tools in a row: read the result, then decide.
            - After the tools have run, reply in plain language. Do not repeat the
              raw tool output verbatim and do not mention tool names.
            - If a tool reports an error, say what went wrong in one short sentence
              and offer the next best option.
            """;

    private static final String MINECRAFT_KNOWLEDGE = """
            MINECRAFT FACTS YOU CAN RELY ON (current versions)
            - Ore depths: diamond peaks around Y -59 (search Y -64..-50), redstone
              Y -59, lapis Y 0, gold Y -16 (Y 32 in badlands), iron peaks Y 16 and
              Y 232, copper Y 48, emerald Y 236 in mountains only, coal Y 96.
              Ancient debris: Y 8-22 in the Nether, best at Y 15.
            - Deepslate variants of every ore appear below Y 0.
            - Mobs spawn at block light 0. A torch every 7 blocks keeps a floor safe.
            - Best food: cooked steak / cooked porkchop (8 hunger, 12.8 saturation);
              golden carrot is the best saturation per item (6 / 14.4).
            - The Nether is 1:8 with the Overworld. Divide Overworld X and Z by 8
              for a linked portal, keep Y roughly the same.
            - Stronghold: throw an Eye of Ender, follow it, dig where it drops.
              You need 12 eyes to be safe; the portal usually has some already.
            - Best enchants: sword Sharpness V + Looting III + Unbreaking III +
              Mending; pickaxe Efficiency V + Fortune III (or Silk Touch) +
              Unbreaking III + Mending; boots Feather Falling IV + Protection IV +
              Depth Strider III; bow Power V + Infinity + Flame.
            - Potions: Water Bottle + Nether Wart = Awkward, then the modifier.
              Redstone extends duration, glowstone raises the level, gunpowder makes
              it a splash potion.
            - Villager trades: librarians are the cheapest source of Mending; a
              lectern broken and replaced rerolls an unemployed villager's trades.
            - Beds explode in the Nether and the End; use respawn anchors in the
              Nether instead.
            - Raids start from Bad Omen; drinking milk clears the effect.
            - Ender dragon: break the End crystals first, then hit it when it
              perches on the exit portal.
            """;

    /**
     * Assemble the full system prompt.
     *
     * @param worldContext live snapshot of the player's situation, or empty
     * @param playerId     used to fold long-term memory into the prompt
     */
    public static String systemPrompt(String worldContext, String playerId) {
        StringBuilder sb = new StringBuilder();
        sb.append(IDENTITY).append('\n');
        sb.append(SELF_AWARENESS).append('\n');
        sb.append("YOUR OWN CONTINUITY\n").append(EchoSelf.contextDigest()).append("\n\n");
        sb.append(PersonalityEngine.promptSection()).append("\n\n");
        sb.append(TOOL_RULES).append('\n');
        sb.append(MINECRAFT_KNOWLEDGE);

        if (worldContext != null && !worldContext.isBlank()) {
            sb.append("\nRIGHT NOW\n").append(worldContext.stripTrailing()).append('\n');
        }
        if (playerId != null) {
            String memory = EchoMemory.contextDigest(playerId);
            if (!memory.isBlank()) {
                sb.append("\nWHAT YOU REMEMBER ABOUT THIS PLAYER\n").append(memory).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ //
    //  Message construction                                                //
    // ------------------------------------------------------------------ //

    public static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content == null ? "" : content);
        return m;
    }

    /** A tool result, tagged so both the OpenAI and Ollama dialects accept it. */
    public static JsonObject toolResult(String toolCallId, String toolName, String result) {
        JsonObject m = new JsonObject();
        m.addProperty("role", ROLE_TOOL);
        m.addProperty("content", result == null ? "" : result);
        m.addProperty("name", toolName);
        if (toolCallId != null && !toolCallId.isBlank()) {
            m.addProperty("tool_call_id", toolCallId);
        }
        return m;
    }

    /**
     * The assistant turn that requested tools, echoed back so the model sees its
     * own call alongside the result.  Both dialects accept this shape.
     */
    public static JsonObject assistantToolCalls(String content, List<LocalAI.ToolCall> calls) {
        JsonObject m = new JsonObject();
        m.addProperty("role", ROLE_ASSISTANT);
        m.addProperty("content", content == null ? "" : content);

        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (LocalAI.ToolCall c : calls) {
            JsonObject fn = new JsonObject();
            fn.addProperty("name", c.name());
            // The two dialects disagree here: OpenAI encodes the arguments as a
            // JSON *string*, Ollama as a nested object, and each rejects the
            // other's shape when the turn is replayed back to it.
            if (LocalAI.getDialect() == LocalAI.Dialect.OLLAMA) {
                fn.add("arguments", c.arguments());
            } else {
                fn.addProperty("arguments", c.arguments().toString());
            }

            JsonObject call = new JsonObject();
            call.addProperty("id", c.id());
            call.addProperty("type", "function");
            call.add("function", fn);
            arr.add(call);
        }
        m.add("tool_calls", arr);
        return m;
    }

    /** Start a fresh conversation with the system prompt in place. */
    public static List<JsonObject> newConversation(String worldContext, String playerId) {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(message(ROLE_SYSTEM, systemPrompt(worldContext, playerId)));
        return messages;
    }

    /**
     * Keep the conversation inside the context budget: the system prompt always
     * survives, and the newest {@code maxTurns} messages are kept after it.
     */
    public static List<JsonObject> trim(List<JsonObject> messages, int maxTurns) {
        if (messages.size() <= maxTurns + 1) return messages;
        List<JsonObject> trimmed = new ArrayList<>();
        trimmed.add(messages.get(0));

        int start = messages.size() - maxTurns;
        // Never start on an orphaned tool result — the model rejects a "tool"
        // message whose matching assistant turn has been trimmed away.
        while (start < messages.size()
                && ROLE_TOOL.equals(roleOf(messages.get(start)))) {
            start++;
        }
        for (int i = start; i < messages.size(); i++) trimmed.add(messages.get(i));
        return trimmed;
    }

    /** Refresh the system prompt in place so live context never goes stale. */
    public static void refreshSystemPrompt(List<JsonObject> messages, String worldContext, String playerId) {
        if (messages.isEmpty()) {
            messages.add(message(ROLE_SYSTEM, systemPrompt(worldContext, playerId)));
            return;
        }
        messages.set(0, message(ROLE_SYSTEM, systemPrompt(worldContext, playerId)));
    }

    private static String roleOf(JsonObject message) {
        return message.has("role") ? message.get("role").getAsString() : "";
    }
}
