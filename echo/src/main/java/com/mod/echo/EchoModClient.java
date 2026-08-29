package com.mod.echo;

import com.mod.echo.entity.EchoOrbRenderer;
import com.mod.echo.event.ClientChatInterceptor;
import com.mod.echo.hosting.EchoPrivateWorld;
import com.mod.echo.hosting.EchoServerHost;
import com.mod.echo.net.SettingsRequestPayload;
import com.mod.echo.settings.SettingsTuner;
import com.mod.echo.config.EchoConfig;
import com.mod.echo.splash.BootSplash;
import com.mod.echo.voice.VoiceHudRenderer;
import com.mod.echo.voice.VoiceListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.network.chat.Component;

/**
 * Client-side setup.
 *
 * The client half carries three things the server cannot: the renderer for the
 * companion orb, the local-only answering path used on servers that do not have
 * ECHO installed, and the settings tuner, since video options exist only here.
 */
@Environment(EnvType.CLIENT)
public class EchoModClient implements ClientModInitializer {

    private static VoiceListener voiceListener;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(EchoMod.ECHO_ORB, EchoOrbRenderer::new);

        ClientChatInterceptor.register();
        SettingsRequestPayload.registerClient();

        voiceListener = new VoiceListener(ClientChatInterceptor::handleVoice);
        VoiceHudRenderer.register(voiceListener);

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            voiceListener.start();
            maybeAutoTune();
            BootSplash.maybeShow();
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (voiceListener != null) voiceListener.stop();
        });

        // The one thing "echo host" cannot do synchronously: after it triggers loading
        // (or freshly creating) echo.net, the actual IntegratedServer only exists once
        // that load finishes — this is what publishes it to LAN the moment it does,
        // with no extra command needed.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            var server = client.getSingleplayerServer();
            if (server == null || !EchoPrivateWorld.is(server) || server.isPublished()) return;
            String result = EchoServerHost.publishToLan(client, server);
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(EchoStyle.block(EchoStyle.TEXT + result)));
            }
        }));

        EchoMod.LOGGER.info("ECHO client ready.");
    }

    /**
     * Optional one-shot tune at startup, off by default so ECHO never silently
     * rewrites someone's carefully chosen settings.
     */
    private static void maybeAutoTune() {
        EchoConfig config = EchoConfig.get();
        if (!config.settingsTunerEnabled || !config.settingsTunerAutoOnJoin) return;
        try {
            String report = SettingsTuner.tune(SettingsTuner.Goal.AUTO, config.settingsTunerTargetFps, true);
            EchoMod.LOGGER.info("Startup tune:\n{}", report);
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Startup tune failed: {}", e.toString());
        }
    }

    public static VoiceListener voiceListener() {
        return voiceListener;
    }
}
