package com.mod.echo.assistant;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only inspection of the world around a player.
 *
 * Every method returns a finished, human-readable string rather than writing to
 * chat itself, so the same code can answer a direct question, feed a tool result
 * back into the language model, or print to the log.
 *
 * Scans only ever read blocks in chunks that are already loaded
 * ({@link ServerLevel#isLoaded(BlockPos)}), and each scan volume is capped, so a
 * request can never force chunk generation or stall the server for long.
 */
public final class WorldScanner {

    private WorldScanner() {}

    /** Hard ceiling on any scan radius a tool may request. */
    public static final int MAX_RADIUS = 48;

    // ------------------------------------------------------------------ //
    //  Ore table                                                           //
    // ------------------------------------------------------------------ //

    /** @param display friendly name @param blocks every block form of the ore */
    public record Ore(String display, List<Block> blocks) {}

    private static final Map<String, Ore> ORES = new LinkedHashMap<>();
    static {
        Ore diamond   = new Ore("diamond",   List.of(Blocks.DIAMOND_ORE,   Blocks.DEEPSLATE_DIAMOND_ORE));
        Ore iron      = new Ore("iron",      List.of(Blocks.IRON_ORE,      Blocks.DEEPSLATE_IRON_ORE));
        Ore gold      = new Ore("gold",      List.of(Blocks.GOLD_ORE,      Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE));
        Ore emerald   = new Ore("emerald",   List.of(Blocks.EMERALD_ORE,   Blocks.DEEPSLATE_EMERALD_ORE));
        Ore coal      = new Ore("coal",      List.of(Blocks.COAL_ORE,      Blocks.DEEPSLATE_COAL_ORE));
        Ore copper    = new Ore("copper",    List.of(Blocks.COPPER_ORE,    Blocks.DEEPSLATE_COPPER_ORE));
        Ore lapis     = new Ore("lapis",     List.of(Blocks.LAPIS_ORE,     Blocks.DEEPSLATE_LAPIS_ORE));
        Ore redstone  = new Ore("redstone",  List.of(Blocks.REDSTONE_ORE,  Blocks.DEEPSLATE_REDSTONE_ORE));
        Ore debris    = new Ore("netherite", List.of(Blocks.ANCIENT_DEBRIS));
        Ore quartz    = new Ore("quartz",    List.of(Blocks.NETHER_QUARTZ_ORE));

        put(diamond,  "diamond", "diamante");
        put(iron,     "iron", "ferro");
        put(gold,     "gold", "ouro");
        put(emerald,  "emerald", "esmeralda");
        put(coal,     "coal", "carvao", "carvão");
        put(copper,   "copper", "cobre");
        put(lapis,    "lapis", "lapislazuli");
        put(redstone, "redstone");
        put(debris,   "netherite", "debris", "ancient debris");
        put(quartz,   "quartz", "quartzo");
    }
    private static void put(Ore ore, String... keys) {
        for (String k : keys) ORES.put(k, ore);
    }

    /** Resolve an ore from free text, or {@code null} when nothing matches. */
    public static Ore matchOre(String text) {
        if (text == null) return null;
        String q = text.toLowerCase(Locale.ROOT);
        Ore best = null;
        int bestLength = -1;
        for (Map.Entry<String, Ore> e : ORES.entrySet()) {
            if (q.contains(e.getKey()) && e.getKey().length() > bestLength) {
                best = e.getValue();
                bestLength = e.getKey().length();
            }
        }
        return best;
    }

    public static List<String> oreNames() {
        List<String> names = new ArrayList<>();
        for (Ore o : ORES.values()) if (!names.contains(o.display())) names.add(o.display());
        return names;
    }

    // ------------------------------------------------------------------ //
    //  Ore search                                                          //
    // ------------------------------------------------------------------ //

    /** Nearest block of one ore type, with distance and a direction hint. */
    public static String findOre(ServerPlayer player, String oreName, int radius) {
        Ore ore = matchOre(oreName);
        if (ore == null) {
            return "I don't know an ore called '" + oreName + "'. I can look for: "
                    + String.join(", ", oreNames()) + ".";
        }
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        int r = clampRadius(radius, 32);

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (!level.isLoaded(pos)) continue;
            Block block = level.getBlockState(pos).getBlock();
            if (!ore.blocks().contains(block)) continue;
            double d = pos.distSqr(origin);
            if (d < bestDistance) {
                bestDistance = d;
                best = pos.immutable();
            }
        }

        if (best == null) {
            return "No " + ore.display() + " within " + r + " blocks. "
                    + bestDepthHint(ore.display());
        }
        return "Nearest " + ore.display() + ": (" + best.getX() + ", " + best.getY() + ", " + best.getZ()
                + "), " + (int) Math.sqrt(bestDistance) + " blocks away — "
                + direction(best.getX() - origin.getX(), best.getZ() - origin.getZ())
                + ", " + verticalHint(best.getY() - origin.getY()) + ".";
    }

    /** One pass over the area, reporting the closest sample of every ore present. */
    public static String scanAllOres(ServerPlayer player, int radius) {
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        int r = clampRadius(radius, 24);

        Map<String, BlockPos> nearest = new LinkedHashMap<>();
        Map<String, Double> distances = new LinkedHashMap<>();

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (!level.isLoaded(pos)) continue;
            Block block = level.getBlockState(pos).getBlock();
            for (Ore ore : ORES.values()) {
                if (!ore.blocks().contains(block)) continue;
                double d = pos.distSqr(origin);
                Double previous = distances.get(ore.display());
                if (previous == null || d < previous) {
                    distances.put(ore.display(), d);
                    nearest.put(ore.display(), pos.immutable());
                }
            }
        }

        if (nearest.isEmpty()) return "No ores at all within " + r + " blocks of you.";

        List<String> lines = new ArrayList<>();
        nearest.entrySet().stream()
                .sorted(Comparator.comparingDouble(e -> distances.get(e.getKey())))
                .forEach(e -> {
                    BlockPos p = e.getValue();
                    lines.add(e.getKey() + " at (" + p.getX() + ", " + p.getY() + ", " + p.getZ()
                            + "), " + (int) Math.sqrt(distances.get(e.getKey())) + " blocks");
                });
        return "Ores within " + r + " blocks:\n  " + String.join("\n  ", lines);
    }

    /** Find any block by its registry name, e.g. {@code chest} or {@code minecraft:spawner}. */
    public static String findBlock(ServerPlayer player, String blockName, int radius) {
        if (blockName == null || blockName.isBlank()) return "Which block should I look for?";
        Identifier id = Identifier.tryParse(normaliseId(blockName));
        if (id == null) return "'" + blockName + "' is not a valid block name.";
        Block target = BuiltInRegistries.BLOCK.getValue(id);
        if (target == null || target == Blocks.AIR) {
            return "There is no block called '" + blockName + "'.";
        }

        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        int r = clampRadius(radius, 32);

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int count = 0;

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockState(pos).getBlock() != target) continue;
            count++;
            double d = pos.distSqr(origin);
            if (d < bestDistance) { bestDistance = d; best = pos.immutable(); }
        }

        if (best == null) return "No " + id.getPath() + " within " + r + " blocks.";
        return count + "x " + id.getPath() + " within " + r + " blocks. Closest: ("
                + best.getX() + ", " + best.getY() + ", " + best.getZ() + "), "
                + (int) Math.sqrt(bestDistance) + " blocks "
                + direction(best.getX() - origin.getX(), best.getZ() - origin.getZ()) + ".";
    }

    // ------------------------------------------------------------------ //
    //  Structures                                                          //
    // ------------------------------------------------------------------ //

    private record StructureRef(String display, TagKey<Structure> tag, String id) {}

    private static final Map<String, StructureRef> STRUCTURES = new LinkedHashMap<>();
    static {
        StructureRef village = new StructureRef("village", StructureTags.VILLAGE, null);
        STRUCTURES.put("village", village);
        STRUCTURES.put("aldeia", village);

        StructureRef stronghold = new StructureRef("stronghold", StructureTags.EYE_OF_ENDER_LOCATED, null);
        STRUCTURES.put("stronghold", stronghold);
        STRUCTURES.put("fortaleza", stronghold);

        STRUCTURES.put("mansion",        new StructureRef("woodland mansion", null, "mansion"));
        STRUCTURES.put("mansao",         new StructureRef("woodland mansion", null, "mansion"));
        STRUCTURES.put("monument",       new StructureRef("ocean monument",   null, "monument"));
        STRUCTURES.put("monumento",      new StructureRef("ocean monument",   null, "monument"));
        STRUCTURES.put("jungle temple",  new StructureRef("jungle temple",    null, "jungle_pyramid"));
        STRUCTURES.put("temple",         new StructureRef("desert pyramid",   null, "desert_pyramid"));
        STRUCTURES.put("templo",         new StructureRef("desert pyramid",   null, "desert_pyramid"));
        STRUCTURES.put("pyramid",        new StructureRef("desert pyramid",   null, "desert_pyramid"));
        STRUCTURES.put("piramide",       new StructureRef("desert pyramid",   null, "desert_pyramid"));
        STRUCTURES.put("bastion",        new StructureRef("bastion remnant",  null, "bastion_remnant"));
        STRUCTURES.put("fortress",       new StructureRef("nether fortress",  null, "fortress"));
        STRUCTURES.put("ancient city",   new StructureRef("ancient city",     null, "ancient_city"));
        STRUCTURES.put("cidade antiga",  new StructureRef("ancient city",     null, "ancient_city"));
        STRUCTURES.put("trial chamber",  new StructureRef("trial chamber",    null, "trial_chambers"));
        STRUCTURES.put("shipwreck",      new StructureRef("shipwreck",        null, "shipwreck"));
        STRUCTURES.put("naufragio",      new StructureRef("shipwreck",        null, "shipwreck"));
        STRUCTURES.put("outpost",        new StructureRef("pillager outpost", null, "pillager_outpost"));
        STRUCTURES.put("igloo",          new StructureRef("igloo",            null, "igloo"));
        STRUCTURES.put("swamp hut",      new StructureRef("swamp hut",        null, "swamp_hut"));
        STRUCTURES.put("ruined portal",  new StructureRef("ruined portal",    null, "ruined_portal"));
        STRUCTURES.put("end city",       new StructureRef("end city",         null, "end_city"));
        STRUCTURES.put("mineshaft",      new StructureRef("mineshaft",        null, "mineshaft"));
        STRUCTURES.put("mina",           new StructureRef("mineshaft",        null, "mineshaft"));
    }

    public static List<String> structureNames() {
        List<String> names = new ArrayList<>();
        for (StructureRef s : STRUCTURES.values()) if (!names.contains(s.display())) names.add(s.display());
        return names;
    }

    /** Locate the nearest structure using the same search the {@code /locate} command uses. */
    public static String findStructure(ServerPlayer player, MinecraftServer server, String name) {
        StructureRef ref = matchStructure(name);
        if (ref == null) {
            return "I don't know a structure called '" + name + "'. I can find: "
                    + String.join(", ", structureNames()) + ".";
        }
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        Registry<Structure> registry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);

        HolderSet<Structure> targets;
        if (ref.tag() != null) {
            Optional<HolderSet.Named<Structure>> tagged = registry.get(ref.tag());
            if (tagged.isEmpty()) return "This world has no " + ref.display() + " generation.";
            targets = tagged.get();
        } else {
            Identifier id = Identifier.tryParse("minecraft:" + ref.id());
            if (id == null) return "Could not resolve " + ref.display() + ".";
            Optional<Holder.Reference<Structure>> holder =
                    registry.get(ResourceKey.create(Registries.STRUCTURE, id));
            if (holder.isEmpty()) return ref.display() + " does not exist in this world.";
            targets = HolderSet.direct(holder.get());
        }

        var found = level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, targets, origin, 100, false);
        if (found == null) {
            return "No " + ref.display() + " found within search range. "
                    + (ref.display().equals("woodland mansion")
                        ? "Mansions are rare — expect thousands of blocks."
                        : "Try travelling a few thousand blocks and asking again.");
        }
        BlockPos at = found.getFirst();
        int dx = at.getX() - origin.getX();
        int dz = at.getZ() - origin.getZ();
        int distance = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
        return "Nearest " + ref.display() + ": (" + at.getX() + ", ~, " + at.getZ()
                + "), about " + distance + " blocks " + direction(dx, dz) + ".";
    }

    private static StructureRef matchStructure(String text) {
        if (text == null) return null;
        String q = text.toLowerCase(Locale.ROOT);
        StructureRef best = null;
        int bestLength = -1;
        for (Map.Entry<String, StructureRef> e : STRUCTURES.entrySet()) {
            if (q.contains(e.getKey()) && e.getKey().length() > bestLength) {
                best = e.getValue();
                bestLength = e.getKey().length();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ //
    //  Entities                                                            //
    // ------------------------------------------------------------------ //

    /** Hostile mobs near the player, closest first. */
    public static String combatRadar(ServerPlayer player, int radius) {
        ServerLevel level = player.level();
        int r = clampRadius(radius, 48);
        List<Monster> hostiles = level.getEntitiesOfClass(
                Monster.class, player.getBoundingBox().inflate(r), e -> true);

        if (hostiles.isEmpty()) return "No hostile mobs within " + r + " blocks. You're clear.";

        StringBuilder sb = new StringBuilder(hostiles.size() + " hostile mob"
                + (hostiles.size() == 1 ? "" : "s") + " within " + r + " blocks:");
        hostiles.stream()
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                .limit(10)
                .forEach(e -> sb.append("\n  ").append(entityName(e))
                        .append(" — ").append((int) e.distanceTo(player)).append(" blocks, (")
                        .append((int) e.getX()).append(", ").append((int) e.getY()).append(", ")
                        .append((int) e.getZ()).append(")"));
        if (hostiles.size() > 10) sb.append("\n  ...and ").append(hostiles.size() - 10).append(" more");
        return sb.toString();
    }

    /** Everything alive nearby, grouped by type. */
    public static String nearbyEntities(ServerPlayer player, int radius) {
        ServerLevel level = player.level();
        int r = clampRadius(radius, 48);
        List<Entity> entities = level.getEntitiesOfClass(
                Entity.class, player.getBoundingBox().inflate(r), e -> e != player);

        if (entities.isEmpty()) return "Nothing else within " + r + " blocks of you.";

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entity e : entities) counts.merge(entityName(e), 1, Integer::sum);

        StringBuilder sb = new StringBuilder(entities.size() + " entities within " + r + " blocks:");
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(20)
                .forEach(e -> sb.append("\n  ").append(e.getValue()).append("x ").append(e.getKey()));
        return sb.toString();
    }

    // ------------------------------------------------------------------ //
    //  Safety                                                              //
    // ------------------------------------------------------------------ //

    /** Report spots dark enough for hostile mobs to spawn on. */
    public static String lightAudit(ServerPlayer player, int radius) {
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        int r = clampRadius(radius, 12);

        int spawnable = 0;
        List<String> samples = new ArrayList<>();

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-r, -3, -r), origin.offset(r, 3, r))) {
            if (!level.isLoaded(pos)) continue;
            if (!isSpawnable(level, pos)) continue;
            spawnable++;
            if (samples.size() < 5) {
                samples.add("(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")");
            }
        }

        if (spawnable == 0) {
            return "Everything within " + r + " blocks is lit well enough — nothing can spawn here.";
        }
        return spawnable + " spawnable dark spot" + (spawnable == 1 ? "" : "s") + " within " + r
                + " blocks. Examples: " + String.join(", ", samples)
                + ". A torch every 7 blocks closes the gaps.";
    }

    /** True when a hostile mob could spawn standing on this position. */
    public static boolean isSpawnable(ServerLevel level, BlockPos pos) {
        if (!level.isEmptyBlock(pos)) return false;
        if (!level.isEmptyBlock(pos.above())) return false;
        BlockState floor = level.getBlockState(pos.below());
        if (floor.isAir() || !floor.isSolidRender()) return false;
        return level.getBrightness(LightLayer.BLOCK, pos) <= 0;
    }

    /** Combined safety audit: light, lava, drops and exposed openings. */
    public static String baseAudit(ServerPlayer player, int radius) {
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        int r = clampRadius(radius, 16);

        int dark = 0, lava = 0, openings = 0;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-r, -4, -r), origin.offset(r, 4, r))) {
            if (!level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() == Blocks.LAVA) lava++;
            else if (isSpawnable(level, pos)) dark++;
            else if (state.isAir() && level.getBrightness(LightLayer.SKY, pos) > 0
                    && pos.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) - 1) {
                openings++;
            }
        }

        List<String> findings = new ArrayList<>();
        if (dark > 0)     findings.add(dark + " dark spots where mobs can spawn");
        if (lava > 0)     findings.add(lava + " lava blocks");
        if (openings > 0) findings.add(openings + " gaps open to the sky");

        if (findings.isEmpty()) return "Base audit clean within " + r + " blocks: lit, sealed and no lava.";
        return "Base audit within " + r + " blocks — " + String.join("; ", findings)
                + ". Light the dark spots first; they are the only ones that spawn mobs.";
    }

    // ------------------------------------------------------------------ //
    //  Containers                                                          //
    // ------------------------------------------------------------------ //

    /** Search nearby chests, barrels and shulkers for an item. */
    public static String findItemInContainers(ServerPlayer player, String itemName, int radius) {
        if (itemName == null || itemName.isBlank()) return "Which item should I look for?";
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        int r = clampRadius(radius, 16);
        String needle = itemName.toLowerCase(Locale.ROOT).replace(' ', '_');

        List<String> hits = new ArrayList<>();
        int scanned = 0;

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (!level.isLoaded(pos)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof net.minecraft.world.Container container)) continue;
            scanned++;

            int found = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                var stack = container.getItem(slot);
                if (stack.isEmpty()) continue;
                Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id != null && id.getPath().contains(needle)) found += stack.getCount();
            }
            if (found > 0 && hits.size() < 8) {
                hits.add(found + "x at (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")");
            }
        }

        if (scanned == 0) return "No containers within " + r + " blocks.";
        if (hits.isEmpty()) return "Checked " + scanned + " containers within " + r
                + " blocks — no " + itemName + " in any of them.";
        return itemName + " found in " + hits.size() + " of " + scanned + " containers:\n  "
                + String.join("\n  ", hits);
    }

    // ------------------------------------------------------------------ //
    //  Snapshots                                                           //
    // ------------------------------------------------------------------ //

    /** Biome, weather, time, dimension — the situation in one paragraph. */
    public static String worldSnapshot(ServerPlayer player, MinecraftServer server) {
        ServerLevel level = player.level();
        BlockPos pos = player.blockPosition();

        long dayTime = Math.floorMod(level.getDefaultClockTime(), 24000L);
        String phase = dayTime < 6000 ? "morning"
                     : dayTime < 12000 ? "afternoon"
                     : dayTime < 13000 ? "sunset"
                     : dayTime < 23000 ? "night" : "dawn";
        String weather = level.isThundering() ? "thunderstorm"
                       : level.isRaining() ? "rain" : "clear";
        String biome = biomeName(level, pos);
        String dimension = level.dimension().identifier().getPath();
        String difficulty = server.getWorldData().getDifficulty().getSerializedName();

        return "Dimension " + dimension + ", biome " + biome + ", " + phase
                + " (tick " + dayTime + "), weather " + weather
                + ", difficulty " + difficulty
                + ", " + server.getPlayerCount() + " player(s) online. "
                + "Player at (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"
                + ", surface here is Y " + level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) + ".";
    }

    public static String biomeName(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .map(key -> key.identifier().getPath().replace('_', ' '))
                .orElse("unknown");
    }

    /** How long until the next sunrise or sunset, in real minutes. */
    public static String timeUntilNight(ServerLevel level) {
        long t = Math.floorMod(level.getDefaultClockTime(), 24000L);
        if (t >= 13000) {
            long ticks = 24000 - t;
            return "It is already night. Sunrise in about " + minutes(ticks) + ".";
        }
        return "Nightfall in about " + minutes(13000 - t) + ".";
    }

    private static String minutes(long ticks) {
        double realMinutes = ticks / 20.0 / 60.0;
        if (realMinutes < 1) return Math.round(ticks / 20.0) + " seconds";
        return String.format(Locale.ROOT, "%.1f minutes", realMinutes);
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    public static String entityName(Entity entity) {
        return entity.getType().getDescriptionId()
                .replace("entity.minecraft.", "")
                .replace('_', ' ');
    }

    /** Compass direction for a horizontal offset. */
    public static String direction(int dx, int dz) {
        if (dx == 0 && dz == 0) return "right where you stand";
        StringBuilder sb = new StringBuilder();
        if (Math.abs(dz) > Math.abs(dx) / 2) sb.append(dz > 0 ? "south" : "north");
        if (Math.abs(dx) > Math.abs(dz) / 2) sb.append(dx > 0 ? "east" : "west");
        return sb.length() == 0 ? "nearby" : sb.toString();
    }

    private static String verticalHint(int dy) {
        if (dy > 2) return dy + " blocks up";
        if (dy < -2) return (-dy) + " blocks down";
        return "level with you";
    }

    private static String bestDepthHint(String ore) {
        return switch (ore) {
            case "diamond"   -> "Diamonds are densest around Y -59.";
            case "redstone"  -> "Redstone peaks around Y -59.";
            case "gold"      -> "Gold peaks around Y -16, or Y 32 in badlands.";
            case "iron"      -> "Iron peaks at Y 16 and again at Y 232.";
            case "copper"    -> "Copper peaks around Y 48.";
            case "lapis"     -> "Lapis peaks around Y 0.";
            case "emerald"   -> "Emeralds only generate in mountain biomes, best near Y 236.";
            case "netherite" -> "Ancient debris only spawns in the Nether, best at Y 15.";
            case "quartz"    -> "Quartz only spawns in the Nether.";
            default          -> "Try mining at a different depth.";
        };
    }

    public static int clampRadius(int requested, int fallback) {
        int r = requested <= 0 ? fallback : requested;
        return Math.max(2, Math.min(MAX_RADIUS, r));
    }

    private static String normaliseId(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return s.contains(":") ? s : "minecraft:" + s;
    }
}
