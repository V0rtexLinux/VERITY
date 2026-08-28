package com.mod.verity.event;

import com.mod.verity.state.VerityWorldState;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Set;

/**
 * Tracks food consumption so Verity can remember what the player ate.
 *
 * Migrated to Mojang mappings (MC 26.1.2):
 *   PlayerEntity → Player, World → Level, TypedActionResult → InteractionResult,
 *   Hand → InteractionHand, FoodComponent → DataComponents.FOOD.
 */
public class FoodTracker {

    private static final Set<String> MEAT_KEYWORDS = Set.of(
            "cooked", "steak", "pork", "beef", "mutton",
            "chicken", "rabbit", "salmon", "cod", "pizza", "meat"
    );

    public static void register() {
        UseItemCallback.EVENT.register(FoodTracker::onItemUse);
    }

    private static InteractionResult onItemUse(
            Player player, Level level, InteractionHand hand) {

        if (level.isClientSide()) return InteractionResult.PASS;

        ItemStack stack = player.getItemInHand(hand);

        if (stack.has(DataComponents.FOOD)) {
            String key    = stack.getItem().getDescriptionId().toLowerCase();
            String regKey = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase();

            boolean isPizzaOrMeat = MEAT_KEYWORDS.stream()
                    .anyMatch(kw -> key.contains(kw) || regKey.contains(kw));

            if (isPizzaOrMeat) {
                VerityWorldState.getOrCreate((ServerLevel) level).setHasEatenPizza(true);
            }
        }

        return InteractionResult.PASS;
    }
}
