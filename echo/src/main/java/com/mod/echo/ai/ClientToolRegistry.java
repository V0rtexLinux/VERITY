package com.mod.echo.ai;

import com.google.gson.JsonObject;
import com.mod.echo.EchoMod;
import com.mod.echo.assistant.KnowledgeBase;
import com.mod.echo.config.EchoConfig;
import com.mod.echo.memory.EchoMemory;
import com.mod.echo.settings.HardwareProbe;
import com.mod.echo.settings.SettingsTuner;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tools ECHO can use while running purely on the client.
 *
 * This is what makes the mod useful on servers that do not have it installed:
 * everything here reads client-side state or drives the local game, and the two
 * tools that can affect the world do it by sending commands the player is
 * already allowed to run.
 *
 * The video-settings tuner lives here and nowhere else, because
 * {@code Minecraft.options} only exists on this side.
 */
@Environment(EnvType.CLIENT)
public final class ClientToolRegistry {

    private ClientToolRegistry() {}

    private static final Map<String, ToolSpec<Minecraft>> TOOLS = new LinkedHashMap<>();

    static {
        registerSettings();
        registerWorld();
        registerPlayer();
        registerKnowledge();
        registerMemory();
        registerSystem();
        EchoMod.LOGGER.info("Registered {} client-side tools.", TOOLS.size());
    }

    public static List<JsonObject> schemas() {
        List<JsonObject> out = new ArrayList<>();
        for (ToolSpec<Minecraft> tool : TOOLS.values()) out.add(tool.toSchema());
        return out;
    }

    public static String execute(String name, JsonObject args) {
        ToolSpec<Minecraft> tool = TOOLS.get(name);
        if (tool == null) {
            return "There is no tool called '" + name + "'. Available: " + String.join(", ", TOOLS.keySet());
        }
        EchoMod.LOGGER.debug("Client tool {} {}", name, args);
        return tool.invoke(Minecraft.getInstance(), args);
    }

    public static int count() { return TOOLS.size(); }

    // ------------------------------------------------------------------ //
    //  Settings tuner — the reason this registry exists                    //
    // ------------------------------------------------------------------ //

    private static void registerSettings() {
        add("tune_minecraft_settings",
            "Read this machine's hardware, current frame rate, installed mods and server, then apply the "
          + "matching Minecraft video and gameplay settings. Use this whenever the player mentions lag, "
          + "low FPS, stuttering, or asks what settings they should use.",
            ToolSpec.Schema.of()
                .str("goal", "What to optimise for; 'auto' lets you decide from the measurements",
                        "auto", "performance", "balanced", "quality", "multiplayer", "modpack")
                .integer("target_fps", "Frame rate to aim for (default: the config value)")
                .build(),
            (mc, a) -> SettingsTuner.tune(
                    SettingsTuner.Goal.parse(ToolSpec.str(a, "goal", "auto")),
                    ToolSpec.integer(a, "target_fps", EchoConfig.get().settingsTunerTargetFps),
                    true));

        add("preview_minecraft_settings",
            "Work out which settings would suit this machine and explain them, without changing anything.",
            ToolSpec.Schema.of()
                .str("goal", "What to optimise for",
                        "auto", "performance", "balanced", "quality", "multiplayer", "modpack")
                .integer("target_fps", "Frame rate to aim for")
                .build(),
            (mc, a) -> SettingsTuner.tune(
                    SettingsTuner.Goal.parse(ToolSpec.str(a, "goal", "auto")),
                    ToolSpec.integer(a, "target_fps", EchoConfig.get().settingsTunerTargetFps),
                    false));

        add("current_settings",
            "Read the player's current video settings.",
            ToolSpec.Schema.none().build(),
            (mc, a) -> {
                var o = mc.options;
                return "render distance " + o.renderDistance().get()
                     + ", simulation distance " + o.simulationDistance().get()
                     + ", graphics " + o.graphicsPreset().get()
                     + ", framerate limit " + o.framerateLimit().get()
                     + ", vsync " + o.enableVsync().get()
                     + ", clouds " + o.cloudStatus().get()
                     + ", particles " + o.particles().get()
                     + ", entity shadows " + o.entityShadows().get()
                     + ", smooth lighting " + o.ambientOcclusion().get()
                     + ", biome blend " + o.biomeBlendRadius().get()
                     + ", mipmaps " + o.mipmapLevels().get()
                     + ", FOV " + o.fov().get()
                     + ", GUI scale " + o.guiScale().get() + ".";
            });

        add("performance_report",
            "Report the current frame rate, memory use, CPU cores, mod count and whether this is a server.",
            ToolSpec.Schema.none().build(),
            (mc, a) -> {
                HardwareProbe.Snapshot probe = HardwareProbe.probe();
                StringBuilder sb = new StringBuilder(probe.describe());
                if (probe.isStrained()) sb.append(". This instance is struggling");
                if (probe.isMemoryConstrained()) sb.append(". Heap headroom is the limiting factor");
                if (probe.isModpack() && !probe.hasRenderingMod()) {
                    sb.append(". No rendering optimiser installed, which is the biggest single win available");
                }
                return sb.append('.').toString();
            });

        add("set_option",
            "Change one specific Minecraft option directly, when the player names it.",
            ToolSpec.Schema.of()
                .requiredStr("option", "render_distance, simulation_distance, framerate_limit, fov, "
                        + "gui_scale, brightness, vsync, entity_shadows or particles")
                .requiredStr("value", "The new value")
                .build(),
            (mc, a) -> setSingleOption(mc, ToolSpec.str(a, "option", ""), ToolSpec.str(a, "value", "")));
    }

    // ------------------------------------------------------------------ //
    //  World                                                               //
    // ------------------------------------------------------------------ //

    private static void registerWorld() {
        add("world_info",
            "Snapshot of the world around the player: dimension, biome, time and weather.",
            ToolSpec.Schema.none().build(),
            (mc, a) -> {
                ClientLevel level = requireLevel(mc);
                LocalPlayer player = requirePlayer(mc);
                long time = Math.floorMod(level.getDefaultClockTime(), 24000L);
                String phase = time < 6000 ? "morning" : time < 12000 ? "afternoon"
                             : time < 13000 ? "sunset" : time < 23000 ? "night" : "dawn";
                String weather = level.isThundering() ? "thunderstorm"
                               : level.isRaining() ? "rain" : "clear";
                BlockPos pos = player.blockPosition();
                return "Dimension " + level.dimension().identifier().getPath()
                        + ", biome " + biomeName(level, pos)
                        + ", " + phase + " (tick " + time + "), weather " + weather
                        + ", player at (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ").";
            });

        add("biome_info",
            "Name the biome the player is standing in.",
            ToolSpec.Schema.none().build(),
            (mc, a) -> "Current biome: "
                    + biomeName(requireLevel(mc), requirePlayer(mc).blockPosition()) + ".");

        add("time_info",
            "How long until nightfall or sunrise, in real minutes.",
            ToolSpec.Schema.none().build(),
            (mc, a) -> {
                long t = Math.floorMod(requireLevel(mc).getDefaultClockTime(), 24000L);
                long remaining = t >= 13000 ? 24000 - t : 13000 - t;
                String what = t >= 13000 ? "Sunrise" : "Nightfall";
                return what + " in about " + String.format(Locale.ROOT, "%.1f", remaining / 20.0 / 60.0)
                        + " real minutes.";
            });

        add("nearby_mobs",
            "List hostile mobs near the player with their distances.",
            ToolSpec.Schema.of().integer("radius", "Search radius in blocks, 4-64 (default 32)").build(),
            (mc, a) -> {
                ClientLevel level = requireLevel(mc);
                LocalPlayer player = requirePlayer(mc);
                int r = Math.max(4, Math.min(64, ToolSpec.integer(a, "radius", 32)));
                List<Monster> mobs = level.getEntitiesOfClass(
                        Monster.class, player.getBoundingBox().inflate(r), e -> true);
                if (mobs.isEmpty()) return "No hostile mobs within " + r + " blocks.";
                StringBuilder sb = new StringBuilder(mobs.size() + " hostile mobs within " + r + " blocks:");
                mobs.stream()
                    .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                    .limit(8)
                    .forEach(e -> sb.append("\n  ").append(e.getType().toShortString())
                            .append(" — ").append((int) e.distanceTo(player)).append(" blocks, (")
                            .append((int) e.getX()).append(", ").append((int) e.getY())
                            .append(", ").append((int) e.getZ()).append(")"));
                return sb.toString();
            });

        add("nearby_entities",
            "Count every entity near the player, grouped by type.",
            ToolSpec.Schema.of().integer("radius", "Search radius in blocks, 4-64 (default 32)").build(),
            (mc, a) -> {
                ClientLevel level = requireLevel(mc);
                LocalPlayer player = requirePlayer(mc);
                int r = Math.max(4, Math.min(64, ToolSpec.integer(a, "radius", 32)));
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (Entity e : level.entitiesForRendering()) {
                    if (e == player || e.distanceTo(player) > r) continue;
                    counts.merge(e.getType().toShortString(), 1, Integer::sum);
                }
                if (counts.isEmpty()) return "Nothing within " + r + " blocks.";
                StringBuilder sb = new StringBuilder("Entities within " + r + " blocks:");
                counts.entrySet().stream()
                      .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                      .limit(15)
                      .forEach(e -> sb.append("\n  ").append(e.getValue()).append("x ").append(e.getKey()));
                return sb.toString();
            });

        add("find_block",
            "Search the loaded area around the player for a block, including ores.",
            ToolSpec.Schema.of()
                .requiredStr("block", "Block name, e.g. 'diamond_ore', 'chest', 'lava'")
                .integer("radius", "Search radius in blocks, 4-32 (default 24)")
                .build(),
            (mc, a) -> {
                ClientLevel level = requireLevel(mc);
                LocalPlayer player = requirePlayer(mc);
                String raw = ToolSpec.str(a, "block", "").toLowerCase(Locale.ROOT).replace(' ', '_');
                if (raw.isBlank()) return "Which block should I look for?";
                Identifier id = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
                Block target = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
                if (target == null || target == Blocks.AIR) return "There is no block called '" + raw + "'.";

                int r = Math.max(4, Math.min(32, ToolSpec.integer(a, "radius", 24)));
                BlockPos origin = player.blockPosition();
                BlockPos best = null;
                double bestDistance = Double.MAX_VALUE;
                int count = 0;
                for (BlockPos pos : BlockPos.betweenClosed(
                        origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
                    if (level.getBlockState(pos).getBlock() != target) continue;
                    count++;
                    double d = pos.distSqr(origin);
                    if (d < bestDistance) { bestDistance = d; best = pos.immutable(); }
                }
                if (best == null) return "No " + raw + " within " + r + " blocks of the loaded area.";
                return count + "x " + raw + " nearby. Closest at (" + best.getX() + ", "
                        + best.getY() + ", " + best.getZ() + "), "
                        + (int) Math.sqrt(bestDistance) + " blocks away.";
            });

        add("light_audit",
            "Find spots near the player dark enough for mobs to spawn.",
            ToolSpec.Schema.of().integer("radius", "Check radius in blocks, 2-24 (default 10)").build(),
            (mc, a) -> {
                ClientLevel level = requireLevel(mc);
                BlockPos origin = requirePlayer(mc).blockPosition();
                int r = Math.max(2, Math.min(24, ToolSpec.integer(a, "radius", 10)));
                int dark = 0;
                for (BlockPos pos : BlockPos.betweenClosed(
                        origin.offset(-r, -3, -r), origin.offset(r, 3, r))) {
                    if (level.isEmptyBlock(pos)
                            && !level.isEmptyBlock(pos.below())
                            && level.getBrightness(LightLayer.BLOCK, pos) <= 0) {
                        dark++;
                    }
                }
                return dark == 0
                        ? "Everything within " + r + " blocks is lit — nothing can spawn."
                        : dark + " dark spots within " + r + " blocks where mobs can still spawn.";
            });

        add("send_command",
            "Send a command to the server as the player. Only works if they have permission for it.",
            ToolSpec.Schema.of()
                .requiredStr("command", "The command without its leading slash, e.g. 'locate structure village'")
                .build(),
            (mc, a) -> {
                LocalPlayer player = requirePlayer(mc);
                String cmd = ToolSpec.str(a, "command", "").trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (cmd.isBlank()) return "There was no command to send.";
                final String toSend = cmd;
                mc.execute(() -> player.connection.sendCommand(toSend));
                return "Sent /" + cmd + " to the server. The server replies in chat directly.";
            });
    }

    // ------------------------------------------------------------------ //
    //  Player                                                              //
    // ------------------------------------------------------------------ //

    private static void registerPlayer() {
        add("player_status",
            "Read the player's health, hunger, experience, armour and position.",
            ToolSpec.Schema.none().build(),
            (mc, a) -> {
                LocalPlayer p = requirePlayer(mc);
                BlockPos pos = p.blockPosition();
                return String.format(Locale.ROOT,
                        "Health %.1f/%.1f, hunger %d/20 (saturation %.1f), XP level %d, armour %d/20, at (%d, %d, %d).",
                        p.getHealth(), p.getMaxHealth(),
                        p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(),
                        p.experienceLevel, p.getArmorValue(),
                        pos.getX(), pos.getY(), pos.getZ());
            });

        add("inventory",
            "Read the player's inventory.",
            ToolSpec.Schema.none().build(),
            (mc, a) -> {
                var inv = requirePlayer(mc).getInventory();
                Map<String, Integer> totals = new LinkedHashMap<>();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty()) continue;
                    totals.merge(itemName(stack), stack.getCount(), Integer::sum);
                }
                if (totals.isEmpty()) return "The inventory is empty.";
                StringBuilder sb = new StringBuilder("Inventory:");
                totals.forEach((n, c) -> sb.append("\n  ").append(c).append("x ").append(n));
                return sb.toString();
            });

        add("count_item",
            "Count how many of an item the player is carrying.",
            ToolSpec.Schema.of().requiredStr("item", "Item name").build(),
            (mc, a) -> {
                String needle = ToolSpec.str(a, "item", "").toLowerCase(Locale.ROOT).replace(' ', '_');
                if (needle.isBlank()) return "Which item?";
                var inv = requirePlayer(mc).getInventory();
                int total = 0;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty()) continue;
                    Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (id != null && id.getPath().contains(needle)) total += stack.getCount();
                }
                return total == 0 ? "No " + needle.replace('_', ' ') + " carried."
                                  : total + "x " + needle.replace('_', ' ') + " carried.";
            });

        add("gear_durability",
            "Check how worn the player's tools and armour are.",
            ToolSpec.Schema.none().build(),
            (mc, a) -> {
                var inv = requirePlayer(mc).getInventory();
                List<String> worn = new ArrayList<>();
                List<String> critical = new ArrayList<>();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty() || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) continue;
                    int max = stack.getMaxDamage();
                    int left = max - stack.getDamageValue();
                    int percent = (int) Math.round(left * 100.0 / max);
                    String entry = itemName(stack) + " " + percent + "%";
                    if (percent <= 15) critical.add(entry); else worn.add(entry);
                }
                if (worn.isEmpty() && critical.isEmpty()) return "Nothing carried has durability.";
                StringBuilder sb = new StringBuilder();
                if (!critical.isEmpty()) sb.append("About to break: ").append(String.join("; ", critical));
                if (!worn.isEmpty()) {
                    if (sb.length() > 0) sb.append(". ");
                    sb.append("Fine: ").append(String.join("; ", worn));
                }
                return sb.toString();
            });
    }

    // ------------------------------------------------------------------ //
    //  Knowledge                                                           //
    // ------------------------------------------------------------------ //

    private static void registerKnowledge() {
        add("crafting_recipe", "Explain how to craft an item.",
            ToolSpec.Schema.of().requiredStr("item", "Item to craft").build(),
            (mc, a) -> KnowledgeBase.crafting(ToolSpec.str(a, "item", "")));

        add("enchantment_advice", "List the best enchantments for a piece of gear.",
            ToolSpec.Schema.of().requiredStr("item", "sword, pickaxe, boots, bow, ...").build(),
            (mc, a) -> KnowledgeBase.enchant(ToolSpec.str(a, "item", "")));

        add("potion_recipe", "Explain how to brew a potion.",
            ToolSpec.Schema.of().requiredStr("potion", "Potion name").build(),
            (mc, a) -> KnowledgeBase.potion(ToolSpec.str(a, "potion", "")));

        add("mob_tactics", "Explain how to fight or avoid a mob.",
            ToolSpec.Schema.of().requiredStr("mob", "Mob name").build(),
            (mc, a) -> KnowledgeBase.mob(ToolSpec.str(a, "mob", "")));

        add("minecraft_guide", "Look up mining depths, farming, trading, redstone, biomes or XP.",
            ToolSpec.Schema.of().requiredStr("topic", "What to look up").build(),
            (mc, a) -> {
                String hit = KnowledgeBase.anything(ToolSpec.str(a, "topic", ""));
                return hit.isEmpty() ? "No fixed entry — answer from what you know." : hit;
            });

        add("nether_coordinates", "Convert coordinates between the Overworld and the Nether.",
            ToolSpec.Schema.of()
                .requiredNumber("x", "X coordinate")
                .requiredNumber("z", "Z coordinate")
                .str("from", "Which dimension the coordinates are in", "overworld", "nether")
                .build(),
            (mc, a) -> {
                int x = (int) ToolSpec.number(a, "x", 0);
                int z = (int) ToolSpec.number(a, "z", 0);
                return ToolSpec.str(a, "from", "overworld").toLowerCase(Locale.ROOT).startsWith("nether")
                        ? "Nether (" + x + ", " + z + ") links to " + KnowledgeBase.netherToOverworld(x, z)
                        : "Overworld (" + x + ", " + z + ") links to " + KnowledgeBase.overworldToNether(x, z);
            });

        add("xp_math", "Work out the raw experience cost of a level range.",
            ToolSpec.Schema.of()
                .requiredInteger("to_level", "Target level")
                .integer("from_level", "Starting level (default: the player's current level)")
                .build(),
            (mc, a) -> {
                int to = Math.max(0, ToolSpec.integer(a, "to_level", 30));
                int from = Math.max(0, ToolSpec.integer(a, "from_level",
                        mc.player == null ? 0 : mc.player.experienceLevel));
                int cost = KnowledgeBase.totalXpForLevel(to) - KnowledgeBase.totalXpForLevel(from);
                return "Level " + from + " to " + to + " costs " + Math.max(0, cost) + " experience points.";
            });

        add("smelting_plan", "Work out how much fuel a smelting job needs.",
            ToolSpec.Schema.of()
                .requiredInteger("items", "How many items to smelt")
                .str("fuel", "Fuel type (default coal)")
                .build(),
            (mc, a) -> {
                int items = Math.max(1, ToolSpec.integer(a, "items", 64));
                String fuel = ToolSpec.str(a, "fuel", "coal");
                int units = (int) Math.ceil(items / KnowledgeBase.fuelItems(fuel));
                int seconds = items * 10;
                return items + " items need " + units + "x " + fuel + " and about "
                        + (seconds / 60) + "m " + (seconds % 60) + "s in one furnace.";
            });
    }

    // ------------------------------------------------------------------ //
    //  Memory                                                              //
    // ------------------------------------------------------------------ //

    private static void registerMemory() {
        add("remember", "Store a fact about this player between sessions.",
            ToolSpec.Schema.of().requiredStr("note", "What to remember").build(),
            (mc, a) -> EchoMemory.addNote(memoryKey(mc), ToolSpec.str(a, "note", "")));

        add("recall", "Search everything remembered about this player.",
            ToolSpec.Schema.of().str("query", "Words to search for; omit to list everything").build(),
            (mc, a) -> {
                List<String> hits = EchoMemory.searchNotes(memoryKey(mc), ToolSpec.str(a, "query", ""));
                return hits.isEmpty() ? "Nothing remembered that matches."
                                      : "Remembered:\n  " + String.join("\n  ", hits);
            });

        add("forget", "Delete a remembered note.",
            ToolSpec.Schema.of().requiredStr("query", "Words identifying the note").build(),
            (mc, a) -> EchoMemory.forgetNote(memoryKey(mc), ToolSpec.str(a, "query", "")));

        add("set_waypoint", "Save the player's position under a name.",
            ToolSpec.Schema.of()
                .requiredStr("name", "Waypoint name")
                .number("x", "X coordinate (default: current position)")
                .number("y", "Y coordinate (default: current position)")
                .number("z", "Z coordinate (default: current position)")
                .build(),
            (mc, a) -> {
                LocalPlayer p = requirePlayer(mc);
                BlockPos at = p.blockPosition();
                return EchoMemory.setWaypoint(memoryKey(mc), ToolSpec.str(a, "name", "waypoint"),
                        (int) ToolSpec.number(a, "x", at.getX()),
                        (int) ToolSpec.number(a, "y", at.getY()),
                        (int) ToolSpec.number(a, "z", at.getZ()),
                        requireLevel(mc).dimension().identifier().getPath());
            });

        add("list_waypoints", "List saved waypoints with distances from the player.",
            ToolSpec.Schema.none().build(),
            (mc, a) -> {
                var list = EchoMemory.waypoints(memoryKey(mc));
                if (list.isEmpty()) return "No waypoints saved yet.";
                BlockPos at = requirePlayer(mc).blockPosition();
                StringBuilder sb = new StringBuilder("Saved waypoints:");
                for (var w : list) {
                    int dx = w.x - at.getX(), dz = w.z - at.getZ();
                    sb.append("\n  ").append(w.name).append(" (").append(w.x).append(", ")
                      .append(w.y).append(", ").append(w.z).append(") — ")
                      .append((int) Math.sqrt((double) dx * dx + (double) dz * dz)).append(" blocks");
                }
                return sb.toString();
            });

        add("remove_waypoint", "Delete a saved waypoint.",
            ToolSpec.Schema.of().requiredStr("name", "Waypoint name").build(),
            (mc, a) -> EchoMemory.removeWaypoint(memoryKey(mc), ToolSpec.str(a, "name", "")));

        add("last_death", "Report where the player last died.",
            ToolSpec.Schema.none().build(),
            (mc, a) -> {
                var death = EchoMemory.lastDeath(memoryKey(mc));
                if (death == null) return "No death recorded yet.";
                BlockPos at = requirePlayer(mc).blockPosition();
                int dx = death.x - at.getX(), dz = death.z - at.getZ();
                return "Last death at (" + death.x + ", " + death.y + ", " + death.z + ") in "
                        + death.dimension + ", "
                        + (int) Math.sqrt((double) dx * dx + (double) dz * dz) + " blocks away.";
            });

        add("set_preference", "Remember a lasting preference for this player.",
            ToolSpec.Schema.of()
                .requiredStr("key", "What the preference is about")
                .requiredStr("value", "The preferred value")
                .build(),
            (mc, a) -> EchoMemory.setPreference(memoryKey(mc),
                    ToolSpec.str(a, "key", ""), ToolSpec.str(a, "value", "")));
    }

    // ------------------------------------------------------------------ //
    //  System                                                              //
    // ------------------------------------------------------------------ //

    private static void registerSystem() {
        add("list_mods", "List the mods loaded in this instance.",
            ToolSpec.Schema.of().str("filter", "Only show mods matching this text").build(),
            (mc, a) -> {
                String needle = ToolSpec.str(a, "filter", "").toLowerCase(Locale.ROOT);
                List<String> mods = new ArrayList<>();
                for (var container : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                    var meta = container.getMetadata();
                    if (meta.getId().startsWith("fabric-") || meta.getId().equals("java")) continue;
                    String line = meta.getName() + " (" + meta.getId() + ") "
                            + meta.getVersion().getFriendlyString();
                    if (needle.isEmpty() || line.toLowerCase(Locale.ROOT).contains(needle)) mods.add(line);
                }
                if (mods.isEmpty()) return "No matching mods loaded.";
                mods.sort(String::compareToIgnoreCase);
                return mods.size() + " mod(s):\n  " + String.join("\n  ", mods);
            });

        add("ai_status", "Report which local AI backend and model ECHO is running on.",
            ToolSpec.Schema.none().build(),
            (mc, a) -> LocalAI.statusReport() + "\nClient tools available: " + TOOLS.size());

        add("list_models", "List the models installed in the local AI backend.",
            ToolSpec.Schema.none().build(),
            (mc, a) -> {
                List<String> models = LocalAI.listModels().join();
                return models.isEmpty() ? "The backend reports no installed models."
                        : "Installed models:\n  " + String.join("\n  ", models)
                          + "\nCurrently using: " + LocalAI.getModel();
            });

        add("switch_model", "Switch ECHO to a different locally installed model.",
            ToolSpec.Schema.of().requiredStr("model", "Model id").build(),
            (mc, a) -> LocalAI.setModel(ToolSpec.str(a, "model", "")));

        add("set_personality",
            "Change how ECHO talks: tone, verbosity, proactivity, teaching, emoji, language or confirm.",
            ToolSpec.Schema.of()
                .requiredStr("parameter", "tone, verbosity, proactivity, teaching, emoji, language or confirm")
                .requiredStr("value", "The new value")
                .build(),
            (mc, a) -> PersonalityEngine.set(ToolSpec.str(a, "parameter", ""), ToolSpec.str(a, "value", "")));

        add("set_config", "Change one of ECHO's own settings and save it.",
            ToolSpec.Schema.of()
                .requiredStr("key", "Setting name")
                .requiredStr("value", "New value")
                .build(),
            (mc, a) -> EchoConfig.get().applyEdit(ToolSpec.str(a, "key", ""), ToolSpec.str(a, "value", "")));
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private static void add(String name, String description, JsonObject schema,
                            ToolSpec.Body<Minecraft> body) {
        TOOLS.put(name, new ToolSpec<>(name, description, schema, body));
    }

    private static LocalPlayer requirePlayer(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) throw new IllegalStateException("the player is not in a world right now");
        return player;
    }

    private static ClientLevel requireLevel(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) throw new IllegalStateException("no world is loaded right now");
        return level;
    }

    private static String memoryKey(Minecraft mc) {
        return mc.player != null ? mc.player.getUUID().toString() : "client";
    }

    private static String biomeName(ClientLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .map(key -> key.identifier().getPath().replace('_', ' '))
                .orElse("unknown");
    }

    private static String itemName(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? stack.getHoverName().getString() : id.getPath().replace('_', ' ');
    }

    /** Apply a single named option, for when the player asks for one thing specifically. */
    private static String setSingleOption(Minecraft mc, String option, String value) {
        String key = option.toLowerCase(Locale.ROOT).replace(' ', '_').replace("-", "_");
        String raw = value == null ? "" : value.trim();
        var o = mc.options;
        String result;
        try {
            switch (key) {
                case "render_distance": {
                    int v = Math.max(2, Math.min(32, Integer.parseInt(raw)));
                    mc.execute(() -> o.renderDistance().set(v));
                    result = "Render distance set to " + v + " chunks.";
                    break;
                }
                case "simulation_distance": {
                    int v = Math.max(5, Math.min(32, Integer.parseInt(raw)));
                    mc.execute(() -> o.simulationDistance().set(v));
                    result = "Simulation distance set to " + v + " chunks.";
                    break;
                }
                case "framerate_limit":
                case "fps_limit":
                case "max_fps": {
                    int v = Math.max(10, Math.min(260, Integer.parseInt(raw)));
                    mc.execute(() -> o.framerateLimit().set(v));
                    result = "Frame rate limit set to " + (v >= 260 ? "unlimited" : v + " FPS") + ".";
                    break;
                }
                case "fov": {
                    int v = Math.max(30, Math.min(110, Integer.parseInt(raw)));
                    mc.execute(() -> o.fov().set(v));
                    result = "Field of view set to " + v + ".";
                    break;
                }
                case "gui_scale": {
                    int v = Math.max(0, Math.min(4, Integer.parseInt(raw)));
                    mc.execute(() -> o.guiScale().set(v));
                    result = "GUI scale set to " + (v == 0 ? "auto" : String.valueOf(v)) + ".";
                    break;
                }
                case "brightness":
                case "gamma": {
                    double v = Math.max(0.0, Math.min(1.0, Double.parseDouble(raw)));
                    mc.execute(() -> o.gamma().set(v));
                    result = "Brightness set to " + v + ".";
                    break;
                }
                case "vsync": {
                    boolean v = parseBool(raw);
                    mc.execute(() -> o.enableVsync().set(v));
                    result = "VSync " + (v ? "on" : "off") + ".";
                    break;
                }
                case "entity_shadows": {
                    boolean v = parseBool(raw);
                    mc.execute(() -> o.entityShadows().set(v));
                    result = "Entity shadows " + (v ? "on" : "off") + ".";
                    break;
                }
                case "particles": {
                    final net.minecraft.server.level.ParticleStatus v =
                            switch (raw.toLowerCase(Locale.ROOT)) {
                                case "minimal", "off", "none" -> net.minecraft.server.level.ParticleStatus.MINIMAL;
                                case "decreased", "reduced"   -> net.minecraft.server.level.ParticleStatus.DECREASED;
                                default                       -> net.minecraft.server.level.ParticleStatus.ALL;
                            };
                    mc.execute(() -> o.particles().set(v));
                    result = "Particles set to " + v.name().toLowerCase(Locale.ROOT) + ".";
                    break;
                }
                default:
                    return "I can set: render_distance, simulation_distance, framerate_limit, fov, "
                         + "gui_scale, brightness, vsync, entity_shadows, particles.";
            }
        } catch (NumberFormatException e) {
            return "'" + value + "' is not a valid value for " + option + ".";
        }
        mc.execute(mc.options::save);
        return result;
    }

    private static boolean parseBool(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("on") || s.equals("yes") || s.equals("1") || s.equals("sim");
    }
}
