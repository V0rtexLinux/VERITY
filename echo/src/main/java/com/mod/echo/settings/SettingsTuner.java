package com.mod.echo.settings;

import com.mod.echo.EchoMod;
import com.mod.echo.config.EchoConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.server.level.ParticleStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adjusts Minecraft's own settings to match the player, their hardware, the
 * mods they have loaded and the server they are on.
 *
 * This is the feature ECHO exists for as much as the chat: it reads the live
 * situation through {@link HardwareProbe}, works out a target profile, and then
 * writes the settings through Minecraft's real {@code OptionInstance} objects —
 * the same path the options screen uses — so the values stick, persist to
 * {@code options.txt} and survive a restart.
 *
 * Every run produces a {@link Plan}, which can be shown to the player without
 * being applied. Nothing is changed until {@link #apply(Plan)} is called.
 */
@Environment(EnvType.CLIENT)
public final class SettingsTuner {

    private SettingsTuner() {}

    /** What the tuner is optimising for. */
    public enum Goal {
        /** Squeeze out frames; sacrifice looks freely. */
        PERFORMANCE,
        /** A sensible middle ground. */
        BALANCED,
        /** Make it look good; assume the machine can take it. */
        QUALITY,
        /** Tuned for a busy multiplayer server. */
        MULTIPLAYER,
        /** Tuned for a large modpack. */
        MODPACK,
        /** Let ECHO choose from what it measures. */
        AUTO;

        public static Goal parse(String raw) {
            if (raw == null) return AUTO;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "performance", "fps", "fast", "potato", "desempenho" -> PERFORMANCE;
                case "quality", "fancy", "pretty", "qualidade"            -> QUALITY;
                case "balanced", "balance", "equilibrado"                 -> BALANCED;
                case "multiplayer", "server", "servidor"                  -> MULTIPLAYER;
                case "modpack", "mods"                                    -> MODPACK;
                default                                                   -> AUTO;
            };
        }
    }

    /** One setting the tuner intends to change. */
    public record Change(String option, String from, String to, String because) {
        @Override public String toString() {
            return option + ": " + from + " -> " + to + "  (" + because + ")";
        }
    }

    /** The full result of a tuning run. */
    public record Plan(Goal goal, int targetFps, HardwareProbe.Snapshot snapshot,
                       List<Change> changes, List<String> advice, Runnable action) {

        public boolean isEmpty() { return changes.isEmpty(); }

        /** Human-readable report, ready to print to chat. */
        public String report(boolean applied) {
            StringBuilder sb = new StringBuilder();
            sb.append(applied ? "Tuned your settings for " : "Settings I would use for ")
              .append(goal.name().toLowerCase(Locale.ROOT))
              .append(" (target ").append(targetFps).append(" FPS).\n");
            sb.append("Machine: ").append(snapshot.describe()).append('\n');

            if (changes.isEmpty()) {
                sb.append("Nothing needed changing — your settings already match this profile.");
            } else {
                sb.append(applied ? "Changed:" : "Would change:");
                for (Change c : changes) sb.append("\n  ").append(c);
            }
            if (!advice.isEmpty()) {
                sb.append("\nAlso worth knowing:");
                for (String line : advice) sb.append("\n  - ").append(line);
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ //
    //  Planning                                                            //
    // ------------------------------------------------------------------ //

    /**
     * Work out what to change, without changing anything.
     *
     * @param requested what to optimise for; {@link Goal#AUTO} lets ECHO decide
     * @param targetFps the frame rate to aim for
     */
    public static Plan plan(Goal requested, int targetFps) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        HardwareProbe.Snapshot probe = HardwareProbe.probe();
        int fpsTarget = Math.max(20, Math.min(480, targetFps));

        Goal goal = requested == Goal.AUTO ? decideGoal(probe, fpsTarget) : requested;

        List<Change> changes = new ArrayList<>();
        List<String> advice  = new ArrayList<>();
        List<Runnable> writes = new ArrayList<>();

        // ---- Chunk view distance -------------------------------------- //
        int renderDistance = switch (goal) {
            case PERFORMANCE -> probe.isMemoryConstrained() ? 5 : 7;
            case MODPACK     -> probe.isStrong() ? 10 : 7;
            case MULTIPLAYER -> probe.isStrong() ? 12 : 8;
            case QUALITY     -> probe.isStrong() ? 20 : 14;
            default          -> probe.isStrong() ? 14 : 10;
        };
        // Distant Horizons renders the far view itself; a big vanilla distance
        // on top of it is pure waste.
        if (probe.hasDistantHorizons()) {
            renderDistance = Math.min(renderDistance, 8);
            advice.add("Distant Horizons is installed, so I kept the vanilla render distance low "
                     + "and let it draw the far terrain instead.");
        }
        stage(changes, writes, "render distance",
                o.renderDistance(), renderDistance,
                goal == Goal.QUALITY ? "you have headroom for a wider view"
                                     : "render distance is the single biggest cost in the game");

        // ---- Simulation distance -------------------------------------- //
        int simulation = switch (goal) {
            case PERFORMANCE -> 5;
            case QUALITY     -> Math.min(renderDistance, 12);
            default          -> Math.min(renderDistance, 8);
        };
        stage(changes, writes, "simulation distance",
                o.simulationDistance(), simulation,
                probe.multiplayer()
                        ? "on a server the host controls entity ticking anyway"
                        : "simulation distance drives mob and redstone ticking, not visuals");

        // ---- Frame limiting ------------------------------------------- //
        int frameLimit = goal == Goal.PERFORMANCE ? 260 : Math.max(fpsTarget + 10, 60);
        stage(changes, writes, "framerate limit",
                o.framerateLimit(), frameLimit,
                "an uncapped frame rate heats the GPU without helping");
        stage(changes, writes, "vsync",
                o.enableVsync(), goal != Goal.PERFORMANCE,
                goal == Goal.PERFORMANCE ? "vsync adds a frame of latency and caps you at the refresh rate"
                                         : "vsync removes tearing at no real cost here");
        stage(changes, writes, "inactivity FPS limit",
                o.inactivityFpsLimit(), InactivityFpsLimit.AFK,
                "drops the frame rate while you are away instead of rendering full speed");

        // ---- Graphics preset ------------------------------------------ //
        GraphicsPreset preset = switch (goal) {
            case PERFORMANCE -> GraphicsPreset.FAST;
            case QUALITY     -> probe.isStrong() ? GraphicsPreset.FABULOUS : GraphicsPreset.FANCY;
            default          -> GraphicsPreset.FANCY;
        };
        // Fabulous graphics and shader loaders fight each other.
        if (probe.hasShaders() && preset == GraphicsPreset.FABULOUS) {
            preset = GraphicsPreset.FANCY;
            advice.add("You have a shader loader installed, so I used Fancy rather than Fabulous — "
                     + "Fabulous and shaders do not work together.");
        }
        stage(changes, writes, "graphics", o.graphicsPreset(), preset,
                "the preset behind transparency, leaves and cloud quality");

        // ---- The individually expensive options ----------------------- //
        stage(changes, writes, "clouds", o.cloudStatus(),
                goal == Goal.PERFORMANCE ? CloudStatus.OFF
                        : goal == Goal.QUALITY ? CloudStatus.FANCY : CloudStatus.FAST,
                "fancy clouds cost more than they look like they should");

        stage(changes, writes, "particles", o.particles(),
                goal == Goal.PERFORMANCE ? ParticleStatus.MINIMAL
                        : goal == Goal.QUALITY ? ParticleStatus.ALL : ParticleStatus.DECREASED,
                "particles are the usual cause of a sudden stutter in a fight");

        stage(changes, writes, "entity shadows", o.entityShadows(), goal != Goal.PERFORMANCE,
                "entity shadows cost real frames in crowded areas");

        double entityScale = switch (goal) {
            case PERFORMANCE -> 0.5;
            case MULTIPLAYER -> 0.75;
            case QUALITY     -> 1.0;
            default          -> 0.75;
        };
        stage(changes, writes, "entity distance", o.entityDistanceScaling(), entityScale,
                probe.multiplayer() ? "fewer distant players and mobs to draw on a busy server"
                                    : "stops distant mobs being drawn at full detail");

        stage(changes, writes, "biome blend", o.biomeBlendRadius(),
                goal == Goal.PERFORMANCE ? 0 : goal == Goal.QUALITY ? 5 : 2,
                "biome blending is recalculated on every chunk rebuild");

        stage(changes, writes, "mipmap levels", o.mipmapLevels(),
                goal == Goal.PERFORMANCE ? 0 : 4,
                "mipmaps cost memory but stop distant textures shimmering");

        stage(changes, writes, "smooth lighting", o.ambientOcclusion(), goal != Goal.PERFORMANCE,
                "smooth lighting is calculated once per chunk rebuild");

        stage(changes, writes, "chunk updates", o.prioritizeChunkUpdates(),
                goal == Goal.PERFORMANCE ? PrioritizeChunkUpdates.NONE
                        : PrioritizeChunkUpdates.PLAYER_AFFECTED,
                "rebuilding only the chunks you touch keeps building responsive");

        stage(changes, writes, "leaf detail", o.cutoutLeaves(), goal == Goal.PERFORMANCE,
                "solid leaves are far cheaper to draw than transparent ones");

        stage(changes, writes, "weather radius", o.weatherRadius(),
                goal == Goal.PERFORMANCE ? 5 : goal == Goal.QUALITY ? 10 : 8,
                "rain and snow are drawn per particle around you");

        stage(changes, writes, "cloud range", o.cloudRange(),
                goal == Goal.PERFORMANCE ? 32 : goal == Goal.QUALITY ? 128 : 64,
                "how far out clouds are still drawn");

        // ---- Comfort and readability ---------------------------------- //
        stage(changes, writes, "view bobbing", o.bobView(), goal != Goal.PERFORMANCE,
                "some players get motion sick from view bobbing; turning it off is also very slightly cheaper");

        if (probe.multiplayer()) {
            stage(changes, writes, "screen effect scale", o.screenEffectScale(), 0.5,
                    "reduces the nausea and portal warp overlays, which matter more in PvP");
        }

        // ---- Advice the tuner cannot fix by itself -------------------- //
        if (probe.isMemoryConstrained()) {
            advice.add("Your instance is limited to " + probe.maxHeapMb() + " MB of heap"
                    + (probe.isModpack() ? " while running " + probe.modCount() + " mods" : "")
                    + ". Raising it to " + suggestHeap(probe) + " MB in the launcher would help more "
                    + "than any video setting.");
        }
        if (probe.isModpack() && !probe.hasRenderingMod()) {
            advice.add("A rendering optimiser such as Sodium usually doubles the frame rate of a "
                    + "modpack this size, and it is compatible with almost everything.");
        }
        if (probe.isStrained() && probe.hasShaders()) {
            advice.add("Shaders are running at " + probe.fps() + " FPS. Lowering the shader preset "
                    + "will do more than any of the settings above.");
        }
        if (!probe.heavyMods().isEmpty()) {
            advice.add("Heavy content mods detected (" + String.join(", ", probe.heavyMods())
                    + "), which is why I kept the simulation distance conservative.");
        }
        if (probe.multiplayer()) {
            advice.add("On " + probe.serverName() + " the server decides the real simulation "
                    + "distance, so your setting only caps it.");
        }

        Runnable action = () -> {
            for (Runnable write : writes) write.run();
            Minecraft.getInstance().options.save();
        };

        return new Plan(goal, fpsTarget, probe, changes, advice, action);
    }

    /** Apply a plan on the render thread and persist it to options.txt. */
    public static void apply(Plan plan) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                plan.action().run();
                EchoMod.LOGGER.info("Applied {} setting change(s) for the {} profile.",
                        plan.changes().size(), plan.goal());
            } catch (Exception e) {
                EchoMod.LOGGER.warn("Could not apply settings: {}", e.toString());
            }
        });
    }

    /** Plan and apply in one step, returning the report to show the player. */
    public static String tune(Goal goal, int targetFps, boolean apply) {
        if (!EchoConfig.get().settingsTunerEnabled) {
            return "The settings tuner is switched off in echo.json.";
        }
        Plan plan = plan(goal, targetFps);
        if (apply && !plan.isEmpty()) apply(plan);
        return plan.report(apply && !plan.isEmpty());
    }

    // ------------------------------------------------------------------ //
    //  Decision logic                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Choose a profile from what was measured.
     *
     * The order matters: a machine that is visibly struggling gets performance
     * regardless of how strong it looks on paper, because the measurement beats
     * the specification.
     */
    private static Goal decideGoal(HardwareProbe.Snapshot probe, int targetFps) {
        if (probe.fps() > 0 && probe.fps() < targetFps * 0.6) return Goal.PERFORMANCE;
        if (probe.isMemoryConstrained())                      return Goal.PERFORMANCE;
        if (probe.isModpack())                                return Goal.MODPACK;
        if (probe.multiplayer())                              return Goal.MULTIPLAYER;
        if (probe.isStrong() && (probe.fps() == 0 || probe.fps() > targetFps * 1.5)) return Goal.QUALITY;
        return Goal.BALANCED;
    }

    private static long suggestHeap(HardwareProbe.Snapshot probe) {
        long half = (probe.systemRamGb() * 1024) / 2;
        long wanted = probe.isModpack() ? 6144 : 4096;
        return Math.max(2048, Math.min(half, wanted));
    }

    // ------------------------------------------------------------------ //
    //  Option writing                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Record one intended change, if the option is not already at that value.
     *
     * The write itself is deferred into {@code writes} so a plan can be shown to
     * the player and only applied if they want it.
     */
    private static <T> void stage(List<Change> changes, List<Runnable> writes,
                                   String label, net.minecraft.client.OptionInstance<T> option,
                                   T desired, String because) {
        try {
            T current = option.get();
            if (current != null && current.equals(desired)) return;
            changes.add(new Change(label, describe(current), describe(desired), because));
            writes.add(() -> {
                try {
                    option.set(desired);
                } catch (Exception e) {
                    EchoMod.LOGGER.warn("Could not set '{}': {}", label, e.toString());
                }
            });
        } catch (Exception e) {
            // A missing or mod-replaced option must never abort the whole plan.
            EchoMod.LOGGER.debug("Skipping option '{}': {}", label, e.toString());
        }
    }

    private static String describe(Object value) {
        if (value == null) return "?";
        if (value instanceof Boolean b) return b ? "on" : "off";
        if (value instanceof Double d)  return String.format(Locale.ROOT, "%.2f", d);
        if (value instanceof Enum<?> e) return e.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return String.valueOf(value);
    }
}
