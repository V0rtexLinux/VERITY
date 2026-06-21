package com.mod.verity.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataDataManager;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks real-world login/logout timestamps per player UUID.
 *
 * Stored in world NBT so Verity remembers how long each player was offline
 * across server restarts.  Used to power the "offline awareness" and yandere
 * reaction mechanics.
 */
public class PlayerSessionData extends SavedData {

    private static final String KEY = "verity_player_sessions";

    /** Maps playerUUID → Unix epoch seconds of last logout. */
    private final Map<UUID, Long> lastLogoutTime = new HashMap<>();

    /** Maps playerUUID → total sessions count (how many times they logged in). */
    private final Map<UUID, Integer> sessionCount = new HashMap<>();

    /** Maps playerUUID → total seconds spent online (lifetime). */
    private final Map<UUID, Long> totalOnlineSeconds = new HashMap<>();

    /** Maps playerUUID → Unix epoch seconds of last login (set on join). */
    private final Map<UUID, Long> lastLoginTime = new HashMap<>();

    // ------------------------------------------------------------------ //
    //  Factory                                                             //
    // ------------------------------------------------------------------ //
    public static PlayerSessionData getOrCreate(ServerLevel world) {
        SavedDataDataManager manager = world.getDataStorage();
        return manager.getOrCreate(
                PlayerSessionData::fromNbt,
                PlayerSessionData::new,
                KEY
        );
    }

    private static PlayerSessionData fromNbt(CompoundTag nbt) {
        PlayerSessionData data = new PlayerSessionData();
        CompoundTag logouts  = nbt.getCompound("lastLogoutTime");
        CompoundTag logins   = nbt.getCompound("lastLoginTime");
        CompoundTag sessions = nbt.getCompound("sessionCount");
        CompoundTag online   = nbt.getCompound("totalOnlineSeconds");

        for (String key : logouts.getKeys()) {
            data.lastLogoutTime.put(UUID.fromString(key), logouts.getLong(key));
        }
        for (String key : logins.getKeys()) {
            data.lastLoginTime.put(UUID.fromString(key), logins.getLong(key));
        }
        for (String key : sessions.getKeys()) {
            data.sessionCount.put(UUID.fromString(key), sessions.getInt(key));
        }
        for (String key : online.getKeys()) {
            data.totalOnlineSeconds.put(UUID.fromString(key), online.getLong(key));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        CompoundTag logouts  = new CompoundTag();
        CompoundTag logins   = new CompoundTag();
        CompoundTag sessions = new CompoundTag();
        CompoundTag online   = new CompoundTag();

        lastLogoutTime.forEach((uuid, ts)  -> logouts.putLong(uuid.toString(), ts));
        lastLoginTime.forEach((uuid, ts)   -> logins.putLong(uuid.toString(), ts));
        sessionCount.forEach((uuid, count) -> sessions.putInt(uuid.toString(), count));
        totalOnlineSeconds.forEach((uuid, secs) -> online.putLong(uuid.toString(), secs));

        nbt.put("lastLogoutTime",     logouts);
        nbt.put("lastLoginTime",      logins);
        nbt.put("sessionCount",       sessions);
        nbt.put("totalOnlineSeconds", online);
        return nbt;
    }

    // ------------------------------------------------------------------ //
    //  On player JOIN                                                      //
    // ------------------------------------------------------------------ //
    public void onPlayerJoin(UUID uuid) {
        lastLoginTime.put(uuid, Instant.now().getEpochSecond());
        sessionCount.merge(uuid, 1, Integer::sum);
        markDirty();
    }

    // ------------------------------------------------------------------ //
    //  On player QUIT                                                      //
    // ------------------------------------------------------------------ //
    public void onPlayerQuit(UUID uuid) {
        long now = Instant.now().getEpochSecond();
        lastLogoutTime.put(uuid, now);

        // Accumulate online time
        Long loginTime = lastLoginTime.get(uuid);
        if (loginTime != null) {
            long sessionSeconds = now - loginTime;
            totalOnlineSeconds.merge(uuid, sessionSeconds, Long::sum);
        }
        markDirty();
    }

    // ------------------------------------------------------------------ //
    //  Queries                                                             //
    // ------------------------------------------------------------------ //

    /**
     * Returns seconds offline since the last logout, or -1 if never logged
     * out (first session).
     */
    public long getSecondsOffline(UUID uuid) {
        Long logoutTime = lastLogoutTime.get(uuid);
        if (logoutTime == null) return -1;
        return Instant.now().getEpochSecond() - logoutTime;
    }

    /**
     * Returns the hour-of-day (0-23, server local time) when the player
     * last logged out.  Used by Verity to "guess" what the player was doing.
     */
    public int getLogoutHour(UUID uuid) {
        Long logoutTime = lastLogoutTime.get(uuid);
        if (logoutTime == null) return -1;
        ZonedDateTime dt = ZonedDateTime.ofInstant(
                Instant.ofEpochSecond(logoutTime), ZoneId.systemDefault());
        return dt.getHour();
    }

    /**
     * Returns the hour-of-day (0-23) at the current moment (login time).
     */
    public int getCurrentHour() {
        return ZonedDateTime.now(ZoneId.systemDefault()).getHour();
    }

    public int getSessionCount(UUID uuid) {
        return sessionCount.getOrDefault(uuid, 0);
    }

    public long getTotalOnlineSeconds(UUID uuid) {
        return totalOnlineSeconds.getOrDefault(uuid, 0L);
    }

    public boolean isFirstSession(UUID uuid) {
        return getSessionCount(uuid) <= 1;
    }
}
