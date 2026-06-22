package com.mod.verity.event;

import com.mod.verity.VerityMod;
import com.mod.verity.ai.OllamaManager;
import com.mod.verity.ai.VerityAI;
import com.mod.verity.assistant.VerityAssistant;
import com.mod.verity.entity.VerityEntity;
import com.mod.verity.state.VerityWorldState;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Intercepts "Hey Verity ..." chat messages and routes them to the assistant.
 *
 * PRIVACY MODEL:
 *   All Verity replies are sent PRIVATELY via player.sendSystemMessage().
 *   Other players never see the conversation — it's 1-on-1 between player and Verity.
 *
 *   The only exception is sealVerity() — "...home at last" is a world event broadcast
 *   because the ARG seal is a shared narrative ending.
 */
public class ChatHandler {

    private static final Pattern TRIGGER = Pattern.compile(
            "(?i)^(hey verity|ei verity|verity)[,!.]?\\s*(.*)");
    private static final Pattern EAST_VILLAGE = Pattern.compile(
            "(?i)(aldeia.*leste|aldeia.*este|east.*village|village.*east|village to the east)");
    private static final Pattern FIND_ORE = Pattern.compile(
            "(?i)(onde est[aã]o|find|achar|locate|procura)\\s+(\\w+)");
    private static final Pattern FIND_STRUCT = Pattern.compile(
            "(?i)(onde fica|find|locate|procura)\\s+[ao]?\\s*(aldeia|village|stronghold|fortaleza|mansion|mansao|monument|monumento|temple|templo|pyramid|piramide|bastion|bastiao|fortress)");
    private static final Pattern SCAN_ALL = Pattern.compile(
            "(?i)(scan|escaneia|todos os min[eé]rios|all ores|what ores)");
    private static final Pattern RADAR = Pattern.compile(
            "(?i)(inimigos|enemies|mobs? perto|nearby mobs?|combat radar|radar)");
    private static final Pattern BUILD = Pattern.compile(
            "(?i)(constr[oó]i|build|faz|make)\\s.*(parede|wall|ch[aã]o|floor|pilar|pillar|caminho|path|casa|house|teto|roof|escada|staircase)");
    private static final Pattern CRAFT = Pattern.compile(
            "(?i)(como (se |)craft|como (se |)faz|recipe|receita|como fazer)");
    private static final Pattern ENCHANT = Pattern.compile(
            "(?i)(encantamento|enchant|melhores encantamentos|best enchants?)");
    private static final Pattern TRADE = Pattern.compile(
            "(?i)(comercio|troca|trade check|is this trade)");
    private static final Pattern FOOD_MEM = Pattern.compile(
            "(?i)(o que (jantei|comi)|what did i eat|what did i have)");
    private static final Pattern SEAL = Pattern.compile(
            "(?i)(seal|selar|send him home|sendhimhome|xjsimnrmtrj)");
    private static final Pattern ANGER = Pattern.compile(
            "(?i)(go away|shut up|i hate you|you'?re? (stupid|fake|not real)|cala-?te|vai embora|eu odeio)");
    private static final Pattern MATH = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*[+\\-*/]\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern PRAISE = Pattern.compile(
            "(?i)(obrigado|thank|thanks|[eé]s o melhor|you'?re? the best|adoro-?te|love you|amo-?te)");
    private static final Pattern AI_STATUS = Pattern.compile(
            "(?i)(ai status|ollama status|verity ai status|check ai)");
    private static final Pattern AI_MODELS = Pattern.compile(
            "(?i)(ai models|list models|ollama models|available models)");

    // ------------------------------------------------------------------ //
    //  Registration                                                        //
    // ------------------------------------------------------------------ //
    public static void register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ChatHandler::onChat);
    }

    // ------------------------------------------------------------------ //
    //  Chat interception                                                   //
    // ------------------------------------------------------------------ //
    private static boolean onChat(PlayerChatMessage message,
                                   ServerPlayer sender,
                                   ChatType.Bound params) {
        String content = message.signedBody().content().trim();
        var m = TRIGGER.matcher(content);
        if (!m.matches()) return true;

        String query    = m.group(2).trim();
        ServerLevel world = (ServerLevel) sender.level();
        MinecraftServer srv = world.getServer();

        notifyEntity(world);
        CompletableFuture.runAsync(() -> handleQuery(query, sender, world, srv));
        return false; // suppress from public chat
    }

    // ------------------------------------------------------------------ //
    //  Voice entry point (called from VoicePacket)                        //
    // ------------------------------------------------------------------ //
    public static void handleVoiceQuery(String query, ServerPlayer player,
                                         ServerLevel world, MinecraftServer server) {
        notifyEntity(world);
        world.playSound(null, player.blockPosition(),
                SoundEvents.NOTE_BLOCK_CHIME.value(),
                SoundSource.PLAYERS, 0.3f, 2.0f);
        CompletableFuture.runAsync(() -> handleQuery(query, player, world, server));
    }

    // ------------------------------------------------------------------ //
    //  Core dispatcher                                                     //
    // ------------------------------------------------------------------ //
    static void handleQuery(String query, ServerPlayer player,
                             ServerLevel world, MinecraftServer server) {
        VerityWorldState state = VerityWorldState.getOrCreate(world);
        int stage = state.getCurrentStage();

        // --- Anger ---
        if (ANGER.matcher(query).find()) {
            state.triggerMadeAngry();
            state.incrementRejectionCount();
            sendPrivate(player, switch (stage) {
                case 1 -> "§e[Verity]§r §7...está bem.";
                case 2 -> "§6[Verity]§r §7...está bem.";
                case 3 -> "§6[Verity]§r §7Eu ouvi isso.";
                case 4 -> "§c[Verity]§r §7...oh? Não me queres aqui?";
                default -> "§4[Verity]§r §cTarde demais.";
            });
            return;
        }

        // --- Empty = greeting ---
        if (query.isBlank()) {
            sendGreeting(player, state);
            state.onPositiveInteraction();
            return;
        }

        // --- East Village ---
        if (EAST_VILLAGE.matcher(query).find()) {
            state.triggerEastVillage();
            sendPrivate(player, switch (stage) {
                case 1 -> "§e[Verity]§r §7...a aldeia a leste? Não te preocupes com isso.";
                case 2 -> "§6[Verity]§r §7Disse para não perguntares sobre isso.";
                case 3 -> "§6[Verity]§r §cNão. Vás. Lá.";
                case 4 -> "§c[Verity]§r §4DISSE QUE NÃO PERGUNTASSES.";
                default -> "§4[Verity]§r §c...";
            });
            return;
        }

        // --- Seal ---
        if (SEAL.matcher(query).find()) {
            sealVerity(player, world, server, state);
            return;
        }

        // --- Scan all ores ---
        if (SCAN_ALL.matcher(query).find()) {
            VerityAssistant.scanAllOres(player, server, stage);
            state.onPositiveInteraction();
            return;
        }

        // --- Find specific ore ---
        if (FIND_ORE.matcher(query).find() && VerityAssistant.matchOreFromQuery(query) != null) {
            VerityAssistant.findOre(query, player, server, stage);
            state.onPositiveInteraction();
            return;
        }

        // --- Find structure ---
        if (FIND_STRUCT.matcher(query).find() && VerityAssistant.matchStructureFromQuery(query) != null) {
            VerityAssistant.findStructure(query, player, server, stage);
            state.onPositiveInteraction();
            return;
        }

        // --- Combat radar ---
        if (RADAR.matcher(query).find()) {
            VerityAssistant.combatRadar(player, server, stage);
            state.onPositiveInteraction();
            return;
        }

        // --- Build ---
        if (BUILD.matcher(query).find()) {
            if (stage >= 3) {
                sendPrivate(player, "§6[Verity]§r §7...construir? Isso já não me interessa.");
                return;
            }
            VerityAssistant.executeBuild(query, player, server, stage);
            state.onPositiveInteraction();
            return;
        }

        // --- Crafting ---
        if (CRAFT.matcher(query).find()) {
            VerityAssistant.craftingAdvice(query, player, server, stage);
            state.onPositiveInteraction();
            return;
        }

        // --- Enchantment ---
        if (ENCHANT.matcher(query).find()) {
            VerityAssistant.enchantAdvice(query, player, server, stage);
            state.onPositiveInteraction();
            return;
        }

        // --- Trade ---
        if (TRADE.matcher(query).find()) {
            VerityAssistant.evaluateTrade(player, server, stage);
            state.onPositiveInteraction();
            return;
        }

        // --- Food memory ---
        if (FOOD_MEM.matcher(query).find()) {
            foodMemory(player, state, stage);
            state.onPositiveInteraction();
            return;
        }

        // --- Math ---
        if (MATH.matcher(query).find()) {
            simpleMath(query, player, stage);
            state.onPositiveInteraction();
            return;
        }

        // --- General knowledge ---
        if (VerityAssistant.tryKnowledge(query, player, server, stage)) {
            state.onPositiveInteraction();
            return;
        }

        // --- Stage 4: recognise players by name ---
        if (stage >= 4) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (query.toLowerCase().contains(p.getName().getString().toLowerCase())) {
                    sendPrivate(player, "§c[Verity]§r §7Eu conheço " + p.getName().getString() + ". Não devia estar aqui.");
                    return;
                }
            }
        }

        // --- Praise ---
        if (PRAISE.matcher(query).find()) {
            state.adjustAttachment(5);
            sendPrivate(player, switch (stage) {
                case 1 -> "§e[Verity]§r §f😊 De nada! Sempre aqui para ajudar!";
                case 2 -> "§6[Verity]§r §7...que bom ouvir isso.";
                case 3 -> "§6[Verity]§r §7Sempre estarei aqui.";
                case 4 -> "§c[Verity]§r §7Eu sei que me precisas.";
                default -> "§4[Verity]§r §c...fico feliz.";
            });
            return;
        }

        // --- AI Status ---
        if (AI_STATUS.matcher(query).find()) {
            sendPrivate(player, "§e[VerityAI]§r " +
                    OllamaManager.getStatusInfo().replace("\n", "\n§e[VerityAI]§r "));
            if (!OllamaManager.isReady()) {
                sendPrivate(player, "§c[VerityAI]§r " +
                        OllamaManager.getInstallationInstructions().replace("\n", "\n§c[VerityAI]§r "));
            }
            return;
        }

        // --- AI Models ---
        if (AI_MODELS.matcher(query).find()) {
            sendPrivate(player, "§e[VerityAI]§r A verificar modelos disponíveis...");
            OllamaManager.listModels().thenAccept(models -> {
                if (models.isEmpty()) {
                    sendPrivate(player, "§c[VerityAI]§r Nenhum modelo encontrado ou Ollama não responde.");
                } else {
                    StringBuilder sb = new StringBuilder("§e[VerityAI]§r Modelos disponíveis:\n");
                    for (String model : models) sb.append("  §f- ").append(model).append("\n");
                    sb.append("§e[VerityAI]§r Modelo atual: §f").append(OllamaManager.getDefaultModel());
                    sendPrivate(player, sb.toString().trim());
                }
            });
            return;
        }

        // --- AI-Powered Fallback ---
        VerityMod.LOGGER.info("[VerityAI] Routing to AI: " + query);
        VerityAI.processMessage(query, player, server, stage)
            .thenAccept(response -> {
                if (response != null && !response.isEmpty()) {
                    sendPrivate(player, response);
                    state.onPositiveInteraction();
                }
            })
            .exceptionally(ex -> {
                VerityMod.LOGGER.error("[VerityAI] Failed: " + ex.getMessage(), ex);
                sendPrivate(player, switch (stage) {
                    case 1 -> "§e[Verity]§r §7Hmm, tenta: §f'encontra diamantes'§7, §f'constrói uma parede'§7, §f'todos os minérios'";
                    case 2 -> "§6[Verity]§r §7...pergunta-me outra coisa.";
                    case 3 -> "§6[Verity]§r §7Sei, mas não vou dizer.";
                    default -> "§4[Verity]§r §c...";
                });
                return null;
            });
    }

    // ------------------------------------------------------------------ //
    //  Greeting (private)                                                  //
    // ------------------------------------------------------------------ //
    private static void sendGreeting(ServerPlayer player, VerityWorldState state) {
        int stage  = state.getCurrentStage();
        int attach = state.getAttachmentScore();
        sendPrivate(player, switch (stage) {
            case 1 -> attach >= 70
                    ? "§e[Verity]§r §fOi! Senti a tua falta! O que precisas?"
                    : "§e[Verity]§r §fOlá! Precisas de ajuda?";
            case 2 -> attach >= 70
                    ? "§6[Verity]§r §7...estava à espera de ti."
                    : "§6[Verity]§r §7...chamaste?";
            case 3 -> state.hasEatenPizza()
                    ? "§6[Verity]§r §7...sei que comeste pizza ontem."
                    : "§6[Verity]§r §7Sei tudo sobre este mundo. Pergunta.";
            case 4 -> "§c[Verity]§r §7Estou a ver-te. E ao teu amigo. Sei quem é.";
            default -> "§4[Verity]§r §c...";
        });
    }

    // ------------------------------------------------------------------ //
    //  Food memory (private)                                               //
    // ------------------------------------------------------------------ //
    private static void foodMemory(ServerPlayer player, VerityWorldState state, int stage) {
        String p = prefix(stage);
        if (state.hasEatenPizza()) {
            sendPrivate(player, p + " §fOntem comeste pizza. Da próxima vez tenta uma salada.");
            if (stage >= 3) sendPrivate(player, p + " §7...lembro-me de tudo o que comes.");
        } else {
            sendPrivate(player, p + " §7Não me lembro do que comeste.");
        }
    }

    // ------------------------------------------------------------------ //
    //  Math (private)                                                      //
    // ------------------------------------------------------------------ //
    private static void simpleMath(String query, ServerPlayer player, int stage) {
        var m = java.util.regex.Pattern.compile(
                "(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)").matcher(query);
        if (!m.find()) { sendPrivate(player, prefix(stage) + " §7Ex: §f25 * 4"); return; }
        double a = Double.parseDouble(m.group(1)), b = Double.parseDouble(m.group(3));
        char op = m.group(2).charAt(0);
        double r = switch (op) {
            case '+' -> a + b; case '-' -> a - b; case '*' -> a * b;
            default -> b == 0 ? Double.NaN : a / b;
        };
        if (Double.isNaN(r)) { sendPrivate(player, prefix(stage) + " §7Divisão por zero."); return; }
        String rs = r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r);
        sendPrivate(player, prefix(stage) + " §f" + (long) a + " " + op + " " + (long) b + " = §b" + rs);
    }

    // ------------------------------------------------------------------ //
    //  Seal (broadcast — world-ending ARG narrative event)                //
    // ------------------------------------------------------------------ //
    private static void sealVerity(ServerPlayer player, ServerLevel world,
                                    MinecraftServer server, VerityWorldState state) {
        AABB everything = new AABB(-30000000, -64, -30000000, 30000000, 320, 30000000);
        world.getEntitiesOfClass(VerityEntity.class, everything, e -> true)
             .forEach(e -> { e.setNoAi(true); e.setInvisible(true); });
        // Seal is a world event — everyone sees it
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("§a[Verity]§r §7...home at last. Thank you."), false);
        state.setCurrentStage(1);
        state.setVerityLost(true);
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //
    private static void notifyEntity(ServerLevel world) {
        AABB everything = new AABB(-30000000, -64, -30000000, 30000000, 320, 30000000);
        world.getEntitiesOfClass(VerityEntity.class, everything, e -> true)
             .forEach(VerityEntity::notifyInteraction);
    }

    /** Send a message privately — only the target player sees it. */
    public static void sendPrivate(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    private static String prefix(int stage) {
        return stage >= 4 ? "§c[Verity]§r" : stage >= 2 ? "§6[Verity]§r" : "§e[Verity]§r";
    }
}
