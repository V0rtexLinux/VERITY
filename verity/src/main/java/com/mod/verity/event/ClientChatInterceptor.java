package com.mod.verity.event;

import com.mod.verity.VerityMod;
import com.mod.verity.ai.VerityAI;
import com.mod.verity.ai.PromptSystem;
import com.mod.verity.state.ClientVerityState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts "Hey Verity ..." chat messages on the CLIENT before they are
 * sent to the server.  This allows the mod to work on vanilla/multiplayer
 * servers that do not have Verity installed.
 *
 * The AI query is handled entirely locally via Ollama; the response is
 * shown only to the player (not broadcasted).
 */
@Environment(EnvType.CLIENT)
public class ClientChatInterceptor {

    private static final Pattern TRIGGER = Pattern.compile(
        "(?i)^(hey verity|ei verity|verity)[,!.]?\\s*(.*)");

    private static final Pattern EAST_VILLAGE = Pattern.compile(
        "(?i)(aldeia.*leste|aldeia.*este|east.*village|village.*east|village to the east)");

    private static final Pattern ANGER = Pattern.compile(
        "(?i)(go away|shut up|i hate you|cala-?te|vai embora|eu odeio)");

    private static final Pattern PRAISE = Pattern.compile(
        "(?i)(obrigado|thank|thanks|adoro|love you|amo)");

    private static final Pattern AI_STATUS = Pattern.compile(
        "(?i)(ai status|ollama status|verity ai status|check ai)");

    public static void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            Matcher m = TRIGGER.matcher(message.trim());
            if (!m.matches()) return true;

            String query = m.group(2).trim();
            Minecraft mc = Minecraft.getInstance();

            CompletableFuture.runAsync(() -> handleQuery(query, mc));
            return false;
        });

        VerityMod.LOGGER.info("[ClientChatInterceptor] Registered — Hey Verity will be handled locally.");
    }

    private static void handleQuery(String query, Minecraft mc) {
        int stage = ClientVerityState.getStage();

        if (ANGER.matcher(query).find()) {
            ClientVerityState.adjustAttachment(-10);
            String msg = switch (stage) {
                case 1 -> "§e[Verity]§r §7...está bem.";
                case 2 -> "§6[Verity]§r §7...está bem.";
                case 3 -> "§6[Verity]§r §7Eu ouvi isso.";
                case 4 -> "§c[Verity]§r §7...oh? Não me queres aqui?";
                default -> "§4[Verity]§r §cTarde demais.";
            };
            sendLocal(mc, msg);
            return;
        }

        if (query.isBlank()) {
            sendLocal(mc, getGreeting(stage));
            ClientVerityState.onPositiveInteraction();
            return;
        }

        if (EAST_VILLAGE.matcher(query).find()) {
            ClientVerityState.adjustAttachment(-5);
            String msg = switch (stage) {
                case 1 -> "§e[Verity]§r §7...a aldeia a leste? Não te preocupes com isso.";
                case 2 -> "§6[Verity]§r §7Disse para não perguntares sobre isso.";
                case 3 -> "§6[Verity]§r §cNão. Vás. Lá.";
                case 4 -> "§c[Verity]§r §4DISSE QUE NÃO PERGUNTASSES.";
                default -> "§4[Verity]§r §c...";
            };
            sendLocal(mc, msg);
            return;
        }

        if (AI_STATUS.matcher(query).find()) {
            sendLocal(mc, "§e[VerityAI]§r Ready: " + VerityAI.isReady()
                + " | Model: " + com.mod.verity.ai.OllamaManager.getDefaultModel());
            return;
        }

        if (PRAISE.matcher(query).find()) {
            ClientVerityState.adjustAttachment(5);
            String msg = switch (stage) {
                case 1 -> "§e[Verity]§r §f😊 De nada!";
                case 2 -> "§6[Verity]§r §7...que bom ouvir isso.";
                case 3 -> "§6[Verity]§r §7Sempre estarei aqui.";
                case 4 -> "§c[Verity]§r §7Eu sei que me precisas.";
                default -> "§4[Verity]§r §c...fico feliz.";
            };
            sendLocal(mc, msg);
            return;
        }

        sendLocal(mc, "§8[Verity]§r §7...");

        VerityAI.processMessageClient(query, mc, stage)
            .thenAccept(response -> {
                if (response != null && !response.isEmpty()) {
                    mc.execute(() -> sendLocal(mc, response));
                    ClientVerityState.onPositiveInteraction();
                }
            })
            .exceptionally(ex -> {
                VerityMod.LOGGER.error("[ClientChatInterceptor] AI error: " + ex.getMessage(), ex);
                mc.execute(() -> sendLocal(mc,
                    "§c[Verity]§r §7Algo correu mal. Tens o Ollama a correr? (ollama serve)"));
                return null;
            });
    }

    private static String getGreeting(int stage) {
        int attach = ClientVerityState.getAttachmentScore();
        return switch (stage) {
            case 1 -> attach >= 70
                ? "§e[Verity]§r §fOi! Senti a tua falta! O que precisas?"
                : "§e[Verity]§r §fOlá! Precisas de ajuda?";
            case 2 -> attach >= 70
                ? "§6[Verity]§r §7...estava à espera de ti."
                : "§6[Verity]§r §7...chamaste?";
            case 3 -> "§6[Verity]§r §7Sei tudo sobre este mundo. Pergunta.";
            case 4 -> "§c[Verity]§r §7Estou a ver-te.";
            default -> "§4[Verity]§r §c...";
        };
    }

    private static void sendLocal(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.execute(() -> mc.player.sendSystemMessage(Component.literal(text)));
        }
    }
}
