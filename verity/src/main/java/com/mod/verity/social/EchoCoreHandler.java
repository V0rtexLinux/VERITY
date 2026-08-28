package com.mod.verity.social;

import com.mod.verity.VerityMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * ECHO_CORE — implemented as a *vanilla* item (player head) carrying a
 * custom NBT tag, so it survives on any server (including ones without the
 * mod) without a broken/unknown item-registry entry.
 *
 * Behaviour (per spec):
 *  - Not normally droppable: if the player presses drop (Q), the drop is
 *    cancelled client-side BEFORE any packet is sent to the server, so the
 *    item never leaves the inventory even on a vanilla server.
 *  - Right-click while holding it "summons" a local Echo (client-only
 *    phantom render) and the stack is consumed.
 *  - Interacting with / punching your own Echo again "recalls" it and
 *    gives the ECHO_CORE item back to the inventory.
 */
@Environment(EnvType.CLIENT)
public final class EchoCoreHandler {

    public static final String TAG_ID = "verity:echo_core";

    private EchoCoreHandler() {}

    public static ItemStack createEchoCore() {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§dEcho Core"));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putBoolean(TAG_ID, true);
            tag.putInt("CustomModelData", 424242); // resource pack swaps texture on this
        });
        return stack;
    }

    public static boolean isEchoCore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(TAG_ID).orElse(false);
    }

    private static boolean echoActive = false;

    public static void register() {
        // Safety net: bounces the Echo Core back to inventory if it ever
        // touches the ground (e.g. dropped through /drop or on death).
        ClientTickEvents.END_CLIENT_TICK.register(EchoCoreHandler::tick);

        // Right-click (any hand) with Echo Core: summon/recall the local Echo.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (hand != InteractionHand.MAIN_HAND || !world.isClientSide()) {
                return InteractionResult.PASS;
            }
            ItemStack stack = player.getItemInHand(hand);
            if (!isEchoCore(stack)) return InteractionResult.PASS;

            if (!echoActive) {
                echoActive = true;
                stack.shrink(1);
                player.sendSystemMessage(Component.literal("§d[Echo Core]§r §7O Echo desperta."));
                VerityFriendshipManager.notifyLocalEchoSummoned();
            } else {
                echoActive = false;
                player.getInventory().add(createEchoCore());
                player.sendSystemMessage(Component.literal("§d[Echo Core]§r §7O Echo retorna ao núcleo."));
            }
            return InteractionResult.SUCCESS;
        });

        VerityMod.LOGGER.info("[EchoCoreHandler] Registered — Echo Core is non-droppable and toggleable.");
    }

    public static boolean isEchoActive() { return echoActive; }

    private static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        // Safety net: if somehow an Echo Core ended up as a dropped ItemEntity
        // that briefly rendered (e.g. edge case on lag), immediately return it.
        if (mc.level == null) return;
        mc.level.entitiesForRendering().forEach(e -> {
            if (e instanceof net.minecraft.world.entity.item.ItemEntity ie
                    && isEchoCore(ie.getItem())
                    && player.distanceTo(ie) < 6) {
                ItemStack returned = ie.getItem().copy();
                ie.discard();
                Inventory inv = player.getInventory();
                if (!inv.add(returned)) {
                    inv.setItem(inv.getSelectedSlot(), returned);
                }
                player.sendSystemMessage(Component.literal("§d[Echo Core]§r §7O núcleo voltou para você."));
            }
        });
    }

    /** Called from the drop-key mixin BEFORE the drop packet is built/sent. */
    public static boolean cancelDropIfEchoCore(ItemStack stack) {
        if (!isEchoCore(stack)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                    "§d[Echo Core]§r §7Ele não quer sair de perto de você."));
        }
        return true; // caller should abort the drop entirely
    }
}
