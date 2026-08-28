package com.mod.verity.net;

import com.mod.verity.VerityMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Tracks which OTHER players in the current server also have VERITY
 * installed (detected via the invisible chat handshake), plus friendship
 * scores between: this player's assistant <-> other players' assistants,
 * and this player's assistant <-> other players themselves.
 *
 * Entirely client-side/local — works with a completely vanilla server.
 */
public final class ModPlayerRegistry {

    public static class KnownPlayer {
        public final UUID uuid;
        public String name;
        public long lastSeenTick;
        public int assistantFriendship = 0; // this player's Verity <-> their Verity
        public int playerFriendship = 0;    // this player's Verity <-> that player

        KnownPlayer(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    private static final Map<UUID, KnownPlayer> KNOWN = new HashMap<>();
    private static final Path FILE_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("verity_known_players.csv");

    private static boolean loaded = false;

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(FILE_PATH)) return;
        try (BufferedReader r = Files.newBufferedReader(FILE_PATH)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",", 4);
                if (p.length < 4) continue;
                KnownPlayer kp = new KnownPlayer(UUID.fromString(p[0]), p[1]);
                kp.assistantFriendship = Integer.parseInt(p[2]);
                kp.playerFriendship = Integer.parseInt(p[3]);
                KNOWN.put(kp.uuid, kp);
            }
        } catch (Exception e) {
            VerityMod.LOGGER.error("[ModPlayerRegistry] load failed: " + e.getMessage());
        }
    }

    public static synchronized void save() {
        try (BufferedWriter w = Files.newBufferedWriter(FILE_PATH)) {
            for (KnownPlayer kp : KNOWN.values()) {
                w.write(kp.uuid + "," + kp.name + "," + kp.assistantFriendship + "," + kp.playerFriendship);
                w.newLine();
            }
        } catch (Exception e) {
            VerityMod.LOGGER.error("[ModPlayerRegistry] save failed: " + e.getMessage());
        }
    }

    /** Called when a valid handshake (PING/PONG) is received from another modded player. */
    public static synchronized KnownPlayer markSeen(UUID uuid, String name, long tick) {
        load();
        KnownPlayer kp = KNOWN.computeIfAbsent(uuid, u -> new KnownPlayer(uuid, name));
        kp.name = name;
        kp.lastSeenTick = tick;
        return kp;
    }

    public static synchronized boolean isKnownModded(UUID uuid, long currentTick, long timeoutTicks) {
        load();
        KnownPlayer kp = KNOWN.get(uuid);
        return kp != null && (currentTick - kp.lastSeenTick) <= timeoutTicks;
    }

    public static synchronized Collection<KnownPlayer> allModded(long currentTick, long timeoutTicks) {
        load();
        List<KnownPlayer> out = new ArrayList<>();
        for (KnownPlayer kp : KNOWN.values()) {
            if (currentTick - kp.lastSeenTick <= timeoutTicks) out.add(kp);
        }
        return out;
    }

    public static synchronized void adjustAssistantFriendship(UUID uuid, int delta) {
        load();
        KnownPlayer kp = KNOWN.get(uuid);
        if (kp == null) return;
        kp.assistantFriendship = Math.max(0, Math.min(100, kp.assistantFriendship + delta));
        save();
    }

    public static synchronized void adjustPlayerFriendship(UUID uuid, int delta) {
        load();
        KnownPlayer kp = KNOWN.get(uuid);
        if (kp == null) return;
        kp.playerFriendship = Math.max(0, Math.min(100, kp.playerFriendship + delta));
        save();
    }
}
