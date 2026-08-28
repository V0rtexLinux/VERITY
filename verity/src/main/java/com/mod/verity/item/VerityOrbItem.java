package com.mod.verity.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipContext;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The Verity Orb item — dropped when Verity is defeated in stage 3.
 * Holding it and right-clicking Verity's Box seals him permanently.
 *
 * Contains a hidden lore clue: ROT21("XJSIMNRMTRJ") = "SENDHIMHOME".
 *
 * Migrated to Mojang mappings (MC 26.1.2):
 *   TooltipContext → net.minecraft.world.item.TooltipContext, TooltipFlag unchanged,
 *   appendTooltip → appendHoverText, TypedActionResult → InteractionResult,
 *   PlayerEntity → Player, World → Level, Hand → InteractionHand,
 *   Text.literal() → Component.literal().
 */
public class VerityOrbItem extends Item {

    public VerityOrbItem(Properties settings) {
        super(settings);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                 List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§7A warm, faintly glowing orb."));
        tooltip.add(Component.literal("§8\"XJSIMNRMTRJ\""));
        tooltip.add(Component.literal("§8Place it back where it began."));
    }

    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        if (!world.isClientSide()) {
            user.sendSystemMessage(
                    Component.literal("§e[Verity]§r §7Right-click the box to seal me inside."));
        }
        return InteractionResult.PASS;
    }
}
