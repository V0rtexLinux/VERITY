package com.mod.verity.ai;

import com.google.gson.JsonObject;
import com.mod.verity.VerityMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executes Verity's tools using the CLIENT world only.
 * Works on servers that do not have the mod installed.
 *
 * Limitations vs. server-side:
 * - Only loaded chunks are visible
 * - Building uses /setblock commands (requires appropriate permissions)
 * - Structure locating relies on block scanning (no /locate fallback)
 */
@Environment(EnvType.CLIENT)
public class ClientToolExecutor {

    private static final int ORE_RANGE  = 64;
    private static final int MOB_RANGE  = 64;

    public static CompletableFuture<String> execute(String toolName, JsonObject args,
                                                     Minecraft mc, int stage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return switch (toolName) {
                    case "find_ore"               -> findOre(args, mc, stage);
                    case "scan_all_ores"          -> scanAllOres(mc, stage);
                    case "find_structure"         -> findStructure(args, mc, stage);
                    case "combat_radar"           -> combatRadar(mc, stage);
                    case "get_all_nearby_entities"-> allEntities(mc, stage);
                    case "get_biome_info"         -> biomeInfo(mc, stage);
                    case "get_full_world_info"    -> worldInfo(mc, stage);
                    case "light_area"             -> lightAreaScan(args, mc, stage);
                    case "build_structure"        -> buildStructure(args, mc, stage);
                    case "get_crafting_recipe"    -> craftingAdvice(args, stage);
                    case "get_enchantment_advice" -> enchantAdvice(args, stage);
                    case "get_potion_recipe"      -> potionRecipe(args, stage);
                    case "get_player_stats"       -> playerStats(mc, stage);
                    case "get_player_inventory"   -> playerInventory(mc, stage);
                    case "set_time"               -> clientCmd("time set " + resolveTime(args), mc);
                    case "set_weather"            -> clientCmd("weather " + resolveWeather(args), mc);
                    case "give_item"              -> clientCmd("give @s minecraft:" + str(args,"item","diamond") + " " + num(args,"count",1), mc);
                    case "heal_player"            -> clientCmd("effect give @s minecraft:instant_health 1 255", mc);
                    case "give_xp"                -> clientCmd("xp add @s " + num(args,"levels",5) + " levels", mc);
                    case "teleport_player"        -> clientCmd(String.format("tp @s %.0f %.0f %.0f", dbl(args,"x",0), dbl(args,"y",64), dbl(args,"z",0)), mc);
                    case "spawn_entity"           -> clientCmd("summon minecraft:" + str(args,"entity","sheep"), mc);
                    case "modify_behavior"        -> { SelfModificationEngine.modifyBehavior(str(args,"parameter",""), str(args,"value","normal")); yield "Behavior modified."; }
                    case "modify_ai_parameter"    -> { SelfModificationEngine.modifyAiParameter(str(args,"param",""), str(args,"value","normal")); yield "AI parameter modified."; }
                    case "execute_command"        -> clientCmd(str(args,"command",""), mc);
                    default -> {
                        VerityMod.LOGGER.warn("[ClientToolExecutor] Unknown tool: " + toolName);
                        yield "Tool not available client-side: " + toolName;
                    }
                };
            } catch (Exception e) {
                VerityMod.LOGGER.error("[ClientToolExecutor] Error in " + toolName + ": " + e.getMessage(), e);
                return "Error executing " + toolName;
            }
        }).thenApply(result -> {
            mc.execute(() -> {
                if (mc.player != null && result != null && !result.isBlank()) {
                    mc.player.sendSystemMessage(Component.literal(
                        PromptSystem.formatResponse(result, stage)));
                }
            });
            return result;
        });
    }

    // ------------------------------------------------------------------ //
    //  Ore scanner                                                         //
    // ------------------------------------------------------------------ //

    private static final java.util.Map<String, String[]> ORE_NAMES = java.util.Map.ofEntries(
        java.util.Map.entry("diamond",   new String[]{"diamond_ore", "deepslate_diamond_ore"}),
        java.util.Map.entry("diamante",  new String[]{"diamond_ore", "deepslate_diamond_ore"}),
        java.util.Map.entry("iron",      new String[]{"iron_ore", "deepslate_iron_ore"}),
        java.util.Map.entry("ferro",     new String[]{"iron_ore", "deepslate_iron_ore"}),
        java.util.Map.entry("gold",      new String[]{"gold_ore", "deepslate_gold_ore", "nether_gold_ore"}),
        java.util.Map.entry("ouro",      new String[]{"gold_ore", "deepslate_gold_ore", "nether_gold_ore"}),
        java.util.Map.entry("emerald",   new String[]{"emerald_ore", "deepslate_emerald_ore"}),
        java.util.Map.entry("esmeralda", new String[]{"emerald_ore", "deepslate_emerald_ore"}),
        java.util.Map.entry("coal",      new String[]{"coal_ore", "deepslate_coal_ore"}),
        java.util.Map.entry("carvao",    new String[]{"coal_ore", "deepslate_coal_ore"}),
        java.util.Map.entry("copper",    new String[]{"copper_ore", "deepslate_copper_ore"}),
        java.util.Map.entry("cobre",     new String[]{"copper_ore", "deepslate_copper_ore"}),
        java.util.Map.entry("lapis",     new String[]{"lapis_ore", "deepslate_lapis_ore"}),
        java.util.Map.entry("redstone",  new String[]{"redstone_ore", "deepslate_redstone_ore"}),
        java.util.Map.entry("netherite", new String[]{"ancient_debris"}),
        java.util.Map.entry("debris",    new String[]{"ancient_debris"})
    );

    private static String findOre(JsonObject args, Minecraft mc, int stage) {
        String oreType = args.has("ore_type") ? args.get("ore_type").getAsString().toLowerCase() : "";
        String[] targets = ORE_NAMES.get(oreType);
        if (targets == null) return "Tipo de minério desconhecido: " + oreType;

        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return "Mundo não disponível.";

        BlockPos origin = player.blockPosition();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-ORE_RANGE, -ORE_RANGE, -ORE_RANGE),
                origin.offset(ORE_RANGE, ORE_RANGE, ORE_RANGE))) {
            BlockState state = level.getBlockState(pos);
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).getPath();
            for (String t : targets) {
                if (id.equals(t)) {
                    double d = origin.distSqr(pos);
                    if (d < nearestDist) { nearestDist = d; nearest = pos.immutable(); }
                }
            }
        }

        if (nearest == null) return "Nenhum " + oreType + " encontrado nos " + ORE_RANGE + " blocos próximos.";
        int dist = (int) Math.sqrt(nearestDist);
        return String.format("§b%s§r encontrado em (%d, %d, %d) — %d blocos de distância!",
            oreType, nearest.getX(), nearest.getY(), nearest.getZ(), dist);
    }

    private static String scanAllOres(Minecraft mc, int stage) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return "Mundo não disponível.";

        BlockPos origin = player.blockPosition();
        java.util.Map<String, BlockPos> nearest = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> nearestDist = new java.util.LinkedHashMap<>();

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-ORE_RANGE, -ORE_RANGE, -ORE_RANGE),
                origin.offset(ORE_RANGE, ORE_RANGE, ORE_RANGE))) {
            BlockState state = level.getBlockState(pos);
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).getPath();
            for (java.util.Map.Entry<String, String[]> entry : ORE_NAMES.entrySet()) {
                for (String t : entry.getValue()) {
                    if (id.equals(t)) {
                        double d = origin.distSqr(pos);
                        String oreName = entry.getKey();
                        if (!nearestDist.containsKey(oreName) || d < nearestDist.get(oreName)) {
                            nearestDist.put(oreName, d);
                            nearest.put(oreName, pos.immutable());
                        }
                    }
                }
            }
        }

        if (nearest.isEmpty()) return "Nenhum minério encontrado nas proximidades.";

        StringBuilder sb = new StringBuilder("§e=== Minérios Próximos ===§r\n");
        nearest.forEach((ore, pos) -> {
            int dist = (int) Math.sqrt(nearestDist.get(ore));
            sb.append(String.format("§b%s§r: (%d,%d,%d) — §f%dm§r\n",
                ore, pos.getX(), pos.getY(), pos.getZ(), dist));
        });
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ //
    //  Structure finder (client-side: basic block scanning)               //
    // ------------------------------------------------------------------ //

    private static String findStructure(JsonObject args, Minecraft mc, int stage) {
        return "§7[Verity]§r Para encontrar estruturas em servidores externos, usa §f/locate§r ou pergunta ao servidor.";
    }

    // ------------------------------------------------------------------ //
    //  Combat radar                                                        //
    // ------------------------------------------------------------------ //

    private static String combatRadar(Minecraft mc, int stage) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return "Mundo não disponível.";

        List<LivingEntity> mobs = level.getEntitiesOfClass(
            Mob.class,
            player.getBoundingBox().inflate(MOB_RANGE),
            e -> e instanceof Mob mob && mob.getTarget() != null
        ).stream()
            .map(e -> (LivingEntity) e)
            .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
            .toList();

        if (mobs.isEmpty()) return "§a✔§r Nenhum mob hostil nas proximidades!";

        StringBuilder sb = new StringBuilder("§c⚠ Mobs hostis próximos:§r\n");
        for (int i = 0; i < Math.min(mobs.size(), 5); i++) {
            LivingEntity mob = mobs.get(i);
            int dist = (int) Math.sqrt(mob.distanceToSqr(player));
            String name = mob.getType().toShortString();
            sb.append(String.format("  §c%s§r — §f%d blocos§r\n", name, dist));
        }
        if (mobs.size() > 5) sb.append("  §7...e mais ").append(mobs.size() - 5).append(" outros.\n");
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ //
    //  Building (client-side: sends /setblock commands)                   //
    // ------------------------------------------------------------------ //

    private static String buildStructure(JsonObject args, Minecraft mc, int stage) {
        String shape    = args.has("shape")    ? args.get("shape").getAsString()    : "wall";
        String material = args.has("material") ? args.get("material").getAsString() : "cobblestone";
        int size        = args.has("size")     ? args.get("size").getAsInt()         : 5;
        size = Math.max(3, Math.min(10, size));

        LocalPlayer player = mc.player;
        if (player == null) return "Jogador não disponível.";

        BlockPos origin = player.blockPosition().offset(3, 0, 0);

        List<String> commands = buildWallCommands(origin, shape, material, size);
        if (commands.isEmpty()) return "Estrutura '" + shape + "' não suportada.";

        for (String cmd : commands) {
            player.connection.sendChat("/" + cmd);
        }

        return String.format("§a✔§r Construindo %s de %s (tamanho %d)...", shape, material, size);
    }

    private static List<String> buildWallCommands(BlockPos origin, String shape, String material, int size) {
        List<String> cmds = new ArrayList<>();
        String block = material.contains(":") ? material : "minecraft:" + material;

        switch (shape.toLowerCase()) {
            case "wall", "parede" -> {
                for (int i = 0; i < size; i++) {
                    for (int y = 0; y < size; y++) {
                        cmds.add(String.format("setblock %d %d %d %s",
                            origin.getX() + i, origin.getY() + y, origin.getZ(), block));
                    }
                }
            }
            case "floor", "chao", "chão" -> {
                for (int x = 0; x < size; x++) {
                    for (int z = 0; z < size; z++) {
                        cmds.add(String.format("setblock %d %d %d %s",
                            origin.getX() + x, origin.getY(), origin.getZ() + z, block));
                    }
                }
            }
            case "pillar", "pilar" -> {
                for (int y = 0; y < size; y++) {
                    cmds.add(String.format("setblock %d %d %d %s",
                        origin.getX(), origin.getY() + y, origin.getZ(), block));
                }
            }
            default -> { return cmds; }
        }
        return cmds;
    }

    // ------------------------------------------------------------------ //
    //  Crafting & Enchantment (text-only, no MC API needed)               //
    // ------------------------------------------------------------------ //

    private static String craftingAdvice(JsonObject args, int stage) {
        String item = args.has("item") ? args.get("item").getAsString().toLowerCase() : "";
        return switch (item) {
            case "pickaxe", "picareta" ->
                "§e=== Picareta ===§r\n  Fila superior: 3x material\n  Meio: 2x sticks\n  Ex: Diamante → §b3 diamantes§r + §62 paus§r";
            case "sword", "espada" ->
                "§e=== Espada ===§r\n  Coluna central: 2x material, 1x stick em baixo";
            case "crafting table", "bancada" ->
                "§e=== Bancada ===§r\n  2x2 de planks de madeira";
            case "enchanting table", "mesa de encantamentos" ->
                "§e=== Mesa de Encantamentos ===§r\n  1 livro + 2 diamantes + 4 obsidiana";
            case "beacon" ->
                "§e=== Beacon ===§r\n  1 Nether Star (centro) + 3 blocos de vidro + 3 obsidiana";
            default ->
                "§7Digita: §f/recipe " + item + "§7 no servidor para ver a receita.";
        };
    }

    private static String enchantAdvice(JsonObject args, int stage) {
        String itemType = args.has("item_type") ? args.get("item_type").getAsString().toLowerCase() : "";
        return switch (itemType) {
            case "sword", "espada" ->
                "§e=== Sword Enchantments ===§r\n"
                + "  §bSharpness V§r — max damage\n"
                + "  §bLooting III§r — more drops\n"
                + "  §bFire Aspect II§r — sets on fire\n"
                + "  §bKnockback II§r — push back\n"
                + "  §bMending§r — repairs with XP\n"
                + "  §bUnbreaking III§r — durability";
            case "pickaxe", "picareta" ->
                "§e=== Pickaxe Enchantments ===§r\n"
                + "  §bEfficiency V§r — fast mining\n"
                + "  §bFortune III§r — more ore drops\n"
                + "  §bSilk Touch§r — picks up exact block\n"
                + "  §bMending§r — repairs with XP\n"
                + "  §bUnbreaking III§r — durability";
            case "bow" ->
                "§e=== Bow Enchantments ===§r\n"
                + "  §bPower V§r — max damage\n"
                + "  §bPunch II§r — knockback\n"
                + "  §bFlame I§r — fire arrows\n"
                + "  §bInfinity I§r — infinite arrows (1 arrow needed)\n"
                + "  §bUnbreaking III§r / §bMending§r";
            case "armor", "armadura", "helmet", "chestplate", "leggings", "boots" ->
                "§e=== Armor Enchantments ===§r\n"
                + "  §bProtection IV§r — general protection\n"
                + "  §bThorns III§r — reflect damage\n"
                + "  §bMending§r — repairs with XP\n"
                + "  §bUnbreaking III§r — durability\n"
                + "  §bFeather Falling IV§r (boots) — safe falling\n"
                + "  §bRespiration III§r (helmet) — underwater breathing\n"
                + "  §bDepth Strider III§r (boots) — fast underwater";
            case "trident" ->
                "§e=== Trident Enchantments ===§r\n"
                + "  §bLoyalty III§r — returns after throw\n"
                + "  §bImpaling V§r — bonus vs aquatic mobs\n"
                + "  §bChanneling§r — lightning on thunderstorm hit\n"
                + "  §bRiptide III§r — launch yourself (rain only)\n"
                + "  §bMending§r / §bUnbreaking III§r";
            default ->
                "§7Ask me about: §fsword, pickaxe, bow, armor, trident§7.";
        };
    }

    // ── New client-side helpers ─────────────────────────────────────── //

    private static String allEntities(Minecraft mc, int stage) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return "World not available.";

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entity e : level.entitiesForRendering()) {
            if (e == player) continue;
            double dist = e.distanceTo(player);
            if (dist <= 64) {
                String name = e.getType().toShortString();
                counts.merge(name, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) return "§6[Verity]§r No entities nearby.";

        StringBuilder sb = new StringBuilder("§6[Verity]§r Nearby entities:\n");
        counts.forEach((name, count) -> sb.append("  §7- ").append(name).append(" x").append(count).append("\n"));
        return sb.toString().trim();
    }

    private static String biomeInfo(Minecraft mc, int stage) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return "World not available.";
        net.minecraft.world.level.biome.Biome biomeValue = level.getBiome(player.blockPosition()).value();
        net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeRegistry =
            level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        net.minecraft.resources.Identifier biomeId = biomeRegistry.getKey(biomeValue);
        String biome = biomeId != null ? biomeId.getPath().replace("_", " ") : "unknown";
        return "§6[Verity]§r You're in a §e" + biome + "§r.";
    }

    private static String worldInfo(Minecraft mc, int stage) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return "World not available.";
        long time = level.getGameTime() % 24000;
        String timeLabel = time < 6000 ? "morning" : time < 12000 ? "afternoon" : time < 13000 ? "sunset" : "night";
        String weather = level.isThundering() ? "thunderstorm" : level.isRaining() ? "rain" : "clear";
        String dim = level.dimension().equals(Level.OVERWORLD) ? "overworld"
            : level.dimension().equals(Level.NETHER) ? "the_nether"
            : level.dimension().equals(Level.END) ? "the_end"
            : "custom";
        return String.format("§6[Verity]§r World: time=%s (%d), weather=%s, dimension=%s",
            timeLabel, time, weather, dim);
    }

    private static String lightAreaScan(JsonObject args, Minecraft mc, int stage) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return "World not available.";
        int radius = num(args, "radius", 8);
        BlockPos origin = player.blockPosition();
        int dark = 0;
        for (BlockPos p : BlockPos.betweenClosed(
                origin.offset(-radius, -2, -radius),
                origin.offset(radius, 2, radius))) {
            if (level.isEmptyBlock(p) && level.getBrightness(LightLayer.BLOCK, p) <= 4) dark++;
        }
        return dark == 0
            ? "§6[Verity]§r The area is well-lit. No spawning threats."
            : "§6[Verity]§r §c" + dark + " dark spots§r in " + radius + " blocks — mobs can spawn there!";
    }

    private static String playerStats(Minecraft mc, int stage) {
        LocalPlayer player = mc.player;
        if (player == null) return "Player not available.";
        return String.format("§6[Verity]§r Stats: Health §c%.1f/%.1f§r | Hunger §6%d/20§r | XP §a%d§r | Armor §7%d§r",
            player.getHealth(), player.getMaxHealth(),
            player.getFoodData().getFoodLevel(),
            player.experienceLevel,
            player.getArmorValue());
    }

    private static String playerInventory(Minecraft mc, int stage) {
        LocalPlayer player = mc.player;
        if (player == null) return "Player not available.";
        StringBuilder sb = new StringBuilder("§6[Verity]§r Inventory:\n");
        var inv = player.getInventory();
        boolean any = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String name = stack.getItem().getDescriptionId()
                    .replace("item.minecraft.", "").replace("block.minecraft.", "");
                sb.append(String.format("  [%2d] %s x%d\n", i, name, stack.getCount()));
                any = true;
            }
        }
        if (!any) sb.append("  §7(empty)");
        return sb.toString().trim();
    }

    private static String potionRecipe(JsonObject args, int stage) {
        String potion = str(args, "potion", "strength").toLowerCase();
        return switch (potion) {
            case "strength"        -> "§6[Verity]§r Strength: Awkward Potion → Blaze Powder. Glowstone=II, Redstone=extended.";
            case "healing"         -> "§6[Verity]§r Healing: Awkward → Glistering Melon. Glowstone=II. Cannot extend.";
            case "regeneration"    -> "§6[Verity]§r Regen: Awkward → Ghast Tear. Extend/amplify with Redstone/Glowstone.";
            case "swiftness"       -> "§6[Verity]§r Swiftness: Awkward → Sugar. Glowstone=II, Redstone=extended.";
            case "fire_resistance" -> "§6[Verity]§r Fire Resistance: Awkward → Magma Cream. Extend with Redstone.";
            case "water_breathing" -> "§6[Verity]§r Water Breathing: Awkward → Pufferfish. Extend with Redstone.";
            case "night_vision"    -> "§6[Verity]§r Night Vision: Awkward → Golden Carrot. Extend with Redstone.";
            case "invisibility"    -> "§6[Verity]§r Invisibility: Night Vision → Fermented Spider Eye.";
            default -> "§6[Verity]§r Start with Water Bottle → Nether Wart = Awkward Potion, then add ingredient.";
        };
    }

    private static String clientCmd(String command, Minecraft mc) {
        if (mc.player == null || command == null || command.isBlank()) return "Cannot execute.";
        String cmd = command.startsWith("/") ? command : "/" + command;
        mc.execute(() -> mc.player.connection.sendCommand(cmd.substring(1)));
        return "Command sent: " + cmd;
    }

    private static String resolveTime(JsonObject args) {
        String time = str(args, "time", "day");
        return switch (time.toLowerCase()) {
            case "day", "morning"  -> "1000";
            case "noon", "midday"  -> "6000";
            case "sunset"          -> "12000";
            case "night", "dusk"   -> "13000";
            case "midnight"        -> "18000";
            default -> time; // assume numeric
        };
    }

    private static String resolveWeather(JsonObject args) {
        String w = str(args, "type", "clear");
        return switch (w.toLowerCase()) {
            case "rain", "raining" -> "rain";
            case "thunder", "storm", "thunderstorm" -> "thunder";
            default -> "clear";
        };
    }

    // ── Micro-helpers ───────────────────────────────────────────────── //

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
}
