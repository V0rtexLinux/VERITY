package com.mod.verity.echo.goal;

import com.mod.verity.echo.EchoDialogue;
import com.mod.verity.echo.EchoEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/**
 * When two Echoes belonging to two different (mod-having) players are near
 * each other, they walk together, "talk", and grow friendship with each
 * other. This is what NOTA / requirement #2 means by "the assistants can
 * hold a conversation and become friends" — it only ever fires between
 * real {@link EchoEntity} instances, which only exist when the server (or
 * singleplayer world) actually runs VERITY.
 */
public class EchoSocializeGoal extends Goal {

    private static final double RANGE = 10.0;
    private static final double TALK_RANGE = 2.5;

    private final EchoEntity echo;
    private EchoEntity partner;
    private int talkCooldown;

    public EchoSocializeGoal(EchoEntity echo) {
        this.echo = echo;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (echo.level().isClientSide() || !(echo.level() instanceof ServerLevel level)) return false;
        if (echo.getSocializeCooldown() > 0) return false;

        List<EchoEntity> nearby = level.getEntitiesOfClass(EchoEntity.class,
                echo.getBoundingBox().inflate(RANGE),
                e -> e != echo && e.isAlive());
        if (nearby.isEmpty()) return false;

        nearby.sort((a, b) -> Double.compare(echo.distanceToSqr(a), echo.distanceToSqr(b)));
        this.partner = nearby.get(0);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return partner != null && partner.isAlive() && echo.distanceTo(partner) <= RANGE;
    }

    @Override
    public void stop() {
        partner = null;
        echo.setSocializeCooldown(200 + echo.getRandom().nextInt(400));
    }

    @Override
    public void tick() {
        if (partner == null) return;
        echo.getLookControl().setLookAt(partner, 30.0f, 30.0f);

        double dist = echo.distanceTo(partner);
        if (dist > TALK_RANGE) {
            echo.getNavigation().moveTo(partner, 0.8);
            return;
        }
        echo.getNavigation().stop();

        if (--talkCooldown > 0) return;
        talkCooldown = 60 + echo.getRandom().nextInt(80);

        // Both sides gain friendship — symmetric relationship.
        echo.increaseEchoFriendship(partner.getUUID(), 4);
        partner.increaseEchoFriendship(echo.getUUID(), 4);

        long seed = EchoDialogue.pairSeed(echo.getUUID(), partner.getUUID(), echo.tickCount / 100L);
        String line = EchoDialogue.pick(EchoDialogue.ECHO_TO_ECHO, seed);

        if (echo.level() instanceof ServerLevel level) {
            level.playSound(null, echo.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(),
                    SoundSource.NEUTRAL, 0.6f, 1.4f);
            for (ServerPlayer p : level.players()) {
                if (p.distanceToSqr(echo) < 24 * 24) {
                    p.sendSystemMessage(Component.literal("§b[Echo ↔ Echo]§r " + line));
                }
            }
        }
    }
}
