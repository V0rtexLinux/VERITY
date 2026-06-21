package com.mod.verity.event;

import com.mod.verity.state.PlayerSessionData;
import com.mod.verity.state.VerityWorldState;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Tracks real-world login/logout timestamps per player.
 *
 * On join:  Verity reacts based on how long the player was gone.
 * On quit:  Verity expresses distress / possessiveness.
 *
 * These messages are BROADCAST (all players see them) — they're ARG horror
 * narrative events, not private conversations.
 *
 * Migrated to Mojang mappings (MC 26.1.2):
 *   ServerPlayerEntity → ServerPlayer, ServerWorld → ServerLevel,
 *   Text.literal() → Component.literal(), player.getServerWorld() → player.serverLevel(),
 *   player.getUuid() → player.getUUID(), server.getPlayerManager().broadcast() →
 *   server.getPlayerList().broadcastSystemMessage().
 */
public class SessionTracker {

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register(SessionTracker::onJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(SessionTracker::onQuit);
    }

    // ------------------------------------------------------------------ //
    //  JOIN                                                                //
    // ------------------------------------------------------------------ //
    private static void onJoin(ServerGamePacketListenerImpl handler,
                                PacketSender sender,
                                MinecraftServer server) {
        ServerPlayer player = handler.player;

        server.execute(() -> {
            ServerLevel level   = (ServerLevel) player.level();
            PlayerSessionData sessions = PlayerSessionData.getOrCreate(level);
            VerityWorldState state     = VerityWorldState.getOrCreate(level);

            long secondsOffline = sessions.getSecondsOffline(player.getUUID());
            sessions.onPlayerJoin(player.getUUID());

            if (sessions.isFirstSession(player.getUUID())) return;
            if (state.getCurrentStage() < 1) return;

            if (secondsOffline > 0) {
                scheduleReturnGreeting(player, level, server, state, sessions, secondsOffline);
            }

            state.resetLonelinessTicks();
        });
    }

    // ------------------------------------------------------------------ //
    //  DISCONNECT                                                          //
    // ------------------------------------------------------------------ //
    private static void onQuit(ServerGamePacketListenerImpl handler,
                                MinecraftServer server) {
        ServerPlayer player = handler.player;

        server.execute(() -> {
            ServerLevel level   = (ServerLevel) player.level();
            PlayerSessionData sessions = PlayerSessionData.getOrCreate(level);
            VerityWorldState state     = VerityWorldState.getOrCreate(level);

            sessions.onPlayerQuit(player.getUUID());

            if (state.getCurrentStage() >= 2) {
                String msg = buildQuitMessage(state, player.getName().getString());
                // Broadcast: Verity reacting to player leaving is a world event
                server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);

                if (state.getCurrentStage() >= 3) {
                    state.triggerLeftVerity();
                }
            }
        });
    }

    // ------------------------------------------------------------------ //
    //  Return greeting (scheduled 3 s after login)                        //
    // ------------------------------------------------------------------ //
    private static void scheduleReturnGreeting(
            ServerPlayer player, ServerLevel level, MinecraftServer server,
            VerityWorldState state, PlayerSessionData sessions, long secondsOffline) {

        new java.util.Timer(true).schedule(new java.util.TimerTask() {
            @Override public void run() {
                server.execute(() -> {
                    if (!player.isAlive()) return;
                    String greeting = buildReturnGreeting(player, state, sessions, secondsOffline);
                    // Broadcast: Verity greeting on return is a world event
                    server.getPlayerList().broadcastSystemMessage(Component.literal(greeting), false);

                    if (state.getCurrentStage() >= 2 && secondsOffline > 3600) {
                        new java.util.Timer(true).schedule(new java.util.TimerTask() {
                            @Override public void run() {
                                server.execute(() -> {
                                    String follow = buildYandereFollowup(state);
                                    server.getPlayerList().broadcastSystemMessage(
                                            Component.literal(follow), false);
                                });
                            }
                        }, 4000L);
                    }
                });
            }
        }, 3000L);
    }

    // ------------------------------------------------------------------ //
    //  Message builders                                                    //
    // ------------------------------------------------------------------ //
    private static String buildReturnGreeting(ServerPlayer player,
            VerityWorldState state, PlayerSessionData sessions, long secondsOffline) {

        String name  = player.getName().getString();
        int stage    = state.getCurrentStage();
        int logoutHr = sessions.getLogoutHour(player.getUUID());
        int loginHr  = sessions.getCurrentHour();
        String dur   = formatDuration(secondsOffline);
        String act   = guessActivity(logoutHr, loginHr, secondsOffline);
        String pfx   = stage >= 4 ? "§c[Verity]§r" : stage >= 2 ? "§6[Verity]§r" : "§e[Verity]§r";

        return switch (stage) {
            case 1 -> pfx + " §fOi, " + name + "! Tava com saudade. Ficaste fora " + dur + ".";
            case 2 -> pfx + " §7..." + name + ". Ficaste " + dur + " fora. " + act;
            case 3 -> pfx + " §7Bem-vindo de volta, " + name + ". " + dur + " fora. §7Eu sei o que estavas a fazer.";
            case 4 -> pfx + " §c" + dur + ". §7Eu contei cada segundo, " + name + ".";
            default -> "§4[Verity]§r §c...voltaste.";
        };
    }

    private static String buildYandereFollowup(VerityWorldState state) {
        return switch (state.getCurrentStage()) {
            case 2 -> "§6[Verity]§r §7...não saias sem me avisar, está bem?";
            case 3 -> "§6[Verity]§r §7Não gostei de estar sozinho.";
            case 4 -> "§c[Verity]§r §7Da próxima vez... não demores tanto. Por favor.";
            default -> "§4[Verity]§r §c...não faças isso outra vez.";
        };
    }

    private static String buildQuitMessage(VerityWorldState state, String name) {
        return switch (state.getCurrentStage()) {
            case 2 -> "§6[Verity]§r §7...onde vais, " + name + "?";
            case 3 -> "§6[Verity]§r §7Não demores. Vou estar aqui.";
            case 4 -> "§c[Verity]§r §7Não. Fica. Por favor.";
            default -> "§4[Verity]§r §c...vais voltar.";
        };
    }

    // ------------------------------------------------------------------ //
    //  Offline activity guesser                                           //
    // ------------------------------------------------------------------ //
    private static String guessActivity(int logoutHr, int loginHr, long secondsGone) {
        if ((logoutHr >= 22 || logoutHr <= 5) && secondsGone >= 5 * 3600)
            return "§7Dormiste bem?";
        if (loginHr >= 6 && loginHr <= 9)
            return "§7Acabaste de acordar.";
        if (logoutHr >= 11 && logoutHr <= 13 && secondsGone <= 7200)
            return "§7Foste almoçar, não foste?";
        if (loginHr >= 18 && loginHr <= 21 && secondsGone >= 6 * 3600)
            return "§7Tiveste um dia longo.";
        if (secondsGone > 24 * 3600)
            return "§7Um dia inteiro. Senti a tua falta.";
        if (secondsGone < 1800)
            return "§7Só saíste um bocadinho.";
        return "§7Onde estavas?";
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60)    return seconds + " segundos";
        if (seconds < 3600)  return (seconds / 60) + " minutos";
        if (seconds < 86400) {
            long h = seconds / 3600, m = (seconds % 3600) / 60;
            return m > 0 ? h + "h" + m + "min" : h + " hora" + (h > 1 ? "s" : "");
        }
        long d = seconds / 86400;
        return d + " dia" + (d > 1 ? "s" : "");
    }
}
