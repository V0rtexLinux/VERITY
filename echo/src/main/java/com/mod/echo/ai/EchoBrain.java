package com.mod.echo.ai;

import com.google.gson.JsonObject;
import com.mod.echo.EchoMod;
import com.mod.echo.config.EchoConfig;
import com.mod.echo.memory.EchoSelf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The conversation loop.
 *
 * One request from the player turns into: build the context, ask the model, run
 * whatever tools it asked for, feed the results back, ask again — until the
 * model answers in prose or the round limit is reached.  The same loop serves
 * the logical server and the client; only the {@link ToolBridge} differs.
 */
public final class EchoBrain {

    private EchoBrain() {}

    /**
     * Everything the loop needs that depends on which side it is running on.
     */
    public interface ToolBridge {
        /** Stable key for this conversation, usually the player's id. */
        String conversationKey();

        /** A short description of the player's live situation, or an empty string. */
        String worldContext();

        /** Tool schemas offered to the model this turn. */
        List<JsonObject> toolSchemas();

        /** Run one tool and return a short factual result. */
        String runTool(String name, JsonObject arguments);

        /** Player id used for long-term memory, or {@code null} for none. */
        default String memoryKey() { return conversationKey(); }
    }

    private static final Map<String, List<JsonObject>> CONVERSATIONS = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ //
    //  Entry point                                                         //
    // ------------------------------------------------------------------ //

    /**
     * Answer one message.
     *
     * @return the text to show the player; never null, never blank
     */
    public static CompletableFuture<String> respond(String message, ToolBridge bridge) {
        return CompletableFuture.supplyAsync(() -> respondSync(message, bridge));
    }

    private static String respondSync(String message, ToolBridge bridge) {
        EchoConfig cfg = EchoConfig.get();
        String key = bridge.conversationKey();

        if (!LocalAI.isReady() && !LocalAI.initialize().join()) {
            return offlineNotice(message);
        }

        String context = safely(bridge::worldContext);
        List<JsonObject> history = CONVERSATIONS.computeIfAbsent(key, k -> {
            EchoSelf.noteConversationStarted();
            return PromptSystem.newConversation(context, bridge.memoryKey());
        });

        // The world moves between turns, so the situation block is rewritten
        // every request rather than left as it was when the chat started.
        PromptSystem.refreshSystemPrompt(history, context, bridge.memoryKey());
        history.add(PromptSystem.message(PromptSystem.ROLE_USER, message));
        history = PromptSystem.trim(history, cfg.aiHistorySize);

        List<JsonObject> tools = safelyList(bridge::toolSchemas);
        String lastText = "";

        for (int round = 0; round < cfg.aiMaxToolRounds; round++) {
            LocalAI.Completion completion = LocalAI.chat(history, tools).join();

            if (completion.failed()) {
                CONVERSATIONS.put(key, history);
                return "I couldn't reach my local model — " + completion.error() + ".";
            }

            if (!completion.content().isBlank()) lastText = completion.content().strip();

            if (!completion.hasToolCalls()) {
                history.add(PromptSystem.message(PromptSystem.ROLE_ASSISTANT, lastText));
                CONVERSATIONS.put(key, history);
                return lastText.isBlank() ? "Done." : clean(lastText);
            }

            // Record the model's own request, then answer each call in order.
            history.add(PromptSystem.assistantToolCalls(completion.content(), completion.toolCalls()));
            for (LocalAI.ToolCall call : completion.toolCalls()) {
                String result;
                try {
                    result = bridge.runTool(call.name(), call.arguments());
                } catch (Exception e) {
                    EchoMod.LOGGER.warn("Tool {} threw: {}", call.name(), e.toString());
                    result = "That tool failed: " + e.getMessage();
                }
                EchoMod.LOGGER.debug("{} -> {}", call.name(), result);
                history.add(PromptSystem.toolResult(call.id(), call.name(), result));
            }
            history = PromptSystem.trim(history, cfg.aiHistorySize);
        }

        // The model kept reaching for tools without ever writing an answer.
        CONVERSATIONS.put(key, history);
        return lastText.isBlank()
                ? "I ran out of steps working on that. Ask me again, more specifically?"
                : clean(lastText);
    }

    // ------------------------------------------------------------------ //
    //  Conversation management                                            //
    // ------------------------------------------------------------------ //

    public static void clear(String conversationKey) {
        CONVERSATIONS.remove(conversationKey);
    }

    public static void clearAll() {
        CONVERSATIONS.clear();
    }

    public static int activeConversations() {
        return CONVERSATIONS.size();
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    /**
     * Strip artefacts small local models leave behind: reasoning blocks, stray
     * tool tags, and the chat-template markers some GGUF conversions emit.
     */
    private static String clean(String raw) {
        String text = raw;
        text = text.replaceAll("(?is)<think>.*?</think>", "");
        text = text.replaceAll("(?is)<tool_call>.*?</tool_call>", "");
        text = text.replaceAll("<\\|[a-z_]+\\|>", "");
        text = text.replaceAll("(?m)^\\s*```\\w*\\s*$", "");
        text = text.strip();
        text = text.isBlank() ? "Done." : text;
        // Safety backstop: every reply, from either chat path, passes through
        // here before the player sees it. See EchoSafety for what it catches.
        return EchoSafety.review(text);
    }

    /**
     * With no model reachable, answer from the built-in reference if it covers
     * the question, and only then explain how to get the model running. ECHO
     * should still be worth having installed before anyone sets up a backend.
     */
    private static String offlineNotice(String message) {
        String known = com.mod.echo.assistant.KnowledgeBase.anything(message);
        if (!known.isEmpty()) {
            return known + "\n(That came from my built-in reference — my local model "
                    + "isn't running, so I can't do anything more involved yet.)";
        }
        return "My local model isn't running yet.\n" + LocalAI.setupHelp();
    }

    private static String safely(java.util.function.Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (Exception e) {
            EchoMod.LOGGER.debug("Could not build world context: {}", e.toString());
            return "";
        }
    }

    private static List<JsonObject> safelyList(java.util.function.Supplier<List<JsonObject>> supplier) {
        try {
            List<JsonObject> value = supplier.get();
            return value == null ? new ArrayList<>() : value;
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Could not build the tool list: {}", e.toString());
            return new ArrayList<>();
        }
    }
}
