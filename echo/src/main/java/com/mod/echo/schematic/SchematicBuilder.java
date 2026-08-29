package com.mod.echo.schematic;

import com.mod.echo.hosting.EchoPrivateWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Places a parsed {@link Schematic} into the world, one block per position.
 *
 * <p>Restricted to ECHO's own private world ({@link EchoPrivateWorld}) — the
 * point of that whole system is a place ECHO can shape freely without ever
 * touching the player's real base or a shared server. This check runs here too,
 * not only where the tool is registered, so nothing can call this class directly
 * and skip it.
 */
public final class SchematicBuilder {

    private SchematicBuilder() {}

    /** Hard ceiling so a huge download cannot freeze the server for minutes. */
    private static final long MAX_BLOCKS = 60_000;

    public static String place(ServerPlayer player, MinecraftServer server, Schematic schem, BlockPos origin) {
        if (!EchoPrivateWorld.is(server)) {
            return "I can only build big schematics here on echo.net, not in your main world — "
                    + "say \"echo host\" to open echo.net first.";
        }
        if (schem.blockCount() > MAX_BLOCKS) {
            return "That schematic needs " + schem.blockCount() + " blocks, over my "
                    + MAX_BLOCKS + "-block safety limit for one build.";
        }

        ServerLevel level = player.level();
        Set<String> unknownBlocks = new LinkedHashSet<>();
        int placed = 0;
        int index = 0;
        for (int y = 0; y < schem.height; y++) {
            for (int z = 0; z < schem.length; z++) {
                for (int x = 0; x < schem.width; x++, index++) {
                    int paletteId = schem.blocks[index];
                    String entry = schem.palette.get(paletteId);
                    if (entry == null) continue;
                    BlockState state = BlockStateCodec.decode(entry, unknownBlocks);
                    BlockPos pos = origin.offset(x, y, z);
                    if (!level.isLoaded(pos)) continue;
                    level.setBlock(pos, state, 3);
                    placed++;
                }
            }
        }

        StringBuilder result = new StringBuilder("Built it — ").append(placed)
                .append(" of ").append(schem.blockCount()).append(" blocks placed");
        if (!unknownBlocks.isEmpty()) {
            result.append(". This game version doesn't have: ")
                  .append(String.join(", ", unknownBlocks))
                  .append(" (left as air there)");
        }
        return result.append('.').toString();
    }
}
