package com.mod.echo.event;

import com.google.gson.JsonObject;
import com.mod.echo.EchoMod;
import com.mod.echo.EchoStyle;
import com.mod.echo.ai.ClientToolRegistry;
import com.mod.echo.ai.EchoBrain;
import com.mod.echo.ai.LocalAI;
import com.mod.echo.ai.PersonalityEngine;
import com.mod.echo.bio.BioSignal;
import com.mod.echo.config.EchoConfig;
import com.mod.echo.memory.EchoMemory;
import com.mod.echo.net.VoiceQueryPayload;
import com.mod.echo.settings.HardwareProbe;
import com.mod.echo.settings.SettingsTuner;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Client-side entry point.
 *
 * ECHO is useful on servers that have never heard of it: the wake word is
 * caught here, before the message leaves the client, and answered locally
 * against the player's own game.
 *
 * When the server <em>does</em> have ECHO installed, the message is let through
 * untouched — the server-side handler has the full tool set (building, giving
 * items, reading the whole world) and should win.
 */
@Environment(EnvType.CLIENT)
public final class ClientChatInterceptor {

    private ClientChatInterceptor() {}

    public static void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String query = ChatHandler.stripWakeWord(message);
            if (query == null) return true;              // Not for ECHO.
            if (serverHasEcho()) return true;            // Let the server answer it properly.

            handle(query, Minecraft.getInstance());
            return false;                                // Never sent to chat.
        });
        EchoMod.LOGGER.info("Client chat trigger active — ECHO answers locally on vanilla servers.");
    }

    /** Entry point for voice input when the server does not have the mod. */
    public static void handleVoice(String query) {
        Minecraft mc = Minecraft.getInstance();
        if (serverHasEcho()) {
            VoiceQueryPayload.sendToServer(query);
            return;
        }
        handle(query, mc);
    }

    // ------------------------------------------------------------------ //
    //  Handling                                                            //
    // ------------------------------------------------------------------ //

    private static void handle(String query, Minecraft mc) {
        String trimmed = query.strip();

        if (trimmed.isEmpty()) {
            show(mc, EchoStyle.info("I'm here. Say " + EchoStyle.value("echo help")
                    + " to see what I can do."));
            return;
        }

        String direct = quickCommand(trimmed, mc);
        if (direct != null) {
            show(mc, EchoStyle.block(EchoStyle.TEXT + direct));
            return;
        }

        show(mc, EchoStyle.hint("thinking..."));

        EchoBrain.respond(trimmed, new ClientBridge())
                .thenAccept(answer -> mc.execute(() ->
                        show(mc, EchoStyle.block(EchoStyle.TEXT + answer))))
                .exceptionally(error -> {
                    EchoMod.LOGGER.warn("Client-side answer failed: {}", error.toString());
                    mc.execute(() -> show(mc, EchoStyle.error(
                            "Something went wrong. Is your local model still running?")));
                    return null;
                });
    }

    private static String quickCommand(String query, Minecraft mc) {
        String q = query.toLowerCase(Locale.ROOT).strip();

        if (q.equals("help") || q.equals("ajuda") || q.equals("?")) {
            return """
                   I'm ECHO, running locally on your machine. Talk to me in chat:
                     "hey echo, my game is lagging"     -> I retune your video settings
                     "hey echo, what's near me?"
                     "hey echo, how do I brew fire resistance?"
                     "hey echo, remember my base is at 120 64 -300"

                   Fixed commands:
                     echo help        this message
                     echo status      which local model I'm running on
                     echo tune        retune your Minecraft settings now
                     echo preview     show the settings I would use, without applying
                     echo fps         current performance report
                     echo tools       everything I can do here
                     echo models      models installed locally
                     echo model <id>  switch to that model right now, no AI needed
                     echo config      show my configuration
                     echo set <k> <v> change one setting
                     echo reset       forget this conversation""";
        }
        if (q.equals("status") || q.equals("ai status") || q.equals("estado")) {
            return LocalAI.statusReport() + "\nClient tools: " + ClientToolRegistry.count();
        }
        if (q.equals("tune") || q.equals("optimize") || q.equals("otimizar")) {
            return SettingsTuner.tune(SettingsTuner.Goal.AUTO,
                    EchoConfig.get().settingsTunerTargetFps, true);
        }
        if (q.equals("preview") || q.equals("preview settings")) {
            return SettingsTuner.tune(SettingsTuner.Goal.AUTO,
                    EchoConfig.get().settingsTunerTargetFps, false);
        }
        if (q.equals("fps") || q.equals("performance") || q.equals("desempenho")) {
            return HardwareProbe.probe().describe();
        }
        if (q.equals("tools") || q.equals("ferramentas")) {
            return ClientToolRegistry.count() + " client-side tools are available. "
                 + "Ask me anything and I'll pick the right one.";
        }
        if (q.equals("models") || q.equals("modelos")) {
            List<String> models = LocalAI.listModels().join();
            return models.isEmpty()
                    ? "No models installed.\n" + LocalAI.setupHelp()
                    : "Installed models:\n  " + String.join("\n  ", models)
                      + "\nCurrently using: " + LocalAI.getModel();
        }
        if (q.startsWith("model ") || q.startsWith("modelo ")) {
            String modelId = q.substring(q.indexOf(' ') + 1).strip();
            return modelId.isEmpty()
                    ? "Give me a model name, e.g. \"echo model llama3.1\". Say \"echo models\" to list them."
                    : LocalAI.setModel(modelId);
        }
        if (q.equals("config") || q.equals("settings")) {
            return "Configuration (config/echo.json):\n" + EchoConfig.get().summary();
        }
        if (q.equals("personality") || q.equals("personalidade")) {
            return "Personality:\n" + PersonalityEngine.summary();
        }
        if (q.equals("reset")) {
            EchoBrain.clear(conversationKey(mc));
            return "Cleared our conversation. Long-term memory is untouched.";
        }
        if (q.startsWith("set ")) {
            String[] parts = q.substring(4).split("[= ]", 2);
            if (parts.length == 2) return EchoConfig.get().applyEdit(parts[0], parts[1].strip());
        }
        if (q.equals("where did i die") || q.equals("last death") || q.equals("onde morri")) {
            var death = EchoMemory.lastDeath(conversationKey(mc));
            return death == null
                    ? "I have no death on record for you yet."
                    : "You died at (" + death.x + ", " + death.y + ", " + death.z + ") in " + death.dimension + ".";
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    //  Bridge into the conversation loop                                   //
    // ------------------------------------------------------------------ //

    private record ClientBridge() implements EchoBrain.ToolBridge {

        @Override public String conversationKey() {
            return conversationKeyStatic();
        }

        @Override public String worldContext() {
            Minecraft mc = Minecraft.getInstance();
            StringBuilder sb = new StringBuilder();
            sb.append("Running on the player's own client");
            if (mc.getCurrentServer() != null && !mc.isLocalServer()) {
                sb.append(", connected to ").append(mc.getCurrentServer().ip)
                  .append(" (a server without ECHO installed, so you can read this player's "
                        + "game and change their settings, but you cannot change the world)");
            } else {
                sb.append(" in singleplayer");
            }
            sb.append(".\n").append(HardwareProbe.probe().describe()).append('\n');

            if (EchoConfig.get().bioSignalEnabled) {
                BioSignal.Reading bio = BioSignal.read();
                if (bio.connected()) sb.append(BioSignal.describe()).append('\n');
            }

            if (mc.player != null && mc.level != null) {
                var pos = mc.player.blockPosition();
                sb.append("Player at (").append(pos.getX()).append(", ").append(pos.getY())
                  .append(", ").append(pos.getZ()).append(") in ")
                  .append(mc.level.dimension().identifier().getPath()).append('.');
            }
            return sb.toString();
        }

        @Override public List<JsonObject> toolSchemas() { return ClientToolRegistry.schemas(); }

        @Override public String runTool(String name, JsonObject arguments) {
            return ClientToolRegistry.execute(name, arguments);
        }
    }

    private static String conversationKeyStatic() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getUUID().toString() : "client";
    }

    private static String conversationKey(Minecraft mc) {
        return mc.player != null ? mc.player.getUUID().toString() : "client";
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    /**
     * True when the connected server has ECHO installed.
     *
     * Fabric tells us which custom payloads the server declared, which is a
     * reliable handshake without needing a ping of our own.
     */
    public static boolean serverHasEcho() {
        try {
            return ClientPlayNetworking.canSend(VoiceQueryPayload.TYPE);
        } catch (Exception e) {
            return false;   // Not connected yet, or a vanilla server.
        }
    }

    private static void show(Minecraft mc, String text) {
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text));
        });
    }
}
