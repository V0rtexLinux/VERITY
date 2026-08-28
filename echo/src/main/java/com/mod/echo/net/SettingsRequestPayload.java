package com.mod.echo.net;

import com.mod.echo.EchoMod;
import com.mod.echo.EchoStyle;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server to client: "re-tune your video settings".
 *
 * Video options only exist on the client, so when ECHO is answering on the
 * logical server it cannot touch them directly.  It sends this instead, and the
 * client runs the tuner locally and reports the result to that player alone.
 */
public record SettingsRequestPayload(String goal, int targetFps, boolean apply)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SettingsRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(EchoMod.MOD_ID, "settings_request"));

    public static final StreamCodec<FriendlyByteBuf, SettingsRequestPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUtf(payload.goal(), 32);
                        buf.writeVarInt(payload.targetFps());
                        buf.writeBoolean(payload.apply());
                    },
                    buf -> new SettingsRequestPayload(buf.readUtf(32), buf.readVarInt(), buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Register the payload type. Must run on both sides so the codec matches. */
    public static void registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    /** Ask one player's client to tune itself. Silently ignored by vanilla clients. */
    public static void sendToPlayer(ServerPlayer player, String goal, int targetFps, boolean apply) {
        try {
            if (!ServerPlayNetworking.canSend(player, TYPE)) {
                EchoMod.LOGGER.debug("{} has no ECHO client, so their settings cannot be tuned from here.",
                        player.getName().getString());
                return;
            }
            ServerPlayNetworking.send(player, new SettingsRequestPayload(goal, targetFps, apply));
        } catch (Exception e) {
            EchoMod.LOGGER.debug("Could not send a settings request to {}: {}",
                    player.getName().getString(), e.toString());
        }
    }

    /** Client-side handler: run the tuner and print the report to this player only. */
    @Environment(EnvType.CLIENT)
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
                context.client().execute(() -> {
                    String report = com.mod.echo.settings.SettingsTuner.tune(
                            com.mod.echo.settings.SettingsTuner.Goal.parse(payload.goal()),
                            payload.targetFps(),
                            payload.apply());
                    if (context.client().player != null) {
                        context.client().player.sendSystemMessage(
                                Component.literal(EchoStyle.block(EchoStyle.TEXT + report)));
                    }
                }));
    }
}
