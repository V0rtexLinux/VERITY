package com.mod.echo.net;

import com.mod.echo.EchoMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: a question the player spoke out loud.
 *
 * Voice input bypasses chat entirely — the transcript never appears in the
 * public chat log, and the player does not have to type anything.
 */
public record VoiceQueryPayload(String query) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<VoiceQueryPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(EchoMod.MOD_ID, "voice_query"));

    public static final StreamCodec<FriendlyByteBuf, VoiceQueryPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeUtf(payload.query(), 512),
                    buf -> new VoiceQueryPayload(buf.readUtf(512)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Register the payload type and the server-side handler. */
    public static void registerServer() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> {
            String query = payload.query().trim();
            if (query.isBlank()) return;
            context.server().execute(() ->
                    com.mod.echo.event.ChatHandler.handleQuery(
                            query,
                            context.player(),
                            context.player().level(),
                            context.server()));
        });
    }

    @Environment(EnvType.CLIENT)
    public static void sendToServer(String query) {
        ClientPlayNetworking.send(new VoiceQueryPayload(query));
    }
}
