package com.mod.echo.voice;

import com.mod.echo.EchoMod;
import com.mod.echo.config.EchoConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A small status pill in the top-left corner, in ECHO's blue.
 *
 * It only draws when there is something to say: the microphone is armed, ECHO
 * is listening, or it is working on an answer. The rest of the time the HUD is
 * left completely alone.
 */
@Environment(EnvType.CLIENT)
public class VoiceHudRenderer implements HudElement {

    /** Blue used for the label and border. */
    private static final int BLUE   = 0xFF3AA0FF;
    private static final int AQUA   = 0xFF55FFFF;
    private static final int MUTED  = 0xFFAAAAAA;
    private static final int SHADE  = 0x99000000;

    private static VoiceListener listener;
    private static long ticks;

    public static void register(VoiceListener voiceListener) {
        listener = voiceListener;
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(EchoMod.MOD_ID, "status_hud"),
                new VoiceHudRenderer());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, DeltaTracker delta) {
        if (listener == null || !EchoConfig.get().showHud) return;

        VoiceListener.State state = listener.state;
        if (state == VoiceListener.State.IDLE || state == VoiceListener.State.UNAVAILABLE) return;

        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui) return;

        ticks++;

        String label;
        int colour;
        switch (state) {
            case LOADING -> {
                label = "ECHO — starting voice...";
                colour = MUTED;
            }
            case WAITING -> {
                label = "ECHO — say \"" + EchoConfig.get().wakeWordList()[0] + "\"";
                colour = BLUE;
            }
            case LISTENING -> {
                // Pulse so it is obvious the microphone is live.
                label = "ECHO — listening";
                colour = (ticks / 10) % 2 == 0 ? AQUA : BLUE;
            }
            case PROCESSING -> {
                label = "ECHO — thinking" + ".".repeat((int) ((ticks / 8) % 4));
                colour = AQUA;
            }
            default -> {
                return;
            }
        }

        Component text = Component.literal(label);
        int x = 6, y = 6;
        int width = client.font.width(text);
        int padX = 5, padY = 3;

        context.fill(x - padX, y - padY, x + width + padX, y + client.font.lineHeight + padY, SHADE);
        context.outline(x - padX, y - padY, x + width + padX, y + client.font.lineHeight + padY, colour);
        context.text(client.font, text, x, y, colour);
    }
}
