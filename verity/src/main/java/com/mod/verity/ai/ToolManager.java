package com.mod.verity.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mod.verity.VerityMod;
import com.mod.verity.assistant.VerityAssistant;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Manages tools/functions that the AI can call.
 * 
 * This system allows the AI to interact with the Minecraft world through
 * defined functions like finding ores, locating structures, building, etc.
 */
public class ToolManager {
    
    private static final Gson gson = new Gson();
    
    /**
     * Represents a tool that the AI can call.
     */
    public static class Tool {
        public final String name;
        public final String description;
        public final JsonObject parameters;
        public final Function<JsonObject, CompletableFuture<String>> executor;
        
        public Tool(String name, String description, JsonObject parameters, 
                    Function<JsonObject, CompletableFuture<String>> executor) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
            this.executor = executor;
        }
        
        /**
         * Convert this tool to the format expected by Ollama's API.
         */
        public JsonObject toOllamaFormat() {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            
            JsonObject function = new JsonObject();
            function.addProperty("name", name);
            function.addProperty("description", description);
            function.add("parameters", parameters);
            
            tool.add("function", function);
            return tool;
        }
    }
    
    private static final List<Tool> tools = new ArrayList<>();
    private static ServerPlayer currentPlayer = null;
    private static MinecraftServer currentServer = null;
    private static int currentStage = 1;
    
    static {
        registerTools();
    }
    
    /**
     * Register all available tools.
     */
    private static void registerTools() {
        // --- Ore Finding Tools ---
        registerTool(new Tool(
            "find_ore",
            "Find the nearest ore of a specific type within 64 blocks. Returns coordinates and distance.",
            createObjectSchema(Map.of(
                "ore_type", Map.of(
                    "type", "string",
                    "description", "Type of ore to find (diamond, iron, gold, emerald, coal, copper, lapis, redstone, netherite). Supports both English and Portuguese names."
                )
            )),
            params -> CompletableFuture.supplyAsync(() -> {
                String oreType = params.has("ore_type") ? params.get("ore_type").getAsString() : "";
                VerityAssistant.findOre(oreType, currentPlayer, currentServer, currentStage);
                return "Searching for " + oreType + "...";
            })
        ));
        
        registerTool(new Tool(
            "scan_all_ores",
            "Scan for all types of ores nearby and return a ranked list with coordinates.",
            createObjectSchema(Map.of()),
            params -> CompletableFuture.supplyAsync(() -> {
                VerityAssistant.scanAllOres(currentPlayer, currentServer, currentStage);
                return "Scanning for all nearby ores...";
            })
        ));
        
        // --- Structure Finding Tools ---
        registerTool(new Tool(
            "find_structure",
            "Find the nearest structure of a specific type within 100 blocks. Returns coordinates and distance.",
            createObjectSchema(Map.of(
                "structure_type", Map.of(
                    "type", "string",
                    "description", "Type of structure to find (village, stronghold, mansion, monument, temple, pyramid, bastion, fortress). Supports both English and Portuguese names."
                )
            )),
            params -> CompletableFuture.supplyAsync(() -> {
                String structureType = params.has("structure_type") ? params.get("structure_type").getAsString() : "";
                VerityAssistant.findStructure(structureType, currentPlayer, currentServer, currentStage);
                return "Searching for " + structureType + "...";
            })
        ));
        
        // --- Combat Tools ---
        registerTool(new Tool(
            "combat_radar",
            "List all hostile mobs within 64 blocks with their positions and distances.",
            createObjectSchema(Map.of()),
            params -> CompletableFuture.supplyAsync(() -> {
                VerityAssistant.combatRadar(currentPlayer, currentServer, currentStage);
                return "Scanning for hostile mobs...";
            })
        ));
        
        // --- Building Tools ---
        registerTool(new Tool(
            "build_structure",
            "Build a structure in front of the player. Places blocks automatically.",
            createObjectSchema(Map.of(
                "shape", Map.of(
                    "type", "string",
                    "description", "Shape to build (wall, floor, pillar, path, roof, house). Supports both English and Portuguese (parede, chão, pilar, caminho, teto, casa)."
                ),
                "material", Map.of(
                    "type", "string",
                    "description", "Block material to use (stone, wood, cobblestone, dirt, sand, brick, etc.). Supports both English and Portuguese (pedra, madeira, terra, areia, tijolo). If not specified, uses held block or cobblestone."
                ),
                "size", Map.of(
                    "type", "integer",
                    "description", "Size of the structure (default: 5, range: 3-20)"
                )
            )),
            params -> CompletableFuture.supplyAsync(() -> {
                String shape = params.has("shape") ? params.get("shape").getAsString() : "wall";
                String material = params.has("material") ? params.get("material").getAsString() : null;
                int size = params.has("size") ? params.get("size").getAsInt() : 5;
                
                // Build a query string for executeBuild
                String query = "build " + shape;
                if (material != null && !material.isEmpty()) {
                    query += " of " + material;
                }
                query += " " + size;
                
                VerityAssistant.executeBuild(query, currentPlayer, currentServer, currentStage);
                return "Building " + shape + " of size " + size + "...";
            })
        ));
        
        // --- Crafting Advice Tool ---
        registerTool(new Tool(
            "get_crafting_recipe",
            "Get crafting recipe or tips for a specific item.",
            createObjectSchema(Map.of(
                "item", Map.of(
                    "type", "string",
                    "description", "Item to get crafting info for (pickaxe, sword, enchanting table, beacon, elytra, shield, anvil, etc.)"
                )
            )),
            params -> CompletableFuture.supplyAsync(() -> {
                String item = params.has("item") ? params.get("item").getAsString() : "";
                VerityAssistant.craftingAdvice(item, currentPlayer, currentServer, currentStage);
                return "Getting crafting info for " + item + "...";
            })
        ));
        
        // --- Enchantment Advice Tool ---
        registerTool(new Tool(
            "get_enchantment_advice",
            "Get the best enchantments for a specific item type.",
            createObjectSchema(Map.of(
                "item_type", Map.of(
                    "type", "string",
                    "description", "Item type to get enchantment advice for (sword, pickaxe, armor, boots, etc.)"
                )
            )),
            params -> CompletableFuture.supplyAsync(() -> {
                String itemType = params.has("item_type") ? params.get("item_type").getAsString() : "";
                VerityAssistant.enchantAdvice(itemType, currentPlayer, currentServer, currentStage);
                return "Getting enchantment advice for " + itemType + "...";
            })
        ));
        
        // --- Trade Evaluation Tool ---
        registerTool(new Tool(
            "evaluate_villager_trade",
            "Evaluate the nearest villager's trades and profession.",
            createObjectSchema(Map.of()),
            params -> CompletableFuture.supplyAsync(() -> {
                VerityAssistant.evaluateTrade(currentPlayer, currentServer, currentStage);
                return "Evaluating villager trades...";
            })
        ));
        
        VerityMod.LOGGER.info("[VerityAI] Registered " + tools.size() + " tools for AI");
    }
    
    /**
     * Register a new tool.
     */
    private static void registerTool(Tool tool) {
        tools.add(tool);
    }
    
    /**
     * Create a JSON schema object from a map.
     */
    @SuppressWarnings("unchecked")
    private static JsonObject createObjectSchema(Map<String, Object> properties) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey();
            Map<String, String> propSpec = (Map<String, String>) entry.getValue();
            
            JsonObject prop = new JsonObject();
            prop.addProperty("type", propSpec.get("type"));
            if (propSpec.containsKey("description")) {
                prop.addProperty("description", propSpec.get("description"));
            }
            props.add(key, prop);
            
            // Make all parameters optional for now
            // required.add(key);
        }
        
        schema.add("properties", props);
        // schema.add("required", required);
        
        return schema;
    }
    
    /**
     * Get all tools in Ollama format.
     */
    public static List<JsonObject> getToolsForOllama() {
        List<JsonObject> ollamaTools = new ArrayList<>();
        for (Tool tool : tools) {
            ollamaTools.add(tool.toOllamaFormat());
        }
        VerityMod.LOGGER.info("[VerityAI] Returning " + ollamaTools.size() + " tools in Ollama format");
        return ollamaTools;
    }
    
    /**
     * Execute a tool call from the AI.
     */
    public static CompletableFuture<String> executeTool(String toolName, JsonObject parameters) {
        for (Tool tool : tools) {
            if (tool.name.equals(toolName)) {
                VerityMod.LOGGER.info("[VerityAI] Executing tool: " + toolName + " with params: " + parameters);
                return tool.executor.apply(parameters);
            }
        }
        
        VerityMod.LOGGER.warn("[VerityAI] Unknown tool requested: " + toolName);
        return CompletableFuture.completedFuture("Unknown tool: " + toolName);
    }
    
    /**
     * Parse tool calls from AI response.
     * Ollama may return tool calls in the response.
     */
    public static List<ToolCall> parseToolCalls(String aiResponse) {
        List<ToolCall> calls = new ArrayList<>();
        
        try {
            // Try to parse as JSON first (OpenAI format)
            JsonObject responseObj = JsonParser.parseString(aiResponse).getAsJsonObject();
            
            if (responseObj.has("tool_calls")) {
                JsonArray toolCallsArray = responseObj.getAsJsonArray("tool_calls");
                for (int i = 0; i < toolCallsArray.size(); i++) {
                    JsonObject tc = toolCallsArray.get(i).getAsJsonObject();
                    String name = tc.get("name").getAsString();
                    JsonObject args = tc.getAsJsonObject("arguments");
                    calls.add(new ToolCall(name, args));
                }
            } else if (responseObj.has("function_call")) {
                // Alternative format
                JsonObject fc = responseObj.getAsJsonObject("function_call");
                String name = fc.get("name").getAsString();
                JsonObject args = fc.getAsJsonObject("arguments");
                calls.add(new ToolCall(name, args));
            } else if (responseObj.has("function")) {
                // Another alternative format
                JsonObject func = responseObj.getAsJsonObject("function");
                if (func.has("name")) {
                    String name = func.get("name").getAsString();
                    JsonObject args = func.has("arguments") ? func.getAsJsonObject("arguments") : new JsonObject();
                    calls.add(new ToolCall(name, args));
                }
            }
        } catch (Exception e) {
            VerityMod.LOGGER.debug("[VerityAI] Not a JSON response with tool calls, trying alternative formats");
            
            // Try to parse tool calls from markdown code blocks
            if (aiResponse.contains("```json")) {
                // Extract JSON from code block
                int start = aiResponse.indexOf("```json") + 7;
                int end = aiResponse.indexOf("```", start);
                if (end > start) {
                    try {
                        String jsonStr = aiResponse.substring(start, end).trim();
                        JsonObject jsonObj = JsonParser.parseString(jsonStr).getAsJsonObject();
                        
                        if (jsonObj.has("tool") || jsonObj.has("function")) {
                            String name = jsonObj.has("tool") ? jsonObj.get("tool").getAsString() : jsonObj.get("function").getAsString();
                            JsonObject args = jsonObj.has("arguments") ? jsonObj.getAsJsonObject("arguments") : new JsonObject();
                            calls.add(new ToolCall(name, args));
                        }
                    } catch (Exception ex) {
                        VerityMod.LOGGER.warn("[VerityAI] Failed to parse tool call from code block: " + ex.getMessage());
                    }
                }
            }
            
            // Try to parse simple text format like "call function_name(arg1=value1, arg2=value2)"
            if (aiResponse.toLowerCase().contains("call") || aiResponse.toLowerCase().contains("use")) {
                parseSimpleToolCall(aiResponse, calls);
            }
        }
        
        return calls;
    }
    
    /**
     * Parse simple text format tool calls.
     */
    private static void parseSimpleToolCall(String response, List<ToolCall> calls) {
        // Look for patterns like "use find_ore" or "call find_structure"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "(?:use|call)\\s+(\\w+)(?:\\s+(.+))?", 
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        
        java.util.regex.Matcher matcher = pattern.matcher(response);
        while (matcher.find()) {
            String toolName = matcher.group(1);
            String argsStr = matcher.group(2) != null ? matcher.group(2) : "";
            
            JsonObject args = new JsonObject();
            // Parse arguments if present
            if (!argsStr.isEmpty()) {
                // Simple parsing for key=value pairs
                String[] argPairs = argsStr.split(",\\s*");
                for (String pair : argPairs) {
                    String[] kv = pair.split("=");
                    if (kv.length == 2) {
                        args.addProperty(kv[0].trim(), kv[1].trim());
                    }
                }
            }
            
            calls.add(new ToolCall(toolName, args));
        }
    }
    
    /**
     * Represents a tool call from the AI.
     */
    public static class ToolCall {
        public final String toolName;
        public final JsonObject arguments;
        
        public ToolCall(String toolName, JsonObject arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }
    }
    
    /**
     * Set the current context for tool execution.
     */
    public static void setContext(ServerPlayer player, MinecraftServer server, int stage) {
        currentPlayer = player;
        currentServer = server;
        currentStage = stage;
    }
    
    /**
     * Get the current player.
     */
    public static ServerPlayer getCurrentPlayer() {
        return currentPlayer;
    }
    
    /**
     * Get the current server.
     */
    public static MinecraftServer getCurrentServer() {
        return currentServer;
    }
    
    /**
     * Get the current stage.
     */
    public static int getCurrentStage() {
        return currentStage;
    }
    
    /**
     * Send a message to the player through the server.
     */
    public static void sendMessage(String message) {
        if (currentServer != null) {
            currentServer.execute(() -> {
                currentServer.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
            });
        }
    }
}
