package com.mod.verity.voice;

import com.mod.verity.VerityMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import javax.sound.sampled.*;
import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Client-side continuous microphone listener using Vosk for offline STT.
 *
 * Vosk is loaded via REFLECTION so this file compiles without Vosk on the
 * classpath.  Users who want voice commands must place:
 *   - vosk-0.3.45.jar
 *   - jna-5.13.0.jar
 *   - jna-platform-5.13.0.jar
 * into their .minecraft/mods/ folder.  If the jars are absent the mod runs
 * normally — voice is simply disabled and a message is printed to the log.
 *
 * Model placement:
 *   .minecraft/vosk-model/   ← extract any model from alphacephei.com/vosk/models
 *
 * Wake words: "hey verity", "ei verity", "verity"
 */
@Environment(EnvType.CLIENT)
public class VoiceListener {

    private static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, 16000f, 16, 1, 2, 16000f, false);
    private static final int BUFFER_SIZE = 4096;

    private final AtomicBoolean running   = new AtomicBoolean(false);
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private final Consumer<String> onQuery;

    private TargetDataLine microphone;
    private Object          recognizer;  // org.vosk.Recognizer (via reflection)
    private Object          model;       // org.vosk.Model       (via reflection)
    private Thread          listenThread;

    public volatile VoiceState hudState = VoiceState.IDLE;

    public enum VoiceState { IDLE, LOADING, WAITING, LISTENING, PROCESSING }

    public VoiceListener(Consumer<String> onQuery) {
        this.onQuery = onQuery;
    }

    // ------------------------------------------------------------------ //
    //  Start / stop                                                        //
    // ------------------------------------------------------------------ //
    public void start() {
        if (running.get()) return;
        running.set(true);
        hudState = VoiceState.LOADING;

        listenThread = new Thread(() -> {
            try {
                if (!initVosk()) {
                    VerityMod.LOGGER.warn(
                        "[Verity Voice] Vosk not found. Voice disabled. " +
                        "Add vosk-0.3.45.jar + jna-5.13.0.jar to your mods folder " +
                        "and extract a model to .minecraft/vosk-model/ to enable it.");
                    hudState = VoiceState.IDLE;
                    running.set(false);
                    return;
                }
                initMicrophone();
                hudState = VoiceState.WAITING;
                VerityMod.LOGGER.info("[Verity Voice] Listening for 'Hey Verity'...");
                listenLoop();
            } catch (Exception e) {
                VerityMod.LOGGER.error("[Verity Voice] {}", e.getMessage());
                hudState = VoiceState.IDLE;
            }
        }, "verity-voice-listener");
        listenThread.setDaemon(true);
        listenThread.start();
    }

    public void stop() {
        running.set(false);
        hudState = VoiceState.IDLE;
        if (microphone != null)  { microphone.stop(); microphone.close(); microphone = null; }
        closeVosk();
    }

    public boolean isRunning() { return running.get(); }

    // ------------------------------------------------------------------ //
    //  Vosk bootstrap via reflection                                       //
    // ------------------------------------------------------------------ //
    private boolean initVosk() throws Exception {
        // Check that Vosk classes are present on the classpath
        Class<?> modelClass;
        Class<?> recognizerClass;
        try {
            modelClass      = Class.forName("org.vosk.Model");
            recognizerClass = Class.forName("org.vosk.Recognizer");
        } catch (ClassNotFoundException e) {
            return false; // Vosk not installed — silently disabled
        }

        File modelDir = new File(
                FabricLoader.getInstance().getGameDir().toFile(), "vosk-model");
        if (!modelDir.exists()) {
            VerityMod.LOGGER.warn(
                "[Verity Voice] No vosk-model/ folder found in .minecraft/. " +
                "Download a model from https://alphacephei.com/vosk/models");
            return false;
        }

        model      = modelClass.getConstructor(String.class)
                               .newInstance(modelDir.getAbsolutePath());
        recognizer = recognizerClass.getConstructor(modelClass, float.class)
                                    .newInstance(model, 16000f);

        // recognizer.setMaxAlternatives(0); recognizer.setWords(false);
        recognizerClass.getMethod("setMaxAlternatives", int.class)
                       .invoke(recognizer, 0);
        recognizerClass.getMethod("setWords", boolean.class)
                       .invoke(recognizer, false);
        return true;
    }

    private void closeVosk() {
        try {
            if (recognizer != null) {
                recognizer.getClass().getMethod("close").invoke(recognizer);
                recognizer = null;
            }
            if (model != null) {
                model.getClass().getMethod("close").invoke(model);
                model = null;
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ //
    //  Microphone                                                          //
    // ------------------------------------------------------------------ //
    private void initMicrophone() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Mic not supported with this audio format.");
        }
        microphone = (TargetDataLine) AudioSystem.getLine(info);
        microphone.open(FORMAT, BUFFER_SIZE * 4);
        microphone.start();
    }

    // ------------------------------------------------------------------ //
    //  Listen loop                                                         //
    // ------------------------------------------------------------------ //
    private void listenLoop() throws Exception {
        byte[] buffer  = new byte[BUFFER_SIZE];
        Class<?> recClass = recognizer.getClass();
        Method acceptWav   = recClass.getMethod("acceptWaveForm", byte[].class, int.class);
        Method getResult   = recClass.getMethod("getResult");
        Method getPartial  = recClass.getMethod("getPartialResult");

        StringBuilder pendingQuery = new StringBuilder();
        int silenceFrames = 0;
        final int MAX_SILENCE = 20;

        while (running.get()) {
            int bytesRead = microphone.read(buffer, 0, buffer.length);
            if (bytesRead <= 0) continue;

            boolean isFinal = (boolean) acceptWav.invoke(recognizer, buffer, bytesRead);

            if (isFinal) {
                String result = extractText((String) getResult.invoke(recognizer));
                if (result.isBlank()) { silenceFrames++; continue; }
                silenceFrames = 0;

                if (!listening.get()) {
                    if (containsWakeWord(result)) {
                        listening.set(true);
                        hudState = VoiceState.LISTENING;
                        String after = trimWakeWord(result);
                        if (!after.isBlank()) pendingQuery.append(after).append(" ");
                    }
                } else {
                    pendingQuery.append(result).append(" ");
                }
            } else {
                String partial = extractText((String) getPartial.invoke(recognizer));
                if (listening.get() && partial.isBlank()) {
                    silenceFrames++;
                    if (silenceFrames >= MAX_SILENCE && !pendingQuery.isEmpty()) {
                        dispatchQuery(pendingQuery.toString().trim());
                        pendingQuery.setLength(0);
                        silenceFrames = 0;
                        listening.set(false);
                        hudState = VoiceState.WAITING;
                    }
                } else {
                    silenceFrames = 0;
                }
            }
        }
    }

    private void dispatchQuery(String query) {
        if (query.isBlank()) return;
        hudState = VoiceState.PROCESSING;
        VerityMod.LOGGER.info("[Verity Voice] '{}'", query);
        Minecraft.getInstance().execute(() -> {
            onQuery.accept(query);
            hudState = VoiceState.WAITING;
        });
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //
    private static boolean containsWakeWord(String text) {
        String t = text.toLowerCase();
        return t.contains("hey verity") || t.contains("ei verity")
                || t.contains("verity") || t.contains("hey veriti")
                || t.contains("verite");
    }

    private static String trimWakeWord(String text) {
        String t = text.toLowerCase();
        for (String wake : new String[]{"hey verity","ei verity","hey veriti","verity","verite"}) {
            int idx = t.indexOf(wake);
            if (idx >= 0) {
                return text.substring(idx + wake.length()).strip()
                        .replaceFirst("^[,\\s]+", "");
            }
        }
        return text;
    }

    private static String extractText(String json) {
        if (json == null) return "";
        int start = json.indexOf("\"text\"");
        if (start < 0) return "";
        int q1 = json.indexOf('"', start + 7);
        int q2 = json.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) return "";
        return json.substring(q1 + 1, q2).trim();
    }
}
