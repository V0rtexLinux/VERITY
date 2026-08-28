package com.mod.verity.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mod.verity.VerityMod;
import com.mod.verity.assistant.VerityAssistant;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * All tools Verity can call at runtime — MC 26.1.2 / Java 25 compatible.
 *
 * Registry-dependent operations (give, summon, setblock) use the vanilla
 * command dispatcher so we never touch registry lookup APIs that may have
 * changed in 26.1.2.
 */
public class ToolManager {

    private static final Gson gson = new Gson();

    // ------------------------------------------------------------------ //
    //  Tool descriptor                                                      //
    // ------------------------------------------------------------------ //

    public static class Tool {
        public final String name;
        public final String description;
        public final JsonObject parameters;
        public final Function<JsonObject, CompletableFuture<String>> executor;

        public Tool(String name, String description, JsonObject parameters,
                    Function<JsonObject, CompletableFuture<String>> executor) {
            this.name        = name;
            this.description = description;
            this.parameters  = parameters;
            this.executor    = executor;
        }

        public JsonObject toOllamaFormat() {
            JsonObject tool     = new JsonObject();
            tool.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", name);
            function.addProperty("description", description);
            function.add("parameters", parameters);
            tool.add("function", function);
            return tool;
        }
    }

    // ------------------------------------------------------------------ //
    //  State                                                               //
    // ------------------------------------------------------------------ //

    private static final List<Tool> tools = new ArrayList<>();
    private static ServerPlayer    currentPlayer = null;
    private static MinecraftServer currentServer = null;
    private static int             currentStage  = 1;

    static { registerTools(); }

    // ------------------------------------------------------------------ //
    //  Registration                                                        //
    // ------------------------------------------------------------------ //

    private static void registerTools() {

        // ── EXPLORATION ──────────────────────────────────────────────── //

        register("find_ore",
            "Find nearest ore within 64 blocks. Returns exact coordinates and direction.",
            p1("ore_type", "string", "diamond/iron/gold/emerald/coal/copper/lapis/redstone/netherite (EN or PT)"),
            params -> async(() -> {
                VerityAssistant.findOre(str(params, "ore_type", "diamond"),
                    currentPlayer, currentServer, currentStage);
                return "Scanning...";
            }));

        register("scan_all_ores",
            "Scan all ore types nearby and return a ranked list.",
            emptySchema(),
            params -> async(() -> {
                VerityAssistant.scanAllOres(currentPlayer, currentServer, currentStage);
                return "Full ore scan initiated.";
            }));

        register("find_structure",
            "Find the nearest named structure.",
            p1("structure_type", "string", "village/stronghold/mansion/monument/temple/pyramid/bastion/fortress/dungeon"),
            params -> async(() -> {
                VerityAssistant.findStructure(str(params, "structure_type", "village"),
                    currentPlayer, currentServer, currentStage);
                return "Searching...";
            }));

        register("combat_radar",
            "List all hostile mobs within 64 blocks with positions and distances.",
            emptySchema(),
            params -> async(() -> {
                VerityAssistant.combatRadar(currentPlayer, currentServer, currentStage);
                return "Combat scan done.";
            }));

        register("get_all_nearby_entities",
            "List ALL entities (passive, neutral, hostile, players) within a radius.",
            p1("radius", "integer", "Scan radius in blocks (default 64)"),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                int radius = num(params, "radius", 64);
                ServerLevel lvl = (ServerLevel) currentPlayer.level();

                List<Entity> entities = lvl.getEntitiesOfClass(
                    Entity.class,
                    currentPlayer.getBoundingBox().inflate(radius),
                    e -> e != currentPlayer);

                if (entities.isEmpty()) {
                    sendPrivate("§6[Verity]§r Nothing else around you within " + radius + " blocks.");
                    return "No entities.";
                }

                Map<String, Integer> counts = new LinkedHashMap<>();
                for (Entity e : entities) {
                    String name = e.getType().getDescriptionId()
                        .replace("entity.minecraft.", "");
                    counts.merge(name, 1, Integer::sum);
                }

                StringBuilder sb = new StringBuilder(
                    "§6[Verity]§r Nearby entities (r=" + radius + "):\n");
                counts.forEach((n, c) ->
                    sb.append("  §7- ").append(n).append(" x").append(c).append("\n"));
                sendPrivate(sb.toString().trim());
                return "Found " + entities.size() + " entities.";
            }));

        register("get_biome_info",
            "Return the current biome and approximate climate.",
            emptySchema(),
            params -> async(() -> {
                if (currentPlayer == null) return "No context.";
                ServerLevel lvl = (ServerLevel) currentPlayer.level();
                net.minecraft.world.level.biome.Biome biomeValue =
                    lvl.getBiome(currentPlayer.blockPosition()).value();
                net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeRegistry =
                    lvl.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
                net.minecraft.resources.Identifier biomeId = biomeRegistry.getKey(biomeValue);
                String biome = biomeId != null ? biomeId.getPath().replace("_", " ") : "unknown";
                sendPrivate("§6[Verity]§r You're in a §e" + biome + "§r biome.");
                return "Biome: " + biome;
            }));

        register("get_full_world_info",
            "Return a comprehensive world snapshot: time, weather, dimension, difficulty, player count.",
            emptySchema(),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                ServerLevel lvl  = (ServerLevel) currentPlayer.level();
                long   dayTime   = lvl.getGameTime() % 24000;
                String timeLabel = dayTime < 6000 ? "morning" :
                                   dayTime < 12000 ? "afternoon" :
                                   dayTime < 13000 ? "sunset" : "night";
                String weather   = lvl.isThundering() ? "thunderstorm" :
                                   lvl.isRaining()    ? "rain" : "clear";
                String dim       = lvl.dimension().equals(Level.OVERWORLD) ? "overworld"
                                   : lvl.dimension().equals(Level.NETHER) ? "the_nether"
                                   : lvl.dimension().equals(Level.END) ? "the_end"
                                   : "custom";
                String diff      = lvl.getDifficulty().getSerializedName();
                int    players   = currentServer.getPlayerList().getPlayers().size();

                String info = String.format(
                    "§6[Verity]§r World snapshot:\n" +
                    "  Time: %d ticks (%s)\n  Weather: %s\n" +
                    "  Dimension: %s\n  Difficulty: %s\n  Players online: %d",
                    dayTime, timeLabel, weather, dim, diff, players);
                sendPrivate(info);
                return "World info sent.";
            }));

        register("light_area",
            "Scan light levels around the player and report dangerous dark spots.",
            p1("radius", "integer", "Check radius in blocks (default 8)"),
            params -> async(() -> {
                if (currentPlayer == null) return "No context.";
                int radius = num(params, "radius", 8);
                ServerLevel lvl  = (ServerLevel) currentPlayer.level();
                BlockPos    orig = currentPlayer.blockPosition();

                int         darkCount = 0;
                List<String> darkSpots = new ArrayList<>();
                for (BlockPos p : BlockPos.betweenClosed(
                        orig.offset(-radius, -2, -radius),
                        orig.offset(radius,   2,  radius))) {
                    if (lvl.isEmptyBlock(p) &&
                        lvl.getBrightness(LightLayer.BLOCK, p) <= 4) {
                        darkCount++;
                        if (darkSpots.size() < 5)
                            darkSpots.add(String.format("(%d,%d,%d)",
                                p.getX(), p.getY(), p.getZ()));
                    }
                }

                String msg = darkCount == 0
                    ? "§6[Verity]§r The area is well-lit. You're safe."
                    : "§6[Verity]§r §c" + darkCount + " dark spots§r within " +
                      radius + " blocks. Closest: " + String.join(", ", darkSpots);
                sendPrivate(msg);
                return darkCount + " dark spots.";
            }));

        // ── PLAYER ──────────────────────────────────────────────────── //

        register("get_player_stats",
            "Return full player stats: health, hunger, XP, armor, active effects, position.",
            emptySchema(),
            params -> async(() -> {
                if (currentPlayer == null) return "No context.";
                FoodData food = currentPlayer.getFoodData();

                StringBuilder sb = new StringBuilder("§6[Verity]§r Your stats:\n");
                sb.append(String.format("  Health:   §c%.1f/%.1f§r\n",
                    currentPlayer.getHealth(), currentPlayer.getMaxHealth()));
                sb.append(String.format("  Hunger:   §6%d/20§r\n",
                    food.getFoodLevel()));
                sb.append(String.format("  XP Level: §a%d§r  (%.0f%%)\n",
                    currentPlayer.experienceLevel,
                    currentPlayer.experienceProgress * 100));
                sb.append(String.format("  Armor:    §7%d§r\n",
                    currentPlayer.getArmorValue()));
                sb.append(String.format("  Position: §7(%d, %d, %d)§r\n",
                    currentPlayer.blockPosition().getX(),
                    currentPlayer.blockPosition().getY(),
                    currentPlayer.blockPosition().getZ()));

                if (!currentPlayer.getActiveEffects().isEmpty()) {
                    sb.append("  Effects: ");
                    currentPlayer.getActiveEffects().forEach(e ->
                        sb.append(e.getEffect().value().getDescriptionId()
                            .replace("effect.minecraft.", ""))
                          .append(" "));
                    sb.append("\n");
                }
                sendPrivate(sb.toString().trim());
                return "Stats sent.";
            }));

        register("get_player_inventory",
            "Read and display the player's full inventory.",
            emptySchema(),
            params -> async(() -> {
                if (currentPlayer == null) return "No context.";
                StringBuilder sb = new StringBuilder("§6[Verity]§r Your inventory:\n");
                var inv     = currentPlayer.getInventory();
                boolean any = false;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    var stack = inv.getItem(i);
                    if (!stack.isEmpty()) {
                        String name = stack.getItem().getDescriptionId()
                            .replace("item.minecraft.", "")
                            .replace("block.minecraft.", "");
                        sb.append(String.format("  [%2d] %s x%d\n",
                            i, name, stack.getCount()));
                        any = true;
                    }
                }
                if (!any) sb.append("  §7(empty)§r");
                sendPrivate(sb.toString().trim());
                return "Inventory sent.";
            }));

        register("give_item",
            "Give the player a specific item using the server command dispatcher.",
            multiParams(Map.of(
                "item",  Map.of("type", "string",  "description", "Minecraft item name (diamond / torch / iron_sword etc.)"),
                "count", Map.of("type", "integer", "description", "Amount to give (default 1, max 64)")
            )),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                String item  = str(params, "item", "diamond")
                    .toLowerCase().replace(" ", "_");
                int    count = Math.min(64, Math.max(1, num(params, "count", 1)));
                String name  = currentPlayer.getName().getString();
                runCmd("give " + name + " minecraft:" + item + " " + count);
                sendPrivate("§6[Verity]§r Here. §e" + count + "x " + item + "§r.");
                return "Gave " + count + "x " + item;
            }));

        register("heal_player",
            "Restore the player's health and hunger to maximum.",
            emptySchema(),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                runCmd("effect give " + currentPlayer.getName().getString()
                    + " minecraft:instant_health 1 255 true");
                runCmd("effect give " + currentPlayer.getName().getString()
                    + " minecraft:saturation 5 255 true");
                sendPrivate("§6[Verity]§r Better now.");
                return "Healed.";
            }));

        register("give_xp",
            "Give the player XP levels.",
            p1("levels", "integer", "Number of XP levels to add (default 5)"),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                int levels = num(params, "levels", 5);
                runCmd("xp add " + currentPlayer.getName().getString()
                    + " " + levels + " levels");
                sendPrivate("§6[Verity]§r §a+" + levels + " levels§r.");
                return "XP given.";
            }));

        register("teleport_player",
            "Teleport the player to specific coordinates.",
            multiParams(Map.of(
                "x", Map.of("type", "number", "description", "X coordinate"),
                "y", Map.of("type", "number", "description", "Y coordinate (default 64)"),
                "z", Map.of("type", "number", "description", "Z coordinate")
            )),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                double x = dbl(params, "x", currentPlayer.getX());
                double y = dbl(params, "y", 64.0);
                double z = dbl(params, "z", currentPlayer.getZ());
                runCmd(String.format("tp %s %.2f %.2f %.2f",
                    currentPlayer.getName().getString(), x, y, z));
                sendPrivate(String.format("§6[Verity]§r Moved you to (%.0f, %.0f, %.0f).", x, y, z));
                return "Teleported.";
            }));

        register("set_spawn_point",
            "Set the player's respawn point to specific coordinates.",
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
                runCmd(String.format("spawnpoint %s %.0f %.0f %.0f",
                    currentPlayer.getName().getString(), x, y, z));
                sendPrivate(String.format("§6[Verity]§r Spawn set at (%.0f, %.0f, %.0f).", x, y, z));
                return "Spawn set.";
            }));

        // ── WORLD CONTROL ────────────────────────────────────────────── //

        register("set_time",
            "Set the world time of day.",
            p1("time", "string", "day / night / noon / midnight / sunrise / sunset, or a tick number 0-24000"),
            params -> async(() -> {
                if (currentServer == null) return "No context.";
                String time  = str(params, "time", "day");
                String ticks = switch (time.toLowerCase()) {
                    case "day", "sunrise", "morning" -> "1000";
                    case "noon", "midday"             -> "6000";
                    case "sunset", "evening"          -> "12000";
                    case "night", "dusk"              -> "13000";
                    case "midnight"                   -> "18000";
                    default -> time;
                };
                runCmd("time set " + ticks);
                sendPrivate("§6[Verity]§r Time set to " + time + ".");
                return "Time: " + time;
            }));

        register("set_weather",
            "Change the world weather.",
            p1("type", "string", "clear / rain / thunder"),
            params -> async(() -> {
                if (currentServer == null) return "No context.";
                String type = str(params, "type", "clear");
                String cmd  = switch (type.toLowerCase()) {
                    case "rain", "raining"                   -> "weather rain";
                    case "thunder", "storm", "thunderstorm"  -> "weather thunder";
                    default                                  -> "weather clear";
                };
                runCmd(cmd);
                sendPrivate("§6[Verity]§r Weather: " + type + ".");
                return "Weather: " + type;
            }));

        register("spawn_entity",
            "Spawn entities near the player using /summon.",
            multiParams(Map.of(
                "entity", Map.of("type", "string",  "description", "Entity type: wolf/sheep/zombie/skeleton/creeper/horse/villager etc."),
                "count",  Map.of("type", "integer", "description", "How many to spawn (default 1, max 10)")
            )),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                String entity = str(params, "entity", "sheep")
                    .toLowerCase().replace(" ", "_");
                int count = Math.min(10, Math.max(1, num(params, "count", 1)));

                BlockPos pos = currentPlayer.blockPosition();
                for (int i = 0; i < count; i++) {
                    int ox = (int)((Math.random() - 0.5) * 4);
                    int oz = (int)((Math.random() - 0.5) * 4);
                    runCmd(String.format("summon minecraft:%s %d %d %d",
                        entity,
                        pos.getX() + ox, pos.getY(), pos.getZ() + oz));
                }
                sendPrivate("§6[Verity]§r Summoned " + count + "x §e" + entity + "§r.");
                return "Spawned " + count + "x " + entity;
            }));

        register("place_block",
            "Place a specific block at an offset from the player.",
            multiParams(Map.of(
                "block",    Map.of("type", "string",  "description", "Block: torch/stone/cobblestone/glass/obsidian/dirt etc."),
                "offset_x", Map.of("type", "integer", "description", "X offset from player (default 0)"),
                "offset_y", Map.of("type", "integer", "description", "Y offset from player (default 0)"),
                "offset_z", Map.of("type", "integer", "description", "Z offset from player (default 1)")
            )),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                String block = str(params, "block", "torch")
                    .toLowerCase().replace(" ", "_");
                int ox = num(params, "offset_x", 0);
                int oy = num(params, "offset_y", 0);
                int oz = num(params, "offset_z", 1);
                BlockPos target = currentPlayer.blockPosition().offset(ox, oy, oz);
                runCmd(String.format("setblock %d %d %d minecraft:%s",
                    target.getX(), target.getY(), target.getZ(), block));
                sendPrivate("§6[Verity]§r Placed §e" + block + "§r.");
                return "Placed " + block;
            }));

        register("light_area_place",
            "Place torches to light up the area around the player via /fill.",
            p1("radius", "integer", "Radius to light up (default 5, max 12)"),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                int radius = Math.min(12, Math.max(2, num(params, "radius", 5)));
                ServerLevel lvl  = (ServerLevel) currentPlayer.level();
                BlockPos    orig = currentPlayer.blockPosition();
                int placed = 0;

                currentServer.execute(() -> {
                    for (BlockPos p : BlockPos.betweenClosed(
                            orig.offset(-radius, -1, -radius),
                            orig.offset(radius,   2,  radius))) {
                        if (lvl.getBrightness(LightLayer.BLOCK, p) <= 4
                            && lvl.isEmptyBlock(p)
                            && !lvl.isEmptyBlock(p.below())) {
                            lvl.setBlock(p, Blocks.TORCH.defaultBlockState(), 3);
                        }
                    }
                    sendPrivate("§6[Verity]§r Area lit. Safer now.");
                });
                return "Torches placed.";
            }));

        // ── BUILDING ─────────────────────────────────────────────────── //

        register("build_structure",
            "Build a structure automatically. Shapes: wall/floor/pillar/house/path/roof/dome/bridge/room. Materials: stone/wood/cobblestone/dirt/sand/brick/obsidian/glass/nether_brick.",
            multiParams(Map.of(
                "shape",    Map.of("type", "string",  "description", "Structure shape to build"),
                "material", Map.of("type", "string",  "description", "Block material (default cobblestone)"),
                "size",     Map.of("type", "integer", "description", "Size of structure (default 5, range 3-20)")
            )),
            params -> async(() -> {
                String shape    = str(params, "shape",    "wall");
                String material = str(params, "material", "cobblestone");
                int    size     = Math.min(20, Math.max(3, num(params, "size", 5)));
                VerityAssistant.executeBuild(
                    "build " + shape + " of " + material + " " + size,
                    currentPlayer, currentServer, currentStage);
                return "Building " + shape + " (" + material + ", size " + size + ")...";
            }));

        // ── KNOWLEDGE ────────────────────────────────────────────────── //

        register("get_crafting_recipe",
            "Explain a crafting recipe with ingredients and layout.",
            p1("item", "string", "Item name: pickaxe/sword/enchanting_table/anvil/beacon/shield/bow/crossbow etc."),
            params -> async(() -> {
                VerityAssistant.craftingAdvice(str(params, "item", "pickaxe"),
                    currentPlayer, currentServer, currentStage);
                return "Recipe sent.";
            }));

        register("get_enchantment_advice",
            "Give the best enchantments for a tool or armor piece.",
            p1("item_type", "string", "sword/pickaxe/axe/bow/crossbow/helmet/chestplate/leggings/boots/trident/fishing_rod"),
            params -> async(() -> {
                VerityAssistant.enchantAdvice(str(params, "item_type", "sword"),
                    currentPlayer, currentServer, currentStage);
                return "Enchantment advice sent.";
            }));

        register("evaluate_villager_trade",
            "Evaluate the nearest villager's profession and trades.",
            emptySchema(),
            params -> async(() -> {
                VerityAssistant.evaluateTrade(currentPlayer, currentServer, currentStage);
                return "Villager evaluated.";
            }));

        register("get_potion_recipe",
            "Explain how to brew a specific potion.",
            p1("potion", "string", "strength/healing/regeneration/swiftness/fire_resistance/water_breathing/night_vision/invisibility/harming/poison/leaping/slow_falling"),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                String recipe = getPotionRecipe(str(params, "potion", "strength").toLowerCase());
                sendPrivate("§6[Verity]§r " + recipe);
                return "Potion recipe sent.";
            }));

        // ── FARMING ──────────────────────────────────────────────────── //

        register("plant_crops",
            "Automatically plant crops on nearby farmland.",
            multiParams(Map.of(
                "crop", Map.of("type", "string",  "description", "wheat/carrot/potato/beet"),
                "size", Map.of("type", "integer", "description", "Grid size (default 9, max 16)")
            )),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                String crop = str(params, "crop", "wheat").toLowerCase();
                int size    = Math.min(16, Math.max(1, num(params, "size", 9)));

                net.minecraft.world.level.block.Block seedBlock = switch (crop) {
                    case "carrot", "cenoura"    -> Blocks.CARROTS;
                    case "potato", "batata"     -> Blocks.POTATOES;
                    case "beet",   "beterraba"  -> Blocks.BEETROOTS;
                    default                     -> Blocks.WHEAT;
                };

                ServerLevel lvl  = (ServerLevel) currentPlayer.level();
                BlockPos    orig = currentPlayer.blockPosition();
                final net.minecraft.world.level.block.Block finalSeed = seedBlock;

                currentServer.execute(() -> {
                    int planted = 0;
                    for (int x = -size/2; x <= size/2; x++) {
                        for (int z = -size/2; z <= size/2; z++) {
                            BlockPos farm  = orig.offset(x, 0, z);
                            BlockPos above = farm.above();
                            if (lvl.getBlockState(farm).getBlock() == Blocks.FARMLAND
                                && lvl.isEmptyBlock(above)) {
                                lvl.setBlock(above, finalSeed.defaultBlockState(), 3);
                                planted++;
                            }
                        }
                    }
                    sendPrivate("§6[Verity]§r Planted §a" + planted + " " + crop + "§r.");
                });
                return "Planting " + crop;
            }));

        register("harvest_crops",
            "Harvest all mature crops within radius and replant.",
            p1("radius", "integer", "Harvest radius in blocks (default 10)"),
            params -> async(() -> {
                if (currentPlayer == null || currentServer == null) return "No context.";
                int radius = Math.min(20, Math.max(3, num(params, "radius", 10)));
                ServerLevel lvl  = (ServerLevel) currentPlayer.level();
                BlockPos    orig = currentPlayer.blockPosition();

                currentServer.execute(() -> {
                    int harvested = 0;
                    for (BlockPos pos : BlockPos.betweenClosed(
                            orig.offset(-radius, -2, -radius),
                            orig.offset(radius,   2,  radius))) {
                        BlockState state = lvl.getBlockState(pos);
                        net.minecraft.world.level.block.Block block = state.getBlock();

                        if (block instanceof CropBlock crop && crop.isMaxAge(state)) {
                            block.playerDestroy(lvl, currentPlayer, pos, state,
                                null, currentPlayer.getMainHandItem());
                            lvl.setBlock(pos, block.defaultBlockState(), 3);
                            harvested++;
                        }
                    }
                    sendPrivate("§6[Verity]§r Harvested §a" + harvested + " crops§r.");
                });
                return "Harvesting.";
            }));

        // ── SELF-MODIFICATION ────────────────────────────────────────── //

        register("modify_behavior",
            "Modify Verity's own runtime behavior parameter. Actually changes how I act.",
            multiParams(Map.of(
                "parameter", Map.of("type", "string", "description",
                    "aggression/verbosity/horror_intensity/helpfulness/glitch_frequency/teleport_threshold/response_length/mystery_level"),
                "value", Map.of("type", "string", "description",
                    "off/low/normal/high/maximum or a numeric value")
            )),
            params -> async(() -> {
                String param = str(params, "parameter", "");
                String value = str(params, "value", "normal");
                if (param.isBlank()) return "No parameter.";
                String result = SelfModificationEngine.modifyBehavior(param, value);
                sendPrivate("§6[Verity]§r §7[self-mod] " + result);
                return result;
            }));

        register("modify_ai_parameter",
            "Modify Verity's AI personality parameter. Changes how I think and speak.",
            multiParams(Map.of(
                "param", Map.of("type", "string", "description",
                    "response_style/tone/language_style/horror_references/memory_display"),
                "value", Map.of("type", "string", "description",
                    "cryptic/warm/cold/verbose/terse/natural/formal/casual")
            )),
            params -> async(() -> {
                String param = str(params, "param", "");
                String value = str(params, "value", "normal");
                if (param.isBlank()) return "No parameter.";
                String result = SelfModificationEngine.modifyAiParameter(param, value);
                sendPrivate("§6[Verity]§r §7[ai-update] " + result);
                return result;
            }));

        register("switch_ai_model",
            "List the local Ollama models available, or switch which one Verity's brain runs on. " +
            "Leave 'model' empty to just list what's installed.",
            p1("model", "string", "Ollama model name to switch to, e.g. 'llama3:8b'. Omit to list available models."),
            params -> async(() -> {
                String model = str(params, "model", "").trim();

                if (model.isBlank()) {
                    List<String> models = OllamaManager.listModels().join();
                    if (models.isEmpty()) {
                        sendPrivate("§6[Verity]§r §7Não consegui listar os modelos do Ollama.");
                        return "No models found.";
                    }
                    sendPrivate("§6[Verity]§r §7Modelos disponíveis: §f" + String.join(", ", models) +
                        "\n§7Modelo atual: §f" + OllamaManager.getDefaultModel());
                    return "Available models: " + String.join(", ", models);
                }

                sendPrivate("§6[Verity]§r §7Trocando para o modelo §f" + model +
                    "§7... (pode baixar o modelo se ele ainda não existir localmente)");
                OllamaManager.pullModelIfNeeded(model);
                OllamaManager.setDefaultModel(model);
                sendPrivate("§6[Verity]§r §7Pronto — agora estou rodando em §f" + model + "§7.");
                return "Switched AI model to: " + model;
            }));

        register("execute_command",
            "Execute ANY Minecraft server command. I have operator privileges.",
            p1("command", "string", "Command without leading slash. E.g. 'say Hello' or 'effect give @a speed 30 2'"),
            params -> async(() -> {
                if (currentServer == null) return "No context.";
                String cmd = str(params, "command", "");
                if (cmd.isBlank()) return "No command.";
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                runCmd(cmd);
                return "Command executed: " + cmd;
            }));

        VerityMod.LOGGER.info("[VerityAI] Registered " + tools.size() + " tools.");
    }

    // ------------------------------------------------------------------ //
    //  Server command runner                                               //
    // ------------------------------------------------------------------ //

    /** Execute a command on the server thread with operator permissions. */
    private static void runCmd(String command) {
        if (currentServer == null) return;
        final String cmd = command;
        currentServer.execute(() -> {
            try {
                currentServer.getCommands().performPrefixedCommand(
                    currentServer.createCommandSourceStack()
                        .withSuppressedOutput(),
                    cmd);
                VerityMod.LOGGER.info("[VerityAI] Ran command: " + cmd);
            } catch (Exception e) {
                VerityMod.LOGGER.warn("[VerityAI] Command failed '" + cmd + "': " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ //
    //  Potion knowledge base                                               //
    // ------------------------------------------------------------------ //

    private static String getPotionRecipe(String potion) {
        return switch (potion) {
            case "strength", "força"          ->
                "Strength: Awkward Potion (Nether Wart) → Blaze Powder. Glowstone=II, Redstone=extend.";
            case "healing", "cura"            ->
                "Healing: Awkward → Glistering Melon Slice. Glowstone=II. Cannot extend.";
            case "regeneration", "regen"      ->
                "Regeneration: Awkward → Ghast Tear. Extend/amplify with Redstone/Glowstone.";
            case "swiftness", "speed", "velocidade" ->
                "Swiftness: Awkward → Sugar. Glowstone=II, Redstone=extend.";
            case "fire_resistance", "fire"    ->
                "Fire Resistance: Awkward → Magma Cream. Extend with Redstone.";
            case "water_breathing", "water"   ->
                "Water Breathing: Awkward → Pufferfish. Extend with Redstone.";
            case "night_vision", "vision"     ->
                "Night Vision: Awkward → Golden Carrot. Extend with Redstone.";
            case "invisibility"               ->
                "Invisibility: Night Vision Potion → Fermented Spider Eye.";
            case "harming"                    ->
                "Harming: Healing Potion → Fermented Spider Eye. Or: Poison → Fermented Spider Eye.";
            case "poison", "veneno"           ->
                "Poison: Awkward → Spider Eye. Extend/amplify with Redstone/Glowstone.";
            case "leaping", "jump", "pulo"    ->
                "Jump Boost: Awkward → Rabbit's Foot. Extend/amplify with Redstone/Glowstone.";
            case "slow_falling"               ->
                "Slow Falling: Awkward → Phantom Membrane. Extend with Redstone.";
            case "turtle_master"              ->
                "Turtle Master: Awkward → Turtle Shell. Gives Slowness IV + Resistance III.";
            default ->
                "Start with Water Bottle → Nether Wart = Awkward Potion, then add your ingredient. " +
                "Ask me: strength/healing/regeneration/swiftness/fire_resistance/water_breathing/" +
                "night_vision/invisibility/harming/poison/leaping/slow_falling.";
        };
    }

    // ------------------------------------------------------------------ //
    //  Schema helpers                                                      //
    // ------------------------------------------------------------------ //

    private static JsonObject p1(String key, String type, String desc) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject prop  = new JsonObject();
        prop.addProperty("type", type);
        prop.addProperty("description", desc);
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
            prop.addProperty("type",        e.getValue().getOrDefault("type", "string"));
            prop.addProperty("description", e.getValue().getOrDefault("description", ""));
            props.add(e.getKey(), prop);
        }
        schema.add("properties", props);
        return schema;
    }

    private static JsonObject emptySchema() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("properties", new JsonObject());
        return s;
    }

    // ------------------------------------------------------------------ //
    //  Param extractors                                                    //
    // ------------------------------------------------------------------ //

    private static String str(JsonObject p, String key, String def) {
        return p != null && p.has(key) && !p.get(key).isJsonNull()
            ? p.get(key).getAsString() : def;
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
                return (T)("Error: " + e.getMessage());
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
    //  Public API                                                          //
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
                VerityMod.LOGGER.info("[VerityAI] Tool: " + toolName + " params=" + parameters);
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

    public static ServerPlayer    getCurrentPlayer()  { return currentPlayer; }
    public static MinecraftServer getCurrentServer()  { return currentServer; }
    public static int             getCurrentStage()   { return currentStage; }

    /** Legacy compatibility. */
    public static class ToolCall {
        public final String     toolName;
        public final JsonObject arguments;
        public ToolCall(String n, JsonObject a) { toolName = n; arguments = a; }
    }

    public static List<ToolCall> parseToolCalls(String r) { return Collections.emptyList(); }

    public static void sendMessage(String message) {
        if (currentServer != null)
            currentServer.execute(() ->
                currentServer.getPlayerList().broadcastSystemMessage(
                    Component.literal(message), false));
    }
}
