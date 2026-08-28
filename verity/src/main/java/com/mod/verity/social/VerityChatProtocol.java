package com.mod.verity.social;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Encodes/decodes invisible "handshake" and "assistant talk" packets inside
 * normal vanilla chat messages, so the mod can detect other players who also
 * have Verity installed EVEN on a server that does not have the mod
 * ("servidor cego").
 *
 * A vanilla server always relays chat text between players untouched — that
 * is the only channel guaranteed to exist without server-side support.
 *
 * Wire format (inside a normal chat message string):
 *   §k§r VERITY:<base64 payload> §k§r
 *
 * The leading/trailing "§k§r" (obfuscated formatting reset) is used purely
 * as a marker prefix that is extremely unlikely to appear in real chat, and
 * ClientChatInterceptor / a receive-side listener strip it so normal
 * players never see raw payloads if the marker fails to match on their
 * (unmodded) client — they'd just see an odd string, so we still keep the
 * payload short. Real handshake messages are additionally never rendered
 * locally either; they're consumed and hidden.
 */
public final class VerityChatProtocol {

    private static final String MARKER = "\u00A7kVRT\u00A7r:";

    private VerityChatProtocol() {}

    public enum Type { HANDSHAKE, HANDSHAKE_ACK, TALK, POSITION }

    public record Packet(Type type, String senderUUID, String data) {}

    public static String encode(Type type, String senderUUID, String data) {
        String raw = type.name() + "|" + senderUUID + "|" + data;
        String b64 = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return MARKER + b64;
    }

    /** Returns empty if the message is not a Verity protocol packet. */
    public static Optional<Packet> decode(String chatMessage) {
        int idx = chatMessage.indexOf(MARKER);
        if (idx < 0) return Optional.empty();
        try {
            String b64 = chatMessage.substring(idx + MARKER.length()).trim();
            String raw = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 3);
            if (parts.length != 3) return Optional.empty();
            Type type = Type.valueOf(parts[0]);
            return Optional.of(new Packet(type, parts[1], parts[2]));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** True if a raw incoming chat line is a protocol packet and should be hidden from view. */
    public static boolean isProtocolMessage(String chatMessage) {
        return chatMessage.contains(MARKER);
    }
}
