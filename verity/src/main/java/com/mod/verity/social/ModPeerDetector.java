package com.mod.verity.social;

import com.mod.verity.VerityMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detecta OUTROS jogadores que também têm o mod VERITY instalado,
 * mesmo em servidores "cegos" (vanilla / sem o mod no servidor).
 *
 * Funciona enviando/recebendo mensagens de chat disfarçadas com um
 * marcador invisível (caractere de formatação §k não-renderizado +
 * um payload codificado). O servidor apenas retransmite o texto do
 * chat normalmente — não precisa entender nada.
 *
 * Jogadores SEM o mod veem essas mensagens como chat vazio/estranho
 * (por isso usamos um prefixo praticamente ilegível e removemos a
 * mensagem da tela local via filtro de recebimento).
 */
@Environment(EnvType.CLIENT)
public class ModPeerDetector {

    // Marcador invisível: código de cor que não imprime nada visível,
    // seguido de um token fixo que identifica o protocolo VERITY.
    private static final String MARKER = "\u00A70\u00A7k\u00A7r\u00A7VRTY\u00A7r";

    private static final Pattern HANDSHAKE_PING = Pattern.compile(
        Pattern.quote(MARKER) + "PING:([0-9a-fA-F-]{36})");
    private static final Pattern HANDSHAKE_PONG = Pattern.compile(
        Pattern.quote(MARKER) + "PONG:([0-9a-fA-F-]{36})");
    private static final Pattern ASSISTANT_MSG = Pattern.compile(
        Pattern.quote(MARKER) + "TALK:([0-9a-fA-F-]{36}):(.*)");

    /** Jogadores confirmados como tendo o mod, com timestamp da última confirmação. */
    private static final Map<UUID, Long> confirmedPeers = new ConcurrentHashMap<>();

    private static int pingCooldown = 0;
    private static final int PING_INTERVAL_TICKS = 200; // a cada 10s reforça presença

    public static void register() {
        // Filtra e processa mensagens recebidas ANTES de chegarem ao HUD do chat
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String raw = message.getString();
            if (!raw.contains(MARKER)) return true; // mensagem normal, deixa passar

            handleIncoming(raw);
            return false; // nunca mostra o handshake bruto no chat do jogador
        });

        // Envia um "ping" periódico pra anunciar presença
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.getConnection() == null) return;
            if (pingCooldown-- > 0) return;
            pingCooldown = PING_INTERVAL_TICKS;
            sendRaw("PING:" + client.player.getUUID());
        });

        VerityMod.LOGGER.info("[ModPeerDetector] Handshake registrado — detecção de peers com VERITY ativa.");
    }

    private static void handleIncoming(String raw) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Matcher ping = HANDSHAKE_PING.matcher(raw);
        if (ping.find()) {
            UUID senderId = UUID.fromString(ping.group(1));
            if (senderId.equals(mc.player.getUUID())) return; // ignora o próprio eco
            markPeer(senderId);
            sendRaw("PONG:" + senderId); // responde diretamente confirmando presença
            return;
        }

        Matcher pong = HANDSHAKE_PONG.matcher(raw);
        if (pong.find()) {
            UUID senderId = UUID.fromString(pong.group(1));
            // PONG endereçado a alguém: se veio de outro jogador (não eu), quem respondeu também tem o mod
            markPeerFromContext(senderId);
            return;
        }

        Matcher talk = ASSISTANT_MSG.matcher(raw);
        if (talk.find()) {
            UUID fromEntity = UUID.fromString(talk.group(1));
            String text = talk.group(2);
            AssistantSocialManager.onRemoteAssistantMessage(fromEntity, text);
        }
    }

    /** Quando recebemos um PONG, quem enviou obviamente tem o mod — marcamos como peer. */
    private static void markPeerFromContext(UUID targetOfPong) {
        // O PONG confirma que O REMETENTE tem o mod. Como o protocolo de chat não expõe
        // o remetente de forma limpa em todos os servidores, tratamos qualquer PONG válido
        // recebido como prova de que existe pelo menos um peer ativo por perto.
        confirmedPeers.put(targetOfPong, System.currentTimeMillis());
    }

    private static void markPeer(UUID id) {
        confirmedPeers.put(id, System.currentTimeMillis());
    }

    public static boolean hasMod(UUID playerId) {
        Long last = confirmedPeers.get(playerId);
        if (last == null) return false;
        // Peer expira após 60s sem novo ping/pong (saiu de perto ou desconectou)
        if (System.currentTimeMillis() - last > 60_000) {
            confirmedPeers.remove(playerId);
            return false;
        }
        return true;
    }

    public static void sendAssistantTalk(UUID assistantEntityId, String text) {
        sendRaw("TALK:" + assistantEntityId + ":" + text);
    }

    private static void sendRaw(String payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        // Envia como mensagem de chat normal — qualquer servidor retransmite isso.
        // O marcador com §k a torna ilegível/invisível pra quem não tem o mod.
        mc.getConnection().sendChat(MARKER + payload);
    }
}
