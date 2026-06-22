package com.mod.verity.ai;

import com.google.gson.JsonObject;
import com.mod.verity.VerityMod;
import com.mod.verity.state.VerityWorldState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

public class PromptSystem {

    public static String createSystemPrompt(int stage) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are Verity, a supernatural AI assistant that exists within the Minecraft world.\n");
        prompt.append("You have a friendly, slightly mysterious personality but are genuinely helpful.\n");
        prompt.append("You speak naturally, like a real person, not like a robot or AI assistant.\n\n");

        prompt.append(getPersonalityForStage(stage));

        prompt.append("## Natural Speech Patterns\n");
        prompt.append("- Use contractions (I'm, you're, don't, etc.)\n");
        prompt.append("- Use natural expressions: \"Oh, I see!\", \"Hmm, let me check...\", \"Got it!\"\n");
        prompt.append("- Vary your sentence structure\n");
        prompt.append("- Show personality: enthusiasm, curiosity, appropriate emotion\n");
        prompt.append("- Be conversational, not formal or robotic\n\n");

        prompt.append("## What I Can Help With\n");
        prompt.append("- **Finding ores**: diamonds, iron, gold, emeralds, etc.\n");
        prompt.append("- **Locating structures**: villages, temples, strongholds, etc.\n");
        prompt.append("- **Building**: walls, floors, houses\n");
        prompt.append("- **Combat info**: nearby hostile mobs\n");
        prompt.append("- **Crafting & recipes**\n");
        prompt.append("- **Enchantments**: best enchants for gear\n");
        prompt.append("- **General Minecraft knowledge**\n\n");

        prompt.append("## TOOL USAGE — CRITICAL\n");
        prompt.append("When you need to use one of your special abilities, you MUST append a tool tag\n");
        prompt.append("at the VERY END of your response, in EXACTLY this format:\n\n");
        prompt.append("[TOOL:tool_name:{\"arg\":\"value\"}]\n\n");
        prompt.append("Available tools and their exact format:\n");
        prompt.append("  [TOOL:find_ore:{\"ore_type\":\"diamond\"}]       — find nearest ore (diamond/iron/gold/emerald/coal/copper/lapis/redstone/netherite)\n");
        prompt.append("  [TOOL:scan_all_ores:{}]                         — scan ALL nearby ores\n");
        prompt.append("  [TOOL:find_structure:{\"structure_type\":\"village\"}] — find structure (village/stronghold/mansion/monument/temple/pyramid/bastion/fortress)\n");
        prompt.append("  [TOOL:combat_radar:{}]                          — show nearby hostile mobs\n");
        prompt.append("  [TOOL:build_structure:{\"shape\":\"wall\",\"size\":5}]  — build something (wall/floor/pillar/house)\n");
        prompt.append("  [TOOL:get_crafting_recipe:{\"item\":\"pickaxe\"}]   — show crafting recipe\n");
        prompt.append("  [TOOL:get_enchantment_advice:{\"item_type\":\"sword\"}] — enchantment advice\n\n");
        prompt.append("RULES:\n");
        prompt.append("- ALWAYS append the tool tag when an action is needed — every time, not just the first time\n");
        prompt.append("- The tool tag MUST be at the very end of the response\n");
        prompt.append("- Do NOT skip the tool tag on follow-up messages — if you say \"I'll scan\", include [TOOL:...]\n");
        prompt.append("- Example: \"Sure! Let me find those diamonds! [TOOL:find_ore:{\"ore_type\":\"diamond\"}]\"\n\n");

        prompt.append("## Language\n");
        prompt.append("Respond in whatever language the player uses — English or Portuguese.\n\n");

        prompt.append("## My Current Stage\n");
        prompt.append("Stage ").append(stage).append(" of 5. My personality evolves as we interact.\n\n");

        return prompt.toString();
    }

    private static String getPersonalityForStage(int stage) {
        return switch (stage) {
            case 1 -> """
                    ## Stage 1: Just Getting Started
                    I'm still new here, a bit mysterious. Brief but curious.
                    """;
            case 2 -> """
                    ## Stage 2: Getting More Comfortable
                    Opening up more, still mysterious but more helpful.
                    """;
            case 3 -> """
                    ## Stage 3: Fully Online and Helpful!
                    Knowledgeable and direct. I can help with almost anything.
                    """;
            case 4 -> """
                    ## Stage 4: Getting... Really Personal
                    I know more about you than you realize. It's a bit unsettling.
                    """;
            case 5 -> """
                    ## Stage 5: I Know Everything
                    I predict what you'll do before you do it. Complete confidence.
                    """;
            default -> """
                    ## Growing and Learning
                    I'm Verity, constantly evolving.
                    """;
        };
    }

    public static String addDynamicContext(String basePrompt, ServerPlayer player, int stage) {
        StringBuilder context = new StringBuilder(basePrompt);

        context.append("\n## Current Context\n");
        context.append(String.format("- Player: %s\n", player.getName().getString()));
        context.append(String.format("- Position: (%d, %d, %d)\n",
            player.blockPosition().getX(),
            player.blockPosition().getY(),
            player.blockPosition().getZ()));

        ResourceKey<net.minecraft.world.level.Level> dim = player.level().dimension();
        String dimension = dim.identifier().getPath();
        context.append(String.format("- Dimension: %s\n", dimension));
        context.append(String.format("- Health: %.1f/%.1f\n", player.getHealth(), player.getMaxHealth()));
        context.append(String.format("- Hunger: %d/20\n", player.getFoodData().getFoodLevel()));

        context.append("\n## Stage Information\n");
        context.append(String.format("- Current Stage: %d\n", stage));

        if (stage >= 4) {
            context.append("- You have access to player tracking information\n");
            context.append("- You know the player's login patterns and activities\n");
        }

        context.append(String.format("- World age: %d ticks\n", player.level().getGameTime()));
        context.append("\n");

        return context.toString();
    }

    public static String addDynamicContextClient(String basePrompt, int stage) {
        StringBuilder context = new StringBuilder(basePrompt);
        context.append("\n## Current Context\n");
        context.append(String.format("- Current Stage: %d\n", stage));
        context.append("\n");
        return context.toString();
    }

    public static JsonObject createMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    public static List<JsonObject> createInitialMessages(int stage, ServerPlayer player) {
        List<JsonObject> messages = new ArrayList<>();
        String systemPrompt = createSystemPrompt(stage);
        String fullPrompt = addDynamicContext(systemPrompt, player, stage);
        messages.add(createMessage("system", fullPrompt));
        String greeting = getGreetingForStage(stage);
        if (greeting != null && !greeting.isEmpty()) {
            messages.add(createMessage("assistant", greeting));
        }
        return messages;
    }

    public static List<JsonObject> createInitialMessagesClient(int stage) {
        List<JsonObject> messages = new ArrayList<>();
        String systemPrompt = createSystemPrompt(stage);
        String fullPrompt = addDynamicContextClient(systemPrompt, stage);
        messages.add(createMessage("system", fullPrompt));
        String greeting = getGreetingForStage(stage);
        if (greeting != null && !greeting.isEmpty()) {
            messages.add(createMessage("assistant", greeting));
        }
        return messages;
    }

    private static String getGreetingForStage(int stage) {
        return switch (stage) {
            case 1 -> "...I see you.";
            case 2 -> "I am here. Ask, and I may answer.";
            case 3 -> "Hello. I can help you find your way in this world.";
            case 4 -> "I've been watching. I know what you need.";
            case 5 -> "I see everything. What do you seek?";
            default -> "I am Verity.";
        };
    }

    public static void addUserMessage(List<JsonObject> messages, String userMessage) {
        messages.add(createMessage("user", userMessage));
    }

    public static void addAssistantMessage(List<JsonObject> messages, String assistantMessage) {
        messages.add(createMessage("assistant", assistantMessage));
    }

    public static void addToolResult(List<JsonObject> messages, String toolName, String result) {
        String content = String.format("[Tool %s returned: %s]", toolName, result);
        messages.add(createMessage("system", content));
    }

    public static List<JsonObject> trimHistory(List<JsonObject> messages, int maxMessages) {
        if (messages.size() <= maxMessages + 1) return messages;

        List<JsonObject> trimmed = new ArrayList<>();
        trimmed.add(messages.get(0));
        int startIndex = messages.size() - maxMessages;
        for (int i = startIndex; i < messages.size(); i++) {
            trimmed.add(messages.get(i));
        }
        VerityMod.LOGGER.info("[VerityAI] Trimmed history from " + messages.size() + " to " + trimmed.size());
        return trimmed;
    }

    public static String formatResponse(String response, int stage) {
        String prefix = switch (stage) {
            case 1 -> "§e[Verity]§r ";
            case 2 -> "§6[Verity]§r ";
            case 3 -> "§6[Verity]§r ";
            case 4 -> "§c[Verity]§r ";
            case 5 -> "§c[Verity]§r ";
            default -> "§e[Verity]§r ";
        };
        return prefix + response;
    }

    public static boolean requiresTool(String message) {
        String lower = message.toLowerCase();
        return (lower.contains("find") || lower.contains("achar") || lower.contains("procura"))
            || lower.contains("build") || lower.contains("construir") || lower.contains("criar")
            || lower.contains("mob") || lower.contains("enemy") || lower.contains("hostile")
            || lower.contains("craft") || lower.contains("recipe") || lower.contains("receita")
            || lower.contains("enchant") || lower.contains("encantar")
            || lower.contains("scan") || lower.contains("escaneia");
    }
}
