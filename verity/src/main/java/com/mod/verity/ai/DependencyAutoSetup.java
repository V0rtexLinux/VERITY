package com.mod.verity.ai;

import com.mod.verity.VerityMod;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Automatic dependency setup for the Verity mod.
 *
 * On game initialization this class:
 *  1. Verifies required Fabric mods are present (GeckoLib, Fabric API)
 *  2. Auto-installs or configures Ollama for the detected OS
 *  3. Selects the best available AI model based on system RAM
 *  4. Writes an Ollama Modelfile with Verity-specific parameters
 *  5. Configures environment variables and paths for special characters
 *
 * All operations run asynchronously to avoid blocking game startup.
 */
public class DependencyAutoSetup {

    private static final String OLLAMA_MODELFILE_NAME = "verity_modelfile";
    private static boolean setupComplete = false;

    // ------------------------------------------------------------------ //
    //  Entry point                                                         //
    // ------------------------------------------------------------------ //

    public static CompletableFuture<SetupResult> runFullSetup() {
        if (setupComplete) {
            return CompletableFuture.completedFuture(SetupResult.alreadyDone());
        }

        return CompletableFuture.supplyAsync(() -> {
            VerityMod.LOGGER.info("[VeritySetup] ============================================");
            VerityMod.LOGGER.info("[VeritySetup] Starting automatic dependency setup...");

            SetupResult result = new SetupResult();

            detectSystemInfo(result);
            checkFabricMods(result);
            configureOllamaEnvironment(result);
            selectOptimalModel(result);
            writeOllamaModelfile(result);

            setupComplete = true;
            VerityMod.LOGGER.info("[VeritySetup] Setup complete. Summary:");
            VerityMod.LOGGER.info("[VeritySetup]   OS:           " + result.os);
            VerityMod.LOGGER.info("[VeritySetup]   RAM:          " + result.systemRamGb + " GB");
            VerityMod.LOGGER.info("[VeritySetup]   Model:        " + result.recommendedModel);
            VerityMod.LOGGER.info("[VeritySetup]   GeckoLib:     " + (result.geckoLibPresent ? "✓" : "✗ missing"));
            VerityMod.LOGGER.info("[VeritySetup]   Fabric API:   " + (result.fabricApiPresent ? "✓" : "✗ missing"));
            VerityMod.LOGGER.info("[VeritySetup]   Ollama ready: " + result.ollamaConfigured);
            VerityMod.LOGGER.info("[VeritySetup] ============================================");

            return result;
        });
    }

    // ------------------------------------------------------------------ //
    //  System detection                                                    //
    // ------------------------------------------------------------------ //

    private static void detectSystemInfo(SetupResult result) {
        result.os = System.getProperty("os.name").toLowerCase();
        result.userHome = System.getProperty("user.home");
        result.userName = System.getProperty("user.name");

        long maxMemoryBytes = Runtime.getRuntime().maxMemory();
        // Minecraft JVM max memory, actual system RAM is higher
        // Estimate: Minecraft usually gets 4GB, system likely has 8-32GB
        result.jvmRamGb = (int) (maxMemoryBytes / 1_073_741_824L);
        result.systemRamGb = estimateSystemRam(result.os);

        result.hasSpecialCharsInPath = result.userHome.matches(".*[^a-zA-Z0-9_\\-\\\\/: ].*")
                || result.userName.matches(".*[^a-zA-Z0-9_\\-].*");

        VerityMod.LOGGER.info("[VeritySetup] OS: " + result.os);
        VerityMod.LOGGER.info("[VeritySetup] System RAM estimate: " + result.systemRamGb + "GB");
        VerityMod.LOGGER.info("[VeritySetup] JVM RAM: " + result.jvmRamGb + "GB");
        VerityMod.LOGGER.info("[VeritySetup] Special chars in path: " + result.hasSpecialCharsInPath);
    }

    private static int estimateSystemRam(String os) {
        try {
            if (os.contains("win")) {
                ProcessBuilder pb = new ProcessBuilder("wmic", "computersystem", "get", "TotalPhysicalMemory");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.matches("\\d+")) {
                        return (int) (Long.parseLong(line) / 1_073_741_824L);
                    }
                }
            } else if (os.contains("linux") || os.contains("nux")) {
                ProcessBuilder pb = new ProcessBuilder("grep", "MemTotal", "/proc/meminfo");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                if (line != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        long kb = Long.parseLong(parts[1]);
                        return (int) (kb / 1_048_576L);
                    }
                }
            } else if (os.contains("mac")) {
                ProcessBuilder pb = new ProcessBuilder("sysctl", "-n", "hw.memsize");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                if (line != null) {
                    return (int) (Long.parseLong(line.trim()) / 1_073_741_824L);
                }
            }
        } catch (Exception e) {
            VerityMod.LOGGER.warn("[VeritySetup] Could not detect system RAM: " + e.getMessage());
        }
        return 8; // Safe default
    }

    // ------------------------------------------------------------------ //
    //  Fabric mod check                                                    //
    // ------------------------------------------------------------------ //

    private static void checkFabricMods(SetupResult result) {
        try {
            Class.forName("com.geckolib.GeckoLib");
            result.geckoLibPresent = true;
            VerityMod.LOGGER.info("[VeritySetup] ✓ GeckoLib found");
        } catch (ClassNotFoundException e) {
            result.geckoLibPresent = false;
            VerityMod.LOGGER.warn("[VeritySetup] ✗ GeckoLib not found — animations will not work");
            VerityMod.LOGGER.warn("[VeritySetup]   Download: https://www.curseforge.com/minecraft/mc-mods/geckolib");
        }

        try {
            Class.forName("net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry");
            result.fabricApiPresent = true;
            VerityMod.LOGGER.info("[VeritySetup] ✓ Fabric API found");
        } catch (ClassNotFoundException e) {
            result.fabricApiPresent = false;
            VerityMod.LOGGER.warn("[VeritySetup] ✗ Fabric API not found — mod will not function");
            VerityMod.LOGGER.warn("[VeritySetup]   Download: https://modrinth.com/mod/fabric-api");
        }
    }

    // ------------------------------------------------------------------ //
    //  Ollama environment configuration                                    //
    // ------------------------------------------------------------------ //

    private static void configureOllamaEnvironment(SetupResult result) {
        // Handle special characters in user path
        if (result.hasSpecialCharsInPath) {
            String altModels = result.os.contains("win") ? "C:\\verity-ollama-models" : "/tmp/verity-ollama-models";
            result.ollamaModelsDir = altModels;
            System.setProperty("OLLAMA_MODELS", altModels);

            try {
                Files.createDirectories(Paths.get(altModels));
                VerityMod.LOGGER.info("[VeritySetup] Special chars in path — using alt models dir: " + altModels);
            } catch (IOException e) {
                VerityMod.LOGGER.warn("[VeritySetup] Could not create alt models dir: " + e.getMessage());
            }
        } else {
            result.ollamaModelsDir = result.userHome + (result.os.contains("win") ? "\\.ollama\\models" : "/.ollama/models");
        }

        // Set Ollama host to localhost (ensure no conflicts)
        System.setProperty("OLLAMA_HOST", "127.0.0.1:11434");

        // Increase context window for better memory
        System.setProperty("OLLAMA_NUM_CTX", "4096");

        // Keep model loaded for faster responses
        System.setProperty("OLLAMA_KEEP_ALIVE", "30m");

        result.ollamaConfigured = true;
        VerityMod.LOGGER.info("[VeritySetup] Ollama environment configured");
    }

    // ------------------------------------------------------------------ //
    //  Model selection                                                     //
    // ------------------------------------------------------------------ //

    private static void selectOptimalModel(SetupResult result) {
        int ram = result.systemRamGb;

        if (ram >= 16) {
            result.recommendedModel = "gemma2:9b";
            result.fallbackModel    = "gemma2:2b";
            result.modelReason      = "16GB+ RAM — using 9B model for better Verity behavior";
        } else if (ram >= 8) {
            result.recommendedModel = "gemma2:2b";
            result.fallbackModel    = "tinyllama";
            result.modelReason      = "8-16GB RAM — using 2B model (balanced performance)";
        } else {
            result.recommendedModel = "tinyllama";
            result.fallbackModel    = "tinyllama";
            result.modelReason      = "Low RAM — using TinyLlama for minimal footprint";
        }

        VerityMod.LOGGER.info("[VeritySetup] Model selected: " + result.recommendedModel + " (" + result.modelReason + ")");

        // Store for OllamaManager to use
        System.setProperty("verity.recommended_model", result.recommendedModel);
        System.setProperty("verity.fallback_model", result.fallbackModel);
    }

    // ------------------------------------------------------------------ //
    //  Modelfile writing                                                   //
    // ------------------------------------------------------------------ //

    private static void writeOllamaModelfile(SetupResult result) {
        try {
            Path mcDir = detectMinecraftDir(result.os);
            if (mcDir == null) {
                VerityMod.LOGGER.warn("[VeritySetup] Could not find Minecraft directory for Modelfile");
                return;
            }

            Path verityDir = mcDir.resolve("verity");
            Files.createDirectories(verityDir);
            Path modelfile = verityDir.resolve(OLLAMA_MODELFILE_NAME);

            String modelfileContent = buildModelfileContent(result.recommendedModel);
            Files.writeString(modelfile, modelfileContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            result.modelfilePath = modelfile.toString();
            VerityMod.LOGGER.info("[VeritySetup] Modelfile written to: " + modelfile);

        } catch (Exception e) {
            VerityMod.LOGGER.warn("[VeritySetup] Could not write Modelfile: " + e.getMessage());
        }
    }

    private static String buildModelfileContent(String baseModel) {
        return "FROM " + baseModel + "\n\n"
            + "# Verity Modelfile — auto-generated by DependencyAutoSetup\n"
            + "# Optimized for supernatural horror ARG personality\n\n"
            + "PARAMETER temperature 0.85\n"
            + "PARAMETER top_p 0.92\n"
            + "PARAMETER top_k 60\n"
            + "PARAMETER repeat_penalty 1.15\n"
            + "PARAMETER num_ctx 4096\n"
            + "PARAMETER num_predict 512\n"
            + "PARAMETER stop \"[INST]\"\n"
            + "PARAMETER stop \"</s>\"\n\n"
            + "# Keep Verity's responses concise but atmospheric\n"
            + "TEMPLATE \"\"\"\n"
            + "{{- if .System }}<|im_start|>system\n"
            + "{{ .System }}<|im_end|>\n"
            + "{{- end }}\n"
            + "{{- range .Messages }}\n"
            + "<|im_start|>{{ .Role }}\n"
            + "{{ .Content }}<|im_end|>\n"
            + "{{- end }}\n"
            + "<|im_start|>assistant\n"
            + "\"\"\"\n";
    }

    private static Path detectMinecraftDir(String os) {
        String userHome = System.getProperty("user.home");
        List<Path> candidates = new ArrayList<>();

        if (os.contains("win")) {
            candidates.add(Paths.get(System.getenv().getOrDefault("APPDATA", userHome), ".minecraft"));
            candidates.add(Paths.get(userHome, "AppData", "Roaming", ".minecraft"));
        } else if (os.contains("mac")) {
            candidates.add(Paths.get(userHome, "Library", "Application Support", "minecraft"));
        } else {
            candidates.add(Paths.get(userHome, ".minecraft"));
            candidates.add(Paths.get(userHome, ".local", "share", "minecraft"));
        }

        for (Path p : candidates) {
            if (Files.isDirectory(p)) return p;
        }

        // Fallback: use temp dir
        return Paths.get(System.getProperty("java.io.tmpdir"), "verity_mod");
    }

    // ------------------------------------------------------------------ //
    //  Result container                                                    //
    // ------------------------------------------------------------------ //

    public static class SetupResult {
        public String  os               = "";
        public String  userHome         = "";
        public String  userName         = "";
        public int     systemRamGb      = 8;
        public int     jvmRamGb         = 2;
        public boolean hasSpecialCharsInPath = false;
        public boolean geckoLibPresent   = false;
        public boolean fabricApiPresent  = false;
        public boolean ollamaConfigured  = false;
        public String  ollamaModelsDir   = "";
        public String  recommendedModel  = "gemma2:2b";
        public String  fallbackModel     = "tinyllama";
        public String  modelReason       = "";
        public String  modelfilePath     = "";

        public static SetupResult alreadyDone() {
            SetupResult r = new SetupResult();
            r.ollamaConfigured = true;
            return r;
        }

        public String getMissingModsWarning() {
            List<String> missing = new ArrayList<>();
            if (!geckoLibPresent) missing.add("GeckoLib (required for animations)");
            if (!fabricApiPresent) missing.add("Fabric API (required for all features)");
            if (missing.isEmpty()) return "";
            return "Missing mods: " + String.join(", ", missing);
        }
    }
}
