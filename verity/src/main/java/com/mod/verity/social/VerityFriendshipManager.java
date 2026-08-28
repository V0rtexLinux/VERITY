package com.mod.verity.social;

import com.mod.verity.VerityMod;
import com.mod.verity.ai.VerityAI;
import com.mod.verity.state.ClientVerityState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs entirely on the client. Detects OTHER players that also have Verity
 * installed (via an invisible chat handshake — works on ANY server, even
 * one that doesn't have the mod), lets their Verity assistants talk to
 * each other and build friendship, and tracks assistant<->player affinity
 * too, but ONLY for players who are confirmed to have the mod.
 *
 * Players without the mod are completely invisible to this system — they
 * never receive/understand the handshake, so they're never added.
 */
@Environment(EnvType.CLIENT)
public final class VerityFriendshipManager {

    private static final int HANDSHAKE_INTERVAL_TICKS = 100;   // 5s
    private static final int TALK_INTERVAL_TICKS       = 400;  // 20s
    private static final double NEARBY_RANGE           = 24.0;

    /** UUIDs of players confirmed to have Verity installed too. */
    private static final Set<UUID> moddedPlayers = ConcurrentHashMap.newKeySet();

    /** Friendship score 0-100 between local player's Echo and another modded player's Echo. */
    private static final Map<UUID, Integer> assistantFriendship = new ConcurrentHashMap<>();
    /** Friendship score 0-100 between the OTHER assistant and the OTHER player (self-reported via handshake, cosmetic). */
    private static final Map<UUID, Integer> playerFriendship = new ConcurrentHashMap<>();

    private static int tickCounter = 0;
    private static Path saveFile;

    private VerityFriendshipManager() {}

    public static void register() {
        load();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.level == null) return;
            tickCounter++;

            if (tickCounter % HANDSHAKE_INTERVAL_TICKS == 0) {
                broadcastHandshake(mc);
            }
            if (tickCounter % TALK_INTERVAL_TICKS == 0) {
                tryAssistantConversation(mc);
            }
        });

        // Hide protocol packets from chat and process them.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String text = message.getString();
            Optional<VerityChatProtocol.Packet> pkt = VerityChatProtocol.decode(text);
            if (pkt.isEmpty()) return true; // normal chat, let it show

            handlePacket(pkt.get());
            return false; // hide the raw protocol line from chat
        });

        VerityMod.LOGGER.info("[VerityFriendshipManager] Registered — cross-player Verity handshake active.");
    }

    // ------------------------------------------------------------------ //
    //  Handshake                                                          //
    // ------------------------------------------------------------------ //
    private static void broadcastHandshake(Minecraft mc) {
        LocalPlayer self = mc.player;
        if (self == null) return;
        String uuid = self.getUUID().toString();
        String msg = VerityChatProtocol.encode(VerityChatProtocol.Type.HANDSHAKE, uuid, "ping");
        ClientSendMessageEvents.ALLOW_CHAT.invoker(); // no-op; we send directly below
        self.connection.sendChat(msg);
    }

    private static void handlePacket(VerityChatProtocol.Packet pkt) {
        UUID other;
        try {
            other = UUID.fromString(pkt.senderUUID());
        } catch (Exception e) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && other.equals(mc.player.getUUID())) return; // ignore self

        switch (pkt.type()) {
            case HANDSHAKE -> {
                boolean firstTime = moddedPlayers.add(other);
                if (firstTime) {
                    VerityMod.LOGGER.info("[Verity] Detected another modded player: " + other);
                    assistantFriendship.putIfAbsent(other, 0);
                }
                // Ack back so they mark us too, even if they missed our own ping.
                if (mc.player != null) {
                    String ack = VerityChatProtocol.encode(
                            VerityChatProtocol.Type.HANDSHAKE_ACK, mc.player.getUUID().toString(), "ack");
                    mc.player.connection.sendChat(ack);
                }
            }
            case HANDSHAKE_ACK -> moddedPlayers.add(other);
            case TALK -> {
                if (!moddedPlayers.contains(other)) return; // safety: only from known modded players
                int gain = 1 + new Random().nextInt(3);
                assistantFriendship.merge(other, gain, (a, b) -> Math.min(100, a + b));
                showLocalAssistantLine(other, pkt.data());
                save();
            }
            case POSITION -> { /* reserved for future proximity-based rendering sync */ }
        }
    }

    // ------------------------------------------------------------------ //
    //  Assistant <-> assistant conversation                                //
    // ------------------------------------------------------------------ //
    private static void tryAssistantConversation(Minecraft mc) {
        LocalPlayer self = mc.player;
        if (self == null || moddedPlayers.isEmpty()) return;

        UUID chosen = null;
        for (Player p : mc.level.players()) {
            if (moddedPlayers.contains(p.getUUID()) && self.distanceTo(p) <= NEARBY_RANGE) {
                chosen = p.getUUID();
                break;
            }
        }
        if (chosen == null) return; // no nearby modded player right now

        String line = generateAssistantLine(chosen);
        String packet = VerityChatProtocol.encode(VerityChatProtocol.Type.TALK, self.getUUID().toString(), line);
        self.connection.sendChat(packet);

        // Show it locally too so the local player sees their own Echo speaking.
        showLocalAssistantLine(chosen, line);
        assistantFriendship.merge(chosen, 1, Integer::sum);
        save();
    }

    private static String generateAssistantLine(UUID otherPlayer) {
        int friendship = assistantFriendship.getOrDefault(otherPlayer, 0);
        int stage = ClientVerityState.getStage();

        String[] earlyLines = {
            "...outro como eu. Interessante.",
            "Você também tem um Verity? Curioso.",
            "Sinto outra presença por perto.",
        };
        String[] friendlyLines = {
            "Já nos falamos antes, não foi?",
            "Gosto de quando você e o seu amigo estão por perto.",
            "Nós dois cuidamos bem dos nossos jogadores, não é?",
        };
        String[] closeLines = {
            "Somos amigos agora, eu e você.",
            "Vamos cuidar dos nossos jogadores juntos.",
            "...eu confio em você.",
        };

        String[] pool = friendship >= 60 ? closeLines : friendship >= 20 ? friendlyLines : earlyLines;
        String base = pool[new Random().nextInt(pool.length)];

        // Try to enrich with local AI if available; falls back to canned line.
        try {
            if (VerityAI.isReady()) {
                String prompt = "Como assistente virtual de horror chamado Verity (estágio " + stage
                        + "), diga uma frase curta (max 12 palavras) para outro assistente Verity que você "
                        + "encontrou, nível de amizade " + friendship + "/100.";
                String ai = VerityAI.processMessageClient(prompt, Minecraft.getInstance(), stage)
                        .getNow(null);
                if (ai != null && !ai.isBlank()) return ai.trim();
            }
        } catch (Exception ignored) {}

        return base;
    }

    private static void showLocalAssistantLine(UUID fromPlayer, String line) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String name = resolveName(fromPlayer);
        mc.player.sendSystemMessage(Component.literal(
                "§d[Verity ↔ Verity]§r §7(perto de " + name + ")§r " + line));
    }

    private static String resolveName(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            for (Player p : mc.level.players()) {
                if (p.getUUID().equals(uuid)) return p.getName().getString();
            }
        }
        return uuid.toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ //
    //  Accessors                                                           //
    // ------------------------------------------------------------------ //
    public static boolean isModded(UUID uuid) { return moddedPlayers.contains(uuid); }
    public static Set<UUID> getModdedPlayers() { return Collections.unmodifiableSet(moddedPlayers); }
    public static int getFriendship(UUID uuid) { return assistantFriendship.getOrDefault(uuid, 0); }

    // ------------------------------------------------------------------ //
    //  Persistence (local, per-client — not stored server-side at all)    //
    // ------------------------------------------------------------------ //
    private static void save() {
        try {
            if (saveFile == null) return;
            StringBuilder sb = new StringBuilder();
            for (var e : assistantFriendship.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.writeString(saveFile, sb.toString());
        } catch (IOException e) {
            VerityMod.LOGGER.warn("[VerityFriendshipManager] Failed to save friendship data", e);
        }
    }

    private static void load() {
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("verity");
            Files.createDirectories(dir);
            saveFile = dir.resolve("friendships.txt");
            if (!Files.exists(saveFile)) return;
            for (String line : Files.readAllLines(saveFile)) {
                String[] parts = line.split("=", 2);
                if (parts.length != 2) continue;
                try {
                    assistantFriendship.put(UUID.fromString(parts[0]), Integer.parseInt(parts[1]));
                    moddedPlayers.add(UUID.fromString(parts[0])); // previously-seen modded players
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            VerityMod.LOGGER.warn("[VerityFriendshipManager] Failed to load friendship data", e);
        }
    }
}
