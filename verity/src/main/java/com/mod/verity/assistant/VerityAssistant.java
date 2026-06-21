package com.mod.verity.assistant;

import com.mod.verity.VerityMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side tool executor for Verity.
 *
 * All responses are sent PRIVATELY to the requesting player only —
 * other players never see Verity's chat replies.
 *
 * Stage-transition horror messages (in VerityEntity) remain as world
 * broadcasts because they are part of the ARG narrative.
 */
public class VerityAssistant {

    // ------------------------------------------------------------------ //
    //  Ore registry                                                        //
    // ------------------------------------------------------------------ //

    private static final Map<String, OreEntry> ORE_MAP = new LinkedHashMap<>();

    static {
        OreEntry diamond   = new OreEntry("§bdiamond§f",   Blocks.DIAMOND_ORE,   Blocks.DEEPSLATE_DIAMOND_ORE);
        OreEntry iron      = new OreEntry("§ffierro§f",    Blocks.IRON_ORE,      Blocks.DEEPSLATE_IRON_ORE);
        OreEntry gold      = new OreEntry("§6gold§f",      Blocks.GOLD_ORE,      Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE);
        OreEntry emerald   = new OreEntry("§aesmeralda§f", Blocks.EMERALD_ORE,   Blocks.DEEPSLATE_EMERALD_ORE);
        OreEntry coal      = new OreEntry("§8carvão§f",    Blocks.COAL_ORE,      Blocks.DEEPSLATE_COAL_ORE);
        OreEntry copper    = new OreEntry("§ccobre§f",     Blocks.COPPER_ORE,    Blocks.DEEPSLATE_COPPER_ORE);
        OreEntry lapis     = new OreEntry("§9lapis§f",     Blocks.LAPIS_ORE,     Blocks.DEEPSLATE_LAPIS_ORE);
        OreEntry redstone  = new OreEntry("§credstone§f",  Blocks.REDSTONE_ORE,  Blocks.DEEPSLATE_REDSTONE_ORE);
        OreEntry netherite = new OreEntry("§4netherite§f", Blocks.ANCIENT_DEBRIS);

        ORE_MAP.put("diamond",   diamond);   ORE_MAP.put("diamante",  diamond);
        ORE_MAP.put("iron",      iron);       ORE_MAP.put("ferro",     iron);
        ORE_MAP.put("gold",      gold);       ORE_MAP.put("ouro",      gold);
        ORE_MAP.put("emerald",   emerald);    ORE_MAP.put("esmeralda", emerald);
        ORE_MAP.put("coal",      coal);       ORE_MAP.put("carvao",    coal);   ORE_MAP.put("carvão", coal);
        ORE_MAP.put("copper",    copper);     ORE_MAP.put("cobre",     copper);
        ORE_MAP.put("lapis",     lapis);
        ORE_MAP.put("redstone",  redstone);
        ORE_MAP.put("netherite", netherite);  ORE_MAP.put("debris",    netherite);
    }

    // ------------------------------------------------------------------ //
    //  Structure registry                                                  //
    // ------------------------------------------------------------------ //

    private static final Map<String, StructureEntry> STRUCTURE_MAP = new LinkedHashMap<>();

    static {
        STRUCTURE_MAP.put("village",      new StructureEntry("§aaldeia§f",              StructureTags.VILLAGE, null));
        STRUCTURE_MAP.put("aldeia",       new StructureEntry("§aaldeia§f",              StructureTags.VILLAGE, null));
        STRUCTURE_MAP.put("stronghold",   new StructureEntry("§7stronghold§f",          StructureTags.EYE_OF_ENDER_LOCATED, null));
        STRUCTURE_MAP.put("fortaleza",    new StructureEntry("§7stronghold§f",          StructureTags.EYE_OF_ENDER_LOCATED, null));
        STRUCTURE_MAP.put("end",          new StructureEntry("§7stronghold§f",          StructureTags.EYE_OF_ENDER_LOCATED, null));
        STRUCTURE_MAP.put("mansion",      new StructureEntry("§cmansão§f",              null, "mansion"));
        STRUCTURE_MAP.put("mansao",       new StructureEntry("§cmansão§f",              null, "mansion"));
        STRUCTURE_MAP.put("monument",     new StructureEntry("§3monumento§f",           null, "monument"));
        STRUCTURE_MAP.put("monumento",    new StructureEntry("§3monumento§f",           null, "monument"));
        STRUCTURE_MAP.put("temple",       new StructureEntry("§6templo§f",              null, "jungle_pyramid"));
        STRUCTURE_MAP.put("templo",       new StructureEntry("§6templo§f",              null, "jungle_pyramid"));
        STRUCTURE_MAP.put("pyramid",      new StructureEntry("§6pirâmide§f",            null, "desert_pyramid"));
        STRUCTURE_MAP.put("piramide",     new StructureEntry("§6pirâmide§f",            null, "desert_pyramid"));
        STRUCTURE_MAP.put("bastion",      new StructureEntry("§4bastião§f",             null, "bastion_remnant"));
        STRUCTURE_MAP.put("bastiao",      new StructureEntry("§4bastião§f",             null, "bastion_remnant"));
        STRUCTURE_MAP.put("fortress",     new StructureEntry("§4fortaleza nether§f",    null, "fortress"));
        STRUCTURE_MAP.put("nether fortress", new StructureEntry("§4fortaleza nether§f", null, "fortress"));
    }

    // ------------------------------------------------------------------ //
    //  Ore finder                                                          //
    // ------------------------------------------------------------------ //

    public static void findOre(String keyword, ServerPlayer player,
                                MinecraftServer server, int stage) {
        OreEntry entry = matchOre(keyword);
        if (entry == null) {
            sendPrivate(player, prefix(stage) + " §7Não conheço esse minério.");
            return;
        }

        sendPrivate(player, prefix(stage) + " §7A procurar " + entry.displayName + "§7...");

        CompletableFuture.runAsync(() -> {
            ServerLevel world   = player.serverLevel();
            BlockPos origin     = player.blockPosition();
            int radius = 64, vertRange = 64;

            BlockPos nearest = null;
            double bestDist  = Double.MAX_VALUE;

            for (BlockPos pos : BlockPos.betweenClosed(
                    origin.offset(-radius, -vertRange, -radius),
                    origin.offset(radius, 16, radius))) {
                Block b = world.getBlockState(pos).getBlock();
                for (Block target : entry.blocks) {
                    if (b == target) {
                        double d = pos.distSqr(origin);
                        if (d < bestDist) { bestDist = d; nearest = pos.immutable(); }
                    }
                }
            }

            final BlockPos result     = nearest;
            final double finalBestDist = bestDist;
            server.execute(() -> {
                if (result == null) {
                    sendPrivate(player, prefix(stage) + " §7Sem " + entry.displayName + "§7 a " + radius + " blocos.");
                } else {
                    int dist = (int) Math.sqrt(finalBestDist);
                    int dx = result.getX() - origin.getX();
                    int dy = result.getY() - origin.getY();
                    int dz = result.getZ() - origin.getZ();
                    sendPrivate(player, String.format(
                            "%s §f%s §fem §b(%d, %d, %d)§f — %d blocos. Direção: §b(%+d, %+d, %+d)§f.",
                            prefix(stage), entry.displayName,
                            result.getX(), result.getY(), result.getZ(),
                            dist, dx, dy, dz));
                    if (stage >= 3) {
                        world.playSound(null, result, SoundEvents.NOTE_BLOCK_PLING.value(),
                                SoundSource.BLOCKS, 1f, 2f);
                    }
                }
            });
        });
    }

    // ------------------------------------------------------------------ //
    //  Ore scanner (all ores)                                              //
    // ------------------------------------------------------------------ //

    public static void scanAllOres(ServerPlayer player,
                                   MinecraftServer server, int stage) {
        sendPrivate(player, prefix(stage) + " §7A escanear todos os minérios perto de ti...");
        CompletableFuture.runAsync(() -> {
            ServerLevel world = player.serverLevel();
            BlockPos origin   = player.blockPosition();
            int radius = 48, vertRange = 48;

            Map<String, BlockPos> nearest = new LinkedHashMap<>();
            Map<String, Double>   dists   = new LinkedHashMap<>();

            Set<String> seen = new HashSet<>();
            for (BlockPos pos : BlockPos.betweenClosed(
                    origin.offset(-radius, -vertRange, -radius),
                    origin.offset(radius, 16, radius))) {
                Block b = world.getBlockState(pos).getBlock();
                for (Map.Entry<String, OreEntry> e : ORE_MAP.entrySet()) {
                    String key = e.getKey();
                    if (seen.contains(key)) continue;
                    if (nearest.containsKey(e.getValue().displayName)) { seen.add(key); continue; }
                    for (Block target : e.getValue().blocks) {
                        if (b == target) {
                            double d = pos.distSqr(origin);
                            String dk = e.getValue().displayName;
                            if (!dists.containsKey(dk) || d < dists.get(dk)) {
                                dists.put(dk, d);
                                nearest.put(dk, pos.immutable());
                            }
                        }
                    }
                }
            }

            server.execute(() -> {
                if (nearest.isEmpty()) {
                    sendPrivate(player, prefix(stage) + " §7Sem minérios nos arredores.");
                    return;
                }
                StringBuilder sb = new StringBuilder(prefix(stage) + " §fMinérios perto de ti:\n");
                nearest.forEach((name, pos) -> {
                    int dist = (int) Math.sqrt(pos.distSqr(origin));
                    sb.append(String.format("  %s §f→ §b(%d, %d, %d)§f — %d blocos\n",
                            name, pos.getX(), pos.getY(), pos.getZ(), dist));
                });
                sendPrivate(player, sb.toString().trim());
            });
        });
    }

    // ------------------------------------------------------------------ //
    //  Structure finder                                                    //
    // ------------------------------------------------------------------ //

    public static void findStructure(String keyword, ServerPlayer player,
                                     MinecraftServer server, int stage) {
        StructureEntry entry = matchStructure(keyword);
        if (entry == null) {
            sendPrivate(player, prefix(stage) + " §7Não conheço essa estrutura.");
            return;
        }

        CompletableFuture.runAsync(() -> {
            ServerLevel world    = player.serverLevel();
            BlockPos playerPos   = player.blockPosition();
            Registry<Structure> reg = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);

            HolderSet<Structure> holderSet;
            if (entry.tag() != null) {
                Optional<HolderSet.Named<Structure>> tagSet = reg.get(entry.tag());
                if (tagSet.isEmpty()) {
                    server.execute(() -> sendPrivate(player,
                            prefix(stage) + " §7Tag de estrutura não encontrada."));
                    return;
                }
                holderSet = tagSet.get();
            } else {
                ResourceKey<Structure> rk = ResourceKey.create(Registries.STRUCTURE,
                        net.minecraft.resources.ResourceLocation.tryParse("minecraft:" + entry.mcId()));
                Optional<Holder.Reference<Structure>> holder = reg.get(rk);
                if (holder.isEmpty()) {
                    server.execute(() -> sendPrivate(player,
                            prefix(stage) + " §7Estrutura desconhecida: §f" + entry.displayName));
                    return;
                }
                holderSet = HolderSet.direct(holder.get());
            }

            var result = world.getChunkSource().getGenerator()
                    .findNearestMapStructure(world, holderSet, playerPos, 100, false);

            server.execute(() -> {
                if (result == null) {
                    sendPrivate(player, prefix(stage) + " §7Sem " + entry.displayName + "§7 perto.");
                    return;
                }
                BlockPos vPos = result.getFirst();
                int dx = vPos.getX() - playerPos.getX();
                int dz = vPos.getZ() - playerPos.getZ();
                int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
                sendPrivate(player, String.format(
                        "%s §f%s §fem §a(%d, ?, %d)§f — %d blocos. Direção: §a(%+d, ?, %+d)§f.",
                        prefix(stage), entry.displayName,
                        vPos.getX(), vPos.getZ(), dist, dx, dz));
            });
        });
    }

    // ------------------------------------------------------------------ //
    //  Combat radar                                                        //
    // ------------------------------------------------------------------ //

    public static void combatRadar(ServerPlayer player,
                                   MinecraftServer server, int stage) {
        ServerLevel world = player.serverLevel();
        var hostiles = world.getEntitiesOfClass(
                net.minecraft.world.entity.monster.Monster.class,
                player.getBoundingBox().inflate(64), e -> true);

        if (hostiles.isEmpty()) {
            sendPrivate(player, prefix(stage) + " §aNenhum mob hostil perto. Estás seguro.");
            return;
        }
        StringBuilder sb = new StringBuilder(
                prefix(stage) + " §c" + hostiles.size() + " inimigo(s) perto:\n");
        hostiles.stream()
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                .limit(8)
                .forEach(e -> {
                    int dist = (int) e.distanceTo(player);
                    String name = e.getType().getDescriptionId()
                            .replace("entity.minecraft.", "").replace("_", " ");
                    sb.append(String.format("  §c%s§f — %d blocos — §b(%d, %d, %d)\n",
                            name, dist,
                            (int) e.getX(), (int) e.getY(), (int) e.getZ()));
                });
        sendPrivate(player, sb.toString().trim());
    }

    // ------------------------------------------------------------------ //
    //  Build assistant                                                     //
    // ------------------------------------------------------------------ //

    private static final Pattern BUILD_PATTERN = Pattern.compile(
            "(?i)(constr[oó]i|build|faz|make)\\s+(um[a]?\\s+)?" +
            "(parede|wall|chão|floor|pilar|pillar|caminho|path|casa|house|teto|roof|escada|staircase)?" +
            "(?:\\s+de\\s+(\\w+))?(?:\\s+(\\d+))?");

    public static void executeBuild(String query, ServerPlayer player,
                                    MinecraftServer server, int stage) {
        Matcher m = BUILD_PATTERN.matcher(query);
        if (!m.find()) {
            sendPrivate(player, prefix(stage) + " §7Não entendi o que construir. Tenta: §fconstruí uma parede de pedra 10");
            return;
        }

        String shape    = m.group(3) != null ? m.group(3).toLowerCase() : "wall";
        String material = m.group(4);
        int size        = m.group(5) != null ? Math.min(Integer.parseInt(m.group(5)), 32) : 8;

        Block block = resolveMaterial(material, player);
        net.minecraft.world.level.block.state.BlockState state = block.defaultBlockState();
        String blockName = BuiltInRegistries.BLOCK.getKey(block).getPath().replace("_", " ");

        sendPrivate(player, String.format("%s §7A construir §f%s§7 de §f%s§7 (tamanho %d)...",
                prefix(stage), shape, blockName, size));

        CompletableFuture.runAsync(() -> {
            ServerLevel world = player.serverLevel();
            BlockPos origin   = player.blockPosition();
            Vec3 look  = player.getLookAngle();
            int fx = (int) Math.round(look.x);
            int fz = (int) Math.round(look.z);
            if (fx == 0 && fz == 0) fx = 1;

            List<BlockPos> positions = switch (shape) {
                case "parede", "wall"   -> buildWall(origin, fx, fz, size);
                case "chão",   "floor"  -> buildFloor(origin, fx, fz, size);
                case "pilar",  "pillar" -> buildPillar(origin, size);
                case "caminho","path"   -> buildPath(origin, fx, fz, size);
                case "teto",   "roof"   -> buildRoof(origin, fx, fz, size);
                case "casa",   "house"  -> buildHouseOutline(origin, fx, fz, size);
                default                  -> buildWall(origin, fx, fz, size);
            };

            for (BlockPos pos : positions) {
                server.execute(() -> {
                    if (world.isEmptyBlock(pos) || world.getBlockState(pos).canBeReplaced()) {
                        world.setBlock(pos, state, 3);
                    }
                });
            }

            server.execute(() -> {
                sendPrivate(player, String.format("%s §f%d blocos de §f%s §fcolocados!",
                        prefix(stage), positions.size(), blockName));
                world.playSound(null, origin, SoundEvents.NOTE_BLOCK_BELL.value(),
                        SoundSource.BLOCKS, 0.8f, 1.2f);
            });
        });
    }

    private static List<BlockPos> buildWall(BlockPos o, int fx, int fz, int length) {
        List<BlockPos> list = new ArrayList<>();
        int px = fz, pz = -fx;
        for (int i = -length / 2; i <= length / 2; i++) {
            for (int y = 0; y < 4; y++) {
                list.add(o.offset(px * i + fx * 2, y, pz * i + fz * 2));
            }
        }
        return list;
    }

    private static List<BlockPos> buildFloor(BlockPos o, int fx, int fz, int size) {
        List<BlockPos> list = new ArrayList<>();
        int px = fz, pz = -fx;
        for (int i = 0; i < size; i++) {
            for (int j = -size / 2; j <= size / 2; j++) {
                list.add(o.offset(fx * i + px * j, -1, fz * i + pz * j));
            }
        }
        return list;
    }

    private static List<BlockPos> buildPillar(BlockPos o, int height) {
        List<BlockPos> list = new ArrayList<>();
        for (int y = 0; y < height; y++) list.add(o.above(y));
        return list;
    }

    private static List<BlockPos> buildPath(BlockPos o, int fx, int fz, int length) {
        List<BlockPos> list = new ArrayList<>();
        int px = fz, pz = -fx;
        for (int i = 0; i < length; i++) {
            for (int j = -1; j <= 1; j++) {
                list.add(o.offset(fx * i + px * j, -1, fz * i + pz * j));
            }
        }
        return list;
    }

    private static List<BlockPos> buildRoof(BlockPos o, int fx, int fz, int size) {
        List<BlockPos> list = new ArrayList<>();
        int px = fz, pz = -fx;
        for (int i = 0; i < size; i++) {
            for (int j = -size / 2; j <= size / 2; j++) {
                list.add(o.offset(fx * i + px * j, 5, fz * i + pz * j));
            }
        }
        return list;
    }

    private static List<BlockPos> buildHouseOutline(BlockPos o, int fx, int fz, int size) {
        List<BlockPos> list = new ArrayList<>();
        int px = fz, pz = -fx;
        list.addAll(buildFloor(o, fx, fz, size));
        for (int i = 0; i < size; i++) {
            for (int y = 0; y < 4; y++) {
                list.add(o.offset(fx * i, y, fz * i));
                list.add(o.offset(fx * i + px * (size / 2), y, fz * i + pz * (size / 2)));
                list.add(o.offset(fx * i - px * (size / 2), y, fz * i - pz * (size / 2)));
            }
        }
        for (int j = -size / 2; j <= size / 2; j++) {
            for (int y = 0; y < 4; y++) {
                list.add(o.offset(fx * (size - 1) + px * j, y, fz * (size - 1) + pz * j));
            }
        }
        list.addAll(buildRoof(o, fx, fz, size));
        return list;
    }

    // ------------------------------------------------------------------ //
    //  Crafting advisor                                                    //
    // ------------------------------------------------------------------ //

    private static final Map<String, String> CRAFTING_TIPS = new LinkedHashMap<>();

    static {
        CRAFTING_TIPS.put("pickaxe|picareta",
                "§fPicareta de §bdiamanite§f: 3 diamantes na linha de cima + 2 paus no meio.");
        CRAFTING_TIPS.put("sword|espada",
                "§fEspada: 2 materiais na vertical + 1 pau em baixo.");
        CRAFTING_TIPS.put("enchanting|encantamento|encantamentos",
                "§fMesa de encantamento: 4 obsidiana em baixo + 2 diamantes nos lados + 1 livro em cima.");
        CRAFTING_TIPS.put("beacon|farol",
                "§fFarol: 3 vidros em cima + 1 estrela do Nether no meio + 5 obsidianas em baixo.");
        CRAFTING_TIPS.put("elytra|elitra",
                "§fElitras encontram-se em §cEnd Ships§f. Não se craftam.");
        CRAFTING_TIPS.put("totem|totem of undying",
                "§fTotem de imortalidade: dropa do §cEvocador§f em mansões ou assaltos.");
        CRAFTING_TIPS.put("potion|pocao|poção",
                "§fPara poções precisas de um §bsuporte de poções§f (blaze rod + 3 cobblestone) e §bwater bottle§f.");
        CRAFTING_TIPS.put("golden apple|maca dourada|maçã dourada",
                "§fMaçã dourada: 1 maçã no centro + 8 pepitas de ouro à volta.");
        CRAFTING_TIPS.put("ender chest|baú do end",
                "§fBaú do End: 1 olho do End no centro + 8 obsidianas à volta.");
        CRAFTING_TIPS.put("shield|escudo",
                "§fEscudo: 1 ferro no centro da linha de cima + 6 madeira em U.");
        CRAFTING_TIPS.put("anvil|bigorna",
                "§fBigorna: 3 blocos de ferro na linha de cima + 1 ferro no centro + 3 ferros em baixo.");
        CRAFTING_TIPS.put("boat|barco",
                "§fBarco: 5 madeiras em U (sem o centro e linha de cima).");
        CRAFTING_TIPS.put("book|livro",
                "§fLivro: 3 papéis + 1 couro (qualquer ordem 2×2).");
    }

    public static void craftingAdvice(String query, ServerPlayer player,
                                      MinecraftServer server, int stage) {
        String q = query.toLowerCase();
        for (Map.Entry<String, String> e : CRAFTING_TIPS.entrySet()) {
            for (String key : e.getKey().split("\\|")) {
                if (q.contains(key)) {
                    sendPrivate(player, prefix(stage) + " " + e.getValue());
                    return;
                }
            }
        }
        sendPrivate(player, prefix(stage) + " §7Não conheço essa receita de cor. Consulta uma wiki!");
    }

    // ------------------------------------------------------------------ //
    //  Enchantment advisor                                                 //
    // ------------------------------------------------------------------ //

    private static final Map<String, String> ENCHANT_TIPS = new LinkedHashMap<>();

    static {
        ENCHANT_TIPS.put("sword|espada",
                "§fMelhor espada: §bSharpness V§f + §bLooting III§f + §bUnbreaking III§f + §bMending§f.");
        ENCHANT_TIPS.put("pickaxe|picareta",
                "§fMelhor picareta: §bEfficiency V§f + §bFortune III§f (ou §bSilk Touch§f) + §bUnbreaking III§f + §bMending§f.");
        ENCHANT_TIPS.put("armor|armadura",
                "§fMelhor armadura: §bProtection IV§f + §bFeather Falling IV§f (botas) + §bThorns III§f + §bMending§f.");
        ENCHANT_TIPS.put("bow|arco",
                "§fMelhor arco: §bPower V§f + §bFlame§f + §bInfinity§f + §bUnbreaking III§f.");
        ENCHANT_TIPS.put("crossbow|besta",
                "§fMelhor besta: §bQuick Charge III§f + §bPiercing IV§f + §bUnbreaking III§f + §bMending§f.");
        ENCHANT_TIPS.put("trident|tridente",
                "§fMelhor tridente: §bLoyalty III§f ou §bRiptide III§f + §bChanneling§f + §bMending§f.");
        ENCHANT_TIPS.put("fishing|pesca",
                "§fMelhor cana: §bLuck of the Sea III§f + §bLure III§f + §bUnbreaking III§f + §bMending§f.");
        ENCHANT_TIPS.put("shovel|pá",
                "§fMelhor pá: §bEfficiency V§f + §bUnbreaking III§f + §bMending§f + §bSilk Touch§f.");
        ENCHANT_TIPS.put("axe|machado",
                "§fMelhor machado: §bEfficiency V§f + §bSharpness V§f + §bUnbreaking III§f + §bMending§f.");
        ENCHANT_TIPS.put("helmet|capacete",
                "§fCapacete: §bProtection IV§f + §bRespiration III§f + §bAqua Affinity§f + §bMending§f.");
        ENCHANT_TIPS.put("boots|botas",
                "§fBotas: §bProtection IV§f + §bFeather Falling IV§f + §bDepth Strider III§f + §bMending§f.");
    }

    public static void enchantAdvice(String query, ServerPlayer player,
                                     MinecraftServer server, int stage) {
        String q = query.toLowerCase();
        for (Map.Entry<String, String> e : ENCHANT_TIPS.entrySet()) {
            for (String key : e.getKey().split("\\|")) {
                if (q.contains(key)) {
                    sendPrivate(player, prefix(stage) + " " + e.getValue());
                    return;
                }
            }
        }
        sendPrivate(player, prefix(stage) + " §7Diz-me o item (espada, picareta, armadura...) e digo-te os melhores encantamentos.");
    }

    // ------------------------------------------------------------------ //
    //  Trade evaluator                                                     //
    // ------------------------------------------------------------------ //

    public static void evaluateTrade(ServerPlayer player,
                                     MinecraftServer server, int stage) {
        ServerLevel world = player.serverLevel();
        var villagers = world.getEntitiesOfClass(
                net.minecraft.world.entity.npc.Villager.class, player.getBoundingBox().inflate(6), e -> true);

        if (villagers.isEmpty()) {
            sendPrivate(player, prefix(stage) + " §7Nenhum aldeão perto para avaliar.");
            return;
        }

        var v = villagers.get(0);
        String profession = v.getVillagerData().getProfession()
                .toString().replace("minecraft:", "").replace("_", " ");
        String level = switch (v.getVillagerData().getLevel()) {
            case 1 -> "Novice"; case 2 -> "Apprentice"; case 3 -> "Journeyman";
            case 4 -> "Expert"; case 5 -> "Master"; default -> "Unknown";
        };

        sendPrivate(player, String.format(
                "%s §fAldeão: §b%s §f(%s). §7Dica: §bMending§f de Librarians e §bLibros de encantamento§f.",
                prefix(stage), profession, level));
    }

    // ------------------------------------------------------------------ //
    //  General knowledge                                                   //
    // ------------------------------------------------------------------ //

    private static final Map<String, String> KNOWLEDGE = new LinkedHashMap<>();

    static {
        KNOWLEDGE.put("y level|melhor y|best y|onde minar",
                "§fMelhor Y:\n  §bDiamante§f → Y=-58  §bFerro§f → Y=15  §bOuro§f → Y=-16\n  §bCobre§f → Y=48  §bNetherite§f → Y=15 (Nether)");
        KNOWLEDGE.put("food|comida|melhor comida",
                "§fMelhor comida: §bSteak§f (8 fome + 12.8 saturação) ou §bGolden Carrot§f (melhor saturação).");
        KNOWLEDGE.put("end portal|portal do end|como ir ao end",
                "§fO End Portal fica no Stronghold. Usa §bo olho do End§f para o encontrar. Precisas de 12 olhos.");
        KNOWLEDGE.put("nether|como ir ao nether",
                "§fPortal do Nether: §b10 blocos de obsidiana§f em moldura 4×5. Acende com isqueiro.");
        KNOWLEDGE.put("wither|como matar o wither",
                "§fWither: luta no Nether ou debaixo do bedrock do End. §bSmite V§f numa espada de diamante.");
        KNOWLEDGE.put("dragon|ender dragon",
                "§fDragão: destroí os End Crystals com flechas. Ataca quando plana sobre o portal. §bSharpness V§f.");
        KNOWLEDGE.put("raid|assalto",
                "§fAssaltos começam com §bBad Omen§f. Bebe §bMilk§f antes de entrar na aldeia para evitar.");
        KNOWLEDGE.put("xp|experiência|melhor xp",
                "§fMelhor XP: §bBlaze Spawner§f no Nether Fortress ou §bEndermen no End§f.");
        KNOWLEDGE.put("sleep|dormir",
                "§fEm multiplayer todos precisam de dormir (ou o servidor tem §bplayersSleepingPercentage§f reduzido).");
        KNOWLEDGE.put("coordinates|coordenadas",
                "§fPressiona §bF3§f para ver coordenadas. §bX§f=Este/Oeste, §bY§f=Altura, §bZ§f=Norte/Sul.");
    }

    public static boolean tryKnowledge(String query, ServerPlayer player,
                                       MinecraftServer server, int stage) {
        String q = query.toLowerCase();
        for (Map.Entry<String, String> e : KNOWLEDGE.entrySet()) {
            for (String key : e.getKey().split("\\|")) {
                if (q.contains(key)) {
                    sendPrivate(player, prefix(stage) + " " + e.getValue());
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private static OreEntry matchOre(String keyword) {
        return ORE_MAP.get(keyword.toLowerCase().strip());
    }

    private static StructureEntry matchStructure(String keyword) {
        return STRUCTURE_MAP.get(keyword.toLowerCase().strip());
    }

    public static OreEntry matchOreFromQuery(String query) {
        String q = query.toLowerCase();
        for (Map.Entry<String, OreEntry> e : ORE_MAP.entrySet()) {
            if (q.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    public static StructureEntry matchStructureFromQuery(String query) {
        String q = query.toLowerCase();
        for (Map.Entry<String, StructureEntry> e : STRUCTURE_MAP.entrySet()) {
            if (q.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    private static Block resolveMaterial(@Nullable String material, ServerPlayer player) {
        if (material == null) {
            Block held = BuiltInRegistries.BLOCK.get(
                    ResourceLocation.tryParse("minecraft:" +
                            player.getMainHandItem().getItem()
                                    .getDescriptionId()
                                    .replace("item.minecraft.", "")
                                    .replace("block.minecraft.", "")));
            if (held != Blocks.AIR) return held;
            return Blocks.COBBLESTONE;
        }
        Map<String, Block> m = Map.ofEntries(
                Map.entry("pedra",       Blocks.STONE),
                Map.entry("stone",       Blocks.STONE),
                Map.entry("madeira",     Blocks.OAK_PLANKS),
                Map.entry("wood",        Blocks.OAK_PLANKS),
                Map.entry("cobblestone", Blocks.COBBLESTONE),
                Map.entry("tijolo",      Blocks.BRICKS),
                Map.entry("brick",       Blocks.BRICKS),
                Map.entry("terra",       Blocks.DIRT),
                Map.entry("dirt",        Blocks.DIRT),
                Map.entry("areia",       Blocks.SAND),
                Map.entry("sand",        Blocks.SAND)
        );
        return m.getOrDefault(material.toLowerCase(), Blocks.COBBLESTONE);
    }

    private static String prefix(int stage) {
        return stage >= 4 ? "§c[Verity]§r" : stage >= 2 ? "§6[Verity]§r" : "§e[Verity]§r";
    }

    /**
     * Send a message PRIVATELY to one player only.
     * Other players will NOT see this message.
     */
    public static void sendPrivate(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    // ------------------------------------------------------------------ //
    //  Inner data classes                                                  //
    // ------------------------------------------------------------------ //

    public record OreEntry(String displayName, Block... blocks) {
        public String getDisplayName() { return displayName; }
    }

    public record StructureEntry(String displayName,
                                  @Nullable TagKey<Structure> tag,
                                  @Nullable String mcId) {
        public String getDisplayName() { return displayName; }
        @Nullable public TagKey<Structure> getTag() { return tag; }
        @Nullable public String getMcId() { return mcId; }
    }
}
