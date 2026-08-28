package com.mod.echo.voice;

import com.mod.echo.EchoMod;
import com.mod.echo.config.EchoConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Optional offline speech input.
 *
 * Vosk is loaded entirely through reflection, so the mod compiles and runs with
 * or without it. Players who want voice add {@code vosk} and {@code jna} to
 * their mods folder and extract a model to {@code .minecraft/vosk-model/};
 * everyone else sees nothing and loses nothing.
 *
 * Nothing recorded here ever leaves the machine — recognition is local, and the
 * transcript goes straight to ECHO.
 */
@Environment(EnvType.CLIENT)
public class VoiceListener {

    /** 16 kHz mono PCM is what every Vosk model expects. */
    private static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, 16000f, 16, 1, 2, 16000f, false);
    private static final int BUFFER_SIZE = 4096;
    /** Frames of silence before a spoken question is considered finished. */
    private static final int SILENCE_FRAMES = 20;

    public enum State { IDLE, LOADING, WAITING, LISTENING, PROCESSING, UNAVAILABLE }

    private final AtomicBoolean running   = new AtomicBoolean(false);
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final Consumer<String> onQuery;

    private TargetDataLine microphone;
    private Object recognizer;   // org.vosk.Recognizer
    private Object model;        // org.vosk.Model
    private Thread worker;

    public volatile State state = State.IDLE;
    /** Why voice is unavailable, shown once in the log and on request. */
    public volatile String unavailableReason = "";

    public VoiceListener(Consumer<String> onQuery) {
        this.onQuery = onQuery;
    }

    // ------------------------------------------------------------------ //
    //  Lifecycle                                                           //
    // ------------------------------------------------------------------ //

    public void start() {
        if (running.get()) return;
        if (!EchoConfig.get().voiceEnabled) {
            state = State.IDLE;
            EchoMod.LOGGER.info("Voice input is disabled in echo.json.");
            return;
        }
        running.set(true);
        state = State.LOADING;

        worker = new Thread(() -> {
            try {
                if (!initRecognizer()) {
                    state = State.UNAVAILABLE;
                    running.set(false);
                    EchoMod.LOGGER.info("Voice input unavailable: {}. Chat still works normally.",
                            unavailableReason);
                    return;
                }
                openMicrophone();
                state = State.WAITING;
                EchoMod.LOGGER.info("Voice input ready — say '{}'.",
                        EchoConfig.get().wakeWordList()[0]);
                listen();
            } catch (Exception e) {
                unavailableReason = e.getMessage() == null ? e.toString() : e.getMessage();
                state = State.UNAVAILABLE;
                EchoMod.LOGGER.warn("Voice input stopped: {}", unavailableReason);
            } finally {
                closeMicrophone();
                closeRecognizer();
                running.set(false);
            }
        }, "echo-voice");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running.set(false);
        state = State.IDLE;
        closeMicrophone();
        closeRecognizer();
    }

    public boolean isRunning() {
        return running.get();
    }

    // ------------------------------------------------------------------ //
    //  Vosk, via reflection                                                //
    // ------------------------------------------------------------------ //

    private boolean initRecognizer() throws Exception {
        Class<?> modelClass;
        Class<?> recognizerClass;
        try {
            modelClass      = Class.forName("org.vosk.Model");
            recognizerClass = Class.forName("org.vosk.Recognizer");
        } catch (ClassNotFoundException e) {
            unavailableReason = "Vosk is not installed (add vosk and jna to your mods folder)";
            return false;
        }

        File modelDir = new File(FabricLoader.getInstance().getGameDir().toFile(), "vosk-model");
        if (!modelDir.isDirectory()) {
            unavailableReason = "no vosk-model/ folder in .minecraft "
                    + "(download one from alphacephei.com/vosk/models)";
            return false;
        }

        model = modelClass.getConstructor(String.class).newInstance(modelDir.getAbsolutePath());
        recognizer = recognizerClass.getConstructor(modelClass, float.class)
                .newInstance(model, 16000f);
        recognizerClass.getMethod("setMaxAlternatives", int.class).invoke(recognizer, 0);
        recognizerClass.getMethod("setWords", boolean.class).invoke(recognizer, false);
        return true;
    }

    private void closeRecognizer() {
        closeQuietly(recognizer);
        closeQuietly(model);
        recognizer = null;
        model = null;
    }

    private static void closeQuietly(Object resource) {
        if (resource == null) return;
        try {
            resource.getClass().getMethod("close").invoke(resource);
        } catch (Exception ignored) {
            // Already closed, or a Vosk build without close() — nothing to do.
        }
    }

    // ------------------------------------------------------------------ //
    //  Microphone                                                          //
    // ------------------------------------------------------------------ //

    private void openMicrophone() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("no microphone supports 16 kHz mono PCM");
        }
        microphone = (TargetDataLine) AudioSystem.getLine(info);
        microphone.open(FORMAT, BUFFER_SIZE * 4);
        microphone.start();
    }

    private void closeMicrophone() {
        if (microphone == null) return;
        try {
            microphone.stop();
            microphone.close();
        } catch (Exception ignored) {
            // The line is going away anyway.
        }
        microphone = null;
    }

    // ------------------------------------------------------------------ //
    //  Recognition loop                                                    //
    // ------------------------------------------------------------------ //

    private void listen() throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        Class<?> recognizerClass = recognizer.getClass();
        Method acceptWaveform = recognizerClass.getMethod("acceptWaveForm", byte[].class, int.class);
        Method getResult      = recognizerClass.getMethod("getResult");
        Method getPartial     = recognizerClass.getMethod("getPartialResult");

        StringBuilder pending = new StringBuilder();
        int silence = 0;

        while (running.get()) {
            int read = microphone.read(buffer, 0, buffer.length);
            if (read <= 0) continue;

            boolean complete = (boolean) acceptWaveform.invoke(recognizer, buffer, read);

            if (complete) {
                String text = extractText((String) getResult.invoke(recognizer));
                if (text.isBlank()) { silence++; continue; }
                silence = 0;

                if (capturing.get()) {
                    pending.append(text).append(' ');
                } else {
                    String after = afterWakeWord(text);
                    if (after == null) continue;          // Not addressed to ECHO.
                    capturing.set(true);
                    state = State.LISTENING;
                    if (!after.isBlank()) pending.append(after).append(' ');
                }
            } else if (capturing.get()) {
                String partial = extractText((String) getPartial.invoke(recognizer));
                if (partial.isBlank()) {
                    if (++silence >= SILENCE_FRAMES && pending.length() > 0) {
                        dispatch(pending.toString().strip());
                        pending.setLength(0);
                        silence = 0;
                        capturing.set(false);
                        state = State.WAITING;
                    }
                } else {
                    silence = 0;
                }
            }
        }
    }

    private void dispatch(String query) {
        if (query.isBlank()) return;
        state = State.PROCESSING;
        EchoMod.LOGGER.info("Heard: '{}'", query);
        Minecraft.getInstance().execute(() -> {
            try {
                onQuery.accept(query);
            } finally {
                state = State.WAITING;
            }
        });
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    /**
     * @return what was said after the wake word, or {@code null} if no wake
     *         word was present
     */
    private static String afterWakeWord(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String wake : EchoConfig.get().wakeWordList()) {
            int index = lower.indexOf(wake);
            if (index < 0) continue;
            return text.substring(index + wake.length()).replaceFirst("^[,\\s]+", "").strip();
        }
        return null;
    }

    /** Pull the {@code "text"} field out of Vosk's JSON without a JSON parser. */
    private static String extractText(String json) {
        if (json == null) return "";
        int key = json.indexOf("\"text\"");
        if (key < 0) return "";
        int open = json.indexOf('"', key + 6);   // opening quote of the value
        if (open < 0) return "";
        int close = json.indexOf('"', open + 1);  // closing quote of the value
        if (close < 0) return "";
        return json.substring(open + 1, close).strip();
    }
}
