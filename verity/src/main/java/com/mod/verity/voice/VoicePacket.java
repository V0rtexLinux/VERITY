package com.mod.verity.voice;

import com.mod.verity.VerityMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Custom packet: voice-recognised query from client → server.
 *
 * The packet bypasses chat entirely, so it doesn't appear in the public
 * chat log and the player doesn't have to type anything.
 *
 * Uses the new Fabric CustomPacketPayload API (Fabric 0.100+).
 */
public class VoicePacket {

    // ------------------------------------------------------------------ //
    //  Payload record                                                      //
    // ------------------------------------------------------------------ //
    public record VoiceQueryPayload(String query) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<VoiceQueryPayload> TYPE =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath(VerityMod.MOD_ID, "voice_query"));

        public static final StreamCodec<FriendlyByteBuf, VoiceQueryPayload> CODEC =
                StreamCodec.of(
                        (buf, payload) -> buf.writeUtf(payload.query(), 512),
                        buf -> new VoiceQueryPayload(buf.readUtf(512)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------ //
    //  Server-side registration                                            //
    // ------------------------------------------------------------------ //
    public static void registerServer() {
        PayloadTypeRegistry.serverboundPlay().register(VoiceQueryPayload.TYPE, VoiceQueryPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(VoiceQueryPayload.TYPE,
                (payload, context) -> {
                    String query = payload.query().trim();
                    if (query.isBlank()) return;

                    VerityMod.LOGGER.info("[Verity Voice] Received voice query from {}: '{}'",
                            context.player().getName().getString(), query);

                    context.server().execute(() ->
                            com.mod.verity.event.ChatHandler.handleVoiceQuery(
                                    query,
                                    context.player(),
                                    (ServerLevel) context.player().level(),
                                    context.server()));
                });
    }

    // ------------------------------------------------------------------ //
    //  Client-side send                                                    //
    // ------------------------------------------------------------------ //
    @Environment(EnvType.CLIENT)
    public static void sendToServer(String query) {
        ClientPlayNetworking.send(new VoiceQueryPayload(query));
    }
}
