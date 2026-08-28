package com.mod.verity.echo.goal;

import com.mod.verity.echo.EchoDialogue;
import com.mod.verity.echo.EchoEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Echo doesn't just look after its owner — any nearby player (with or
 * without the mod, it makes no difference from Echo's side) can slowly
 * befriend it by hanging around. Satisfies "faz amizade com os outros
 * jogadores" independently of the owner relationship.
 */
public class EchoBefriendPlayerGoal extends Goal {

    private static final double RANGE = 6.0;

    private final EchoEntity echo;
    private Player target;
    private int greetCooldown;

    public EchoBefriendPlayerGoal(EchoEntity echo) {
        this.echo = echo;
        this.setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(echo.level() instanceof ServerLevel level)) return false;
        Player nearest = level.getNearestPlayer(echo, RANGE);
        if (nearest == null) return false;
        // Owner bonding is handled by EchoFollowOwnerGoal; this goal is for
        // strangers so it doesn't fight that goal for the MOVE flag.
        if (nearest.getUUID().equals(echo.getOwnerUUID())) return false;
        this.target = nearest;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && echo.distanceTo(target) <= RANGE * 1.5;
    }

    @Override
    public void stop() {
        target = null;
    }

    @Override
    public void tick() {
        if (target == null) return;
        echo.getLookControl().setLookAt(target, 30.0f, 30.0f);

        if (--greetCooldown > 0) return;
        greetCooldown = 200 + echo.getRandom().nextInt(200);

        int before = echo.getPlayerFriendship(target.getUUID());
        echo.increasePlayerFriendship(target, 3);

        if (before < 10) {
            long seed = EchoDialogue.singleSeed(target.getUUID(), echo.tickCount / 100L);
            target.sendSystemMessage(Component.literal("§b[Echo]§r " + EchoDialogue.pick(EchoDialogue.STRANGER_GREETING, seed)));
        }
    }
}
