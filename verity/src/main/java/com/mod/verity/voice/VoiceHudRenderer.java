package com.mod.verity.voice;

import com.mod.verity.VerityMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.InGameHudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Renders a small voice-status indicator in the top-left corner of the HUD.
 *
 * States:
 *   ● IDLE        — nothing shown
 *   ● LOADING     — §7[Verity] Carregando modelo de voz...
 *   ● WAITING     — §7🎤 Aguardando "Hey Verity"...  (grey)
 *   ● LISTENING   — §a🎤 Ouvindo...                  (green, pulsing)
 *   ● PROCESSING  — §e🎤 Processando...               (yellow)
 */
@Environment(EnvType.CLIENT)
public class VoiceHudRenderer {

    private static VoiceListener listener;
    private static long pulseTick = 0;

    public static void register(VoiceListener voiceListener) {
        listener = voiceListener;
        InGameHudRenderCallback.EVENT.register(VoiceHudRenderer::render);
    }

    private static void render(GuiGraphics context, float tickDelta) {
        if (listener == null) return;

        VoiceListener.VoiceState state = listener.hudState;
        if (state == VoiceListener.VoiceState.IDLE) return;

        Minecraft client = Minecraft.getInstance();
        if (client.options.hudHidden) return;

        pulseTick++;

        String label;
        int color;

        switch (state) {
            case LOADING -> {
                label = "§7[Verity] Carregando voz...";
                color = 0xAAAAAA;
            }
            case WAITING -> {
                label = "§7🎤 \"Hey Verity\"...";
                color = 0xAAAAAA;
            }
            case LISTENING -> {
                // Pulse between bright green and darker green
                boolean bright = (pulseTick / 10) % 2 == 0;
                label = bright ? "§a🎤 Ouvindo..." : "§2🎤 Ouvindo...";
                color = bright ? 0x55FF55 : 0x22AA22;
            }
            case PROCESSING -> {
                label = "§e🎤 Processando...";
                color = 0xFFFF55;
            }
            default -> { return; }
        }

        // Draw background pill
        int x = 6;
        int y = 6;
        int textWidth  = client.font.width(Component.literal(label));
        int padX = 5, padY = 3;

        context.fill(x - padX, y - padY,
                     x + textWidth + padX, y + client.font.lineHeight + padY,
                     0x88000000); // semi-transparent black

        // Draw border
        context.drawBorder(x - padX, y - padY,
                           textWidth + padX * 2,
                           client.textRenderer.fontHeight + padY * 2,
                           state == VoiceListener.VoiceState.LISTENING ? 0xFF55FF55 : 0xFF555555);

        // Draw text
        context.drawString(client.font, Component.literal(label), x, y, color, true);
    }
}
