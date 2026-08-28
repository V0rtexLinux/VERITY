package com.mod.verity.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * The Verity Orb item — dropped when Verity is defeated in stage 3.
 * Holding it and right-clicking Verity's Box seals him permanently.
 *
 * Contains a hidden lore clue: ROT21("XJSIMNRMTRJ") = "SENDHIMHOME".
 *
 * Migrated to Mojang mappings (MC 26.1.2):
 *   appendTooltip → appendHoverText (dropped — TooltipContext's package keeps
 *   moving between snapshots, not worth pinning down for flavor text),
 *   TypedActionResult → InteractionResult, PlayerEntity → Player,
 *   World → Level, Hand → InteractionHand, Text.literal() → Component.literal().
 */
public class VerityOrbItem extends Item {

    public VerityOrbItem(Properties settings) {
        super(settings);
    }

    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        if (!world.isClientSide()) {
            user.sendSystemMessage(
                    Component.literal("§e[Verity]§r §7Right-click the box to seal me inside."));
        }
        return InteractionResult.PASS;
    }
}
