package com.mod.verity.mixin;

import com.mod.verity.VerityMod;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents the Echo Core item from ever being dropped, on either logical
 * side — this mixin targets {@code Player}, the common superclass of both
 * {@code LocalPlayer} (client-side prediction, e.g. pressing Q) and
 * {@code ServerPlayer} (authoritative handling of the drop packet). One
 * injection covers both, so it works the instant this mod runs on a given
 * side — no server round trip needed to stop the drop from happening.
 */
@Mixin(Player.class)
public abstract class EchoDropPreventionMixin {

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void verity$preventEchoCoreDrop(ItemStack stack, boolean throwRandomly,
                                             CallbackInfoReturnable<ItemEntity> cir) {
        if (stack.isEmpty() || stack.getItem() != VerityMod.ECHO_CORE_ITEM) {
            return;
        }
        Player self = (Player) (Object) this;
        if (!self.level().isClientSide()) {
            self.sendSystemMessage(Component.literal("§b[Echo]§r §7O Echo Core não pode ser largado — voltou para o inventário."));
        }
        cir.setReturnValue(null);
    }
}
