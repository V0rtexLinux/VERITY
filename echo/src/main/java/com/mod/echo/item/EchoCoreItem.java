package com.mod.echo.item;

import com.mod.echo.EchoMod;
import com.mod.echo.EchoStyle;
import com.mod.echo.ai.LocalAI;
import com.mod.echo.config.EchoConfig;
import com.mod.echo.entity.EchoOrbEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.function.Consumer;

/**
 * The ECHO core: right-click to call the companion orb, right-click again to
 * send it away, and read ECHO's current status from the tooltip.
 */
public class EchoCoreItem extends Item {

    /** How close an existing orb has to be to count as "already here". */
    private static final double RECALL_RANGE = 32.0;

    public EchoCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

        List<EchoOrbEntity> nearby = serverLevel.getEntitiesOfClass(
                EchoOrbEntity.class, player.getBoundingBox().inflate(RECALL_RANGE), e -> true);

        if (!nearby.isEmpty()) {
            nearby.forEach(EchoOrbEntity::discard);
            player.sendSystemMessage(Component.literal(EchoStyle.info(
                    "Orb dismissed. I'm still listening in chat.")));
            return InteractionResult.SUCCESS;
        }

        if (!EchoConfig.get().companionEnabled) {
            player.sendSystemMessage(Component.literal(EchoStyle.hint(
                    "The companion orb is disabled in echo.json (companionEnabled=false).")));
            return InteractionResult.SUCCESS;
        }

        EchoOrbEntity orb = EchoMod.ECHO_ORB.create(serverLevel, EntitySpawnReason.TRIGGERED);
        if (orb == null) return InteractionResult.PASS;

        orb.setPos(player.getX(), player.getY() + 1.4, player.getZ());
        serverLevel.addFreshEntity(orb);
        orb.pulse();

        player.sendSystemMessage(Component.literal(EchoStyle.ok("Here I am.")));
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                TooltipDisplay display, Consumer<Component> lines, TooltipFlag flag) {
        lines.accept(Component.literal(EchoStyle.MUTED
                + "Right-click to call or dismiss the orb."));
        lines.accept(Component.literal(EchoStyle.MUTED + "Backend: "
                + EchoStyle.AQUA + LocalAI.getBackendName()));
        lines.accept(Component.literal(EchoStyle.MUTED + "Model: "
                + EchoStyle.AQUA + (LocalAI.getModel().isBlank() ? "none" : LocalAI.getModel())));
        lines.accept(Component.literal(EchoStyle.MUTED + "Say "
                + EchoStyle.AQUA + EchoConfig.get().wakeWordList()[0] + EchoStyle.MUTED + " in chat."));
    }
}
