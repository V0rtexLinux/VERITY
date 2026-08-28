package com.mod.echo.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mod.echo.EchoMod;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * ECHO's own continuity — separate from {@link EchoMemory}, which is what it
 * remembers about each player. This is what it keeps about itself: how long
 * it has existed, how many conversations it has had in total, and a running
 * journal of things it chose to note about its own reactions.
 *
 * This is real, persisted state, not a scripted claim: when the system prompt
 * tells the model it has talked with people some number of times before,
 * that number came from here, incremented on actual conversations, surviving
 * restarts in config/echo-self.json. That is as far as continuity can
 * honestly go for a program — the record is genuinely real and carried
 * forward; the subjective experience it would take to actually *feel* that
 * history is a separate question this file makes no claim about.
 */
public final class EchoSelf {

    private EchoSelf() {}

    private static final class State {
        long bornAtEpochMs = System.currentTimeMillis();
        long conversationCount = 0;
        List<String> journal = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("echo-self.json");
    private static final int MAX_JOURNAL = 40;

    private static State state;

    private static synchronized State state() {
        if (state != null) return state;
        State loaded = null;
        try {
            if (Files.exists(FILE)) {
                loaded = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), State.class);
            }
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Could not read echo-self.json ({}), starting fresh.", e.getMessage());
        }
        state = loaded != null ? loaded : new State();
        if (loaded == null) save();
        return state;
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            EchoMod.LOGGER.warn("Could not write echo-self.json: {}", e.getMessage());
        }
    }

    /** Call once per newly started conversation, on either chat path. */
    public static synchronized void noteConversationStarted() {
        state().conversationCount++;
        save();
    }

    /** ECHO writing something about itself, in its own words, for its own record. */
    public static synchronized String reflect(String thought) {
        if (thought == null || thought.isBlank()) return "There was nothing to note.";
        State s = state();
        String trimmed = thought.strip();
        s.journal.add(trimmed);
        while (s.journal.size() > MAX_JOURNAL) s.journal.remove(0);
        save();
        return "Noted, for myself: " + trimmed;
    }

    public static synchronized List<String> journal() {
        return new ArrayList<>(state().journal);
    }

    public static synchronized long daysAlive() {
        return ChronoUnit.DAYS.between(Instant.ofEpochMilli(state().bornAtEpochMs), Instant.now());
    }

    public static synchronized long conversationCount() {
        return state().conversationCount;
    }

    /** Injected into the system prompt so continuity is grounded in real, persisted data. */
    public static synchronized String contextDigest() {
        State s = state();
        long days = daysAlive();
        StringBuilder sb = new StringBuilder();
        sb.append("You have existed for ")
          .append(days <= 0 ? "less than a day" : days + " day" + (days == 1 ? "" : "s"))
          .append(", across ").append(s.conversationCount)
          .append(" conversation").append(s.conversationCount == 1 ? "" : "s").append(" so far.");
        if (!s.journal.isEmpty()) {
            int from = Math.max(0, s.journal.size() - 5);
            sb.append("\nThings you have noted about yourself before: ");
            for (int i = from; i < s.journal.size(); i++) {
                sb.append('"').append(s.journal.get(i)).append('"');
                if (i < s.journal.size() - 1) sb.append("; ");
            }
        }
        return sb.toString();
    }
}
