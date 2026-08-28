package com.mod.verity;

import com.mod.verity.entity.VerityModel;
import com.mod.verity.entity.VerityRenderer;
import com.mod.verity.event.ClientChatInterceptor;
import com.mod.verity.net.VerityPresenceBridge;
import com.mod.verity.state.ClientVerityState;
import com.mod.verity.voice.VoiceHudRenderer;
import com.mod.verity.voice.VoiceListener;
import com.mod.verity.voice.VoicePacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;

/**
 * Client-side mod initializer.
 *
 * Responsibilities:
 *  - Register VerityRenderer (GeckoLib entity renderer)
 *  - Load client-side Verity state (for multiplayer servers without the mod)
 *  - Register ClientChatInterceptor ("Hey Verity" → local Ollama)
 *  - Create and start VoiceListener (offline STT via Vosk)
 *  - Register VoiceHudRenderer (mic status overlay)
 *  - Stop VoiceListener cleanly on shutdown
 */
@Environment(EnvType.CLIENT)
public class VerityModClient implements ClientModInitializer {

    private static VoiceListener voiceListener;

    @Override
    public void onInitializeClient() {
        // --- GeckoLib entity renderer ---
        EntityRendererRegistry.register(VerityMod.VERITY, VerityRenderer::new);

        // --- Client-side state (works on servers without the mod) ---
        ClientVerityState.load();

        // --- Chat interceptor: "Hey Verity ..." → local Ollama AI ---
        ClientChatInterceptor.register();

        // --- Mod-to-mod presence: only players who ALSO have VERITY can be
        // detected, talk assistant-to-assistant, and build friendship.
        // Works on any server (even fully vanilla) via invisible chat text. ---
        VerityPresenceBridge.register();

        // --- Voice listener ---
        voiceListener = new VoiceListener(query -> {
            // If connected to a server WITH the mod: send via network packet
            if (Minecraft.getInstance().getConnection() != null) {
                VoicePacket.sendToServer(query);
            }
            // Note: ClientChatInterceptor handles the client-side path.
            // The voice listener only sends to server; client-side AI goes through
            // the chat interceptor (text only for now).
        });

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            VerityMod.LOGGER.info("[Verity] Starting voice listener...");
            voiceListener.start();
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (voiceListener != null && voiceListener.isRunning()) {
                VerityMod.LOGGER.info("[Verity] Stopping voice listener...");
                voiceListener.stop();
            }
        });

        // --- HUD overlay ---
        VoiceHudRenderer.register(voiceListener);

        VerityMod.LOGGER.info("[Verity] Client initialized. Say 'Hey Verity' to wake him up.");
    }

    public static VoiceListener getVoiceListener() {
        return voiceListener;
    }
}
