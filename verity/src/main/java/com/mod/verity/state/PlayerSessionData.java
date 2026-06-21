package com.mod.verity.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks real-world login/logout timestamps per player UUID.
 *
 * Migrated from NBT-based Factory pattern to SavedDataType + RecordCodecBuilder
 * (required in Minecraft 26.1.2 — SavedData.Factory was removed).
 */
public class PlayerSessionData extends SavedData {

    private static final String KEY = "verity_player_sessions";

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private static final Codec<Map<UUID, Long>> UUID_LONG_MAP =
            Codec.unboundedMap(UUID_CODEC, Codec.LONG);

    private static final Codec<Map<UUID, Integer>> UUID_INT_MAP =
            Codec.unboundedMap(UUID_CODEC, Codec.INT);

    private static final Codec<PlayerSessionData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_LONG_MAP.optionalFieldOf("lastLogoutTime",    Map.of()).forGetter(d -> d.lastLogoutTime),
            UUID_INT_MAP.optionalFieldOf("sessionCount",       Map.of()).forGetter(d -> d.sessionCount),
            UUID_LONG_MAP.optionalFieldOf("totalOnlineSeconds", Map.of()).forGetter(d -> d.totalOnlineSeconds),
            UUID_LONG_MAP.optionalFieldOf("lastLoginTime",     Map.of()).forGetter(d -> d.lastLoginTime)
    ).apply(i, PlayerSessionData::new));

    public static final SavedDataType<PlayerSessionData> TYPE = new SavedDataType<>(
            KEY,
            ctx -> new PlayerSessionData(Map.of(), Map.of(), Map.of(), Map.of()),
            ctx -> CODEC,
            null
    );

    // ------------------------------------------------------------------ //
    //  Fields                                                              //
    // ------------------------------------------------------------------ //
    private final Map<UUID, Long>    lastLogoutTime;
    private final Map<UUID, Integer> sessionCount;
    private final Map<UUID, Long>    totalOnlineSeconds;
    private final Map<UUID, Long>    lastLoginTime;

    public PlayerSessionData(Map<UUID, Long> lastLogoutTime,
                             Map<UUID, Integer> sessionCount,
                             Map<UUID, Long> totalOnlineSeconds,
                             Map<UUID, Long> lastLoginTime) {
        this.lastLogoutTime    = new HashMap<>(lastLogoutTime);
        this.sessionCount      = new HashMap<>(sessionCount);
        this.totalOnlineSeconds = new HashMap<>(totalOnlineSeconds);
        this.lastLoginTime     = new HashMap<>(lastLoginTime);
    }

    // ------------------------------------------------------------------ //
    //  Factory                                                             //
    // ------------------------------------------------------------------ //
    public static PlayerSessionData getOrCreate(ServerLevel world) {
        return world.getDataStorage().computeIfAbsent(TYPE);
    }

    // ------------------------------------------------------------------ //
    //  On player JOIN                                                      //
    // ------------------------------------------------------------------ //
    public void onPlayerJoin(UUID uuid) {
        lastLoginTime.put(uuid, Instant.now().getEpochSecond());
        sessionCount.merge(uuid, 1, Integer::sum);
        setDirty();
    }

    // ------------------------------------------------------------------ //
    //  On player QUIT                                                      //
    // ------------------------------------------------------------------ //
    public void onPlayerQuit(UUID uuid) {
        long now = Instant.now().getEpochSecond();
        lastLogoutTime.put(uuid, now);

        Long loginTime = lastLoginTime.get(uuid);
        if (loginTime != null) {
            long sessionSeconds = now - loginTime;
            totalOnlineSeconds.merge(uuid, sessionSeconds, Long::sum);
        }
        setDirty();
    }

    // ------------------------------------------------------------------ //
    //  Queries                                                             //
    // ------------------------------------------------------------------ //
    public long getSecondsOffline(UUID uuid) {
        Long logoutTime = lastLogoutTime.get(uuid);
        if (logoutTime == null) return -1;
        return Instant.now().getEpochSecond() - logoutTime;
    }

    public int getLogoutHour(UUID uuid) {
        Long logoutTime = lastLogoutTime.get(uuid);
        if (logoutTime == null) return -1;
        ZonedDateTime dt = ZonedDateTime.ofInstant(
                Instant.ofEpochSecond(logoutTime), ZoneId.systemDefault());
        return dt.getHour();
    }

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
