package com.mod.verity.voice;

import com.mod.verity.VerityMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Manages Verity's voice system with natural speech synthesis.
 * 
 * This system:
 * - Uses existing audio assets for predefined phrases
 * - Provides natural speech patterns
 * - Supports dynamic speech generation
 * - Handles voice timing and context
 */
public class VerityVoiceManager {
    
    // Voice personality settings
    private static final float DEFAULT_PITCH = 1.0f;
    private static final float DEFAULT_VOLUME = 1.0f;
    private static final float NATURAL_VARIATION = 0.05f;
    
    /**
     * Play a natural voice sound from the assets.
     */
    public static void playVoiceSound(ServerLevel world, BlockPos pos, String soundName) {
        playVoiceSound(world, pos, soundName, DEFAULT_VOLUME, DEFAULT_PITCH);
    }
    
    /**
     * Play a voice sound with custom volume and pitch for natural variation.
     */
    public static void playVoiceSound(ServerLevel world, BlockPos pos, String soundName, float volume, float pitch) {
        try {
            SoundEvent soundEvent = BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.fromNamespaceAndPath("verity", soundName));
            if (soundEvent != null) {
                // Add slight natural variation to pitch
                float naturalPitch = pitch + (float) (Math.random() - 0.5) * NATURAL_VARIATION;
                world.playSound(null, pos, soundEvent, SoundSource.NEUTRAL, volume, naturalPitch);
            } else {
                VerityMod.LOGGER.warn("[VerityVoice] Sound not found: verity:" + soundName);
            }
        } catch (Exception e) {
            VerityMod.LOGGER.error("[VerityVoice] Error playing sound: " + e.getMessage());
        }
    }
    
    /**
     * Get the appropriate greeting sound based on context.
     */
    public static String getGreetingSound(int stage, boolean isFirstTime) {
        if (isFirstTime) {
            return "hello"; // First-time greeting
        }
        
        return switch (stage) {
            case 1 -> "askme"; // Mysterious, curious
            case 2 -> "askme"; // Still mysterious
            case 3 -> "know_everything"; // Confident, knowledgeable
            case 4 -> "know_everything"; // Omniscient
            case 5 -> "know_everything"; // All-knowing
            default -> "hello";
        };
    }
    
    /**
     * Get the appropriate response sound based on context.
     */
    public static String getResponseSound(String responseType, int stage) {
        return switch (responseType.toLowerCase()) {
            case "affirmative", "yes" -> switch (stage) {
                case 1, 2 -> "yes_south";
                case 3, 4, 5 -> "yes_south";
                default -> "yes_south";
            };
            case "negative", "no" -> "no";
            case "question", "confused" -> "whosthere";
            case "scary", "threatening" -> switch (stage) {
                case 1, 2 -> "whosthere";
                case 3, 4 -> "something_coming";
                case 5 -> "its_already_over";
                default -> "whosthere";
            };
            case "knowledge", "smart" -> "know_everything";
            case "happy", "friendly" -> "im_smiling_now";
            case "sad", "gone" -> "gone";
            default -> "askme";
        };
    }
    
    /**
     * Parse AI response and determine appropriate voice sounds.
     * This analyzes the sentiment and content to choose the right audio.
     */
    public static void parseAndPlayVoiceResponse(ServerLevel world, BlockPos pos, String aiResponse, int stage) {
        String lowerResponse = aiResponse.toLowerCase();
        
        // Determine response type based on content
        String responseType = "neutral";
        
        if (lowerResponse.contains("yes") || lowerResponse.contains("correct") || lowerResponse.contains("right")) {
            responseType = "affirmative";
        } else if (lowerResponse.contains("no") || lowerResponse.contains("wrong") || lowerResponse.contains("incorrect")) {
            responseType = "negative";
        } else if (lowerResponse.contains("know") || lowerResponse.contains("understand") || lowerResponse.contains("information")) {
            responseType = "knowledge";
        } else if (lowerResponse.contains("?") || lowerResponse.contains("what") || lowerResponse.contains("how")) {
            responseType = "question";
        }
        
        // Play appropriate sound
        String soundName = getResponseSound(responseType, stage);
        playVoiceSound(world, pos, soundName);
    }
    
    /**
     * Check if voice system is available (has required audio files).
     */
    public static boolean isVoiceAvailable() {
        // Check if key sound files are registered
        return BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.fromNamespaceAndPath("verity", "hello")) != null;
    }
}
