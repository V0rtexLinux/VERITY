package com.mod.verity.echo.network;

import com.mod.verity.VerityMod;
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
 * Empty "hello" payload used purely to detect whether the server we're
 * connected to also runs VERITY (and therefore supports the fully
 * server-authoritative Echo entity/item).
 *
 * Fabric's networking layer negotiates the list of supported channels
 * during login, so {@code ClientPlayNetworking.canSend(TYPE)} tells us —
 * with zero round trips — whether the remote server registered a receiver
 * for this channel. If it didn't (vanilla server, or a server that simply
 * doesn't have this mod), Echo falls back to compatibility mode; see
 * {@link com.mod.verity.echo.compat.EchoCompatibility}.
 */
public class EchoHandshakePacket {

    public record HelloPayload() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HelloPayload> TYPE =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath(VerityMod.MOD_ID, "echo_hello"));

        public static final StreamCodec<FriendlyByteBuf, HelloPayload> CODEC =
                StreamCodec.unit(new HelloPayload());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Called from the common {@code ModInitializer} entrypoint, which Fabric
     * runs on BOTH the client and the dedicated server JVM. That single call
     * registers the codec on whichever side is executing (so the client can
     * serialize the packet to send, and the server can deserialize it to
     * receive) and installs the server-side receiver (harmless no-op on a
     * pure client). Mirrors {@link com.mod.verity.voice.VoicePacket#registerServer()}.
     */
    public static void registerServer() {
        PayloadTypeRegistry.serverboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE, (payload, context) ->
                VerityMod.LOGGER.debug("[Echo] Handshake received from {} — native mode confirmed.",
                        context.player().getName().getString()));
    }

    @Environment(EnvType.CLIENT)
    public static void sendToServer() {
        if (ClientPlayNetworking.canSend(HelloPayload.TYPE)) {
            ClientPlayNetworking.send(new HelloPayload());
        }
    }

    /** True if the currently connected server declared support for this channel. */
    @Environment(EnvType.CLIENT)
    public static boolean serverSupportsEcho() {
        return ClientPlayNetworking.canSend(HelloPayload.TYPE);
    }
}
