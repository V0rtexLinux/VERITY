package com.mod.verity.ai;

import com.google.gson.JsonObject;
import com.mod.verity.VerityMod;
import com.mod.verity.assistant.VerityAssistant;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI-powered assistant for Verity using local Ollama.
 *
 * Tool calling works via the [TOOL:name:{json}] tag appended by the model
 * at the end of every response that needs an action.  The tag is stripped
 * from the displayed text, the tool is executed, and the result is fed back
 * into the conversation.
 */
public class VerityAI {

    private static boolean initialized = false;
    private static final int MAX_HISTORY_SIZE = 20;

    private static final ConcurrentHashMap<String, List<JsonObject>> conversationHistory = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<JsonObject>> clientHistory = new ConcurrentHashMap<>();

    private static final Pattern TOOL_PATTERN = Pattern.compile(
        "\\[TOOL:(\\w+):([^\\]]+)\\]\\s*$", Pattern.DOTALL);

    public static CompletableFuture<Boolean> initialize() {
        if (initialized) {
            return CompletableFuture.completedFuture(OllamaManager.isReady());
        }
        VerityMod.LOGGER.info("[VerityAI] Initializing AI system...");
        return OllamaManager.initialize().thenApply(success -> {
            initialized = true;
            if (success) {
                VerityMod.LOGGER.info("[VerityAI] AI system ready");
            } else {
                VerityMod.LOGGER.warn("[VerityAI] AI init failed, using fallback");
            }
            return success;
        });
    }

    // ------------------------------------------------------------------ //
    //  SERVER-SIDE processing                                              //
    // ------------------------------------------------------------------ //

    public static CompletableFuture<String> processMessage(String message, ServerPlayer player,
                                                            MinecraftServer server, int stage) {
        String playerId = player.getUUID().toString();

        if (!initialized) initialize();

        ToolManager.setContext(player, server, stage);

        List<JsonObject> history = conversationHistory.computeIfAbsent(playerId, k ->
            PromptSystem.createInitialMessages(stage, player));

        PromptSystem.addUserMessage(history, message);
        final List<JsonObject> trimmed = PromptSystem.trimHistory(history, MAX_HISTORY_SIZE);
        conversationHistory.put(playerId, trimmed);

        if (!OllamaManager.isReady()) {
            return fallbackResponse(message, player, server, stage);
        }

        return OllamaManager.chat(trimmed, OllamaManager.getDefaultModel(), null)
            .thenCompose(aiResponse -> {
                VerityMod.LOGGER.info("[VerityAI] Raw response: " + aiResponse);
                return handleToolTagServer(aiResponse, trimmed, playerId, stage, player, server);
            })
            .exceptionally(ex -> {
                VerityMod.LOGGER.error("[VerityAI] Error: " + ex.getMessage(), ex);
                return fallbackResponse(message, player, server, stage).join();
            });
    }

    private static CompletableFuture<String> handleToolTagServer(String rawResponse,
                                                                   List<JsonObject> history,
                                                                   String playerId, int stage,
                                                                   ServerPlayer player,
                                                                   MinecraftServer server) {
        Matcher m = TOOL_PATTERN.matcher(rawResponse);
        if (!m.find()) {
            PromptSystem.addAssistantMessage(history, rawResponse);
            conversationHistory.put(playerId, history);
            return CompletableFuture.completedFuture(
                PromptSystem.formatResponse(rawResponse.trim(), stage));
        }

        String toolName = m.group(1);
        String argsJson  = m.group(2).trim();
        String cleanText = rawResponse.substring(0, m.start()).trim();

        VerityMod.LOGGER.info("[VerityAI] Tool call detected: " + toolName + " args=" + argsJson);

        JsonObject args;
        try {
            args = com.google.gson.JsonParser.parseString(argsJson).getAsJsonObject();
        } catch (Exception e) {
            VerityMod.LOGGER.warn("[VerityAI] Bad tool args JSON: " + argsJson);
            args = new JsonObject();
        }

        final JsonObject finalArgs = args;
        return ToolManager.executeTool(toolName, finalArgs).thenApply(toolResult -> {
            PromptSystem.addToolResult(history, toolName, toolResult);
            PromptSystem.addAssistantMessage(history, cleanText);
            conversationHistory.put(playerId, history);

            String display = cleanText.isEmpty()
                ? PromptSystem.formatResponse("✔", stage)
                : PromptSystem.formatResponse(cleanText, stage);
            return display;
        });
    }

    // ------------------------------------------------------------------ //
    //  CLIENT-SIDE processing (for servers that don't have the mod)       //
    // ------------------------------------------------------------------ //

    public static CompletableFuture<String> processMessageClient(String message,
                                                                  Minecraft mc, int stage) {
        String clientId = "client_local";

        if (!initialized) initialize();

        List<JsonObject> history = clientHistory.computeIfAbsent(clientId, k ->
            PromptSystem.createInitialMessagesClient(stage));

        PromptSystem.addUserMessage(history, message);
        final List<JsonObject> trimmed = PromptSystem.trimHistory(history, MAX_HISTORY_SIZE);
        clientHistory.put(clientId, trimmed);

        if (!OllamaManager.isReady()) {
            return CompletableFuture.completedFuture(
                PromptSystem.formatResponse("...Ollama isn't running. Start it with: ollama serve", stage));
        }

        return OllamaManager.chat(trimmed, OllamaManager.getDefaultModel(), null)
            .thenCompose(aiResponse -> {
                VerityMod.LOGGER.info("[VerityAI-Client] Raw: " + aiResponse);
                return handleToolTagClient(aiResponse, trimmed, clientId, stage, mc);
            })
            .exceptionally(ex -> {
                VerityMod.LOGGER.error("[VerityAI-Client] Error: " + ex.getMessage(), ex);
                return PromptSystem.formatResponse("...something went wrong.", stage);
            });
    }

    private static CompletableFuture<String> handleToolTagClient(String rawResponse,
                                                                   List<JsonObject> history,
                                                                   String clientId, int stage,
                                                                   Minecraft mc) {
        Matcher m = TOOL_PATTERN.matcher(rawResponse);
        if (!m.find()) {
            PromptSystem.addAssistantMessage(history, rawResponse);
            clientHistory.put(clientId, history);
            return CompletableFuture.completedFuture(
                PromptSystem.formatResponse(rawResponse.trim(), stage));
        }

        String toolName = m.group(1);
        String argsJson  = m.group(2).trim();
        String cleanText = rawResponse.substring(0, m.start()).trim();

        JsonObject args;
        try {
            args = com.google.gson.JsonParser.parseString(argsJson).getAsJsonObject();
        } catch (Exception e) {
            args = new JsonObject();
        }

        final JsonObject finalArgs = args;
        return ClientToolExecutor.execute(toolName, finalArgs, mc, stage).thenApply(toolResult -> {
            PromptSystem.addToolResult(history, toolName, toolResult);
            PromptSystem.addAssistantMessage(history, cleanText);
            clientHistory.put(clientId, history);

            return cleanText.isEmpty()
                ? PromptSystem.formatResponse("✔", stage)
                : PromptSystem.formatResponse(cleanText, stage);
        });
    }

    // ------------------------------------------------------------------ //
    //  Fallback (server-side, Ollama unavailable)                         //
    // ------------------------------------------------------------------ //

    private static CompletableFuture<String> fallbackResponse(String message, ServerPlayer player,
                                                               MinecraftServer server, int stage) {
        if (VerityAssistant.tryKnowledge(message, player, server, stage)) {
            return CompletableFuture.completedFuture("");
        }

        VerityAssistant.OreEntry ore = VerityAssistant.matchOreFromQuery(message);
        if (ore != null) {
            String oreName = ore.getDisplayName().replace("§", "").replace("f", "");
            VerityAssistant.findOre(oreName, player, server, stage);
            return CompletableFuture.completedFuture("");
        }

        VerityAssistant.StructureEntry structure = VerityAssistant.matchStructureFromQuery(message);
        if (structure != null) {
            String structName = structure.getDisplayName().replace("§", "").replace("f", "");
            VerityAssistant.findStructure(structName, player, server, stage);
            return CompletableFuture.completedFuture("");
        }

        String fallback = switch (stage) {
            case 1 -> "...I cannot help with that right now.";
            case 2 -> "I'm not sure how to help with that.";
            case 3 -> "I don't understand. Could you rephrase?";
            case 4 -> "That's beyond even my knowledge right now.";
            case 5 -> "Even I don't know everything... yet.";
            default -> "I don't understand.";
        };
        // Send privately — only the requesting player sees the fallback
        server.execute(() -> player.sendSystemMessage(
            Component.literal(PromptSystem.formatResponse(fallback, stage))));
        return CompletableFuture.completedFuture(fallback);
    }

    public static void clearHistory(String playerId) {
        conversationHistory.remove(playerId);
    }

    public static void clearClientHistory() {
        clientHistory.clear();
    }

    public static boolean isReady() {
        return initialized && OllamaManager.isReady();
    }

    public static void shutdown() {
        VerityMod.LOGGER.info("[VerityAI] Shutting down...");
        OllamaManager.shutdown();
        conversationHistory.clear();
        clientHistory.clear();
        initialized = false;
    }
}
