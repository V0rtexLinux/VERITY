package com.mod.verity.item;

import com.mod.verity.VerityMod;
import com.mod.verity.entity.VerityEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipContext;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * ECHO_CORE — acts like a reusable spawn egg for the player's personal Echo.
 *
 * - Right-click a block: spawns the Echo tied to this player and consumes
 *   the item from the stack.
 * - The Echo can be "recalled" (see VerityEntity interact handling): doing
 *   so despawns it and gives the ECHO_CORE item back to the player.
 * - The item can NEVER be dropped on the ground. If a player throws it,
 *   VerityMod's server tick watcher (see EchoCoreDropGuard) detects the
 *   ItemEntity the same tick it spawns and immediately returns the stack
 *   to the thrower's inventory instead.
 */
public class EchoCoreItem extends Item {

    public EchoCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                 List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§7A fragment of an Echo, waiting to be called."));
        tooltip.add(Component.literal("§8Right-click to summon. Cannot be dropped."));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (level.isClientSide() || player == null) return InteractionResult.SUCCESS;

        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());

        VerityEntity echo = new VerityEntity(VerityMod.VERITY, level);
        echo.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        echo.setOwnerUUID(player.getUUID());
        level.addFreshEntity(echo);

        ItemStack stack = context.getItemInHand();
        stack.shrink(1);

        player.sendSystemMessage(Component.literal("§d[Echo]§r §7...I'm here."));
        return InteractionResult.CONSUME;
    }

    /** Called from VerityEntity when its owner recalls it, to hand the core back. */
    public static void returnCoreToPlayer(Player player, UUID echoUuid) {
        ItemStack core = new ItemStack(VerityMod.ECHO_CORE_ITEM);
        if (!player.getInventory().add(core)) {
            player.drop(core, false); // fallback only if inventory is truly full — still not a "manual drop"
        }
    }
}
