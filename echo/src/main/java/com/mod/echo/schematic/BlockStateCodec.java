package com.mod.echo.schematic;

import com.mod.echo.EchoMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Turns a schematic palette entry like {@code "minecraft:oak_stairs[facing=north,half=bottom]"}
 * into a real {@link BlockState}.
 *
 * <p>A schematic can reference a block this game version does not have (renamed or
 * removed between versions), or a property value it does not recognise. Neither
 * case is treated as fatal: an unknown block falls back to air (so the build keeps
 * going with one gap instead of aborting), and an unknown property is left at the
 * block's default value. {@link #unknownBlocks} accumulates what was skipped so
 * the caller can report it honestly instead of pretending every block landed.
 */
public final class BlockStateCodec {

    private BlockStateCodec() {}

    public static BlockState decode(String paletteEntry, java.util.Set<String> unknownBlocks) {
        String raw = paletteEntry.trim();
        int bracket = raw.indexOf('[');
        String idPart = bracket < 0 ? raw : raw.substring(0, bracket);
        String propsPart = bracket < 0 ? "" : raw.substring(bracket + 1, Math.max(bracket + 1, raw.lastIndexOf(']')));

        Identifier id = Identifier.tryParse(idPart.contains(":") ? idPart : "minecraft:" + idPart);
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
        if (block == null || block == Blocks.AIR && !idPart.endsWith("air")) {
            unknownBlocks.add(idPart);
            return Blocks.AIR.defaultBlockState();
        }

        BlockState state = block.defaultBlockState();
        if (propsPart.isBlank()) return state;

        StateDefinition<Block, BlockState> definition = block.getStateDefinition();
        for (String pair : propsPart.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            state = applyProperty(state, definition, key, value);
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperty(BlockState state, StateDefinition<Block, BlockState> definition,
                                             String key, String value) {
        try {
            Property property = definition.getProperty(key);
            if (property == null) return state;
            var parsed = property.getValue(value);
            if (parsed.isEmpty()) return state;
            return state.setValue(property, (Comparable) parsed.get());
        } catch (Exception e) {
            // A version mismatch in one property is not worth losing the whole block over.
            EchoMod.LOGGER.debug("Could not apply schematic property {}={}: {}", key, value, e.toString());
            return state;
        }
    }
}
