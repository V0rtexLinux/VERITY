package com.mod.verity.mixin;

import com.mod.verity.entity.VerityEntity;
import com.mod.verity.state.VerityWorldState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin on ServerPlayerEntity.tick() that drives the stalking / glass-break
 * horror mechanic each server tick.
 *
 * Logic:
 *  1. For each ServerPlayerEntity, compute the dot product between the
 *     player's look vector and the vector from the player to Verity.
 *  2. If dot product < 0.7 (player is NOT looking at Verity) AND Verity is
 *     within 12 blocks, teleport Verity behind window glass near the player.
 *  3. If Verity ends up behind glass and the player looks toward it, shatter
 *     the glass, apply a brief nausea/blindness effect, and play a jumpscare
 *     sound.
 */
@Mixin(ServerPlayer.class)
public abstract class WindowStalkingMixin {

    private static final double LOOK_THRESHOLD   = 0.7;
    private static final double STALK_RANGE      = 12.0;
    private static final double GLASS_SCAN_RANGE = 8.0;

    /** Cooldown ticks between stalking teleports. */
    private int stalkCooldown = 0;

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        ServerLevel world = (ServerLevel) player.level();

        VerityWorldState state = VerityWorldState.getOrCreate(world);
        if (state.isVerityLost()) return;

        // Only stalk in stage 2+
        if (state.getCurrentStage() < 2) return;

        stalkCooldown--;
        if (stalkCooldown > 0) return;

        // Find Verity in the world
        var verityList = world.getEntitiesOfClass(VerityEntity.class,
                player.getBoundingBox().inflate(64), e -> true);
        if (verityList.isEmpty()) return;
        VerityEntity verity = verityList.get(0);

        Vec3 playerLook = player.getLookAngle();
        Vec3 toVerity   = verity.position().subtract(player.position()).normalize();
        double dot       = playerLook.dot(toVerity);

        boolean playerLooking = dot >= LOOK_THRESHOLD;
        double dist = player.distanceTo(verity);

        // ---- Stalking: teleport behind glass when not watched ----------
        if (!playerLooking && dist <= STALK_RANGE && !verity.hasJumped()) {
            BlockPos glassPos = findGlassNearPlayer(player, world);
            if (glassPos != null) {
                // Place Verity 1 block behind the glass from the player's side
                Vec3 playerPos = player.position();
                Vec3 glassCenter = Vec3.atCenterOf(glassPos);
                Vec3 awayDir = glassCenter.subtract(playerPos).normalize();
                double behindX = glassCenter.x + awayDir.x;
                double behindZ = glassCenter.z + awayDir.z;
                verity.teleportTo(behindX, glassPos.getY(), behindZ);
                stalkCooldown = 100 + world.getRandom().nextInt(100);
            }
        }

        // ---- Glass break: player looks at Verity behind glass ----------
        if (playerLooking && dist <= STALK_RANGE && !verity.hasJumped()) {
            BlockPos glassPos = getGlassBetween(player.position(), verity.position(), world);
            if (glassPos != null) {
                // Break the glass
                world.destroyBlock(glassPos, false);
                // Play shatter + jumpscare sounds
                world.playSound(null, glassPos, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 2f, 0.8f);
                world.playSound(null, glassPos, SoundEvents.WITHER_SPAWN,  SoundSource.HOSTILE, 0.6f, 1.5f);
                // Apply blindness + nausea for 2 seconds
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 1, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.NAUSEA,       60, 1, false, false));
                verity.setHasJumped(true);
                stalkCooldown = 200;
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    /**
     * Scan for any glass/glass-pane block within GLASS_SCAN_RANGE of the
     * player at roughly eye level.
     */
    private BlockPos findGlassNearPlayer(Player player, ServerLevel world) {
        BlockPos origin = player.blockPosition().above();
        int r = (int) GLASS_SCAN_RANGE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -2, -r), origin.offset(r, 2, r))) {
            if (isGlass(world, pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    /**
     * Raycast-style search for a glass block between two positions.
     * Samples 20 points along the line.
     */
    private BlockPos getGlassBetween(Vec3 from, Vec3 to, ServerLevel world) {
        Vec3 step = to.subtract(from).scale(1.0 / 20.0);
        Vec3 cur  = from;
        for (int i = 0; i < 20; i++) {
            cur = cur.add(step);
            BlockPos bp = BlockPos.containing(cur);
            if (isGlass(world, bp)) return bp;
        }
        return null;
    }

    private boolean isGlass(ServerLevel world, BlockPos pos) {
        var block = world.getBlockState(pos).getBlock();
        return block == Blocks.GLASS
                || block == Blocks.GLASS_PANE
                || block == Blocks.TINTED_GLASS
                || block.getDescriptionId().contains("glass");
    }
}
