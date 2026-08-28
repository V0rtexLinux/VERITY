package com.mod.verity.echo.compat;

import com.mod.verity.VerityMod;
import com.mod.verity.echo.EchoDialogue;
import com.mod.verity.echo.network.EchoHandshakePacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fallback layer so Echo still "works" on a server that doesn't have VERITY
 * installed at all — vanilla or otherwise. There is a hard technical limit
 * here worth being explicit about: a truly new registered item/entity type
 * cannot be synced into a foreign server's registries without that server
 * running the mod, and a client cannot inject a new inventory item into a
 * world it doesn't authoritatively control. So compatibility mode doesn't
 * pretend the Echo Core item exists there.
 *
 * Instead, on a foreign server Echo rides on a REAL vanilla entity the
 * player already owns/tamed the normal survival way (an Allay freed from a
 * Pillager Outpost cage is the natural fit, but this works for any tamed
 * companion), renamed with a vanilla Name Tag to the exact {@link #ECHO_TAG}
 * string. Because that's 100% vanilla data (a normal {@code CustomName}
 * component), it is already sent to and rendered by every client in range —
 * modded or not — satisfying "the entity must appear to other players even
 * without the mod" for real, not as an illusion.
 *
 * What VERITY adds on top, purely client-side, for players who DO have the
 * mod: recognising any nearby entity carrying that name as an Echo, and
 * rendering the same social behaviour (conversations between two Echoes,
 * friendship growth with nearby players) as the native entity does — using
 * the deterministic picker in {@link EchoDialogue} so every modded observer
 * computes the identical result with zero networking.
 */
@Environment(EnvType.CLIENT)
public final class EchoCompatibility {
    private EchoCompatibility() {}

    /** Exact vanilla CustomName text that marks an entity as somebody's Echo. */
    public static final String ECHO_TAG = "✧Echo✧"; // "✧Echo✧"

    private static final double SCAN_RANGE = 32.0;
    private static final double CHAT_RANGE = 6.0;

    private static boolean nativeServer = false;
    private static int scanCooldown = 0;
    /** Session-scoped only (see class docs — no server to persist against). */
    private static final Map<UUID, Integer> localFriendship = new HashMap<>();

    public static boolean isNativeServer() { return nativeServer; }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            EchoHandshakePacket.sendToServer();
            nativeServer = EchoHandshakePacket.serverSupportsEcho();
            VerityMod.LOGGER.info("[Echo] Server support: {}", nativeServer ? "native" : "compatibility mode");
            if (!nativeServer && client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                        "§b[Echo]§r §7Este servidor não tem o VERITY instalado — modo de compatibilidade ativo."));
                client.player.sendSystemMessage(Component.literal(
                        "§b[Echo]§r §7Nomeia qualquer mob domesticado como \"" + ECHO_TAG + "\" com uma Placa de Identificação para o ligar."));
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(EchoCompatibility::tick);
    }

    private static void tick(Minecraft client) {
        if (nativeServer || client.level == null || client.player == null) return;
        if (--scanCooldown > 0) return;
        scanCooldown = 40;

        List<LivingEntity> tagged = new ArrayList<>();
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living)) continue;
            if (!living.hasCustomName()) continue;
            if (living.getCustomName() == null) continue;
            if (!ECHO_TAG.equals(living.getCustomName().getString())) continue;
            if (living.distanceTo(client.player) > SCAN_RANGE) continue;
            tagged.add(living);
        }

        if (tagged.isEmpty()) return;

        // Local friendship growth: any tagged Echo close to the viewer.
        for (LivingEntity echo : tagged) {
            if (echo.distanceTo(client.player) <= CHAT_RANGE) {
                localFriendship.merge(echo.getUUID(), 1, (a, b) -> Math.min(100, a + b));
            }
        }

        // Two Echoes near each other → synchronized deterministic "conversation".
        for (int i = 0; i < tagged.size(); i++) {
            for (int j = i + 1; j < tagged.size(); j++) {
                LivingEntity a = tagged.get(i);
                LivingEntity b = tagged.get(j);
                if (a.distanceTo(b) > 3.0) continue;
                if (a.distanceTo(client.player) > SCAN_RANGE || b.distanceTo(client.player) > SCAN_RANGE) continue;

                long bucket = client.level.getGameTime() / 200L;
                long seed = EchoDialogue.pairSeed(a.getUUID(), b.getUUID(), bucket);
                String line = EchoDialogue.pick(EchoDialogue.ECHO_TO_ECHO, seed);
                client.gui.setOverlayMessage(Component.literal("§b[Echo ↔ Echo]§r " + line), false);
                return;
            }
        }
    }

    public static int getLocalFriendship(UUID echoUuid) {
        return localFriendship.getOrDefault(echoUuid, 0);
    }
}
