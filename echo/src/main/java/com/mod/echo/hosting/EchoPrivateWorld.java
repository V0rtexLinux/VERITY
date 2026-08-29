package com.mod.echo.hosting;

import net.minecraft.server.MinecraftServer;

/**
 * Identity of ECHO's private world ("echo.net") — the one save that the join
 * gate and the schematic builder are restricted to.
 *
 * <p>This is deliberately common code, not client-only: {@link com.mod.echo.EchoMod}
 * needs it too, on the logical server side, and that side also runs on a real
 * standalone dedicated server (unrelated to echo.net) where client-only classes
 * like {@link EchoServerHost} are never loaded. A plain level-name check here
 * keeps that check safe everywhere the mod runs.
 */
public final class EchoPrivateWorld {

    private EchoPrivateWorld() {}

    public static final String LEVEL_NAME = "echo_net_world";
    public static final String MOTD = "o mundo de amigos e assistentes virtuais, onde a harmonia nunca vai ser quebrada";

    public static boolean is(MinecraftServer server) {
        try {
            return server != null && LEVEL_NAME.equals(server.getWorldData().getLevelName());
        } catch (Exception e) {
            return false;
        }
    }
}
