package com.mod.verity.ai;

import com.google.gson.JsonObject;
import com.mod.verity.VerityMod;
import com.mod.verity.ai.PromptSystem;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
                    case "find_ore"             -> findOre(args, mc, stage);
                    case "scan_all_ores"        -> scanAllOres(mc, stage);
                    case "find_structure"       -> findStructure(args, mc, stage);
                    case "combat_radar"         -> combatRadar(mc, stage);
                    case "build_structure"      -> buildStructure(args, mc, stage);
                    case "get_crafting_recipe"  -> craftingAdvice(args, stage);
                    case "get_enchantment_advice" -> enchantAdvice(args, stage);
                    default -> {
                        VerityMod.LOGGER.warn("[ClientToolExecutor] Unknown tool: " + toolName);
                        yield "Tool not found: " + toolName;
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
                "§e=== Melhores Encantamentos — Espada ===§r\n"
                + "  §bSharpness V§r — máximo dano\n"
                + "  §bLooting III§r — mais drops\n"
                + "  §bKnockback II§r — empurra inimigos\n"
                + "  §bMending§r — repara com XP\n"
                + "  §bUnbreaking III§r — durabilidade";
            case "pickaxe", "picareta" ->
                "§e=== Melhores Encantamentos — Picareta ===§r\n"
                + "  §bEfficiency V§r — mineração rápida\n"
                + "  §bFortune III§r — mais drops de minério\n"
                + "  §bMending§r — repara com XP\n"
                + "  §bUnbreaking III§r — durabilidade\n"
                + "  §bSilk Touch§r — coleta blocos inteiros";
            case "armor", "armadura" ->
                "§e=== Melhores Encantamentos — Armadura ===§r\n"
                + "  §bProtection IV§r — proteção geral\n"
                + "  §bMending§r — repara com XP\n"
                + "  §bUnbreaking III§r — durabilidade\n"
                + "  §bThorns III§r — reflete dano\n"
                + "  §bFeather Falling IV§r (botas) — queda segura";
            default ->
                "§7Pergunta-me sobre: §fespada, picareta, armadura§7.";
        };
    }
}
