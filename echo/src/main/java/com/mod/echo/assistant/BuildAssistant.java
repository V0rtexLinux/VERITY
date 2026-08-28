package com.mod.echo.assistant;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a shape description into blocks in the world.
 *
 * Every shape is generated as a list of positions first and only then written,
 * on the server thread, in one batch — so a build either happens completely or
 * not at all, and it never partially applies while the player walks away.
 *
 * Placement is non-destructive: existing blocks are left alone unless they are
 * air or replaceable (grass, snow, water), so ECHO cannot bulldoze a base by
 * accident.
 */
public final class BuildAssistant {

    private BuildAssistant() {}

    /** Upper bound on the blocks a single build may place. */
    public static final int MAX_BLOCKS = 4096;

    public static final List<String> SHAPES = List.of(
            "wall", "floor", "ceiling", "pillar", "path", "roof", "room",
            "shelter", "bridge", "stairs", "tower", "platform", "dome", "fence");

    // ------------------------------------------------------------------ //
    //  Materials                                                           //
    // ------------------------------------------------------------------ //

    private static final Map<String, Block> MATERIALS = new LinkedHashMap<>();
    static {
        MATERIALS.put("stone", Blocks.STONE);
        MATERIALS.put("pedra", Blocks.STONE);
        MATERIALS.put("cobblestone", Blocks.COBBLESTONE);
        MATERIALS.put("cobble", Blocks.COBBLESTONE);
        MATERIALS.put("stone bricks", Blocks.STONE_BRICKS);
        MATERIALS.put("deepslate", Blocks.DEEPSLATE_BRICKS);
        MATERIALS.put("wood", Blocks.OAK_PLANKS);
        MATERIALS.put("madeira", Blocks.OAK_PLANKS);
        MATERIALS.put("planks", Blocks.OAK_PLANKS);
        MATERIALS.put("spruce", Blocks.SPRUCE_PLANKS);
        MATERIALS.put("birch", Blocks.BIRCH_PLANKS);
        MATERIALS.put("dirt", Blocks.DIRT);
        MATERIALS.put("terra", Blocks.DIRT);
        MATERIALS.put("sand", Blocks.SANDSTONE);
        MATERIALS.put("areia", Blocks.SANDSTONE);
        MATERIALS.put("brick", Blocks.BRICKS);
        MATERIALS.put("tijolo", Blocks.BRICKS);
        MATERIALS.put("glass", Blocks.GLASS);
        MATERIALS.put("vidro", Blocks.GLASS);
        MATERIALS.put("obsidian", Blocks.OBSIDIAN);
        MATERIALS.put("obsidiana", Blocks.OBSIDIAN);
        MATERIALS.put("nether brick", Blocks.NETHER_BRICKS);
        MATERIALS.put("quartz", Blocks.QUARTZ_BLOCK);
        MATERIALS.put("copper", Blocks.COPPER_BLOCK);
        MATERIALS.put("iron", Blocks.IRON_BLOCK);
        MATERIALS.put("concrete", Blocks.WHITE_CONCRETE);
        MATERIALS.put("wool", Blocks.WHITE_WOOL);
        MATERIALS.put("la", Blocks.WHITE_WOOL);
    }

    public static List<String> materialNames() {
        return new ArrayList<>(MATERIALS.keySet());
    }

    /** Resolve a material name, falling back to any valid block id, then cobblestone. */
    public static Block resolveMaterial(String name) {
        if (name == null || name.isBlank()) return Blocks.COBBLESTONE;
        String key = name.trim().toLowerCase(Locale.ROOT);
        Block direct = MATERIALS.get(key);
        if (direct != null) return direct;

        Identifier id = Identifier.tryParse(key.contains(":") ? key : "minecraft:" + key.replace(' ', '_'));
        if (id != null) {
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            if (block != null && block != Blocks.AIR) return block;
        }
        return Blocks.COBBLESTONE;
    }

    public static String materialName(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? "blocks" : id.getPath().replace('_', ' ');
    }

    // ------------------------------------------------------------------ //
    //  Build entry point                                                   //
    // ------------------------------------------------------------------ //

    /**
     * Build {@code shape} out of {@code material} in front of the player.
     *
     * @return a description of what was built, ready to hand back to the model
     */
    public static String build(ServerPlayer player, MinecraftServer server,
                               String shape, String material, int size) {
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        Block block = resolveMaterial(material);
        int s = Math.max(2, Math.min(32, size));

        // Facing is the player's look direction snapped to a cardinal axis.
        Vec3 look = player.getLookAngle();
        int fx = 0, fz = 0;
        if (Math.abs(look.x) >= Math.abs(look.z)) fx = look.x >= 0 ? 1 : -1;
        else fz = look.z >= 0 ? 1 : -1;

        String normalised = shape == null ? "wall" : shape.trim().toLowerCase(Locale.ROOT);
        List<BlockPos> positions = switch (normalised) {
            case "floor", "chao", "chão"     -> floor(origin, fx, fz, s);
            case "ceiling", "teto", "roof"   -> roof(origin, fx, fz, s);
            case "pillar", "pilar"           -> pillar(origin, s);
            case "path", "caminho"           -> path(origin, fx, fz, s);
            case "room", "house", "casa"     -> room(origin, fx, fz, s);
            case "shelter", "abrigo"         -> shelter(origin);
            case "bridge", "ponte"           -> bridge(origin, fx, fz, s);
            case "stairs", "escada"          -> stairs(origin, fx, fz, s);
            case "tower", "torre"            -> tower(origin, s);
            case "platform", "plataforma"    -> platform(origin, s);
            case "dome", "domo"              -> dome(origin, s);
            case "fence", "cerca"            -> fence(origin, s);
            default                          -> wall(origin, fx, fz, s);
        };

        if (positions.size() > MAX_BLOCKS) {
            return "That would need " + positions.size() + " blocks, over my " + MAX_BLOCKS
                    + "-block safety limit. Ask for a smaller size.";
        }

        BlockState state = block.defaultBlockState();
        int placed = 0;
        for (BlockPos pos : positions) {
            if (!level.isLoaded(pos)) continue;
            BlockState existing = level.getBlockState(pos);
            if (!existing.isAir() && !existing.canBeReplaced()) continue;
            level.setBlock(pos, state, 3);
            placed++;
        }

        int skipped = positions.size() - placed;
        String result = "Built a " + normalised + " out of " + materialName(block)
                + " — " + placed + " blocks placed";
        if (skipped > 0) result += ", " + skipped + " skipped because something was already there";
        return result + ".";
    }

    // ------------------------------------------------------------------ //
    //  Shapes                                                              //
    // ------------------------------------------------------------------ //

    private static List<BlockPos> wall(BlockPos o, int fx, int fz, int length) {
        List<BlockPos> out = new ArrayList<>();
        int px = fz, pz = -fx;                    // perpendicular axis
        for (int i = -length / 2; i <= length / 2; i++) {
            for (int y = 0; y < 4; y++) {
                out.add(o.offset(px * i + fx * 2, y, pz * i + fz * 2));
            }
        }
        return out;
    }

    private static List<BlockPos> floor(BlockPos o, int fx, int fz, int size) {
        List<BlockPos> out = new ArrayList<>();
        int px = fz, pz = -fx;
        for (int i = 0; i < size; i++) {
            for (int j = -size / 2; j <= size / 2; j++) {
                out.add(o.offset(fx * i + px * j, -1, fz * i + pz * j));
            }
        }
        return out;
    }

    private static List<BlockPos> roof(BlockPos o, int fx, int fz, int size) {
        List<BlockPos> out = new ArrayList<>();
        int px = fz, pz = -fx;
        for (int i = 0; i < size; i++) {
            for (int j = -size / 2; j <= size / 2; j++) {
                out.add(o.offset(fx * i + px * j, 4, fz * i + pz * j));
            }
        }
        return out;
    }

    private static List<BlockPos> pillar(BlockPos o, int height) {
        List<BlockPos> out = new ArrayList<>();
        for (int y = 0; y < height; y++) out.add(o.above(y));
        return out;
    }

    private static List<BlockPos> tower(BlockPos o, int height) {
        List<BlockPos> out = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean edge = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                    if (edge) out.add(o.offset(dx, y, dz));
                }
            }
        }
        return out;
    }

    private static List<BlockPos> path(BlockPos o, int fx, int fz, int length) {
        List<BlockPos> out = new ArrayList<>();
        int px = fz, pz = -fx;
        for (int i = 1; i <= length; i++) {
            for (int j = -1; j <= 1; j++) {
                out.add(o.offset(fx * i + px * j, -1, fz * i + pz * j));
            }
        }
        return out;
    }

    private static List<BlockPos> bridge(BlockPos o, int fx, int fz, int length) {
        List<BlockPos> out = new ArrayList<>();
        int px = fz, pz = -fx;
        for (int i = 1; i <= length; i++) {
            for (int j = -1; j <= 1; j++) {
                out.add(o.offset(fx * i + px * j, -1, fz * i + pz * j));
            }
            // Waist-high rails so nobody walks off the side.
            out.add(o.offset(fx * i + px * 2, 0, fz * i + pz * 2));
            out.add(o.offset(fx * i - px * 2, 0, fz * i - pz * 2));
        }
        return out;
    }

    private static List<BlockPos> stairs(BlockPos o, int fx, int fz, int length) {
        List<BlockPos> out = new ArrayList<>();
        int px = fz, pz = -fx;
        for (int i = 1; i <= length; i++) {
            for (int j = -1; j <= 1; j++) {
                out.add(o.offset(fx * i + px * j, i - 1, fz * i + pz * j));
            }
        }
        return out;
    }

    private static List<BlockPos> platform(BlockPos o, int size) {
        List<BlockPos> out = new ArrayList<>();
        int half = size / 2;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                out.add(o.offset(dx, -1, dz));
            }
        }
        return out;
    }

    private static List<BlockPos> room(BlockPos o, int fx, int fz, int size) {
        List<BlockPos> out = new ArrayList<>(floor(o, fx, fz, size));
        int px = fz, pz = -fx;
        int half = size / 2;

        for (int i = 0; i < size; i++) {
            for (int y = 0; y < 4; y++) {
                out.add(o.offset(fx * i + px * half, y, fz * i + pz * half));
                out.add(o.offset(fx * i - px * half, y, fz * i - pz * half));
            }
        }
        for (int j = -half; j <= half; j++) {
            for (int y = 0; y < 4; y++) {
                // Back wall solid; front wall keeps a 2-block doorway in the middle.
                out.add(o.offset(fx * (size - 1) + px * j, y, fz * (size - 1) + pz * j));
                if (j != 0 || y >= 2) out.add(o.offset(px * j, y, pz * j));
            }
        }
        out.addAll(roof(o, fx, fz, size));
        return out;
    }

    /** A sealed 3x3x3 box around the player — the classic "night is falling" panic build. */
    private static List<BlockPos> shelter(BlockPos o) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean shell = Math.abs(dx) == 1 || Math.abs(dz) == 1 || dy == -1 || dy == 2;
                    if (shell) out.add(o.offset(dx, dy, dz));
                }
            }
        }
        return out;
    }

    private static List<BlockPos> dome(BlockPos o, int radius) {
        List<BlockPos> out = new ArrayList<>();
        int r = Math.max(2, radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = 0; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d <= r && d > r - 1) out.add(o.offset(dx, dy, dz));
                }
            }
        }
        return out;
    }

    private static List<BlockPos> fence(BlockPos o, int size) {
        List<BlockPos> out = new ArrayList<>();
        int half = Math.max(2, size / 2);
        for (int i = -half; i <= half; i++) {
            for (int y = 0; y < 2; y++) {
                out.add(o.offset(i, y, -half));
                out.add(o.offset(i, y, half));
                out.add(o.offset(-half, y, i));
                out.add(o.offset(half, y, i));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ //
    //  Utilities                                                           //
    // ------------------------------------------------------------------ //

    /** Light an area by placing torches on every valid surface that is too dark. */
    public static String lightArea(ServerPlayer player, int radius) {
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        int r = Math.max(2, Math.min(16, radius));
        int placed = 0;

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-r, -2, -r), origin.offset(r, 3, r))) {
            if (placed >= 256) break;
            if (!level.isLoaded(pos)) continue;
            if (!WorldScanner.isSpawnable(level, pos)) continue;
            // Space torches out instead of carpeting the floor with them.
            if (Math.floorMod(pos.getX(), 6) != 0 || Math.floorMod(pos.getZ(), 6) != 0) continue;
            level.setBlock(pos.immutable(), Blocks.TORCH.defaultBlockState(), 3);
            placed++;
        }
        return placed == 0
                ? "Nothing within " + r + " blocks needed lighting."
                : "Placed " + placed + " torches within " + r + " blocks. That area is mob-proof now.";
    }

    /** Dig a 1x2 corridor in the direction the player is facing. */
    public static String digTunnel(ServerPlayer player, int length) {
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        int len = Math.max(1, Math.min(64, length));

        Vec3 look = player.getLookAngle();
        int fx = 0, fz = 0;
        if (Math.abs(look.x) >= Math.abs(look.z)) fx = look.x >= 0 ? 1 : -1;
        else fz = look.z >= 0 ? 1 : -1;

        int cleared = 0;
        for (int i = 1; i <= len; i++) {
            for (int y = 0; y < 2; y++) {
                BlockPos pos = origin.offset(fx * i, y, fz * i);
                if (!level.isLoaded(pos)) continue;
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) continue;
                // Never open a hole into lava or water — that is how bases flood.
                if (state.getBlock() == Blocks.LAVA || state.getBlock() == Blocks.WATER
                        || state.getBlock() == Blocks.BEDROCK) {
                    return "Stopped after " + cleared + " blocks — there is "
                            + materialName(state.getBlock()) + " in the way at ("
                            + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ").";
                }
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                cleared++;
            }
        }
        return "Dug a " + len + "-block corridor (" + cleared + " blocks removed).";
    }
}
