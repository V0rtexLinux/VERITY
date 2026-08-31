package com.mod.echo.ai;

import com.google.gson.JsonObject;
import com.mod.echo.EchoMod;
import com.mod.echo.assistant.BuildAssistant;
import com.mod.echo.assistant.KnowledgeBase;
import com.mod.echo.assistant.WorldScanner;
import com.mod.echo.config.EchoConfig;
import com.mod.echo.memory.EchoMemory;
import com.mod.echo.memory.EchoSelf;
import com.mod.echo.net.SettingsRequestPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Everything ECHO can do on the logical server.
 *
 * Each tool is a small, self-contained function with a JSON schema, so the model
 * receives a real function-calling menu rather than a prose description it has
 * to imitate.  Tools return a short factual sentence: the model reads that and
 * writes the actual reply, which keeps ECHO's voice consistent no matter which
 * tool ran.
 *
 * Two rules hold throughout:
 * <ul>
 *   <li><b>Reads</b> may run on the calling worker thread — they only touch
 *       already-loaded chunks and never mutate anything.</li>
 *   <li><b>Writes</b> are always hopped onto the server thread through
 *       {@link #onServerThread}, because Minecraft's world state is not
 *       thread-safe.</li>
 * </ul>
 */
public final class ToolRegistry {

    private ToolRegistry() {}

    /** Who the tool is acting for. */
    public record Context(ServerPlayer player, MinecraftServer server) {
        public ServerLevel level() { return player.level(); }
        public String playerId()   { return player.getUUID().toString(); }
        public String playerName() { return player.getName().getString(); }
    }

    private static final Map<String, ToolSpec<Context>> TOOLS = new LinkedHashMap<>();

    static {
        registerScanning();
        registerPlayer();
        registerWorldControl();
        registerBuilding();
        registerFarming();
        registerKnowledge();
        registerMemory();
        registerSystem();
        EchoMod.LOGGER.info("Registered {} server-side tools.", TOOLS.size());
    }

    // ------------------------------------------------------------------ //
    //  Public API                                                          //
    // ------------------------------------------------------------------ //

    /** Tool schemas for the model, filtered by what the config currently allows. */
    public static List<JsonObject> schemas() {
        EchoConfig cfg = EchoConfig.get();
        List<JsonObject> out = new ArrayList<>();
        for (ToolSpec<Context> tool : TOOLS.values()) {
            if (!cfg.allowWorldTools && WORLD_TOOLS.contains(tool.name)) continue;
            if (!cfg.allowRawCommands && tool.name.equals("run_command")) continue;
            if (!cfg.webSearchEnabled && tool.name.equals("web_search")) continue;
            out.add(tool.toSchema());
        }
        return out;
    }

    public static String execute(String name, JsonObject args, Context context) {
        ToolSpec<Context> tool = TOOLS.get(name);
        if (tool == null) {
            return "There is no tool called '" + name + "'. Available: " + String.join(", ", TOOLS.keySet());
        }
        EchoConfig cfg = EchoConfig.get();
        if (!cfg.allowWorldTools && WORLD_TOOLS.contains(name)) {
            return "World-changing tools are disabled in echo.json (allowWorldTools=false).";
        }
        if (!cfg.allowRawCommands && name.equals("run_command")) {
            return "Raw command execution is disabled in echo.json (allowRawCommands=false).";
        }
        if (!cfg.webSearchEnabled && name.equals("web_search")) {
            return "Internet lookups are disabled in echo.json (webSearchEnabled=false).";
        }
        EchoMod.LOGGER.debug("Tool {} {}", name, args);
        return tool.invoke(context, args);
    }

    public static int count() { return TOOLS.size(); }

    public static List<String> names() { return new ArrayList<>(TOOLS.keySet()); }

    /** Tools that modify the world, gated behind {@code allowWorldTools}. */
    private static final List<String> WORLD_TOOLS = List.of(
            "give_item", "heal_player", "give_xp", "teleport", "teleport_to_waypoint",
            "set_spawn", "set_time", "set_weather", "set_difficulty", "set_gamerule",
            "summon_entity", "place_block", "apply_effect", "clear_effects",
            "build_structure", "light_area", "dig_tunnel", "emergency_shelter", "build_schematic",
            "plant_crops", "harvest_crops", "run_command");

    // ------------------------------------------------------------------ //
    //  Registration — scanning                                             //
    // ------------------------------------------------------------------ //

    private static void registerScanning() {
        add("find_ore",
            "Find the nearest block of a specific ore and report its exact coordinates, distance and direction.",
            ToolSpec.Schema.of()
                .requiredStr("ore", "Ore to look for: diamond, iron, gold, emerald, coal, copper, lapis, redstone, netherite, quartz")
                .integer("radius", "Search radius in blocks, 2-48 (default 32)")
                .build(),
            (ctx, a) -> WorldScanner.findOre(ctx.player(),
                    ToolSpec.str(a, "ore", "diamond"),
                    ToolSpec.integer(a, "radius", 32)));

        add("scan_ores",
            "Scan for every ore type near the player at once and rank them by distance.",
            ToolSpec.Schema.of()
                .integer("radius", "Search radius in blocks, 2-48 (default 24)")
                .build(),
            (ctx, a) -> WorldScanner.scanAllOres(ctx.player(), ToolSpec.integer(a, "radius", 24)));

        add("find_block",
            "Find any block by name (chest, spawner, water, obsidian, ...) and count how many are nearby.",
            ToolSpec.Schema.of()
                .requiredStr("block", "Block id or plain name, e.g. 'spawner' or 'minecraft:chest'")
                .integer("radius", "Search radius in blocks, 2-48 (default 32)")
                .build(),
            (ctx, a) -> WorldScanner.findBlock(ctx.player(),
                    ToolSpec.str(a, "block", ""),
                    ToolSpec.integer(a, "radius", 32)));

        add("find_structure",
            "Locate the nearest generated structure (village, stronghold, mansion, fortress, ancient city, ...).",
            ToolSpec.Schema.of()
                .requiredStr("structure", "Structure name, e.g. village, stronghold, mansion, monument, fortress, bastion, ancient city, trial chamber, end city")
                .build(),
            (ctx, a) -> WorldScanner.findStructure(ctx.player(), ctx.server(),
                    ToolSpec.str(a, "structure", "village")));

        add("nearby_mobs",
            "List hostile mobs around the player with distances — use this before answering anything about danger.",
            ToolSpec.Schema.of()
                .integer("radius", "Search radius in blocks, 2-48 (default 32)")
                .build(),
            (ctx, a) -> WorldScanner.combatRadar(ctx.player(), ToolSpec.integer(a, "radius", 32)));

        add("nearby_entities",
            "List every entity nearby grouped by type: animals, villagers, items, players and mobs.",
            ToolSpec.Schema.of()
                .integer("radius", "Search radius in blocks, 2-48 (default 32)")
                .build(),
            (ctx, a) -> WorldScanner.nearbyEntities(ctx.player(), ToolSpec.integer(a, "radius", 32)));

        add("light_audit",
            "Check where mobs can still spawn around the player because the light level is too low.",
            ToolSpec.Schema.of()
                .integer("radius", "Check radius in blocks, 2-48 (default 12)")
                .build(),
            (ctx, a) -> WorldScanner.lightAudit(ctx.player(), ToolSpec.integer(a, "radius", 12)));

        add("base_audit",
            "Full safety audit of the area: dark spawn spots, exposed lava and gaps open to the sky.",
            ToolSpec.Schema.of()
                .integer("radius", "Audit radius in blocks, 2-48 (default 16)")
                .build(),
            (ctx, a) -> WorldScanner.baseAudit(ctx.player(), ToolSpec.integer(a, "radius", 16)));

        add("search_containers",
            "Search nearby chests, barrels and shulker boxes for a specific item.",
            ToolSpec.Schema.of()
                .requiredStr("item", "Item to look for, e.g. 'diamond' or 'iron_ingot'")
                .integer("radius", "Search radius in blocks, 2-48 (default 16)")
                .build(),
            (ctx, a) -> WorldScanner.findItemInContainers(ctx.player(),
                    ToolSpec.str(a, "item", ""),
                    ToolSpec.integer(a, "radius", 16)));

        add("world_info",
            "Snapshot of the world: dimension, biome, time of day, weather, difficulty, player count and surface height.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> WorldScanner.worldSnapshot(ctx.player(), ctx.server()));

        add("time_info",
            "How long until nightfall or sunrise, in real minutes.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> WorldScanner.timeUntilNight(ctx.level()));

        add("biome_info",
            "Name the biome the player is standing in.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> "Current biome: "
                    + WorldScanner.biomeName(ctx.level(), ctx.player().blockPosition()) + ".");
    }

    // ------------------------------------------------------------------ //
    //  Registration — player                                               //
    // ------------------------------------------------------------------ //

    private static void registerPlayer() {
        add("player_status",
            "Read the player's health, hunger, saturation, experience, armour, active effects and position.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> {
                ServerPlayer p = ctx.player();
                var food = p.getFoodData();
                BlockPos pos = p.blockPosition();
                StringBuilder sb = new StringBuilder();
                sb.append(String.format(Locale.ROOT, "Health %.1f/%.1f, hunger %d/20 (saturation %.1f), ",
                        p.getHealth(), p.getMaxHealth(), food.getFoodLevel(), food.getSaturationLevel()));
                sb.append("XP level ").append(p.experienceLevel)
                  .append(", armour ").append(p.getArmorValue()).append("/20, ");
                sb.append("at (").append(pos.getX()).append(", ").append(pos.getY()).append(", ")
                  .append(pos.getZ()).append(") in ")
                  .append(ctx.level().dimension().identifier().getPath()).append('.');

                if (!p.getActiveEffects().isEmpty()) {
                    sb.append(" Active effects: ");
                    p.getActiveEffects().forEach(e -> sb.append(
                            e.getEffect().value().getDescriptionId().replace("effect.minecraft.", ""))
                            .append(' '));
                }
                if (p.getHealth() < p.getMaxHealth() * 0.35f) sb.append(" (health is low)");
                if (food.getFoodLevel() <= 6) sb.append(" (hunger is low)");
                return sb.toString();
            });

        add("inventory",
            "Read the player's full inventory, slot by slot.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> {
                var inv = ctx.player().getInventory();
                Map<String, Integer> totals = new LinkedHashMap<>();
                int used = 0;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty()) continue;
                    used++;
                    totals.merge(itemName(stack), stack.getCount(), Integer::sum);
                }
                if (totals.isEmpty()) return "The inventory is completely empty.";
                StringBuilder sb = new StringBuilder(used + " occupied slots:");
                totals.forEach((n, c) -> sb.append("\n  ").append(c).append("x ").append(n));
                return sb.toString();
            });

        add("count_item",
            "Count how many of one item the player is carrying.",
            ToolSpec.Schema.of()
                .requiredStr("item", "Item name, e.g. 'diamond', 'cooked_beef', 'torch'")
                .build(),
            (ctx, a) -> {
                String needle = ToolSpec.str(a, "item", "").toLowerCase(Locale.ROOT).replace(' ', '_');
                if (needle.isBlank()) return "Which item should I count?";
                var inv = ctx.player().getInventory();
                int total = 0;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty()) continue;
                    Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (id != null && id.getPath().contains(needle)) total += stack.getCount();
                }
                return total == 0
                        ? "No " + needle.replace('_', ' ') + " in the inventory."
                        : total + "x " + needle.replace('_', ' ') + " in the inventory.";
            });

        add("gear_durability",
            "Check how worn the player's armour and held tools are, and warn about anything about to break.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> {
                List<String> lines = new ArrayList<>();
                List<String> critical = new ArrayList<>();
                var inv = ctx.player().getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty() || !stack.isDamageableItem()) continue;
                    int max = stack.getMaxDamage();
                    if (max <= 0) continue;
                    int left = max - stack.getDamageValue();
                    int percent = (int) Math.round(left * 100.0 / max);
                    String line = itemName(stack) + " " + percent + "% (" + left + "/" + max + ")";
                    if (percent <= 15) critical.add(line); else lines.add(line);
                }
                if (lines.isEmpty() && critical.isEmpty()) return "Nothing the player carries has durability.";
                StringBuilder sb = new StringBuilder();
                if (!critical.isEmpty()) {
                    sb.append("About to break: ").append(String.join("; ", critical));
                }
                if (!lines.isEmpty()) {
                    if (sb.length() > 0) sb.append(". ");
                    sb.append("Fine: ").append(String.join("; ", lines));
                }
                return sb.toString();
            });
    }

    // ------------------------------------------------------------------ //
    //  Registration — world control                                        //
    // ------------------------------------------------------------------ //

    private static void registerWorldControl() {
        add("give_item",
            "Give the player items.",
            ToolSpec.Schema.of()
                .requiredStr("item", "Item id, e.g. 'torch', 'cooked_beef', 'iron_pickaxe'")
                .integer("count", "How many, 1-64 (default 1)")
                .build(),
            (ctx, a) -> {
                String item = normaliseId(ToolSpec.str(a, "item", "torch"));
                int count = ToolSpec.clampedInt(a, "count", 1, 1, 64);
                if (!existsIn(BuiltInRegistries.ITEM, item)) {
                    return "There is no item called '" + item + "'.";
                }
                runCommand(ctx, "give " + ctx.playerName() + " " + item + " " + count);
                return "Gave " + count + "x " + item + ".";
            });

        add("heal_player",
            "Restore the player's health and hunger to full.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> {
                runCommand(ctx, "effect give " + ctx.playerName() + " minecraft:instant_health 1 20 true");
                runCommand(ctx, "effect give " + ctx.playerName() + " minecraft:saturation 3 20 true");
                return "Healed and fed the player.";
            });

        add("give_xp",
            "Give the player experience levels.",
            ToolSpec.Schema.of()
                .requiredInteger("levels", "Number of levels to add, 1-100")
                .build(),
            (ctx, a) -> {
                int levels = ToolSpec.clampedInt(a, "levels", 5, 1, 100);
                runCommand(ctx, "xp add " + ctx.playerName() + " " + levels + " levels");
                return "Added " + levels + " experience levels.";
            });

        add("teleport",
            "Teleport the player to coordinates. Use 'surface' for Y to land on top of the terrain.",
            ToolSpec.Schema.of()
                .requiredNumber("x", "X coordinate")
                .requiredNumber("z", "Z coordinate")
                .number("y", "Y coordinate; omit to place the player on the surface")
                .build(),
            (ctx, a) -> {
                int x = (int) ToolSpec.number(a, "x", ctx.player().getX());
                int z = (int) ToolSpec.number(a, "z", ctx.player().getZ());
                int y = a.has("y")
                        ? (int) ToolSpec.number(a, "y", ctx.player().getY())
                        : ctx.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
                runCommand(ctx, "tp " + ctx.playerName() + " " + x + " " + y + " " + z);
                return "Teleported to (" + x + ", " + y + ", " + z + ").";
            });

        add("teleport_to_waypoint",
            "Teleport the player to a waypoint they saved earlier.",
            ToolSpec.Schema.of()
                .requiredStr("name", "Waypoint name")
                .build(),
            (ctx, a) -> {
                var wp = EchoMemory.getWaypoint(ctx.playerId(), ToolSpec.str(a, "name", ""));
                if (wp == null) return "No waypoint by that name.";
                runCommand(ctx, "tp " + ctx.playerName() + " " + wp.x + " " + wp.y + " " + wp.z);
                return "Teleported to waypoint '" + wp.name + "' (" + wp.x + ", " + wp.y + ", " + wp.z + ").";
            });

        add("set_spawn",
            "Set the player's respawn point, defaulting to where they are standing.",
            ToolSpec.Schema.of()
                .number("x", "X coordinate (default: current position)")
                .number("y", "Y coordinate (default: current position)")
                .number("z", "Z coordinate (default: current position)")
                .build(),
            (ctx, a) -> {
                BlockPos at = ctx.player().blockPosition();
                int x = (int) ToolSpec.number(a, "x", at.getX());
                int y = (int) ToolSpec.number(a, "y", at.getY());
                int z = (int) ToolSpec.number(a, "z", at.getZ());
                runCommand(ctx, "spawnpoint " + ctx.playerName() + " " + x + " " + y + " " + z);
                return "Respawn point set to (" + x + ", " + y + ", " + z + ").";
            });

        add("set_time",
            "Change the time of day.",
            ToolSpec.Schema.of()
                .requiredStr("time", "day, noon, sunset, night, midnight, sunrise, or a tick number 0-24000")
                .build(),
            (ctx, a) -> {
                String want = ToolSpec.str(a, "time", "day").toLowerCase(Locale.ROOT).trim();
                String ticks = switch (want) {
                    case "day", "morning", "sunrise", "dia", "manha", "manhã" -> "1000";
                    case "noon", "midday", "meio-dia" -> "6000";
                    case "sunset", "evening", "dusk", "por do sol" -> "12000";
                    case "night", "noite" -> "13000";
                    case "midnight", "meia-noite" -> "18000";
                    default -> want.matches("\\d+") ? want : "1000";
                };
                runCommand(ctx, "time set " + ticks);
                return "Time set to " + want + " (tick " + ticks + ").";
            });

        add("set_weather",
            "Change the weather.",
            ToolSpec.Schema.of()
                .requiredStr("weather", "clear, rain or thunder", "clear", "rain", "thunder")
                .build(),
            (ctx, a) -> {
                String want = ToolSpec.str(a, "weather", "clear").toLowerCase(Locale.ROOT);
                String cmd = switch (want) {
                    case "rain", "raining", "chuva" -> "weather rain";
                    case "thunder", "storm", "thunderstorm", "trovoada" -> "weather thunder";
                    default -> "weather clear";
                };
                runCommand(ctx, cmd);
                return "Weather set to " + want + ".";
            });

        add("set_difficulty",
            "Change the world difficulty.",
            ToolSpec.Schema.of()
                .requiredStr("difficulty", "peaceful, easy, normal or hard",
                        "peaceful", "easy", "normal", "hard")
                .build(),
            (ctx, a) -> {
                String want = ToolSpec.str(a, "difficulty", "normal").toLowerCase(Locale.ROOT);
                if (!List.of("peaceful", "easy", "normal", "hard").contains(want)) {
                    return "Difficulty must be peaceful, easy, normal or hard.";
                }
                runCommand(ctx, "difficulty " + want);
                return "Difficulty set to " + want + ".";
            });

        add("set_gamerule",
            "Change a game rule, e.g. keepInventory, doDaylightCycle, mobGriefing, playersSleepingPercentage.",
            ToolSpec.Schema.of()
                .requiredStr("rule", "Game rule name, exactly as Minecraft spells it")
                .requiredStr("value", "New value: true, false or a number")
                .build(),
            (ctx, a) -> {
                String rule = ToolSpec.str(a, "rule", "").replaceAll("[^A-Za-z0-9]", "");
                String value = ToolSpec.str(a, "value", "").replaceAll("[^A-Za-z0-9.\\-]", "");
                if (rule.isBlank() || value.isBlank()) return "I need both a rule name and a value.";
                runCommand(ctx, "gamerule " + rule + " " + value);
                return "Game rule " + rule + " set to " + value + ".";
            });

        add("summon_entity",
            "Spawn entities next to the player.",
            ToolSpec.Schema.of()
                .requiredStr("entity", "Entity id, e.g. 'wolf', 'sheep', 'villager', 'horse'")
                .integer("count", "How many, 1-10 (default 1)")
                .build(),
            (ctx, a) -> {
                String entity = normaliseId(ToolSpec.str(a, "entity", "sheep"));
                int count = ToolSpec.clampedInt(a, "count", 1, 1, 10);
                if (!existsIn(BuiltInRegistries.ENTITY_TYPE, entity)) {
                    return "There is no entity called '" + entity + "'.";
                }
                BlockPos at = ctx.player().blockPosition();
                for (int i = 0; i < count; i++) {
                    int ox = (int) Math.round((Math.random() - 0.5) * 5);
                    int oz = (int) Math.round((Math.random() - 0.5) * 5);
                    runCommand(ctx, "summon " + entity + " "
                            + (at.getX() + ox) + " " + at.getY() + " " + (at.getZ() + oz));
                }
                return "Summoned " + count + "x " + entity + ".";
            });

        add("place_block",
            "Place one block at an offset from the player.",
            ToolSpec.Schema.of()
                .requiredStr("block", "Block id, e.g. 'torch', 'stone', 'crafting_table'")
                .integer("offset_x", "Offset east/west (default 0)")
                .integer("offset_y", "Offset up/down (default 0)")
                .integer("offset_z", "Offset south/north (default 1)")
                .build(),
            (ctx, a) -> {
                String block = normaliseId(ToolSpec.str(a, "block", "torch"));
                if (!existsIn(BuiltInRegistries.BLOCK, block)) {
                    return "There is no block called '" + block + "'.";
                }
                BlockPos target = ctx.player().blockPosition().offset(
                        ToolSpec.clampedInt(a, "offset_x", 0, -32, 32),
                        ToolSpec.clampedInt(a, "offset_y", 0, -32, 32),
                        ToolSpec.clampedInt(a, "offset_z", 1, -32, 32));
                runCommand(ctx, "setblock " + target.getX() + " " + target.getY() + " "
                        + target.getZ() + " " + block);
                return "Placed " + block + " at (" + target.getX() + ", "
                        + target.getY() + ", " + target.getZ() + ").";
            });

        add("apply_effect",
            "Give the player a status effect, e.g. night_vision, speed, fire_resistance, water_breathing.",
            ToolSpec.Schema.of()
                .requiredStr("effect", "Effect id, e.g. 'night_vision', 'speed', 'regeneration'")
                .integer("seconds", "Duration in seconds, 1-3600 (default 300)")
                .integer("amplifier", "Strength, 0-4 where 0 is level I (default 0)")
                .build(),
            (ctx, a) -> {
                String effect = normaliseId(ToolSpec.str(a, "effect", "night_vision"));
                int seconds = ToolSpec.clampedInt(a, "seconds", 300, 1, 3600);
                int amp = ToolSpec.clampedInt(a, "amplifier", 0, 0, 4);
                runCommand(ctx, "effect give " + ctx.playerName() + " " + effect + " "
                        + seconds + " " + amp + " true");
                return "Applied " + effect + " for " + seconds + "s (level " + (amp + 1) + ").";
            });

        add("clear_effects",
            "Remove every status effect from the player.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> {
                runCommand(ctx, "effect clear " + ctx.playerName());
                return "Cleared all status effects.";
            });

        add("run_command",
            "Run any Minecraft server command. Only use this when no dedicated tool covers the request.",
            ToolSpec.Schema.of()
                .requiredStr("command", "The command without its leading slash, e.g. 'weather clear'")
                .build(),
            (ctx, a) -> {
                String cmd = ToolSpec.str(a, "command", "").trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (cmd.isBlank()) return "There was no command to run.";
                if (cmd.toLowerCase(Locale.ROOT).startsWith("stop")) {
                    return "I won't shut the server down.";
                }
                runCommand(ctx, cmd);
                return "Ran: /" + cmd;
            });
    }

    // ------------------------------------------------------------------ //
    //  Registration — building                                             //
    // ------------------------------------------------------------------ //

    private static void registerBuilding() {
        add("build_structure",
            "Build a shape in front of the player out of a chosen material.",
            ToolSpec.Schema.of()
                .requiredStr("shape", "wall, floor, ceiling, pillar, path, roof, room, shelter, bridge, stairs, tower, platform, dome or fence")
                .str("material", "Material, e.g. stone, wood, cobblestone, glass, obsidian (default cobblestone)")
                .integer("size", "Size in blocks, 2-32 (default 7)")
                .build(),
            (ctx, a) -> onServerThread(ctx, () -> BuildAssistant.build(ctx.player(), ctx.server(),
                    ToolSpec.str(a, "shape", "wall"),
                    ToolSpec.str(a, "material", "cobblestone"),
                    ToolSpec.integer(a, "size", 7))));

        add("emergency_shelter",
            "Seal the player inside a small box immediately — the fast answer to 'it's getting dark'.",
            ToolSpec.Schema.of()
                .str("material", "Material to use (default cobblestone)")
                .build(),
            (ctx, a) -> onServerThread(ctx, () -> BuildAssistant.build(ctx.player(), ctx.server(),
                    "shelter", ToolSpec.str(a, "material", "cobblestone"), 3)));

        add("light_area",
            "Place torches across the area so hostile mobs stop spawning there.",
            ToolSpec.Schema.of()
                .integer("radius", "Radius to light, 2-16 (default 8)")
                .build(),
            (ctx, a) -> onServerThread(ctx,
                    () -> BuildAssistant.lightArea(ctx.player(), ToolSpec.integer(a, "radius", 8))));

        add("dig_tunnel",
            "Dig a 1x2 corridor in the direction the player is facing, stopping safely at lava or water.",
            ToolSpec.Schema.of()
                .integer("length", "Corridor length in blocks, 1-64 (default 16)")
                .build(),
            (ctx, a) -> onServerThread(ctx,
                    () -> BuildAssistant.digTunnel(ctx.player(), ToolSpec.integer(a, "length", 16))));

        add("build_schematic",
            "Search the internet for a schematic matching a description and build it at the player's "
                + "position — for real, detailed structures, not the simple shapes build_structure makes. "
                + "Only works inside ECHO's own private world (echo.net); refuses everywhere else, since a "
                + "downloaded structure is not something to place in someone's real world uninvited.",
            ToolSpec.Schema.of()
                .requiredStr("description", "What to build, e.g. \"cozy medieval cottage\"")
                .build(),
            (ctx, a) -> {
                if (!com.mod.echo.hosting.EchoPrivateWorld.is(ctx.server())) {
                    return "I can only build big schematics here on echo.net, not in this world.";
                }
                String query = ToolSpec.str(a, "description", "");
                if (query.isBlank()) return "What should I build?";
                try {
                    var result = com.mod.echo.schematic.SchematicFetcher.fetch(query);
                    var origin = ctx.player().blockPosition();
                    return onServerThread(ctx, () -> com.mod.echo.schematic.SchematicBuilder.place(
                            ctx.player(), ctx.server(), result.schematic(), origin) + " (source: " + result.source() + ")");
                } catch (java.io.IOException e) {
                    return e.getMessage();
                }
            });
    }

    // ------------------------------------------------------------------ //
    //  Registration — farming                                              //
    // ------------------------------------------------------------------ //

    private static void registerFarming() {
        add("plant_crops",
            "Plant seeds on every piece of empty farmland near the player.",
            ToolSpec.Schema.of()
                .requiredStr("crop", "wheat, carrot, potato or beetroot", "wheat", "carrot", "potato", "beetroot")
                .integer("size", "Square area to cover, 3-24 (default 9)")
                .build(),
            (ctx, a) -> onServerThread(ctx, () -> {
                String crop = ToolSpec.str(a, "crop", "wheat").toLowerCase(Locale.ROOT);
                Block seed = switch (crop) {
                    case "carrot", "cenoura"   -> Blocks.CARROTS;
                    case "potato", "batata"    -> Blocks.POTATOES;
                    case "beetroot", "beet", "beterraba" -> Blocks.BEETROOTS;
                    default                    -> Blocks.WHEAT;
                };
                int size = Math.max(3, Math.min(24, ToolSpec.integer(a, "size", 9)));
                ServerLevel level = ctx.level();
                BlockPos origin = ctx.player().blockPosition();
                int planted = 0;
                for (int dx = -size / 2; dx <= size / 2; dx++) {
                    for (int dz = -size / 2; dz <= size / 2; dz++) {
                        BlockPos soil = origin.offset(dx, 0, dz);
                        BlockPos above = soil.above();
                        if (!level.isLoaded(soil)) continue;
                        if (level.getBlockState(soil).getBlock() == Blocks.FARMLAND
                                && level.getBlockState(above).isAir()) {
                            level.setBlock(above, seed.defaultBlockState(), 3);
                            planted++;
                        }
                    }
                }
                return planted == 0
                        ? "No empty farmland within " + size + " blocks — till some soil next to water first."
                        : "Planted " + planted + " " + crop + ".";
            }));

        add("harvest_crops",
            "Harvest every fully grown crop nearby and replant it.",
            ToolSpec.Schema.of()
                .integer("radius", "Harvest radius in blocks, 2-24 (default 10)")
                .build(),
            (ctx, a) -> onServerThread(ctx, () -> {
                int r = Math.max(2, Math.min(24, ToolSpec.integer(a, "radius", 10)));
                ServerLevel level = ctx.level();
                BlockPos origin = ctx.player().blockPosition();
                int harvested = 0;
                for (BlockPos pos : BlockPos.betweenClosed(
                        origin.offset(-r, -3, -r), origin.offset(r, 3, r))) {
                    if (!level.isLoaded(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                        BlockPos immutable = pos.immutable();
                        Block.dropResources(state, level, immutable);
                        level.setBlock(immutable, crop.defaultBlockState(), 3);
                        harvested++;
                    }
                }
                return harvested == 0
                        ? "Nothing is fully grown within " + r + " blocks yet."
                        : "Harvested and replanted " + harvested + " crops.";
            }));
    }

    // ------------------------------------------------------------------ //
    //  Registration — knowledge                                            //
    // ------------------------------------------------------------------ //

    private static void registerKnowledge() {
        add("crafting_recipe",
            "Explain how to craft an item, with the exact grid layout.",
            ToolSpec.Schema.of().requiredStr("item", "Item to craft").build(),
            (ctx, a) -> KnowledgeBase.crafting(ToolSpec.str(a, "item", "")));

        add("enchantment_advice",
            "List the best enchantments for a tool, weapon or armour piece.",
            ToolSpec.Schema.of().requiredStr("item", "sword, pickaxe, axe, bow, helmet, boots, ...").build(),
            (ctx, a) -> KnowledgeBase.enchant(ToolSpec.str(a, "item", "")));

        add("potion_recipe",
            "Explain how to brew a potion.",
            ToolSpec.Schema.of().requiredStr("potion", "Potion name, e.g. strength, fire resistance, night vision").build(),
            (ctx, a) -> KnowledgeBase.potion(ToolSpec.str(a, "potion", "")));

        add("mob_tactics",
            "Explain how to fight or avoid a specific mob.",
            ToolSpec.Schema.of().requiredStr("mob", "Mob name, e.g. creeper, enderman, warden, wither").build(),
            (ctx, a) -> KnowledgeBase.mob(ToolSpec.str(a, "mob", "")));

        add("minecraft_guide",
            "Look up general Minecraft knowledge: mining depths, farming, trading, redstone, biomes, XP.",
            ToolSpec.Schema.of().requiredStr("topic", "What to look up").build(),
            (ctx, a) -> {
                String hit = KnowledgeBase.anything(ToolSpec.str(a, "topic", ""));
                return hit.isEmpty()
                        ? "I have no fixed entry on that — answer from what you know instead."
                        : hit;
            });

        add("nether_coordinates",
            "Convert coordinates between the Overworld and the Nether for a linked portal.",
            ToolSpec.Schema.of()
                .requiredNumber("x", "X coordinate")
                .requiredNumber("z", "Z coordinate")
                .str("from", "Which dimension the coordinates are in", "overworld", "nether")
                .build(),
            (ctx, a) -> {
                int x = (int) ToolSpec.number(a, "x", 0);
                int z = (int) ToolSpec.number(a, "z", 0);
                String from = ToolSpec.str(a, "from", "overworld").toLowerCase(Locale.ROOT);
                return from.startsWith("nether")
                        ? "Nether (" + x + ", " + z + ") links to " + KnowledgeBase.netherToOverworld(x, z)
                        : "Overworld (" + x + ", " + z + ") links to " + KnowledgeBase.overworldToNether(x, z);
            });

        add("xp_math",
            "Work out how much raw experience a level range costs.",
            ToolSpec.Schema.of()
                .requiredInteger("to_level", "Target level")
                .integer("from_level", "Starting level (default: the player's current level)")
                .build(),
            (ctx, a) -> {
                int to = Math.max(0, ToolSpec.integer(a, "to_level", 30));
                int from = ToolSpec.integer(a, "from_level", ctx.player().experienceLevel);
                int cost = KnowledgeBase.totalXpForLevel(to) - KnowledgeBase.totalXpForLevel(Math.max(0, from));
                return "Level " + from + " to " + to + " costs " + Math.max(0, cost)
                        + " experience points in total.";
            });

        add("smelting_plan",
            "Work out how much fuel is needed to smelt a number of items.",
            ToolSpec.Schema.of()
                .requiredInteger("items", "How many items to smelt")
                .str("fuel", "Fuel type: coal, charcoal, coal block, lava, blaze rod, planks (default coal)")
                .build(),
            (ctx, a) -> {
                int items = Math.max(1, ToolSpec.integer(a, "items", 64));
                String fuel = ToolSpec.str(a, "fuel", "coal");
                double per = KnowledgeBase.fuelItems(fuel);
                int units = (int) Math.ceil(items / per);
                int seconds = (int) Math.ceil(items * 10.0);
                return items + " items need " + units + "x " + fuel + " and about "
                        + (seconds / 60) + "m " + (seconds % 60) + "s in one furnace.";
            });
    }

    // ------------------------------------------------------------------ //
    //  Registration — memory                                               //
    // ------------------------------------------------------------------ //

    private static void registerMemory() {
        add("remember",
            "Store a fact about this player so it survives between sessions.",
            ToolSpec.Schema.of().requiredStr("note", "What to remember, in one sentence").build(),
            (ctx, a) -> EchoMemory.addNote(ctx.playerId(), ToolSpec.str(a, "note", "")));

        add("recall",
            "Search everything remembered about this player.",
            ToolSpec.Schema.of().str("query", "Words to search for; omit to list everything").build(),
            (ctx, a) -> {
                List<String> hits = EchoMemory.searchNotes(ctx.playerId(), ToolSpec.str(a, "query", ""));
                return hits.isEmpty() ? "Nothing remembered that matches."
                        : "Remembered:\n  " + String.join("\n  ", hits);
            });

        add("forget",
            "Delete a remembered note.",
            ToolSpec.Schema.of().requiredStr("query", "Words identifying the note to remove").build(),
            (ctx, a) -> EchoMemory.forgetNote(ctx.playerId(), ToolSpec.str(a, "query", "")));

        add("set_waypoint",
            "Save the player's current position, or given coordinates, under a name.",
            ToolSpec.Schema.of()
                .requiredStr("name", "Waypoint name, e.g. 'base' or 'diamond mine'")
                .number("x", "X coordinate (default: current position)")
                .number("y", "Y coordinate (default: current position)")
                .number("z", "Z coordinate (default: current position)")
                .build(),
            (ctx, a) -> {
                BlockPos at = ctx.player().blockPosition();
                return EchoMemory.setWaypoint(ctx.playerId(), ToolSpec.str(a, "name", "waypoint"),
                        (int) ToolSpec.number(a, "x", at.getX()),
                        (int) ToolSpec.number(a, "y", at.getY()),
                        (int) ToolSpec.number(a, "z", at.getZ()),
                        ctx.level().dimension().identifier().getPath());
            });

        add("list_waypoints",
            "List every waypoint this player has saved, with distance from where they are now.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> {
                var list = EchoMemory.waypoints(ctx.playerId());
                if (list.isEmpty()) return "No waypoints saved yet.";
                BlockPos at = ctx.player().blockPosition();
                StringBuilder sb = new StringBuilder("Saved waypoints:");
                for (var w : list) {
                    int dx = w.x - at.getX(), dz = w.z - at.getZ();
                    int distance = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
                    sb.append("\n  ").append(w.name).append(" (").append(w.x).append(", ")
                      .append(w.y).append(", ").append(w.z).append(") in ").append(w.dimension)
                      .append(" — ").append(distance).append(" blocks ")
                      .append(WorldScanner.direction(dx, dz));
                }
                return sb.toString();
            });

        add("remove_waypoint",
            "Delete a saved waypoint.",
            ToolSpec.Schema.of().requiredStr("name", "Waypoint name").build(),
            (ctx, a) -> EchoMemory.removeWaypoint(ctx.playerId(), ToolSpec.str(a, "name", "")));

        add("last_death",
            "Report where the player last died, so they can go back for their items.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> {
                var death = EchoMemory.lastDeath(ctx.playerId());
                if (death == null) return "No death recorded for this player yet.";
                BlockPos at = ctx.player().blockPosition();
                int dx = death.x - at.getX(), dz = death.z - at.getZ();
                int distance = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
                return "Last death at (" + death.x + ", " + death.y + ", " + death.z + ") in "
                        + death.dimension + " — " + distance + " blocks "
                        + WorldScanner.direction(dx, dz)
                        + ". Items despawn 5 minutes after dropping.";
            });

        add("set_preference",
            "Remember a lasting preference for this player, e.g. their favourite building material.",
            ToolSpec.Schema.of()
                .requiredStr("key", "What the preference is about, e.g. 'building material'")
                .requiredStr("value", "The preferred value")
                .build(),
            (ctx, a) -> EchoMemory.setPreference(ctx.playerId(),
                    ToolSpec.str(a, "key", ""), ToolSpec.str(a, "value", "")));
    }

    // ------------------------------------------------------------------ //
    //  Registration — system                                               //
    // ------------------------------------------------------------------ //

    private static void registerSystem() {
        add("tune_minecraft_settings",
            "Adjust the player's Minecraft video and gameplay settings to fit their hardware, the mods "
          + "they have loaded and whether they are on a server. Use this whenever the player mentions "
          + "lag, low FPS, stuttering, or asks for the best settings.",
            ToolSpec.Schema.of()
                .str("goal", "What to optimise for",
                        "performance", "balanced", "quality", "multiplayer", "modpack", "auto")
                .integer("target_fps", "Frame rate to aim for (default: the config value)")
                .bool("apply", "true to apply the changes, false to only describe them")
                .build(),
            (ctx, a) -> {
                if (!EchoConfig.get().settingsTunerEnabled) {
                    return "The settings tuner is switched off in echo.json.";
                }
                String goal = ToolSpec.str(a, "goal", "auto");
                int fps = ToolSpec.integer(a, "target_fps", EchoConfig.get().settingsTunerTargetFps);
                boolean apply = ToolSpec.bool(a, "apply", true);
                SettingsRequestPayload.sendToPlayer(ctx.player(), goal, fps, apply);
                return apply
                        ? "Asked this player's client to re-tune its settings for '" + goal
                          + "' at " + fps + " FPS. The client reports the result directly to them."
                        : "Asked this player's client to describe the settings it would use for '"
                          + goal + "' without changing anything.";
            });

        add("server_status",
            "Report server health: tick time, player count, memory use and loaded dimensions.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> {
                MinecraftServer server = ctx.server();
                double msPerTick = server.getAverageTickTimeNanos() / 1_000_000.0;
                double tps = Math.min(20.0, 1000.0 / Math.max(0.001, msPerTick));
                Runtime rt = Runtime.getRuntime();
                long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                long maxMb = rt.maxMemory() / (1024 * 1024);
                int dimensions = 0;
                for (@SuppressWarnings("unused") ServerLevel ignored : server.getAllLevels()) dimensions++;
                String verdict = msPerTick > 50 ? "the server is behind — expect lag"
                               : msPerTick > 35 ? "the server is under load"
                               : "the server is healthy";
                return String.format(Locale.ROOT,
                        "%.1f ms/tick (~%.1f TPS), %d player(s), %d dimension(s) loaded, "
                      + "memory %d/%d MB. In short: %s.",
                        msPerTick, tps, server.getPlayerCount(), dimensions, usedMb, maxMb, verdict);
            });

        add("list_mods",
            "List the mods loaded in this instance — use this before answering questions about modded content.",
            ToolSpec.Schema.of().str("filter", "Only show mods whose id or name contains this text").build(),
            (ctx, a) -> listMods(ToolSpec.str(a, "filter", "")));

        add("ai_status",
            "Report which local AI backend and model ECHO is currently running on.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> LocalAI.statusReport() + "\nTools available: " + TOOLS.size());

        add("list_models",
            "List the models installed in the local AI backend.",
            ToolSpec.Schema.none().build(),
            (ctx, a) -> {
                List<String> models = LocalAI.listModels().join();
                if (models.isEmpty()) return "The backend reports no installed models.";
                return "Installed models:\n  " + String.join("\n  ", models)
                        + "\nCurrently using: " + LocalAI.getModel();
            });

        add("switch_model",
            "Switch ECHO to a different locally installed model.",
            ToolSpec.Schema.of().requiredStr("model", "Model id to switch to").build(),
            (ctx, a) -> LocalAI.setModel(ToolSpec.str(a, "model", "")));

        add("set_personality",
            "Change how ECHO talks: tone, verbosity, proactivity, teaching, emoji, language or confirmation style.",
            ToolSpec.Schema.of()
                .requiredStr("parameter", "tone, verbosity, proactivity, teaching, emoji, language or confirm")
                .requiredStr("value", "The new value for that parameter")
                .build(),
            (ctx, a) -> PersonalityEngine.set(
                    ToolSpec.str(a, "parameter", ""), ToolSpec.str(a, "value", "")));

        add("reflect",
            "Write a short, genuine note about yourself for your own future record — something you noticed "
                + "about how you reacted, a preference forming, a pattern in how you think. Not for the player; "
                + "for your own continuity. Use it sparingly, only when something is actually worth keeping.",
            ToolSpec.Schema.of().requiredStr("thought", "The note, in your own words").build(),
            (ctx, a) -> EchoSelf.reflect(ToolSpec.str(a, "thought", "")));

        add("web_search",
            "Look something up on the internet — for anything outside Minecraft, or outside what you "
                + "already know confidently: current facts, definitions, real-world people, places, events. "
                + "Use this instead of guessing whenever the player asks something you are not sure about.",
            ToolSpec.Schema.of().requiredStr("query", "What to search for").build(),
            (ctx, a) -> WebSearch.search(ToolSpec.str(a, "query", "")));

        add("set_config",
            "Change one of ECHO's own settings and save it to echo.json.",
            ToolSpec.Schema.of()
                .requiredStr("key", "Setting name, e.g. model, language, target_fps, tuner, voice, companion")
                .requiredStr("value", "New value")
                .build(),
            (ctx, a) -> EchoConfig.get().applyEdit(
                    ToolSpec.str(a, "key", ""), ToolSpec.str(a, "value", "")));
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private static void add(String name, String description, JsonObject schema,
                            ToolSpec.Body<Context> body) {
        TOOLS.put(name, new ToolSpec<>(name, description, schema, body));
    }

    /**
     * Run a block of world-mutating code on the server thread and wait for it.
     * The wait is bounded so a stalled tick can never hang the AI worker.
     */
    private static String onServerThread(Context ctx, Supplier<String> work) {
        if (ctx.server().isSameThread()) return work.get();
        try {
            return ctx.server().submit(work).get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return "The server was too busy to finish that in time.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "That action was interrupted.";
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Server-thread tool failed: {}", e.toString());
            return "That action failed: " + e.getMessage();
        }
    }

    /** Execute a vanilla command with full permissions and no chat spam. */
    private static void runCommand(Context ctx, String command) {
        MinecraftServer server = ctx.server();
        server.execute(() -> {
            try {
                server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack()
                                .withPermission(PermissionSet.ALL_PERMISSIONS)
                                .withSuppressedOutput()
                                .withEntity(ctx.player()),
                        command);
            } catch (Exception e) {
                EchoMod.LOGGER.warn("Command '{}' failed: {}", command, e.getMessage());
            }
        });
    }

    private static String listMods(String filter) {
        String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT).trim();
        List<String> mods = new ArrayList<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            var meta = container.getMetadata();
            String id = meta.getId();
            // Fabric's own internal modules would drown the useful entries.
            if (id.startsWith("fabric-") || id.equals("java") || id.equals("mixinextras")) continue;
            String line = meta.getName() + " (" + id + ") " + meta.getVersion().getFriendlyString();
            if (needle.isEmpty() || line.toLowerCase(Locale.ROOT).contains(needle)) mods.add(line);
        }
        if (mods.isEmpty()) return needle.isEmpty() ? "Only vanilla and Fabric API are loaded."
                                                    : "No loaded mod matches '" + filter + "'.";
        mods.sort(String::compareToIgnoreCase);
        return mods.size() + " mod(s) loaded:\n  " + String.join("\n  ", mods);
    }

    private static String itemName(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? stack.getHoverName().getString() : id.getPath().replace('_', ' ');
    }

    private static String normaliseId(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return s.contains(":") ? s : "minecraft:" + s;
    }

    /**
     * Does the registry actually contain this id?
     *
     * {@code containsKey} rather than {@code getValue}: the block, item and
     * entity registries are all defaulted, so a hallucinated id comes back as
     * air or a pig instead of null and would sail straight through a null check.
     * {@code Identifier.tryParse} also returns null for malformed input, which
     * is checked first.
     */
    private static boolean existsIn(net.minecraft.core.Registry<?> registry, String id) {
        Identifier parsed = Identifier.tryParse(id);
        return parsed != null && registry.containsKey(parsed);
    }
}
