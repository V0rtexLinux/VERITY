package com.mod.verity.social;

import com.mod.verity.VerityMod;
import com.mod.verity.ai.OllamaManager;
import com.mod.verity.state.ClientVerityState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controla a interação social entre assistentes VERITY de jogadores DIFERENTES.
 *
 * Regras:
 *  - Só conversa/faz amizade com assistentes de jogadores que o
 *    {@link ModPeerDetector} confirmou terem o mod instalado.
 *  - A "conversa" é puramente local: cada cliente renderiza seu próprio
 *    assistente falando, sincronizado via chat disfarçado — ninguém sem
 *    o mod vê ou ouve nada disso.
 *  - A amizade é persistida por par de UUIDs (jogador local <-> jogador remoto).
 */
@Environment(EnvType.CLIENT)
public class AssistantSocialManager {

    private static final double CONVO_RANGE = 12.0;
    private static final int CONVO_COOLDOWN_TICKS = 600; // ~30s entre falas espontâneas

    /** amizade 0-100 por UUID de jogador remoto */
    private static final Map<UUID, Integer> friendship = new ConcurrentHashMap<>();
    private static int convoCooldown = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;
            if (convoCooldown-- > 0) return;

            AbstractClientPlayer nearestPeer = findNearestPeer(client);
            if (nearestPeer == null) return;

            convoCooldown = CONVO_COOLDOWN_TICKS;
            startExchange(nearestPeer);
        });

        VerityMod.LOGGER.info("[AssistantSocialManager] Interação social entre assistentes ativa.");
    }

    private static AbstractClientPlayer findNearestPeer(Minecraft client) {
        return client.level.players().stream()
            .filter(p -> p instanceof AbstractClientPlayer)
            .map(p -> (AbstractClientPlayer) p)
            .filter(p -> !p.getUUID().equals(client.player.getUUID()))
            .filter(p -> ModPeerDetector.hasMod(p.getUUID())) // <-- filtro central: só quem tem o mod
            .filter(p -> p.distanceTo(client.player) <= CONVO_RANGE)
            .findFirst()
            .orElse(null);
    }

    private static void startExchange(AbstractClientPlayer peer) {
        UUID peerId = peer.getUUID();
        int currentFriendship = friendship.getOrDefault(peerId, 0);

        String localName = Minecraft.getInstance().player.getGameProfile().getName();
        String peerName = peer.getGameProfile().getName();

        String prompt = buildSocialPrompt(localName, peerName, currentFriendship);

        OllamaManager.chat(
            java.util.List.of(PromptSystem_userMessage(prompt)),
            OllamaManager.getDefaultModel(),
            null
        ).thenAccept(response -> {
            String line = (response == null || response.isBlank())
                ? fallbackLine(currentFriendship, peerName)
                : response.trim();

            showLocalSpeech(line);
            ModPeerDetector.sendAssistantTalk(Minecraft.getInstance().player.getUUID(), line);
            adjustFriendship(peerId, +2);
        }).exceptionally(ex -> {
            showLocalSpeech(fallbackLine(currentFriendship, peerName));
            adjustFriendship(peerId, +1);
            return null;
        });
    }

    private static com.google.gson.JsonObject PromptSystem_userMessage(String content) {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        obj.addProperty("role", "user");
        obj.addProperty("content", content);
        return obj;
    }

    private static String buildSocialPrompt(String me, String other, int friendship) {
        String tone = friendship < 20 ? "cauteloso e curioso, como se estivesse conhecendo alguém novo"
                     : friendship < 60 ? "amigável e receptivo"
                     : "próximo, como se já fossem amigos de longa data";
        return "Você é o assistente virtual VERITY do jogador " + me +
            ". Você encontrou o assistente VERITY do jogador " + other +
            ". Nível de amizade atual: " + friendship + "/100. Tom: " + tone +
            ". Gere UMA única fala curta (máx 20 palavras) que seu assistente diria " +
            "ao encontrar o outro assistente, em português.";
    }

    private static String fallbackLine(int friendship, String otherName) {
        if (friendship < 20) return "§d[Verity]§r §7...outro como eu? Interessante.";
        if (friendship < 60) return "§d[Verity]§r §7Olá de novo. Bom te ver por aqui.";
        return "§d[Verity]§r §7Ei! Sabia que ia te encontrar de novo.";
    }

    /** Chamado pelo ModPeerDetector quando chega uma fala de um assistente remoto. */
    public static void onRemoteAssistantMessage(UUID remoteAssistantOwner, String text) {
        // Exibe a fala do assistente remoto pro jogador local (só ele vê, via HUD/chat local)
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.displayClientMessage(Component.literal(text), false);
    }

    private static void showLocalSpeech(String line) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.displayClientMessage(Component.literal(line), false);
    }

    public static void adjustFriendship(UUID peerId, int delta) {
        friendship.merge(peerId, delta, (a, b) -> Math.max(0, Math.min(100, a + b)));
    }

    public static int getFriendship(UUID peerId) {
        return friendship.getOrDefault(peerId, 0);
    }
}
