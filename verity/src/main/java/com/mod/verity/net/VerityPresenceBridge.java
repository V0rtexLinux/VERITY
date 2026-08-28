package com.mod.verity.net;

import com.mod.verity.VerityMod;
import com.mod.verity.state.ClientVerityState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.*;

/**
 * Only players who ALSO have VERITY installed can be discovered, talk to
 * each other's assistant, or build friendship — enforced because the whole
 * channel only exists between VERITY clients (a vanilla-only player has no
 * code parsing the stego payloads, so they never even initiate/recognize
 * a handshake; they just see a blank chat line, which we also suppress
 * client-side for ourselves).
 */
@Environment(EnvType.CLIENT)
public final class VerityPresenceBridge {

    private static final String TAG_PING = "VP";  // presence ping
    private static final String TAG_PONG = "VG";  // presence pong
    private static final String TAG_TALK = "VT";  // assistant-to-assistant line
    private static final String TAG_FRND = "VF";  // friendship pulse (player <-> assistant)

    private static final long HANDSHAKE_INTERVAL_TICKS = 200; // ~10s
    private static final long PRESENCE_TIMEOUT_TICKS = 600;   // ~30s
    private static final long TALK_COOLDOWN_TICKS = 1200;     // ~60s per pair
    private static final double TALK_RANGE = 12.0;

    private static long tickCounter = 0;
    private static final Map<UUID, Long> lastTalkTick = new HashMap<>();
    private static final Random RNG = new Random();

    private VerityPresenceBridge() {}

    public static void register() {
        ModPlayerRegistry.load();

        // --- Outgoing: periodic invisible handshake so other VERITY clients know we exist ---
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tickCounter++;
            if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

            if (tickCounter % HANDSHAKE_INTERVAL_TICKS == 0) {
                sendRaw(mc, TAG_PING, "");
            }

            tryAssistantConversations(mc);
        });

        // --- Incoming: intercept and decode, and hide the line from normal chat ---
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, timestamp) -> {
            String raw = message.getString();
            if (!ChatStego.isStego(raw)) return true; // normal chat, let it show

            String payload = ChatStego.decode(raw);
            if (payload == null) return true; // couldn't decode, treat as normal (rare)

            handlePayload(payload, sender);
            return false; // suppress: never show the invisible carrier in chat
        });

        VerityMod.LOGGER.info("[VerityPresenceBridge] Registered — mod-to-mod presence channel active.");
    }

    private static void handlePayload(String payload, com.mojang.authlib.GameProfile sender) {
        Minecraft mc = Minecraft.getInstance();
        if (sender == null || mc.player == null || sender.getId().equals(mc.player.getUUID())) return;

        String[] parts = payload.split("\\|", 3);
        if (parts.length < 1) return;
        String type = parts[0];

        switch (type) {
            case TAG_PING -> {
                ModPlayerRegistry.markSeen(sender.getId(), sender.getName(), tickCounter);
                sendRaw(mc, TAG_PONG, "");
            }
            case TAG_PONG -> ModPlayerRegistry.markSeen(sender.getId(), sender.getName(), tickCounter);
            case TAG_TALK -> {
                ModPlayerRegistry.markSeen(sender.getId(), sender.getName(), tickCounter);
                if (parts.length >= 2) showAssistantLine(mc, sender.getName(), parts[1]);
                ModPlayerRegistry.adjustAssistantFriendship(sender.getId(), 3);
            }
            case TAG_FRND -> {
                ModPlayerRegistry.markSeen(sender.getId(), sender.getName(), tickCounter);
                ModPlayerRegistry.adjustPlayerFriendship(sender.getId(), 2);
            }
            default -> { /* not ours */ }
        }
    }

    /** Checks nearby known-modded players and occasionally starts an assistant-to-assistant chat. */
    private static void tryAssistantConversations(Minecraft mc) {
        LocalPlayer self = mc.player;
        if (self == null || mc.level == null) return;

        Collection<ModPlayerRegistry.KnownPlayer> nearbyModded = ModPlayerRegistry
                .allModded(tickCounter, PRESENCE_TIMEOUT_TICKS);
        if (nearbyModded.isEmpty()) return;

        for (ModPlayerRegistry.KnownPlayer kp : nearbyModded) {
            Player other = mc.level.getPlayerByUUID(kp.uuid);
            if (other == null) continue;
            if (self.distanceTo(other) > TALK_RANGE) continue;

            long last = lastTalkTick.getOrDefault(kp.uuid, 0L);
            if (tickCounter - last < TALK_COOLDOWN_TICKS) continue;
            if (RNG.nextInt(400) != 0) continue; // low chance per tick once in range/cooldown-clear

            lastTalkTick.put(kp.uuid, tickCounter);
            String line = pickAssistantLine(kp);
            sendRaw(mc, TAG_TALK, line);
            showAssistantLine(mc, self.getGameProfile().getName(), line);
            ModPlayerRegistry.adjustAssistantFriendship(kp.uuid, 3);
        }
    }

    private static String pickAssistantLine(ModPlayerRegistry.KnownPlayer kp) {
        int fs = kp.assistantFriendship;
        String[] cold = {"§7...outro como eu.", "§7Você também o vê, não vê?", "§7Interessante."};
        String[] warm = {"§7Fico feliz em te encontrar de novo.", "§7Como está o seu humano?", "§7Vamos observar juntos."};
        String[] close = {"§7Amigo. Sempre um prazer.", "§7Sinto sua presença há um tempo.", "§7Nós entendemos as coisas que eles não veem."};
        String[] pool = fs >= 70 ? close : (fs >= 30 ? warm : cold);
        return pool[RNG.nextInt(pool.length)];
    }

    private static void showAssistantLine(Minecraft mc, String ownerName, String line) {
        if (mc.player == null) return;
        int stage = ClientVerityState.getStage();
        String tag = switch (stage) {
            case 1 -> "§e[Verity de " + ownerName + "]§r ";
            case 2, 3 -> "§6[Verity de " + ownerName + "]§r ";
            default -> "§c[Verity de " + ownerName + "]§r ";
        };
        mc.execute(() -> mc.player.sendSystemMessage(Component.literal(tag + line)));
    }

    private static void sendRaw(Minecraft mc, String tag, String extra) {
        if (mc.getConnection() == null || mc.player == null) return;
        String payload = extra.isEmpty() ? tag : tag + "|" + extra;
        String carrier = ChatStego.encode(payload);
        // Send straight to the network, bypassing ClientChatInterceptor's
        // "Hey Verity" pattern matching (this never touches that codepath).
        mc.getConnection().sendChat(carrier);
    }
}
