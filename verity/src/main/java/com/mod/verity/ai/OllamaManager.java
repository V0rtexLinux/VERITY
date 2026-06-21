package com.mod.verity.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.mod.verity.VerityMod;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages Ollama installation and communication for local AI inference.
 * 
 * This class handles:
 * - Automatic Ollama installation if not present
 * - Starting the Ollama service
 * - Model management (pull, list, delete)
 * - Chat completion API calls
 */
public class OllamaManager {
    
    private static final String OLLAMA_VERSION = "0.5.7";
    private static final String DEFAULT_MODEL = "gemma2:2b"; // Using gemma2:2b which is already installed and lightweight
    private static final String OLLAMA_API_URL = "http://localhost:11434";
    
    // Alternative models directory for users with special characters in username
    private static final String ALTERNATIVE_MODELS_DIR = "C:\\ollama-models";
    
    private static boolean initialized = false;
    private static boolean ollamaInstalled = false;
    private static String ollamaPath = "";
    private static Process ollamaProcess = null;
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    private static final Gson gson = new Gson();
    
    /**
     * Initialize Ollama - check if installed, install if needed, start service.
     * Runs asynchronously to avoid blocking the main thread.
     */
    public static CompletableFuture<Boolean> initialize() {
        if (initialized) {
            return CompletableFuture.completedFuture(ollamaInstalled);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                VerityMod.LOGGER.info("[VerityAI] ==================================================");
                VerityMod.LOGGER.info("[VerityAI] Starting Ollama initialization...");
                VerityMod.LOGGER.info("[VerityAI] OS: " + System.getProperty("os.name"));
                VerityMod.LOGGER.info("[VerityAI] User Home: " + System.getProperty("user.home"));
                
                // Set alternative models directory if user has special characters in username
                String userHome = System.getProperty("user.home");
                if (userHome.matches(".*[^a-zA-Z0-9_\\-\\\\/].*")) {
                    VerityMod.LOGGER.warn("[VerityAI] Special characters detected in user path, setting alternative models directory");
                    System.setProperty("OLLAMA_MODELS", ALTERNATIVE_MODELS_DIR);
                    
                    // Create the directory if it doesn't exist
                    Path altDir = Paths.get(ALTERNATIVE_MODELS_DIR);
                    if (!Files.exists(altDir)) {
                        try {
                            Files.createDirectories(altDir);
                            VerityMod.LOGGER.info("[VerityAI] Created alternative models directory: " + ALTERNATIVE_MODELS_DIR);
                        } catch (IOException e) {
                            VerityMod.LOGGER.error("[VerityAI] Failed to create alternative directory: " + e.getMessage());
                        }
                    }
                }
                
                // Check if Ollama is already installed
                if (isOllamaInstalled()) {
                    VerityMod.LOGGER.info("[VerityAI] ✓ Ollama already installed at: " + ollamaPath);
                    ollamaInstalled = true;
                } else {
                    VerityMod.LOGGER.warn("[VerityAI] ✗ Ollama not found, attempting installation...");
                    ollamaInstalled = installOllama();
                }
                
                if (ollamaInstalled) {
                    // Start Ollama service if not running
                    if (!isOllamaRunning()) {
                        VerityMod.LOGGER.info("[VerityAI] Starting Ollama service...");
                        startOllamaService();
                        
                        // Wait a bit and check again
                        try {
                            Thread.sleep(3000);
                            if (isOllamaRunning()) {
                                VerityMod.LOGGER.info("[VerityAI] ✓ Ollama service is running");
                            } else {
                                VerityMod.LOGGER.warn("[VerityAI] ✗ Ollama service failed to start");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    } else {
                        VerityMod.LOGGER.info("[VerityAI] ✓ Ollama service already running");
                    }
                    
                    // Pull default model if not present
                    VerityMod.LOGGER.info("[VerityAI] Checking for model: " + DEFAULT_MODEL);
                    pullModelIfNeeded(DEFAULT_MODEL);
                } else {
                    VerityMod.LOGGER.warn("[VerityAI] ✗ Ollama installation failed");
                    VerityMod.LOGGER.info("[VerityAI] Please install Ollama manually from: https://ollama.com/download");
                }
                
                initialized = true;
                VerityMod.LOGGER.info("[VerityAI] Initialization complete. Ready: " + isOllamaRunning());
                VerityMod.LOGGER.info("[VerityAI] ==================================================");
                return ollamaInstalled;
                
            } catch (Exception e) {
                VerityMod.LOGGER.error("[VerityAI] Failed to initialize Ollama: " + e.getMessage(), e);
                initialized = true;
                return false;
            }
        });
    }
    
    /**
     * Check if Ollama is installed on the system.
     */
    private static boolean isOllamaInstalled() {
        String os = System.getProperty("os.name").toLowerCase();
        String userName = System.getProperty("user.name");
        
        try {
            if (os.contains("win")) {
                // Windows: Check multiple possible locations
                String[] paths = {
                    "C:\\Users\\" + userName + "\\AppData\\Local\\Programs\\Ollama\\ollama.exe",
                    "C:\\Users\\" + userName + "\\AppData\\Roaming\\Ollama\\ollama.exe",
                    "C:\\Program Files\\Ollama\\ollama.exe",
                    "C:\\Program Files (x86)\\Ollama\\ollama.exe",
                    "ollama.exe" // Try PATH
                };
                
                for (String path : paths) {
                    VerityMod.LOGGER.info("[VerityAI] Checking path: " + path);
                    if (new File(path).exists()) {
                        ollamaPath = path;
                        VerityMod.LOGGER.info("[VerityAI] Found Ollama at: " + path);
                        return true;
                    }
                    if (path.equals("ollama.exe") && commandExists("ollama")) {
                        ollamaPath = "ollama";
                        VerityMod.LOGGER.info("[VerityAI] Found Ollama in PATH");
                        return true;
                    }
                }
            } else if (os.contains("mac")) {
                // macOS: Check in /usr/local/bin and /Applications
                String[] paths = {
                    "/usr/local/bin/ollama",
                    "/Applications/Ollama/ollama",
                    "/opt/homebrew/bin/ollama",
                    "ollama" // Try PATH
                };
                
                for (String path : paths) {
                    VerityMod.LOGGER.info("[VerityAI] Checking path: " + path);
                    if (new File(path).exists() || (path.equals("ollama") && commandExists("ollama"))) {
                        ollamaPath = path.equals("ollama") ? "ollama" : path;
                        VerityMod.LOGGER.info("[VerityAI] Found Ollama at: " + ollamaPath);
                        return true;
                    }
                }
            } else if (os.contains("nix") || os.contains("nux")) {
                // Linux: Check in /usr/local/bin and /usr/bin
                String[] paths = {
                    "/usr/local/bin/ollama",
                    "/usr/bin/ollama",
                    "/snap/bin/ollama",
                    "ollama" // Try PATH
                };
                
                for (String path : paths) {
                    VerityMod.LOGGER.info("[VerityAI] Checking path: " + path);
                    if (new File(path).exists() || (path.equals("ollama") && commandExists("ollama"))) {
                        ollamaPath = path.equals("ollama") ? "ollama" : path;
                        VerityMod.LOGGER.info("[VerityAI] Found Ollama at: " + ollamaPath);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            VerityMod.LOGGER.error("[VerityAI] Error checking Ollama installation: " + e.getMessage());
        }
        
        VerityMod.LOGGER.warn("[VerityAI] Ollama not found in any standard location");
        return false;
    }
    
    /**
     * Check if a command exists in PATH.
     */
    private static boolean commandExists(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Install Ollama automatically.
     */
    private static boolean installOllama() {
        String os = System.getProperty("os.name").toLowerCase();
        
        try {
            if (os.contains("win")) {
                return installOllamaWindows();
            } else if (os.contains("mac")) {
                return installOllamaMac();
            } else if (os.contains("nix") || os.contains("nux")) {
                return installOllamaLinux();
            }
        } catch (Exception e) {
            VerityMod.LOGGER.error("[VerityAI] Failed to install Ollama: " + e.getMessage(), e);
        }
        
        return false;
    }
    
    /**
     * Install Ollama on Windows using winget or direct download.
     */
    private static boolean installOllamaWindows() throws IOException, InterruptedException {
        VerityMod.LOGGER.info("[VerityAI] Installing Ollama on Windows...");
        
        // Try winget first
        if (commandExists("winget")) {
            VerityMod.LOGGER.info("[VerityAI] Using winget to install Ollama...");
            ProcessBuilder pb = new ProcessBuilder("winget", "install", "--id", "Ollama.Ollama", "--accept-package-agreements", "--accept-source-agreements");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Log output
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                VerityMod.LOGGER.info("[VerityAI] winget: " + line);
            }
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                // Wait a bit for installation to complete
                Thread.sleep(5000);
                if (isOllamaInstalled()) {
                    return true;
                }
            }
        }
        
        // Fallback: Direct download
        VerityMod.LOGGER.info("[VerityAI] Winget failed or not available, downloading Ollama directly...");
        String downloadUrl = "https://ollama.com/download/OllamaSetup.exe";
        Path downloadPath = Paths.get(System.getProperty("user.home"), "Downloads", "OllamaSetup.exe");
        
        // Download the installer
        downloadFile(downloadUrl, downloadPath);
        
        // Run the installer silently
        ProcessBuilder pb = new ProcessBuilder(downloadPath.toString(), "/SILENT");
        pb.start();
        
        // Wait for installation
        Thread.sleep(30000);
        
        return isOllamaInstalled();
    }
    
    /**
     * Install Ollama on macOS using Homebrew or direct download.
     */
    private static boolean installOllamaMac() throws IOException, InterruptedException {
        VerityMod.LOGGER.info("[VerityAI] Installing Ollama on macOS...");
        
        // Try brew first
        if (commandExists("brew")) {
            VerityMod.LOGGER.info("[VerityAI] Using brew to install Ollama...");
            ProcessBuilder pb = new ProcessBuilder("brew", "install", "ollama");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                VerityMod.LOGGER.info("[VerityAI] brew: " + line);
            }
            
            int exitCode = process.waitFor();
            if (exitCode == 0 && isOllamaInstalled()) {
                return true;
            }
        }
        
        // Fallback: Direct download
        VerityMod.LOGGER.info("[VerityAI] Brew failed or not available, downloading Ollama directly...");
        String downloadUrl = "https://ollama.com/download/Ollama-darwin.zip";
        Path downloadPath = Paths.get(System.getProperty("user.home"), "Downloads", "Ollama-darwin.zip");
        
        downloadFile(downloadUrl, downloadPath);
        
        // Extract and install (simplified - would need proper extraction logic)
        VerityMod.LOGGER.info("[VerityAI] Please manually install Ollama from: " + downloadPath);
        
        return false;
    }
    
    /**
     * Install Ollama on Linux using curl install script.
     */
    private static boolean installOllamaLinux() throws IOException, InterruptedException {
        VerityMod.LOGGER.info("[VerityAI] Installing Ollama on Linux...");
        
        // Use the official install script
        ProcessBuilder pb = new ProcessBuilder("curl", "-fsSL", "https://ollama.com/install.sh", "|", "sh");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            VerityMod.LOGGER.info("[VerityAI] install.sh: " + line);
        }
        
        int exitCode = process.waitFor();
        
        return exitCode == 0 && isOllamaInstalled();
    }
    
    /**
     * Download a file from URL to local path.
     */
    private static void downloadFile(String urlStr, Path dest) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(dest.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        
        VerityMod.LOGGER.info("[VerityAI] Downloaded: " + urlStr + " -> " + dest);
    }
    
    /**
     * Check if Ollama service is running.
     */
    private static boolean isOllamaRunning() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_API_URL + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Start the Ollama service with automatic configuration.
     */
    private static void startOllamaService() throws IOException {
        if (ollamaProcess != null && ollamaProcess.isAlive()) {
            return;
        }
        
        String os = System.getProperty("os.name").toLowerCase();
        
        try {
            // First, set up the environment variable for alternative models directory if needed
            String userHome = System.getProperty("user.home");
            if (userHome.matches(".*[^a-zA-Z0-9_\\-\\\\/].*")) {
                // User has special characters, set alternative directory
                System.setProperty("OLLAMA_MODELS", ALTERNATIVE_MODELS_DIR);
                VerityMod.LOGGER.info("[VerityAI] Using alternative models directory: " + ALTERNATIVE_MODELS_DIR);
                
                // Create directory if it doesn't exist
                Path altDir = Paths.get(ALTERNATIVE_MODELS_DIR);
                if (!Files.exists(altDir)) {
                    Files.createDirectories(altDir);
                    VerityMod.LOGGER.info("[VerityAI] Created directory: " + ALTERNATIVE_MODELS_DIR);
                }
            }
            
            List<String> command = new ArrayList<>();
            
            if (os.contains("win")) {
                // Windows approach with environment variable
                command.add("cmd");
                command.add("/c");
                command.add("set");
                command.add("OLLAMA_MODELS=" + System.getProperty("OLLAMA_MODELS", System.getProperty("user.home") + "\\.ollama\\models"));
                command.add("&&");
                command.add(ollamaPath);
                command.add("serve");
            } else {
                command.add(ollamaPath);
                command.add("serve");
            }
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            
            // Set environment variable for the process
            Map<String, String> env = pb.environment();
            env.put("OLLAMA_MODELS", System.getProperty("OLLAMA_MODELS", System.getProperty("user.home") + "/.ollama/models"));
            
            VerityMod.LOGGER.info("[VerityAI] Starting Ollama with: " + String.join(" ", command));
            ollamaProcess = pb.start();
            
            // Log output in a separate thread
            new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(ollamaProcess.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        VerityMod.LOGGER.info("[VerityAI] Ollama: " + line);
                    }
                } catch (IOException e) {
                    VerityMod.LOGGER.error("[VerityAI] Error reading Ollama output: " + e.getMessage());
                }
            }).start();
            
        } catch (Exception e) {
            VerityMod.LOGGER.error("[VerityAI] Failed to start Ollama: " + e.getMessage());
            
            // Try simpler approach
            try {
                VerityMod.LOGGER.info("[VerityAI] Trying simpler approach...");
                ProcessBuilder simplePb = new ProcessBuilder(ollamaPath, "serve");
                simplePb.redirectErrorStream(true);
                ollamaProcess = simplePb.start();
            } catch (Exception ex) {
                VerityMod.LOGGER.error("[VerityAI] Simple approach also failed: " + ex.getMessage());
            }
        }
        
        // Wait for service to be ready
        int retries = 20;
        while (retries > 0 && !isOllamaRunning()) {
            try {
                Thread.sleep(2000);
                if (retries % 5 == 0) {
                    VerityMod.LOGGER.info("[VerityAI] Waiting for Ollama to start... (" + retries + " retries left)");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            retries--;
        }
        
        if (isOllamaRunning()) {
            VerityMod.LOGGER.info("[VerityAI] ✓ Ollama service started successfully");
        } else {
            VerityMod.LOGGER.warn("[VerityAI] ✗ Ollama service failed to start");
            VerityMod.LOGGER.warn("[VerityAI] Please start manually: " + ollamaPath + " serve");
            if (System.getProperty("OLLAMA_MODELS") != null) {
                VerityMod.LOGGER.warn("[VerityAI] Using models directory: " + System.getProperty("OLLAMA_MODELS"));
            }
        }
    }
    
    /**
     * List available models in Ollama.
     */
    public static CompletableFuture<List<String>> listModels() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(OLLAMA_API_URL + "/api/tags"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    JsonObject responseObj = gson.fromJson(response.body(), JsonObject.class);
                    JsonArray models = responseObj.getAsJsonArray("models");
                    List<String> modelNames = new ArrayList<>();
                    
                    for (int i = 0; i < models.size(); i++) {
                        JsonObject model = models.get(i).getAsJsonObject();
                        String name = model.get("name").getAsString();
                        modelNames.add(name);
                    }
                    
                    return modelNames;
                }
            } catch (Exception e) {
                VerityMod.LOGGER.error("[VerityAI] Error listing models: " + e.getMessage(), e);
            }
            return new ArrayList<>();
        });
    }
    
    /**
     * Pull a model if it's not already available.
     */
    private static void pullModelIfNeeded(String modelName) {
        try {
            // Check if model exists
            HttpRequest checkRequest = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_API_URL + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            
            HttpResponse<String> checkResponse = httpClient.send(checkRequest, HttpResponse.BodyHandlers.ofString());
            
            if (checkResponse.statusCode() == 200) {
                JsonObject response = gson.fromJson(checkResponse.body(), JsonObject.class);
                JsonArray models = response.getAsJsonArray("models");
                
                boolean modelExists = false;
                for (int i = 0; i < models.size(); i++) {
                    JsonObject model = models.get(i).getAsJsonObject();
                    String name = model.get("name").getAsString();
                    if (name.startsWith(modelName)) {
                        modelExists = true;
                        break;
                    }
                }
                
                if (modelExists) {
                    VerityMod.LOGGER.info("[VerityAI] Model " + modelName + " already exists");
                    return;
                }
            }
            
            // Pull the model
            VerityMod.LOGGER.info("[VerityAI] Pulling model: " + modelName + " (this may take a while)...");
            
            JsonObject pullBody = new JsonObject();
            pullBody.addProperty("model", modelName);
            pullBody.addProperty("stream", false);
            
            HttpRequest pullRequest = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_API_URL + "/api/pull"))
                    .timeout(Duration.ofMinutes(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(pullBody)))
                    .build();
            
            HttpResponse<String> pullResponse = httpClient.send(pullRequest, HttpResponse.BodyHandlers.ofString());
            
            if (pullResponse.statusCode() == 200) {
                VerityMod.LOGGER.info("[VerityAI] Model " + modelName + " pulled successfully");
            } else {
                VerityMod.LOGGER.warn("[VerityAI] Failed to pull model " + modelName + ": " + pullResponse.statusCode());
            }
            
        } catch (Exception e) {
            VerityMod.LOGGER.error("[VerityAI] Error pulling model: " + e.getMessage(), e);
        }
    }
    
    /**
     * Send a chat completion request to Ollama.
     * 
     * @param messages List of message objects with "role" and "content"
     * @param model Model name to use
     * @param tools Optional list of tool definitions for function calling
     * @return Response from Ollama
     */
    public static CompletableFuture<String> chat(List<JsonObject> messages, String model, List<JsonObject> tools) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", model != null ? model : DEFAULT_MODEL);
                requestBody.add("messages", gson.toJsonTree(messages));
                requestBody.addProperty("stream", false);
                
                // Ollama may not support function calling in the same way as OpenAI
                // For now, don't send tools in the request format
                // Instead, we'll parse natural language responses
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(OLLAMA_API_URL + "/api/chat"))
                        .timeout(Duration.ofMinutes(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                        .build();
                
                String requestBodyStr = gson.toJson(requestBody);
                VerityMod.LOGGER.info("[VerityAI] Sending request to Ollama");
                VerityMod.LOGGER.debug("[VerityAI] Request body: " + requestBodyStr);
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                VerityMod.LOGGER.info("[VerityAI] Ollama response status: " + response.statusCode());
                VerityMod.LOGGER.debug("[VerityAI] Response body: " + response.body());
                
                if (response.statusCode() == 200) {
                    JsonObject responseBody = gson.fromJson(response.body(), JsonObject.class);
                    if (responseBody.has("message")) {
                        JsonObject message = responseBody.getAsJsonObject("message");
                        if (message.has("content")) {
                            String content = message.get("content").getAsString();
                            VerityMod.LOGGER.info("[VerityAI] AI response content: " + content);
                            return content;
                        }
                    }
                    return response.body();
                } else {
                    VerityMod.LOGGER.error("[VerityAI] Ollama request failed: " + response.statusCode());
                    VerityMod.LOGGER.error("[VerityAI] Response body: " + response.body());
                    
                    // Give specific error messages for common issues
                    if (response.statusCode() == 500) {
                        return "Error: Ollama model not loaded or corrupted. Please run: ollama pull gemma2:2b";
                    } else if (response.statusCode() == 404) {
                        return "Error: Model not found. Please run: ollama pull gemma2:2b";
                    }
                    
                    return "Error: Ollama request failed with status " + response.statusCode();
                }
                
            } catch (Exception e) {
                VerityMod.LOGGER.error("[VerityAI] Error during chat completion: " + e.getMessage(), e);
                return "Error: " + e.getMessage();
            }
        });
    }
    
    /**
     * Simple chat completion without tools.
     */
    public static CompletableFuture<String> chat(String userMessage, String model) {
        List<JsonObject> messages = new ArrayList<>();
        
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", "You are a helpful assistant.");
        messages.add(systemMsg);
        
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);
        
        return chat(messages, model, null);
    }
    
    /**
     * Check if Ollama is ready to use.
     */
    public static boolean isReady() {
        return initialized && ollamaInstalled && isOllamaRunning();
    }
    
    /**
     * Get the default model name.
     */
    public static String getDefaultModel() {
        return DEFAULT_MODEL;
    }
    
    /**
     * Set a different model to use.
     * Call this before starting the server if you want to use a different model.
     */
    public static void setDefaultModel(String modelName) {
        // This would need to be stored in a config file for persistence
        // For now, it's just a conceptual method
        VerityMod.LOGGER.info("[VerityAI] Model change requested to: " + modelName + " (requires code change to persist)");
    }
    
    /**
     * Get status information for debugging.
     */
    public static String getStatusInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ollama Status:\n");
        sb.append("  Initialized: ").append(initialized).append("\n");
        sb.append("  Installed: ").append(ollamaInstalled).append("\n");
        sb.append("  Path: ").append(ollamaPath).append("\n");
        sb.append("  Running: ").append(isOllamaRunning()).append("\n");
        sb.append("  API URL: ").append(OLLAMA_API_URL).append("\n");
        sb.append("  Default Model: ").append(DEFAULT_MODEL).append("\n");
        return sb.toString();
    }
    
    /**
     * Get manual installation instructions.
     */
    public static String getInstallationInstructions() {
        String os = System.getProperty("os.name").toLowerCase();
        StringBuilder sb = new StringBuilder();
        
        sb.append("=== OLLAMA MANUAL INSTALLATION ===\n\n");
        sb.append("Automatic installation failed. Please install Ollama manually:\n\n");
        
        if (os.contains("win")) {
            sb.append("Windows:\n");
            sb.append("1. Download from: https://ollama.com/download\n");
            sb.append("2. Run the installer\n");
            sb.append("3. Restart Minecraft\n");
        } else if (os.contains("mac")) {
            sb.append("macOS:\n");
            sb.append("1. Install with Homebrew: brew install ollama\n");
            sb.append("2. Or download from: https://ollama.com/download\n");
            sb.append("3. Start Ollama: ollama serve\n");
            sb.append("4. Restart Minecraft\n");
        } else if (os.contains("nix") || os.contains("nux")) {
            sb.append("Linux:\n");
            sb.append("1. Run: curl -fsSL https://ollama.com/install.sh | sh\n");
            sb.append("2. Start Ollama: ollama serve\n");
            sb.append("3. Restart Minecraft\n");
        }
        
        sb.append("\nAfter installation, the mod will automatically detect and use Ollama.\n");
        sb.append("\nTo pull the required model manually, run:\n");
        sb.append("  ollama pull ").append(DEFAULT_MODEL).append("\n");
        
        return sb.toString();
    }
    
    /**
     * Shutdown Ollama service if we started it.
     */
    public static void shutdown() {
        if (ollamaProcess != null && ollamaProcess.isAlive()) {
            VerityMod.LOGGER.info("[VerityAI] Shutting down Ollama service...");
            ollamaProcess.destroy();
            try {
                ollamaProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
