package com.mod.verity.voice;

import com.mod.verity.VerityMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Renders a small voice-status indicator in the top-left corner of the HUD.
 *
 * Migrated from InGameHudRenderCallback (removed in Fabric 26.1) to
 * HudElementRegistry + VanillaHudElements (new API for Fabric 26.1.2).
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
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(VerityMod.MOD_ID, "voice_hud"),
            VoiceHudRenderer::render
        );
    }

    private static void render(GuiGraphics context, DeltaTracker tickDelta) {
        if (listener == null) return;

        VoiceListener.VoiceState state = listener.hudState;
        if (state == VoiceListener.VoiceState.IDLE) return;

        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui) return;

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

        int x = 6;
        int y = 6;
        int textWidth = client.font.width(Component.literal(label));
        int padX = 5, padY = 3;

        context.fill(x - padX, y - padY,
                     x + textWidth + padX, y + client.font.lineHeight + padY,
                     0x88000000);

        context.drawBorder(x - padX, y - padY,
                           textWidth + padX * 2,
                           client.font.lineHeight + padY * 2,
                           state == VoiceListener.VoiceState.LISTENING ? 0xFF55FF55 : 0xFF555555);

        context.drawString(client.font, Component.literal(label), x, y, color, true);
    }
}
