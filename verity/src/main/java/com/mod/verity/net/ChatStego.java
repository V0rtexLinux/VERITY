package com.mod.verity.net;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes/decodes small payloads as invisible chat text using zero-width
 * unicode characters. This lets two VERITY clients exchange data through
 * ANY vanilla server (which just relays chat text verbatim) while players
 * without the mod see nothing meaningful — the message renders as a blank
 * or near-blank line.
 *
 * Format: ZWNJ (\u200C) = bit 0, ZWJ (\u200D) = bit 1, wrapped between two
 * ZWSP (\u200B) markers so we can detect our own payloads and strip
 * everything else (real player chat) instantly without decoding overhead.
 */
public final class ChatStego {

    private static final char MARK = '\u200B'; // zero-width space (start/end marker)
    private static final char ZERO = '\u200C'; // zero-width non-joiner
    private static final char ONE  = '\u200D'; // zero-width joiner

    private ChatStego() {}

    /** Encodes a payload string into an invisible carrier message. */
    public static String encode(String payload) {
        byte[] bytes = Base64.getEncoder().encode(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        sb.append(MARK);
        for (byte b : bytes) {
            for (int i = 7; i >= 0; i--) {
                sb.append(((b >> i) & 1) == 1 ? ONE : ZERO);
            }
        }
        sb.append(MARK);
        // A single visible space so vanilla chat doesn't collapse/trim it away.
        return " " + sb;
    }

    /** Returns true if this raw chat message contains a VERITY stego payload. */
    public static boolean isStego(String rawMessage) {
        int first = rawMessage.indexOf(MARK);
        int last = rawMessage.lastIndexOf(MARK);
        return first >= 0 && last > first;
    }

    /** Decodes the payload, or null if the message isn't a valid stego carrier. */
    public static String decode(String rawMessage) {
        int first = rawMessage.indexOf(MARK);
        int last = rawMessage.lastIndexOf(MARK);
        if (first < 0 || last <= first) return null;

        String bits = rawMessage.substring(first + 1, last);
        if (bits.isEmpty() || bits.length() % 8 != 0) return null;

        byte[] bytes = new byte[bits.length() / 8];
        try {
            for (int i = 0; i < bytes.length; i++) {
                int b = 0;
                for (int j = 0; j < 8; j++) {
                    char c = bits.charAt(i * 8 + j);
                    if (c != ZERO && c != ONE) return null; // not our payload
                    b = (b << 1) | (c == ONE ? 1 : 0);
                }
                bytes[i] = (byte) b;
            }
            return new String(Base64.getDecoder().decode(bytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null; // not a valid payload — treat as normal chat
        }
    }
}
