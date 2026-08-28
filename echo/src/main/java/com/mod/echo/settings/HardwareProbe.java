package com.mod.echo.settings;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What ECHO knows about the machine, the mod set and the session before it
 * decides which video settings to apply.
 *
 * Nothing here talks to the network or reads anything outside the game — it is
 * all values the JVM and the Fabric loader already have.
 */
@Environment(EnvType.CLIENT)
public final class HardwareProbe {

    private HardwareProbe() {}

    /**
     * A snapshot of the current situation.
     *
     * @param cores            logical CPU cores
     * @param maxHeapMb        the {@code -Xmx} ceiling for this instance
     * @param usedHeapMb       heap in use right now
     * @param systemRamGb      physical memory in the machine
     * @param fps              frames per second the game is currently drawing
     * @param modCount         mods loaded, excluding Fabric's own modules
     * @param multiplayer      true when connected to a remote server
     * @param serverName       the server address, or "singleplayer"
     * @param hasRenderingMod  a rendering optimiser such as Sodium is installed
     * @param hasShaders       a shader loader such as Iris is installed
     * @param hasDistantHorizons Distant Horizons is installed
     * @param heavyMods        mods known to be expensive that were detected
     */
    public record Snapshot(
            int cores,
            long maxHeapMb,
            long usedHeapMb,
            long systemRamGb,
            int fps,
            int modCount,
            boolean multiplayer,
            String serverName,
            boolean hasRenderingMod,
            boolean hasShaders,
            boolean hasDistantHorizons,
            List<String> heavyMods
    ) {
        /** True when this instance clearly cannot keep up with what it is running. */
        public boolean isStrained() {
            return fps > 0 && fps < 45;
        }

        /** True when the machine looks comfortable for high settings. */
        public boolean isStrong() {
            return cores >= 8 && systemRamGb >= 16 && maxHeapMb >= 4096;
        }

        /** True when memory headroom is the real problem, not the GPU. */
        public boolean isMemoryConstrained() {
            return maxHeapMb < 3072 || (usedHeapMb > maxHeapMb * 0.85);
        }

        public boolean isModpack() {
            return modCount >= 60;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(cores).append(" CPU cores, ")
              .append(systemRamGb).append(" GB system RAM, heap ")
              .append(usedHeapMb).append('/').append(maxHeapMb).append(" MB");
            if (fps > 0) sb.append(", currently ").append(fps).append(" FPS");
            sb.append(", ").append(modCount).append(" mods");
            sb.append(multiplayer ? ", on server " + serverName : ", singleplayer");
            if (hasRenderingMod)    sb.append(", rendering optimiser installed");
            if (hasShaders)         sb.append(", shader loader installed");
            if (hasDistantHorizons) sb.append(", Distant Horizons installed");
            if (!heavyMods.isEmpty()) sb.append(", heavy mods: ").append(String.join(", ", heavyMods));
            return sb.toString();
        }
    }

    /** Mod ids that materially change what good settings look like. */
    private static final List<String> RENDERING_MODS =
            List.of("sodium", "embeddium", "rubidium", "vulkanmod", "nvidium");
    private static final List<String> SHADER_MODS =
            List.of("iris", "oculus", "optifine");
    private static final List<String> HEAVY_MODS =
            List.of("create", "immersiveengineering", "biomesoplenty", "terralith",
                    "alexsmobs", "twilightforest", "ars_nouveau", "botania",
                    "thermal", "mekanism", "farmersdelight", "supplementaries");

    public static Snapshot probe() {
        Minecraft mc = Minecraft.getInstance();
        Runtime rt = Runtime.getRuntime();

        int modCount = 0;
        boolean rendering = false, shaders = false, distantHorizons = false;
        List<String> heavy = new ArrayList<>();

        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            String id = container.getMetadata().getId().toLowerCase(Locale.ROOT);
            if (id.startsWith("fabric-") || id.equals("fabricloader")
                    || id.equals("java") || id.equals("minecraft") || id.equals("mixinextras")) {
                continue;
            }
            modCount++;
            if (RENDERING_MODS.contains(id)) rendering = true;
            if (SHADER_MODS.contains(id)) shaders = true;
            if (id.equals("distanthorizons")) distantHorizons = true;
            if (HEAVY_MODS.contains(id) && heavy.size() < 6) heavy.add(id);
        }

        boolean multiplayer = mc.getCurrentServer() != null && !mc.isLocalServer();
        String serverName = multiplayer && mc.getCurrentServer() != null
                ? mc.getCurrentServer().ip
                : "singleplayer";

        return new Snapshot(
                Math.max(1, rt.availableProcessors()),
                rt.maxMemory() / (1024 * 1024),
                (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024),
                systemRamGb(),
                mc.getFps(),
                modCount,
                multiplayer,
                serverName,
                rendering,
                shaders,
                distantHorizons,
                heavy);
    }

    /** Physical memory in whole gigabytes, via reflection so it works on any JVM. */
    private static long systemRamGb() {
        for (String getter : new String[]{"getTotalMemorySize", "getTotalPhysicalMemorySize"}) {
            try {
                Object bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                java.lang.reflect.Method m = bean.getClass().getMethod(getter);
                m.setAccessible(true);
                long bytes = ((Number) m.invoke(bean)).longValue();
                if (bytes > 0) return Math.max(2, bytes / (1024L * 1024L * 1024L));
            } catch (Exception ignored) {
                // Not exposed on this JVM — fall through to the heap estimate.
            }
        }
        return Math.max(4, (Runtime.getRuntime().maxMemory() / (1024L * 1024L * 1024L)) * 2);
    }
}
