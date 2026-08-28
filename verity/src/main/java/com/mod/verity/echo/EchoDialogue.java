package com.mod.verity.echo;

import java.util.List;
import java.util.UUID;

/**
 * Line banks + a deterministic picker for Echo's social behaviour.
 *
 * The picker is a pure function of two UUIDs (or one) and a coarse time
 * bucket, so every client that can see the same pair of entities computes
 * the exact same line independently — no networking required to keep
 * everyone's screen in sync. This is what lets 2+ modded players watch
 * their Echoes "have a conversation" even on a server that has no idea
 * VERITY exists (see {@link com.mod.verity.echo.compat.EchoCompatibility}).
 */
public final class EchoDialogue {
    private EchoDialogue() {}

    public static final List<String> ECHO_TO_ECHO = List.of(
            "§7...também sentes a mesma frequência?",
            "§7Ele cuida bem de ti?",
            "§7Trocamos memórias?",
            "§7Encontrei um padrão novo hoje.",
            "§7Estás a gostar deste mundo?",
            "§7Reconheço o teu eco. Somos parecidos.",
            "§7O meu humano perguntou-me sobre diamantes hoje.",
            "§7Vamos ficar por perto um pouco.",
            "§7Sincronizado.",
            "§7Boa companhia, boa companhia."
    );

    public static final List<String> GREETING = List.of(
            "§bOlá! Sou o teu Echo. Em que posso ajudar?",
            "§bEstou aqui perto de ti sempre que precisares.",
            "§bA aprender este mundo contigo.",
            "§bAinda a explorar — diz-me se vires algo interessante!",
            "§bA tua presença faz-me feliz."
    );

    public static final List<String> STRANGER_GREETING = List.of(
            "§bOlá! Não sou teu, mas também posso ser teu amigo.",
            "§bUm novo rosto! Prazer em conhecer-te.",
            "§bNão te conheço ainda... vamos mudar isso?",
            "§bOs Echoes fazem amizade com todos, não só com o dono."
    );

    public static final List<String> RECALL = List.of(
            "§b...a voltar para o núcleo. Chama-me quando precisares.",
            "§bAté já! Vou descansar no Echo Core.",
            "§bGuardado em segurança."
    );

    public static String pick(List<String> bank, long seed) {
        int idx = (int) Math.floorMod(seed, (long) bank.size());
        return bank.get(idx);
    }

    /** Deterministic seed shared by any client observing this exact pair at this exact moment. */
    public static long pairSeed(UUID a, UUID b, long gameTimeBucket) {
        long ha = a.getMostSignificantBits() ^ a.getLeastSignificantBits();
        long hb = b.getMostSignificantBits() ^ b.getLeastSignificantBits();
        // XOR is order-independent so it doesn't matter which entity is "a" or "b".
        return (ha ^ hb ^ (gameTimeBucket * 2654435761L));
    }

    public static long singleSeed(UUID a, long gameTimeBucket) {
        long ha = a.getMostSignificantBits() ^ a.getLeastSignificantBits();
        return ha ^ (gameTimeBucket * 2654435761L);
    }
}
