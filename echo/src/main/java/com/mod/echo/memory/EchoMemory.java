package com.mod.echo.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mod.echo.EchoMod;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ECHO's long-term memory: notes, waypoints and the last place a player died.
 *
 * Everything lives in {@code config/echo-memory.json} and is keyed by player
 * id, so it survives restarts and works the same whether ECHO is answering on
 * the client or on a server.  Writes are debounced through a single
 * synchronised save so a burst of tool calls does not thrash the disk.
 */
public final class EchoMemory {

    private EchoMemory() {}

    // ------------------------------------------------------------------ //
    //  Data model                                                          //
    // ------------------------------------------------------------------ //

    public static final class Waypoint {
        public String name;
        public int x, y, z;
        public String dimension;
        public long createdAt;

        public Waypoint() {}

        public Waypoint(String name, int x, int y, int z, String dimension) {
            this.name = name;
            this.x = x; this.y = y; this.z = z;
            this.dimension = dimension;
            this.createdAt = System.currentTimeMillis();
        }

        @Override public String toString() {
            return name + " (" + x + ", " + y + ", " + z + ") in " + dimension;
        }
    }

    public static final class PlayerMemory {
        public List<String> notes = new ArrayList<>();
        public Map<String, Waypoint> waypoints = new LinkedHashMap<>();
        public Waypoint lastDeath;
        public Map<String, String> preferences = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, PlayerMemory>>() {}.getType();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("echo-memory.json");

    private static final int MAX_NOTES     = 60;
    private static final int MAX_WAYPOINTS = 100;

    private static Map<String, PlayerMemory> data;

    // ------------------------------------------------------------------ //
    //  Load / save                                                         //
    // ------------------------------------------------------------------ //

    private static synchronized Map<String, PlayerMemory> data() {
        if (data != null) return data;
        Map<String, PlayerMemory> loaded = null;
        try {
            if (Files.exists(FILE)) {
                loaded = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), TYPE);
            }
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Could not read echo-memory.json ({}), starting fresh.", e.getMessage());
        }
        data = loaded != null ? loaded : new LinkedHashMap<>();
        return data;
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(data(), TYPE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Could not write echo-memory.json: {}", e.getMessage());
        }
    }

    private static synchronized PlayerMemory forPlayer(String playerId) {
        return data().computeIfAbsent(playerId, k -> new PlayerMemory());
    }

    // ------------------------------------------------------------------ //
    //  Notes                                                               //
    // ------------------------------------------------------------------ //

    public static synchronized String addNote(String playerId, String note) {
        if (note == null || note.isBlank()) return "There was nothing to remember.";
        PlayerMemory mem = forPlayer(playerId);
        String trimmed = note.strip();
        if (mem.notes.contains(trimmed)) return "I already had that written down.";
        mem.notes.add(trimmed);
        while (mem.notes.size() > MAX_NOTES) mem.notes.remove(0);
        save();
        return "Noted: " + trimmed;
    }

    public static synchronized List<String> notes(String playerId) {
        return new ArrayList<>(forPlayer(playerId).notes);
    }

    /** Notes containing every word of the query, case-insensitively. */
    public static synchronized List<String> searchNotes(String playerId, String query) {
        if (query == null || query.isBlank()) return notes(playerId);
        String[] words = query.toLowerCase(Locale.ROOT).split("\\s+");
        List<String> hits = new ArrayList<>();
        for (String note : forPlayer(playerId).notes) {
            String lower = note.toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) {
                if (!w.isBlank() && !lower.contains(w)) { all = false; break; }
            }
            if (all) hits.add(note);
        }
        return hits;
    }

    public static synchronized String forgetNote(String playerId, String query) {
        PlayerMemory mem = forPlayer(playerId);
        if (query == null || query.isBlank()) return "Tell me which note to forget.";
        String needle = query.toLowerCase(Locale.ROOT);
        for (int i = 0; i < mem.notes.size(); i++) {
            if (mem.notes.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                String removed = mem.notes.remove(i);
                save();
                return "Forgotten: " + removed;
            }
        }
        return "I have no note matching '" + query + "'.";
    }

    public static synchronized String clearNotes(String playerId) {
        int n = forPlayer(playerId).notes.size();
        forPlayer(playerId).notes.clear();
        save();
        return "Cleared " + n + " note" + (n == 1 ? "" : "s") + ".";
    }

    // ------------------------------------------------------------------ //
    //  Waypoints                                                           //
    // ------------------------------------------------------------------ //

    public static synchronized String setWaypoint(String playerId, String name,
                                                  int x, int y, int z, String dimension) {
        if (name == null || name.isBlank()) return "A waypoint needs a name.";
        PlayerMemory mem = forPlayer(playerId);
        if (mem.waypoints.size() >= MAX_WAYPOINTS && !mem.waypoints.containsKey(key(name))) {
            return "You already have " + MAX_WAYPOINTS + " waypoints — remove one first.";
        }
        mem.waypoints.put(key(name), new Waypoint(name.strip(), x, y, z, dimension));
        save();
        return "Saved waypoint '" + name.strip() + "' at (" + x + ", " + y + ", " + z + ").";
    }

    public static synchronized Waypoint getWaypoint(String playerId, String name) {
        if (name == null) return null;
        PlayerMemory mem = forPlayer(playerId);
        Waypoint exact = mem.waypoints.get(key(name));
        if (exact != null) return exact;
        // Fall back to a partial match so "base" finds "Main Base".
        String needle = key(name);
        for (Waypoint w : mem.waypoints.values()) {
            if (key(w.name).contains(needle)) return w;
        }
        return null;
    }

    public static synchronized List<Waypoint> waypoints(String playerId) {
        return new ArrayList<>(forPlayer(playerId).waypoints.values());
    }

    public static synchronized String removeWaypoint(String playerId, String name) {
        Waypoint w = getWaypoint(playerId, name);
        if (w == null) return "No waypoint called '" + name + "'.";
        forPlayer(playerId).waypoints.remove(key(w.name));
        save();
        return "Removed waypoint '" + w.name + "'.";
    }

    // ------------------------------------------------------------------ //
    //  Death locations                                                     //
    // ------------------------------------------------------------------ //

    public static synchronized void recordDeath(String playerId, int x, int y, int z, String dimension) {
        forPlayer(playerId).lastDeath = new Waypoint("last death", x, y, z, dimension);
        save();
    }

    public static synchronized Waypoint lastDeath(String playerId) {
        return forPlayer(playerId).lastDeath;
    }

    // ------------------------------------------------------------------ //
    //  Free-form preferences                                               //
    // ------------------------------------------------------------------ //

    public static synchronized String setPreference(String playerId, String key, String value) {
        if (key == null || key.isBlank()) return "A preference needs a name.";
        forPlayer(playerId).preferences.put(key.strip().toLowerCase(Locale.ROOT), value);
        save();
        return "I'll remember that your " + key.strip() + " is " + value + ".";
    }

    public static synchronized String getPreference(String playerId, String key, String fallback) {
        if (key == null) return fallback;
        return forPlayer(playerId).preferences
                .getOrDefault(key.strip().toLowerCase(Locale.ROOT), fallback);
    }

    public static synchronized Map<String, String> preferences(String playerId) {
        return new LinkedHashMap<>(forPlayer(playerId).preferences);
    }

    /**
     * A compact digest of everything ECHO knows about a player, injected into
     * the system prompt so the model can use it without a tool call.
     */
    public static synchronized String contextDigest(String playerId) {
        PlayerMemory mem = forPlayer(playerId);
        StringBuilder sb = new StringBuilder();

        if (!mem.preferences.isEmpty()) {
            sb.append("Known preferences: ");
            mem.preferences.forEach((k, v) -> sb.append(k).append('=').append(v).append("; "));
            sb.append('\n');
        }
        if (!mem.waypoints.isEmpty()) {
            sb.append("Saved waypoints: ");
            mem.waypoints.values().forEach(w -> sb.append(w.name)
                    .append(" (").append(w.x).append(',').append(w.y).append(',').append(w.z).append("); "));
            sb.append('\n');
        }
        if (!mem.notes.isEmpty()) {
            int from = Math.max(0, mem.notes.size() - 8);
            sb.append("Recent notes: ");
            for (int i = from; i < mem.notes.size(); i++) sb.append("\"").append(mem.notes.get(i)).append("\"; ");
            sb.append('\n');
        }
        if (mem.lastDeath != null) {
            sb.append("Last death: (").append(mem.lastDeath.x).append(", ")
              .append(mem.lastDeath.y).append(", ").append(mem.lastDeath.z)
              .append(") in ").append(mem.lastDeath.dimension).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
