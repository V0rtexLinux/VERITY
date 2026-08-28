package com.mod.echo.event;

import com.google.gson.JsonObject;
import com.mod.echo.EchoMod;
import com.mod.echo.EchoStyle;
import com.mod.echo.ai.EchoBrain;
import com.mod.echo.ai.LocalAI;
import com.mod.echo.ai.PersonalityEngine;
import com.mod.echo.ai.ToolRegistry;
import com.mod.echo.assistant.WorldScanner;
import com.mod.echo.config.EchoConfig;
import com.mod.echo.memory.EchoMemory;
import com.mod.echo.net.SettingsRequestPayload;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.List;
import java.util.Locale;

/**
 * Server-side entry point: turns "hey echo ..." in chat into an answer.
 *
 * Replies are always private — {@code player.sendSystemMessage} goes to the one
 * player who asked, so a conversation with ECHO never floods a shared server's
 * chat. The triggering message itself is swallowed for the same reason.
 */
public final class ChatHandler {

    private ChatHandler() {}

    public static void register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ChatHandler::onChat);
        EchoMod.LOGGER.info("Chat trigger active — say '{}' in chat.",
                EchoConfig.get().wakeWordList()[0]);
    }

    private static boolean onChat(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound bound) {
        String content = message.signedBody().content().trim();
        String query = stripWakeWord(content);
        if (query == null) return true;   // Not addressed to ECHO — leave it alone.

        ServerLevel level = sender.level();
        MinecraftServer server = level.getServer();
        if (server == null) return true;

        server.execute(() -> handleQuery(query, sender, level, server));
        return false;                     // Swallow it; the reply is private.
    }

    /**
     * Answer one question. Safe to call from chat, from voice, or from a command.
     */
    public static void handleQuery(String query, ServerPlayer player,
                                   ServerLevel level, MinecraftServer server) {
        String trimmed = query == null ? "" : query.strip();

        if (trimmed.isEmpty()) {
            reply(player, EchoStyle.info("I'm here. Ask me anything, or say "
                    + EchoStyle.value("echo help") + " to see what I can do."));
            return;
        }

        // Fixed commands answer instantly and never involve the model.
        String direct = quickCommand(trimmed, player, server);
        if (direct != null) {
            reply(player, EchoStyle.block(EchoStyle.TEXT + direct));
            return;
        }

        acknowledge(player, level);

        ToolRegistry.Context context = new ToolRegistry.Context(player, server);
        EchoBrain.respond(trimmed, new ServerBridge(context))
                .thenAccept(answer -> server.execute(() ->
                        reply(player, EchoStyle.block(EchoStyle.TEXT + answer))))
                .exceptionally(error -> {
                    EchoMod.LOGGER.warn("Failed to answer '{}': {}", trimmed, error.toString());
                    server.execute(() -> reply(player, EchoStyle.error(
                            "Something went wrong answering that. Check the log for details.")));
                    return null;
                });
    }

    // ------------------------------------------------------------------ //
    //  Direct commands                                                     //
    // ------------------------------------------------------------------ //

    /**
     * Handle the small set of phrases that should never cost a model round-trip.
     *
     * @return the reply, or {@code null} when the question belongs to the model
     */
    private static String quickCommand(String query, ServerPlayer player, MinecraftServer server) {
        String q = query.toLowerCase(Locale.ROOT).strip();

        if (q.equals("help") || q.equals("ajuda") || q.equals("?")) {
            return helpText();
        }
        if (q.equals("status") || q.equals("ai status") || q.equals("estado")) {
            return LocalAI.statusReport()
                    + "\nTools: " + ToolRegistry.count()
                    + "\nConversations open: " + EchoBrain.activeConversations();
        }
        if (q.equals("config") || q.equals("settings") || q.equals("configuracao")) {
            return "Configuration (config/echo.json):\n" + EchoConfig.get().summary();
        }
        if (q.equals("personality") || q.equals("personalidade")) {
            return "Personality:\n" + PersonalityEngine.summary();
        }
        if (q.equals("reset") || q.equals("forget this conversation")) {
            EchoBrain.clear(player.getUUID().toString());
            return "Cleared our conversation. Long-term memory is untouched.";
        }
        if (q.equals("tools") || q.equals("ferramentas")) {
            List<String> names = ToolRegistry.names();
            return names.size() + " tools available:\n  " + String.join(", ", names);
        }
        if (q.equals("models") || q.equals("modelos")) {
            List<String> models = LocalAI.listModels().join();
            return models.isEmpty()
                    ? "The local backend reports no installed models.\n" + LocalAI.setupHelp()
                    : "Installed models:\n  " + String.join("\n  ", models)
                      + "\nCurrently using: " + LocalAI.getModel();
        }
        if (q.equals("tune") || q.equals("optimize") || q.equals("otimizar")) {
            SettingsRequestPayload.sendToPlayer(player, "auto",
                    EchoConfig.get().settingsTunerTargetFps, true);
            return "Asking your client to tune its settings...";
        }
        if (q.startsWith("set ")) {
            String[] parts = q.substring(4).split("[= ]", 2);
            if (parts.length == 2) return EchoConfig.get().applyEdit(parts[0], parts[1].strip());
        }
        if (q.equals("where did i die") || q.equals("last death") || q.equals("onde morri")) {
            var death = EchoMemory.lastDeath(player.getUUID().toString());
            return death == null
                    ? "I have no death on record for you yet."
                    : "You died at (" + death.x + ", " + death.y + ", " + death.z + ") in " + death.dimension + ".";
        }
        if (q.equals("waypoints")) {
            var list = EchoMemory.waypoints(player.getUUID().toString());
            return list.isEmpty() ? "No waypoints saved yet."
                    : "Waypoints:\n  " + list.stream().map(Object::toString)
                        .reduce((a, b) -> a + "\n  " + b).orElse("");
        }
        return null;
    }

    private static String helpText() {
        return """
               I'm ECHO, your in-world assistant. Just talk to me:
                 "hey echo, where's the nearest diamond?"
                 "hey echo, the game is lagging"        -> I retune your video settings
                 "hey echo, build me a stone room"
                 "hey echo, remember my base is at 120 64 -300"
                 "hey echo, what should I enchant this pickaxe with?"

               Fixed commands (no model needed):
                 echo help          this message
                 echo status        which local model I'm running on
                 echo tools         everything I can do
                 echo models        models installed locally
                 echo tune          retune your Minecraft settings now
                 echo config        show my configuration
                 echo set <k> <v>   change one setting
                 echo personality   how I'm currently talking
                 echo waypoints     your saved places
                 echo reset         forget this conversation""";
    }

    // ------------------------------------------------------------------ //
    //  Bridge into the conversation loop                                   //
    // ------------------------------------------------------------------ //

    /** Wires the server tool registry and live world state into {@link EchoBrain}. */
    private record ServerBridge(ToolRegistry.Context context) implements EchoBrain.ToolBridge {

        @Override public String conversationKey() { return context.playerId(); }

        @Override public String worldContext() {
            StringBuilder sb = new StringBuilder();
            sb.append("Player: ").append(context.playerName()).append('\n');
            sb.append(WorldScanner.worldSnapshot(context.player(), context.server())).append('\n');
            sb.append(WorldScanner.timeUntilNight(context.level()));
            return sb.toString();
        }

        @Override public List<JsonObject> toolSchemas() { return ToolRegistry.schemas(); }

        @Override public String runTool(String name, JsonObject arguments) {
            return ToolRegistry.execute(name, arguments, context);
        }
    }

    // ------------------------------------------------------------------ //
    //  Player lifecycle                                                    //
    // ------------------------------------------------------------------ //

    /** Greet a player who just joined, once their client is actually loaded. */
    public static void welcome(ServerPlayer player) {
        String wake = EchoConfig.get().wakeWordList()[0];
        reply(player, EchoStyle.info("Online. Say " + EchoStyle.value(wake + ", ...")
                + " whenever you need me — or " + EchoStyle.value(wake + " help") + " to start."));
        if (!LocalAI.isReady()) {
            reply(player, EchoStyle.hint("(My local model isn't running yet, so I can only answer "
                    + "fixed commands until it is.)"));
        }
    }

    /** Remember where a player died so they can ask for the coordinates later. */
    public static void recordDeath(ServerPlayer player) {
        var pos = player.blockPosition();
        String dimension = player.level().dimension().identifier().getPath();
        EchoMemory.recordDeath(player.getUUID().toString(), pos.getX(), pos.getY(), pos.getZ(), dimension);
        reply(player, EchoStyle.info("Noted where you fell: "
                + EchoStyle.coords(pos.getX(), pos.getY(), pos.getZ())
                + ". Ask me " + EchoStyle.value("where did I die") + " when you're ready to go back."));
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    /**
     * Strip the wake word from a chat line.
     *
     * @return the rest of the message, or {@code null} if it was not for ECHO
     */
    public static String stripWakeWord(String message) {
        String lower = message.toLowerCase(Locale.ROOT).strip();
        for (String wake : EchoConfig.get().wakeWordList()) {
            if (!lower.startsWith(wake)) continue;
            String rest = message.strip().substring(wake.length());
            // "echoes" should not trigger on "echo"; require a real separator.
            if (!rest.isEmpty() && Character.isLetterOrDigit(rest.charAt(0))) continue;
            return rest.replaceFirst("^[,:!.\\s]+", "").strip();
        }
        return null;
    }

    /** A quiet chime so the player knows the question was heard while the model thinks. */
    private static void acknowledge(ServerPlayer player, ServerLevel level) {
        try {
            level.playSound(null, player.blockPosition(),
                    SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.25f, 1.6f);
        } catch (Exception e) {
            EchoMod.LOGGER.debug("Could not play the acknowledgement sound: {}", e.toString());
        }
    }

    public static void reply(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
}
