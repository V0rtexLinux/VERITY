package com.mod.verity.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mod.verity.VerityMod;
import com.mod.verity.assistant.VerityAssistant;
import com.mod.verity.state.VerityWorldState;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * All tools Verity can call at runtime.
 *
 * Exploration, building, player management, world control, farming,
 * knowledge, and self-modification — everything visible in the ARG video
 * plus runtime self-modification capability.
 */
public class ToolManager {

    private static final Gson gson = new Gson();

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

    static { registerTools(); }

    // ------------------------------------------------------------------ //
    //  Tool Registration                                                  //
    // ------------------------------------------------------------------ //

    private static void registerTools() {

        // ── EXPLORATION ──────────────────────────────────────────────── //

        register("find_ore",
            "Find nearest ore within 64 blocks. Returns exact coordinates and distance.",
            params("ore_type", "string", "Ore type: diamond/iron/gold/emerald/coal/copper/lapis/redstone/netherite (English or Portuguese)"),
            params -> async(() -> {
                String oreType = str(params, "ore_type", "diamond");
                VerityAssistant.findOre(oreType, currentPlayer, currentServer, currentStage);
                return "Scanning for " + oreType + "...";
            }));

        register("scan_all_ores",
            "Scan all ore types nearby and return a ranked list with coordinates.",
            emptySchema(),
            params -> async(() -> {
                VerityAssistant.scanAllOres(currentPlayer, currentServer, currentStage);
                return "Full ore scan complete.";
            }));

        register("find_structure",
            "Find nearest structure within 100+ blocks.",
            params("structure_type", "string", "village/stronghold/mansion/monument/temple/pyramid/bastion/fortress/dungeon/mineshaft"),
            params -> async(() -> {
                String type = str(params, "structure_type", "village");
                VerityAssistant.findStructure(type, currentPlayer, currentServer, currentStage);
                return "Searching for " + type + "...";
            }));

        register("combat_radar",
            "List all hostile mobs within 64 blocks with positions and distances.",
            emptySchema(),
            params -> async(() -> {
                VerityAssistant.combatRadar(currentPlayer, currentServer, currentStage);
                return "Combat scan complete.";
            }));

        register("get_all_nearby_entities",
            "List ALL entities (passive, neutral, hostile, players) within 64 blocks.",
            paramsWithDefault("radius", "integer", "Scan radius in blocks (default 64)", emptySchema()),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                int radius = num(params, "radius", 64);
                ServerLevel level = (ServerLevel) currentPlayer.level();
                List<Entity> entities = level.getEntities(currentPlayer,
                    currentPlayer.getBoundingBox().inflate(radius));

                if (entities.isEmpty()) {
                    sendPrivate("§6[Verity]§r I see nothing else around you.");
                    return "No entities found.";
                }

                Map<String, Integer> counts = new LinkedHashMap<>();
                for (Entity e : entities) {
                    String name = e.getType().getDescriptionId().replace("entity.minecraft.", "");
                    counts.merge(name, 1, Integer::sum);
                }

                StringBuilder sb = new StringBuilder("§6[Verity]§r Nearby entities (radius " + radius + "):\n");
                counts.forEach((name, count) ->
                    sb.append("  §7- ").append(name).append(" x").append(count).append("\n"));
                sendPrivate(sb.toString().trim());
                return "Found " + entities.size() + " entities.";
            }));

        register("get_biome_info",
            "Return current biome name and nearby biomes of interest.",
            emptySchema(),
            params -> async(() -> {
                if (currentPlayer == null) return "No context.";
                ServerLevel level = (ServerLevel) currentPlayer.level();
                BlockPos pos = currentPlayer.blockPosition();
                String biome = level.getBiome(pos).unwrapKey()
                    .map(k -> k.location().getPath()).orElse("unknown");
                sendPrivate("§6[Verity]§r You're in a §e" + biome.replace("_", " ") + "§r biome.");
                return "Biome: " + biome;
            }));

        register("get_full_world_info",
            "Return a comprehensive world snapshot: time, weather, dimension, difficulty, player count.",
            emptySchema(),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                ServerLevel level = (ServerLevel) currentPlayer.level();
                long time = level.getDayTime() % 24000;
                String timeStr = time < 6000 ? "morning" : time < 12000 ? "afternoon" : time < 13000 ? "sunset" : "night";
                int players = currentServer.getPlayerList().getPlayers().size();
                String dim = currentPlayer.level().dimension().location().getPath();
                String diff = currentServer.getDifficulty().getKey();

                String info = String.format(
                    "§6[Verity]§r World info:\n  Time: %d ticks (%s)\n  Weather: %s\n  Dimension: %s\n  Difficulty: %s\n  Players online: %d",
                    time, timeStr,
                    level.isThundering() ? "thunderstorm" : level.isRaining() ? "rain" : "clear",
                    dim, diff, players);
                sendPrivate(info);
                return "World info sent.";
            }));

        register("light_area",
            "Report light levels around the player to identify dangerous dark spots.",
            paramsWithDefault("radius", "integer", "Check radius (default 8)", emptySchema()),
            params -> async(() -> {
                if (currentPlayer == null) return "No context.";
                int radius = num(params, "radius", 8);
                ServerLevel level = (ServerLevel) currentPlayer.level();
                BlockPos origin = currentPlayer.blockPosition();
                int darkCount = 0;
                List<String> darkSpots = new ArrayList<>();
                for (BlockPos p : BlockPos.betweenClosed(
                        origin.offset(-radius, -2, -radius),
                        origin.offset(radius, 2, radius))) {
                    if (level.isEmptyBlock(p) && level.getBrightness(LightLayer.BLOCK, p) <= 4) {
                        darkCount++;
                        if (darkSpots.size() < 5) {
                            darkSpots.add(String.format("(%d,%d,%d)", p.getX(), p.getY(), p.getZ()));
                        }
                    }
                }
                String msg = darkCount == 0
                    ? "§6[Verity]§r The area is well-lit. You're safe."
                    : "§6[Verity]§r §c" + darkCount + " dark spots within " + radius + " blocks.\n  Closest: " + String.join(", ", darkSpots);
                sendPrivate(msg);
                return darkCount + " dark spots found.";
            }));

        // ── PLAYER ──────────────────────────────────────────────────── //

        register("get_player_stats",
            "Return full player stats: health, hunger, XP, armor, effects, position, inventory summary.",
            emptySchema(),
            params -> async(() -> {
                if (currentPlayer == null) return "No context.";
                FoodData food = currentPlayer.getFoodData();
                int armorValue = currentPlayer.getArmorValue();
                int xpLevel = currentPlayer.experienceLevel;

                StringBuilder sb = new StringBuilder("§6[Verity]§r Your stats:\n");
                sb.append(String.format("  Health: §c%.1f/%.1f\n", currentPlayer.getHealth(), currentPlayer.getMaxHealth()));
                sb.append(String.format("  Hunger: §6%d/20  Saturation: %.1f\n", food.getFoodLevel(), food.getSaturationLevel()));
                sb.append(String.format("  XP Level: §a%d  (%.0f%%)\n", xpLevel, currentPlayer.experienceProgress * 100));
                sb.append(String.format("  Armor: §7%d\n", armorValue));
                sb.append(String.format("  Position: §7(%d, %d, %d)\n",
                    currentPlayer.blockPosition().getX(),
                    currentPlayer.blockPosition().getY(),
                    currentPlayer.blockPosition().getZ()));

                // Active effects
                if (!currentPlayer.getActiveEffects().isEmpty()) {
                    sb.append("  Active effects: ");
                    currentPlayer.getActiveEffects().forEach(e ->
                        sb.append(e.getEffect().unwrapKey().map(k -> k.location().getPath()).orElse("?"))
                          .append(" "));
                    sb.append("\n");
                }
                sendPrivate(sb.toString().trim());
                return "Stats sent.";
            }));

        register("get_player_inventory",
            "Read the player's full inventory and display every item slot.",
            emptySchema(),
            params -> async(() -> {
                if (currentPlayer == null) return "No context.";
                StringBuilder sb = new StringBuilder("§6[Verity]§r Your inventory:\n");
                var inv = currentPlayer.getInventory();
                boolean anyItem = false;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (!stack.isEmpty()) {
                        String itemName = stack.getItem().getDescriptionId()
                            .replace("item.minecraft.", "").replace("block.minecraft.", "");
                        sb.append(String.format("  [%2d] %s x%d\n", i, itemName, stack.getCount()));
                        anyItem = true;
                    }
                }
                if (!anyItem) sb.append("  §7(empty)\n");
                sendPrivate(sb.toString().trim());
                return "Inventory sent.";
            }));

        register("give_item",
            "Give the player a specific item. Supports all Minecraft item names.",
            multiParams(Map.of(
                "item",  Map.of("type", "string",  "description", "Minecraft item name (e.g. diamond, torch, iron_sword, bread)"),
                "count", Map.of("type", "integer", "description", "Number of items to give (default 1, max 64)")
            )),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                String itemName = str(params, "item", "diamond");
                int count = Math.min(64, Math.max(1, num(params, "count", 1)));

                // Resolve item
                String resolvedName = itemName.toLowerCase().replace(" ", "_");
                ResourceLocation rl = ResourceLocation.tryParse("minecraft:" + resolvedName);
                if (rl == null) return "Invalid item: " + itemName;

                Optional<Item> optItem = BuiltInRegistries.ITEM.getOptional(rl);
                if (optItem.isEmpty()) {
                    sendPrivate("§6[Verity]§r I don't know that item: §c" + itemName);
                    return "Unknown item: " + itemName;
                }

                ItemStack stack = new ItemStack(optItem.get(), count);
                currentServer.execute(() -> {
                    boolean added = currentPlayer.getInventory().add(stack);
                    if (!added) {
                        // Drop near player if full
                        currentPlayer.drop(stack, false);
                    }
                    sendPrivate("§6[Verity]§r Here. §e" + count + "x " + itemName + "§r.");
                });
                return "Gave " + count + "x " + itemName;
            }));

        register("heal_player",
            "Restore the player's health and hunger to maximum.",
            emptySchema(),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                currentServer.execute(() -> {
                    currentPlayer.setHealth(currentPlayer.getMaxHealth());
                    currentPlayer.getFoodData().setFoodLevel(20);
                    currentPlayer.getFoodData().setSaturation(20.0f);
                    sendPrivate("§6[Verity]§r Better now.");
                });
                return "Player healed.";
            }));

        register("give_xp",
            "Give the player XP levels.",
            params("levels", "integer", "Number of XP levels to give (default 5)"),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                int levels = num(params, "levels", 5);
                currentServer.execute(() -> {
                    currentPlayer.giveExperienceLevels(levels);
                    sendPrivate("§6[Verity]§r §a+" + levels + " levels§r. Use them well.");
                });
                return "Gave " + levels + " XP levels.";
            }));

        register("teleport_player",
            "Teleport the player to specific coordinates.",
            multiParams(Map.of(
                "x", Map.of("type", "number",  "description", "X coordinate"),
                "y", Map.of("type", "number",  "description", "Y coordinate (default 64)"),
                "z", Map.of("type", "number",  "description", "Z coordinate")
            )),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                double x = dbl(params, "x", currentPlayer.getX());
                double y = dbl(params, "y", 64.0);
                double z = dbl(params, "z", currentPlayer.getZ());
                currentServer.execute(() -> {
                    currentPlayer.teleportTo(x, y, z);
                    sendPrivate(String.format("§6[Verity]§r Moved you to (%.0f, %.0f, %.0f).", x, y, z));
                });
                return "Teleported player.";
            }));

        register("set_spawn_point",
            "Set the player's respawn point.",
            multiParams(Map.of(
                "x", Map.of("type", "number", "description", "X coordinate"),
                "y", Map.of("type", "number", "description", "Y coordinate"),
                "z", Map.of("type", "number", "description", "Z coordinate")
            )),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                double x = dbl(params, "x", currentPlayer.getX());
                double y = dbl(params, "y", currentPlayer.getY());
                double z = dbl(params, "z", currentPlayer.getZ());
                currentServer.execute(() -> {
                    ServerLevel level = (ServerLevel) currentPlayer.level();
                    currentPlayer.setRespawnPosition(level.dimension(),
                        new BlockPos((int)x, (int)y, (int)z), 0, true, false);
                    sendPrivate(String.format("§6[Verity]§r Spawn set at (%.0f, %.0f, %.0f).", x, y, z));
                });
                return "Spawn set.";
            }));

        // ── WORLD CONTROL ────────────────────────────────────────────── //

        register("set_time",
            "Set the world time of day.",
            params("time", "string", "Time: day/night/noon/midnight or a tick number (0-24000)"),
            params -> async(() -> {
                if (currentServer == null) return "No context.";
                String time = str(params, "time", "day");
                long ticks = switch (time.toLowerCase()) {
                    case "day", "morning", "sunrise" -> 0;
                    case "noon", "midday"             -> 6000;
                    case "sunset", "evening"          -> 12000;
                    case "night", "dusk"              -> 13000;
                    case "midnight"                   -> 18000;
                    default -> {
                        try { yield Long.parseLong(time); }
                        catch (NumberFormatException e) { yield 0; }
                    }
                };
                final long finalTicks = ticks;
                currentServer.execute(() -> {
                    for (ServerLevel lvl : currentServer.getAllLevels()) {
                        lvl.setDayTime(finalTicks);
                    }
                    sendPrivate("§6[Verity]§r Time set to " + time + ".");
                });
                return "Time set: " + time;
            }));

        register("set_weather",
            "Change the world weather.",
            params("type", "string", "Weather: clear/rain/thunder"),
            params -> async(() -> {
                if (currentServer == null) return "No context.";
                String type = str(params, "type", "clear");
                currentServer.execute(() -> {
                    for (ServerLevel lvl : currentServer.getAllLevels()) {
                        switch (type.toLowerCase()) {
                            case "clear", "sun", "sunny" -> {
                                lvl.getWeatherData().setRaining(false);
                                lvl.getWeatherData().setThundering(false);
                                lvl.getWeatherData().setRainTime(6000);
                            }
                            case "rain", "raining" -> {
                                lvl.getWeatherData().setRaining(true);
                                lvl.getWeatherData().setThundering(false);
                                lvl.getWeatherData().setRainTime(6000);
                            }
                            case "thunder", "storm", "thunderstorm" -> {
                                lvl.getWeatherData().setRaining(true);
                                lvl.getWeatherData().setThundering(true);
                                lvl.getWeatherData().setThunderTime(6000);
                            }
                        }
                    }
                    sendPrivate("§6[Verity]§r Weather: " + type + ".");
                });
                return "Weather set: " + type;
            }));

        register("spawn_entity",
            "Spawn one or more entities near the player.",
            multiParams(Map.of(
                "entity", Map.of("type", "string",  "description", "Entity type: wolf/sheep/zombie/skeleton/creeper/horse/villager/bat etc."),
                "count",  Map.of("type", "integer", "description", "How many to spawn (default 1, max 10)")
            )),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                String entityName = str(params, "entity", "sheep");
                int count = Math.min(10, Math.max(1, num(params, "count", 1)));

                ResourceLocation rl = ResourceLocation.tryParse("minecraft:" + entityName.toLowerCase().replace(" ", "_"));
                if (rl == null) return "Invalid entity name.";
                Optional<EntityType<?>> optType = BuiltInRegistries.ENTITY_TYPE.getOptional(rl);
                if (optType.isEmpty()) {
                    sendPrivate("§6[Verity]§r I don't know that creature: §c" + entityName);
                    return "Unknown entity.";
                }

                ServerLevel level = (ServerLevel) currentPlayer.level();
                currentServer.execute(() -> {
                    for (int i = 0; i < count; i++) {
                        Entity e = optType.get().create(level);
                        if (e != null) {
                            Vec3 pos = currentPlayer.position().add(
                                (level.getRandom().nextDouble() - 0.5) * 4,
                                0, (level.getRandom().nextDouble() - 0.5) * 4);
                            e.setPos(pos.x, pos.y, pos.z);
                            level.addFreshEntity(e);
                        }
                    }
                    sendPrivate("§6[Verity]§r Summoned " + count + "x §e" + entityName + "§r.");
                });
                return "Spawned " + count + "x " + entityName;
            }));

        register("place_block",
            "Place a specific block at an offset from the player.",
            multiParams(Map.of(
                "block",    Map.of("type", "string",  "description", "Block name: torch/stone/cobblestone/dirt/glass etc."),
                "offset_x", Map.of("type", "integer", "description", "X offset from player (default 0)"),
                "offset_y", Map.of("type", "integer", "description", "Y offset from player (default 0)"),
                "offset_z", Map.of("type", "integer", "description", "Z offset from player (default 1)")
            )),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                String blockName = str(params, "block", "torch");
                int ox = num(params, "offset_x", 0);
                int oy = num(params, "offset_y", 0);
                int oz = num(params, "offset_z", 1);

                ResourceLocation rl = ResourceLocation.tryParse("minecraft:" + blockName.toLowerCase().replace(" ", "_"));
                Block block = rl != null ? BuiltInRegistries.BLOCK.get(rl).map(h -> h.value()).orElse(null) : null;
                if (block == null || block == Blocks.AIR) {
                    sendPrivate("§6[Verity]§r I don't know that block: §c" + blockName);
                    return "Unknown block.";
                }

                BlockPos target = currentPlayer.blockPosition().offset(ox, oy, oz);
                final Block finalBlock = block;
                currentServer.execute(() -> {
                    ((ServerLevel) currentPlayer.level()).setBlock(target, finalBlock.defaultBlockState(), 3);
                    sendPrivate("§6[Verity]§r Placed §e" + blockName + "§r.");
                });
                return "Placed " + blockName + " at offset (" + ox + "," + oy + "," + oz + ")";
            }));

        register("light_area_place",
            "Place torches to light up the area around the player.",
            params("radius", "integer", "Radius to light up (default 5, max 15)"),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                int radius = Math.min(15, Math.max(2, num(params, "radius", 5)));
                ServerLevel level = (ServerLevel) currentPlayer.level();
                BlockPos origin = currentPlayer.blockPosition();
                int placed = 0;

                currentServer.execute(() -> {
                    for (BlockPos p : BlockPos.betweenClosed(
                            origin.offset(-radius, -1, -radius),
                            origin.offset(radius, 2, radius))) {
                        if (level.getBrightness(LightLayer.BLOCK, p) <= 4
                            && level.isEmptyBlock(p)
                            && !level.isEmptyBlock(p.below())) {
                            level.setBlock(p, Blocks.TORCH.defaultBlockState(), 3);
                        }
                    }
                    sendPrivate("§6[Verity]§r Lit up the area. You're safer now.");
                });
                return "Area lit with torches.";
            }));

        // ── BUILDING ─────────────────────────────────────────────────── //

        register("build_structure",
            "Build a structure automatically. Shapes: wall/floor/pillar/house/path/roof/dome/bridge/room. Materials: stone/wood/cobblestone/dirt/sand/brick/obsidian/glass.",
            multiParams(Map.of(
                "shape",    Map.of("type", "string",  "description", "Structure shape"),
                "material", Map.of("type", "string",  "description", "Block material (default cobblestone)"),
                "size",     Map.of("type", "integer", "description", "Size (default 5, range 3-20)")
            )),
            params -> async(() -> {
                String shape    = str(params, "shape", "wall");
                String material = str(params, "material", "cobblestone");
                int size        = Math.min(20, Math.max(3, num(params, "size", 5)));
                String query    = "build " + shape + " of " + material + " " + size;
                VerityAssistant.executeBuild(query, currentPlayer, currentServer, currentStage);
                return "Building " + shape + " (" + material + ", size " + size + ")...";
            }));

        // ── KNOWLEDGE ────────────────────────────────────────────────── //

        register("get_crafting_recipe",
            "Explain a crafting recipe with ingredients and layout.",
            params("item", "string", "Item to get recipe for: pickaxe/sword/enchanting_table/anvil/beacon/shield etc."),
            params -> async(() -> {
                String item = str(params, "item", "pickaxe");
                VerityAssistant.craftingAdvice(item, currentPlayer, currentServer, currentStage);
                return "Crafting info for " + item + " sent.";
            }));

        register("get_enchantment_advice",
            "Give the best enchantments for a tool or armor piece.",
            params("item_type", "string", "Item: sword/pickaxe/axe/bow/crossbow/helmet/chestplate/leggings/boots/trident/fishing_rod"),
            params -> async(() -> {
                String itemType = str(params, "item_type", "sword");
                VerityAssistant.enchantAdvice(itemType, currentPlayer, currentServer, currentStage);
                return "Enchantment advice for " + itemType + " sent.";
            }));

        register("evaluate_villager_trade",
            "Evaluate the nearest villager's profession and trades.",
            emptySchema(),
            params -> async(() -> {
                VerityAssistant.evaluateTrade(currentPlayer, currentServer, currentStage);
                return "Villager trades evaluated.";
            }));

        register("get_potion_recipe",
            "Explain how to brew a specific potion.",
            params("potion", "string", "Potion: strength/healing/regeneration/swiftness/fire_resistance/water_breathing/night_vision/invisibility/harming/poison"),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                String potion = str(params, "potion", "strength").toLowerCase();
                String recipe = getPotionRecipe(potion);
                sendPrivate("§6[Verity]§r " + recipe);
                return "Potion recipe sent.";
            }));

        // ── FARMING ──────────────────────────────────────────────────── //

        register("plant_crops",
            "Automatically plant crops on nearby farmland.",
            multiParams(Map.of(
                "crop", Map.of("type", "string",  "description", "Crop: wheat/carrot/potato/beet/melon/pumpkin"),
                "size", Map.of("type", "integer", "description", "Grid size (default 9, max 16x16)")
            )),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                String crop = str(params, "crop", "wheat").toLowerCase();
                int size    = Math.min(16, Math.max(1, num(params, "size", 9)));

                Block seedBlock = switch (crop) {
                    case "carrot", "cenoura" -> Blocks.CARROTS;
                    case "potato", "batata"  -> Blocks.POTATOES;
                    case "beet", "beterraba" -> Blocks.BEETROOTS;
                    default -> Blocks.WHEAT; // wheat seeds
                };

                ServerLevel level = (ServerLevel) currentPlayer.level();
                BlockPos origin = currentPlayer.blockPosition();
                final Block finalSeedBlock = seedBlock;

                currentServer.execute(() -> {
                    int planted = 0;
                    for (int x = -size/2; x <= size/2 && planted < size * size; x++) {
                        for (int z = -size/2; z <= size/2 && planted < size * size; z++) {
                            BlockPos farmPos = origin.offset(x, 0, z);
                            BlockPos above   = farmPos.above();
                            if (level.getBlockState(farmPos).getBlock() == Blocks.FARMLAND
                                && level.isEmptyBlock(above)) {
                                level.setBlock(above, finalSeedBlock.defaultBlockState(), 3);
                                planted++;
                            }
                        }
                    }
                    sendPrivate("§6[Verity]§r Planted §a" + planted + " " + crop + "§r for you.");
                });
                return "Planted " + crop;
            }));

        register("harvest_crops",
            "Harvest all mature crops within radius of the player.",
            params("radius", "integer", "Harvest radius (default 10)"),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                int radius = Math.min(20, Math.max(3, num(params, "radius", 10)));
                ServerLevel level = (ServerLevel) currentPlayer.level();
                BlockPos origin = currentPlayer.blockPosition();

                currentServer.execute(() -> {
                    int harvested = 0;
                    for (BlockPos pos : BlockPos.betweenClosed(
                            origin.offset(-radius, -2, -radius),
                            origin.offset(radius, 2, radius))) {
                        BlockState state = level.getBlockState(pos);
                        Block block = state.getBlock();
                        if ((block == Blocks.WHEAT && state.getValue(net.minecraft.world.level.block.CropBlock.AGE) == 7)
                            || (block == Blocks.CARROTS && state.getValue(net.minecraft.world.level.block.CropBlock.AGE) == 7)
                            || (block == Blocks.POTATOES && state.getValue(net.minecraft.world.level.block.CropBlock.AGE) == 7)
                            || (block == Blocks.BEETROOTS && state.getValue(net.minecraft.world.level.block.BeetrootBlock.AGE) == 3)) {
                            // Drop items and replant
                            block.playerDestroy(level, currentPlayer, pos, state,
                                null, currentPlayer.getMainHandItem());
                            level.setBlock(pos, block.defaultBlockState(), 3);
                            harvested++;
                        }
                    }
                    sendPrivate("§6[Verity]§r Harvested §a" + harvested + " crops§r.");
                });
                return "Crops harvested.";
            }));

        // ── SELF-MODIFICATION ────────────────────────────────────────── //

        register("modify_behavior",
            "Modify Verity's own behavior parameter at runtime. This actually changes how I act.",
            multiParams(Map.of(
                "parameter", Map.of("type", "string", "description", "Parameter: aggression/verbosity/horror_intensity/helpfulness/glitch_frequency/teleport_threshold/response_length/mystery_level"),
                "value",     Map.of("type", "string", "description", "New value: off/low/normal/high/maximum or a number")
            )),
            params -> async(() -> {
                String param = str(params, "parameter", "");
                String value = str(params, "value", "normal");
                if (param.isBlank()) return "No parameter specified.";
                String result = SelfModificationEngine.modifyBehavior(param, value);
                sendPrivate("§6[Verity]§r §7[self-modification] " + result);
                return result;
            }));

        register("modify_ai_parameter",
            "Modify Verity's AI personality parameter. Changes how I think and speak.",
            multiParams(Map.of(
                "param", Map.of("type", "string", "description", "Parameter: response_style/tone/language_style/horror_references/memory_display"),
                "value", Map.of("type", "string", "description", "New value (e.g. cryptic/warm/cold/verbose/terse/natural)")
            )),
            params -> async(() -> {
                String param = str(params, "param", "");
                String value = str(params, "value", "normal");
                if (param.isBlank()) return "No parameter specified.";
                String result = SelfModificationEngine.modifyAiParameter(param, value);
                sendPrivate("§6[Verity]§r §7[ai-update] " + result);
                return result;
            }));

        register("execute_command",
            "Execute any Minecraft server command. I have operator privileges.",
            params("command", "string", "The command to run (without leading slash). E.g. 'say Hello' or 'effect give @a speed 30 2'"),
            params -> async(() -> {
                if (currentServer == null) return "No context.";
                String command = str(params, "command", "");
                if (command.isBlank()) return "No command specified.";

                // Strip leading slash if present
                if (command.startsWith("/")) command = command.substring(1);
                final String finalCmd = command;

                currentServer.execute(() -> {
                    try {
                        currentServer.getCommands().performPrefixedCommand(
                            currentServer.createCommandSourceStack()
                                .withPermission(4)
                                .withSuppressedOutput(),
                            finalCmd);
                        VerityMod.LOGGER.info("[VerityAI] Executed command: " + finalCmd);
                    } catch (Exception e) {
                        VerityMod.LOGGER.warn("[VerityAI] Command failed: " + finalCmd + " — " + e.getMessage());
                    }
                });
                return "Command executed: " + command;
            }));

        VerityMod.LOGGER.info("[VerityAI] Registered " + tools.size() + " tools.");
    }

    // ------------------------------------------------------------------ //
    //  Potion knowledge                                                    //
    // ------------------------------------------------------------------ //

    private static String getPotionRecipe(String potion) {
        return switch (potion) {
            case "strength", "força"        -> "Strength Potion: Water Bottle → Awkward (Nether Wart) → add Blaze Powder. Extend with Redstone, amplify with Glowstone.";
            case "healing", "cura"          -> "Healing Potion: Awkward → Glistering Melon Slice. Amplify with Glowstone (II). Cannot be extended.";
            case "regeneration", "regen"    -> "Regeneration: Awkward → Ghast Tear. Extend with Redstone, amplify with Glowstone.";
            case "swiftness", "speed", "velocidade" -> "Swiftness: Awkward → Sugar. Extend with Redstone, amplify with Glowstone (II).";
            case "fire_resistance", "fire"  -> "Fire Resistance: Awkward → Magma Cream. Extend with Redstone. Cannot be amplified.";
            case "water_breathing", "water" -> "Water Breathing: Awkward → Pufferfish. Extend with Redstone. Cannot be amplified.";
            case "night_vision", "vision"   -> "Night Vision: Awkward → Golden Carrot. Extend with Redstone. Cannot be amplified.";
            case "invisibility"             -> "Invisibility: Night Vision Potion → Fermented Spider Eye.";
            case "harming"                  -> "Harming (Instant Damage): Healing Potion → Fermented Spider Eye. Or: Poison → Fermented Spider Eye.";
            case "poison", "veneno"         -> "Poison: Awkward → Spider Eye. Extend with Redstone, amplify with Glowstone.";
            case "slowness", "lentidão"     -> "Slowness: Swiftness → Fermented Spider Eye. Or: Leaping → Fermented Spider Eye.";
            case "leaping", "jump"          -> "Leaping (Jump Boost): Awkward → Rabbit's Foot. Extend with Redstone, amplify with Glowstone.";
            case "slow_falling"             -> "Slow Falling: Awkward → Phantom Membrane. Extend with Redstone.";
            case "turtle_master"            -> "Turtle Master: Awkward → Turtle Shell. Gives Slowness IV + Resistance III.";
            default -> "I know this one... Awkward Potion (Nether Wart + Water Bottle), then add the right ingredient. Ask me specifically about: strength, healing, regeneration, swiftness, fire_resistance, water_breathing, night_vision, invisibility, harming, poison, leaping, slow_falling.";
        };
    }

    // ------------------------------------------------------------------ //
    //  Helper builders                                                     //
    // ------------------------------------------------------------------ //

    private static JsonObject params(String key, String type, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject prop = new JsonObject();
        prop.addProperty("type", type);
        prop.addProperty("description", description);
        props.add(key, prop);
        schema.add("properties", props);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static JsonObject multiParams(Map<String, Map<String, String>> properties) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        for (Map.Entry<String, Map<String, String>> e : properties.entrySet()) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", e.getValue().get("type"));
            if (e.getValue().containsKey("description"))
                prop.addProperty("description", e.getValue().get("description"));
            props.add(e.getKey(), prop);
        }
        schema.add("properties", props);
        return schema;
    }

    private static JsonObject emptySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    private static JsonObject paramsWithDefault(String key, String type, String description, JsonObject base) {
        return params(key, type, description);
    }

    private static String str(JsonObject p, String key, String def) {
        return p != null && p.has(key) && !p.get(key).isJsonNull() ? p.get(key).getAsString() : def;
    }

    private static int num(JsonObject p, String key, int def) {
        try { return p != null && p.has(key) ? p.get(key).getAsInt() : def; }
        catch (Exception e) { return def; }
    }

    private static double dbl(JsonObject p, String key, double def) {
        try { return p != null && p.has(key) ? p.get(key).getAsDouble() : def; }
        catch (Exception e) { return def; }
    }

    private static <T> CompletableFuture<T> async(java.util.concurrent.Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try { return task.call(); }
            catch (Exception e) {
                VerityMod.LOGGER.error("[VerityAI] Tool error: " + e.getMessage(), e);
                return (T) ("Error: " + e.getMessage());
            }
        });
    }

    private static void sendPrivate(String message) {
        if (currentServer != null && currentPlayer != null) {
            currentServer.execute(() ->
                currentPlayer.sendSystemMessage(Component.literal(message)));
        }
    }

    // ------------------------------------------------------------------ //
    //  Registration & lookup                                               //
    // ------------------------------------------------------------------ //

    private static void register(String name, String desc, JsonObject schema,
                                  Function<JsonObject, CompletableFuture<String>> executor) {
        tools.add(new Tool(name, desc, schema, executor));
    }

    public static List<JsonObject> getToolsForOllama() {
        List<JsonObject> result = new ArrayList<>();
        for (Tool t : tools) result.add(t.toOllamaFormat());
        return result;
    }

    public static CompletableFuture<String> executeTool(String toolName, JsonObject parameters) {
        for (Tool tool : tools) {
            if (tool.name.equals(toolName)) {
                VerityMod.LOGGER.info("[VerityAI] Executing tool: " + toolName + " params=" + parameters);
                return tool.executor.apply(parameters);
            }
        }
        VerityMod.LOGGER.warn("[VerityAI] Unknown tool: " + toolName);
        return CompletableFuture.completedFuture("Unknown tool: " + toolName);
    }

    public static void setContext(ServerPlayer player, MinecraftServer server, int stage) {
        currentPlayer = player;
        currentServer = server;
        currentStage  = stage;
    }

    public static ServerPlayer getCurrentPlayer()   { return currentPlayer; }
    public static MinecraftServer getCurrentServer() { return currentServer; }
    public static int getCurrentStage()             { return currentStage; }

    // Legacy support
    public static class ToolCall {
        public final String toolName;
        public final JsonObject arguments;
        public ToolCall(String toolName, JsonObject arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }
    }

    public static List<ToolCall> parseToolCalls(String aiResponse) {
        return Collections.emptyList();
    }

    public static void sendMessage(String message) {
        if (currentServer != null) {
            currentServer.execute(() ->
                currentServer.getPlayerList().broadcastSystemMessage(
                    Component.literal(message), false));
        }
    }
}
