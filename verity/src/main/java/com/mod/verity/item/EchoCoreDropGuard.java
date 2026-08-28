package com.mod.verity.item;

import com.mod.verity.VerityMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;

/**
 * ECHO_CORE is never allowed to exist as a ground/thrown ItemEntity.
 * Every server tick we scan for stray ECHO_CORE ItemEntities (dropped,
 * thrown with Q, knocked out via death, etc.) and immediately return the
 * stack to the nearest owning player instead of letting it sit on the ground.
 */
public final class EchoCoreDropGuard {

    private EchoCoreDropGuard() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(EchoCoreDropGuard::scanServer);
    }

    private static void scanServer(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            scan(level);
        }
    }

    private static void scan(ServerLevel level) {
        var stray = level.getEntities(
            EntityTypeTest.<Entity, ItemEntity>forClass(ItemEntity.class), e -> true);

        for (ItemEntity itemEntity : stray) {
            if (itemEntity.getItem().isEmpty()) continue;
            if (!itemEntity.getItem().is(VerityMod.ECHO_CORE_ITEM)) continue;

            Player owner = itemEntity.getOwner() instanceof Player p
                    ? p
                    : level.getNearestPlayer(itemEntity, 16);

            if (owner == null) {
                owner = level.getNearestPlayer(itemEntity, 32);
            }

            if (owner != null) {
                if (!owner.getInventory().add(itemEntity.getItem())) {
                    owner.drop(itemEntity.getItem(), false); // truly no room — last resort only
                }
                owner.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("§d[Echo]§r §7...you can't leave me behind."));
            }
            itemEntity.discard();
        }
    }
}
