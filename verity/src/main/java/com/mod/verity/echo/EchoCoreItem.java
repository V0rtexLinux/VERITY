package com.mod.verity.echo;

import com.mod.verity.VerityMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.Consumer;

/**
 * The Echo Core — right-click to summon your Echo companion.
 *
 * Anti-drop is enforced two ways:
 *  1. {@code EchoDropPreventionMixin} cancels {@code Player#drop} for this
 *     item on whichever side runs it (client key-press prediction AND the
 *     dedicated server's authoritative packet handling) — works on any
 *     server that has this mod, and this is the ONLY item class check that
 *     matters since the mixin targets the shared {@code Player} superclass.
 *  2. It is never placed in a lootable/droppable context to begin with:
 *     spawning consumes it, and recalling gives a fresh stack directly into
 *     the player's inventory (see {@link EchoEntity#mobInteract}).
 */
public class EchoCoreItem extends Item {

    public EchoCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay tooltipDisplay,
                                 Consumer<Component> tooltipAdder, TooltipFlag flag) {
        tooltipAdder.accept(Component.literal("§7Um núcleo pulsante de dados vivos."));
        tooltipAdder.accept(Component.literal("§7Usa para invocar o teu Echo."));
        tooltipAdder.accept(Component.literal("§7Agacha-te + clica no Echo para o guardar."));
        tooltipAdder.accept(Component.literal("§8Não pode ser largado."));
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        if (world.isClientSide() || !(world instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }

        List<EchoEntity> owned = level.getEntitiesOfClass(EchoEntity.class,
                user.getBoundingBox().inflate(128),
                e -> user.getUUID().equals(e.getOwnerUUID()) && e.isAlive());
        if (!owned.isEmpty()) {
            user.sendSystemMessage(Component.literal("§b[Echo]§r §7Já tens um Echo por perto."));
            return InteractionResult.FAIL;
        }

        EchoEntity echo = new EchoEntity(VerityMod.ECHO, level);
        echo.setOwnerUUID(user.getUUID());
        echo.setPos(user.getX(), user.getY() + 1.0, user.getZ());
        echo.setYRot(user.getYRot());
        level.addFreshEntity(echo);

        user.getItemInHand(hand).shrink(1);
        user.sendSystemMessage(Component.literal("§b[Echo]§r " +
                EchoDialogue.pick(EchoDialogue.GREETING, level.getGameTime() / 100L)));
        level.playSound(null, user.blockPosition(), SoundEvents.ALLAY_AMBIENT_WITH_ITEM,
                SoundSource.NEUTRAL, 0.9f, 1.1f);

        return InteractionResult.SUCCESS_SERVER;
    }
}
