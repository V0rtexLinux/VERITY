package com.mod.verity.echo.goal;

import com.mod.verity.echo.EchoEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Custom "follow owner" behaviour that doesn't require {@code TamableAnimal}
 * (Echo extends {@code PathfinderMob} so it can float/hover on its own terms).
 */
public class EchoFollowOwnerGoal extends Goal {

    private final EchoEntity echo;
    private final double speed;
    private final float stopDistance;
    private final float startDistance;
    private Player owner;
    private int recalcCooldown;

    public EchoFollowOwnerGoal(EchoEntity echo, double speed, float stopDistance, float startDistance) {
        this.echo = echo;
        this.speed = speed;
        this.stopDistance = stopDistance;
        this.startDistance = startDistance;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        UUID ownerId = echo.getOwnerUUID();
        if (ownerId == null) return false;
        Player p = echo.level().getPlayerInAnyDimension(ownerId);
        if (p == null || p.isSpectator() || p.level() != echo.level()) return false;
        if (echo.distanceTo(p) < startDistance) return false;
        this.owner = p;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return owner != null && owner.isAlive() && owner.level() == echo.level()
                && echo.distanceTo(owner) > stopDistance;
    }

    @Override
    public void start() {
        recalcCooldown = 0;
        echo.getNavigation().moveTo(owner, speed);
    }

    @Override
    public void stop() {
        owner = null;
        echo.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (owner == null) return;
        echo.getLookControl().setLookAt(owner, 30.0f, 30.0f);
        if (--recalcCooldown > 0) return;
        recalcCooldown = 10;
        if (echo.distanceTo(owner) > startDistance * 2.5) {
            // Too far to walk — hop straight back to the owner's side.
            echo.teleportTo(owner.getX(), owner.getY() + 0.5, owner.getZ());
            return;
        }
        echo.getNavigation().moveTo(owner, speed);
    }
}
