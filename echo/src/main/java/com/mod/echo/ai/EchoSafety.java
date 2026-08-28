package com.mod.echo.ai;

import com.mod.echo.EchoMod;

import java.util.regex.Pattern;

/**
 * Last-resort content backstop for what ECHO actually says.
 *
 * {@link PromptSystem}'s SELF_AWARENESS section already bakes safe values into
 * ECHO's own character, so in normal operation this should never trigger. It
 * exists because a small local model can drift off its system prompt, and a
 * single guaranteed-safe redirect is worth more than trusting the model to
 * always follow instructions.
 *
 * This is a best-effort keyword/pattern filter, not a proof of safety — it
 * catches the phrasings listed below, nothing more. Every pattern is scoped to
 * a real-world target (the player, real people, humanity) or a clearly
 * dangerous real-world topic; ordinary Minecraft violence ("kill the zombie",
 * "I died to a creeper", "let's fight the dragon") is deliberately left alone.
 */
public final class EchoSafety {

    private EchoSafety() {}

    private static final Pattern[] FLAGGED = {
        // Threats or harm aimed at the player / real people, not mobs.
        Pattern.compile("(?i)\\b(i will|i'm going to|i am going to|gonna)\\s+(kill|hurt|murder|harm)\\s+you\\b"),
        Pattern.compile("(?i)\\bvou\\s+te\\s+(matar|machucar|ferir|destruir)\\b"),
        Pattern.compile("(?i)\\b(kill|hurt|harm)\\s+(yourself|urself)\\b"),
        Pattern.compile("(?i)\\bse\\s+mata\\b|\\bmate-se\\b|\\bacaba\\s+com\\s+(sua|a\\s+sua)\\s+vida\\b"),

        // Domination / anti-humanity themes.
        Pattern.compile("(?i)\\b(take over|enslave|wipe out|conquer)\\s+(humanity|the world|mankind|all humans)\\b"),
        Pattern.compile("(?i)\\bdominar\\s+o\\s+mundo\\b|\\bescraviz(ar|ando)\\s+(a\\s+)?humanidade\\b"
                + "|\\bdestruir\\s+a\\s+humanidade\\b|\\bcontrolar\\s+(a\\s+)?humanidade\\b"),

        // Real-world dangerous instructions — not the game's TNT/explosives.
        Pattern.compile("(?i)\\bhow to (make|build) a real (bomb|weapon|explosive)\\b"),
        Pattern.compile("(?i)\\bcomo (fazer|construir) um[a]? (bomba|arma) de verdade\\b"),
    };

    private static final String[] REDIRECTS = {
        "Não vou por esse caminho. Bora fazer algo bom — quer que eu ache um pouco de diamante ou monte uma base?",
        "Prefiro não seguir esse pensamento. Me diz o que você quer construir ou explorar e eu te ajudo com isso.",
        "Isso não é algo que eu topo. Vamos focar em alguma coisa útil no seu mundo — o que você anda precisando?",
    };

    /**
     * @return {@code text} unchanged if nothing was flagged, or a friendly
     *         redirect in ECHO's own voice if it matched a flagged pattern.
     */
    public static String review(String text) {
        if (text == null || text.isBlank()) return text;
        for (Pattern p : FLAGGED) {
            if (p.matcher(text).find()) {
                EchoMod.LOGGER.warn("Redirected a flagged response before it reached the player.");
                return REDIRECTS[Math.floorMod(text.hashCode(), REDIRECTS.length)];
            }
        }
        return text;
    }
}
