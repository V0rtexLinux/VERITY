package com.mod.echo;

/**
 * Central place for every piece of ECHO's visual identity.
 *
 * ECHO always speaks in blue: the label is dark blue ({@code §9}) and the name
 * itself is bright aqua ({@code §b}).  Every message the assistant produces goes
 * through one of the helpers here so the colour scheme can never drift between
 * the client path, the server path and the voice path.
 */
public final class EchoStyle {

    private EchoStyle() {}

    /** Dark blue — brackets, separators, secondary label text. */
    public static final String BLUE   = "§9";
    /** Bright aqua — ECHO's own name and highlighted values. */
    public static final String AQUA   = "§b";
    /** Neutral body text. */
    public static final String TEXT   = "§f";
    /** Muted body text (explanations, hints). */
    public static final String MUTED  = "§7";
    /** Positive result. */
    public static final String OK     = "§a";
    /** Warning / attention. */
    public static final String WARN   = "§e";
    /** Error. */
    public static final String ERROR  = "§c";
    /** Reset. */
    public static final String RESET  = "§r";

    /** The assistant's blue name tag, e.g. {@code §9[§bECHO§9]§r}. */
    public static final String NAME = BLUE + "[" + AQUA + "ECHO" + BLUE + "]" + RESET;

    /** Name tag followed by a space — the standard chat prefix. */
    public static String prefix() {
        return NAME + " ";
    }

    /** Prefix a single line with ECHO's blue name tag. */
    public static String line(String body) {
        return prefix() + body;
    }

    /** Prefix normal informational text. */
    public static String info(String body) {
        return prefix() + TEXT + body;
    }

    /** Prefix muted / explanatory text. */
    public static String hint(String body) {
        return prefix() + MUTED + body;
    }

    /** Prefix a success message. */
    public static String ok(String body) {
        return prefix() + OK + body;
    }

    /** Prefix a warning. */
    public static String warn(String body) {
        return prefix() + WARN + body;
    }

    /** Prefix an error. */
    public static String error(String body) {
        return prefix() + ERROR + body;
    }

    /** Highlight a value in aqua and return to normal text afterwards. */
    public static String value(Object value) {
        return AQUA + value + TEXT;
    }

    /** Format a block position the way ECHO always writes coordinates. */
    public static String coords(int x, int y, int z) {
        return AQUA + "(" + x + ", " + y + ", " + z + ")" + TEXT;
    }

    /**
     * Prefix every line of a multi-line block, so wrapped output keeps the
     * blue name tag on each row instead of only on the first one.
     */
    public static String block(String body) {
        StringBuilder sb = new StringBuilder();
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(prefix()).append(lines[i]);
        }
        return sb.toString();
    }
}
