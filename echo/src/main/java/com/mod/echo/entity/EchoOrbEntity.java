package com.mod.echo.entity;

import com.geckolib.animatable.GeoAnimatable;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
import com.mod.echo.EchoStyle;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * ECHO's physical presence: a small blue orb that hovers beside the player.
 *
 * It is deliberately inert — it cannot be hurt, cannot hurt anything, cannot be
 * pushed and never wanders off. Its whole job is to be somewhere you can point
 * at while you talk to the assistant, and to pulse when it is answering.
 *
 * Movement is driven directly rather than through pathfinding: an orb that
 * floats has no business asking the navigator for a walkable path, and driving
 * the position keeps it smooth over water, lava and cliffs alike.
 */
public class EchoOrbEntity extends PathfinderMob implements GeoAnimatable {

    private static final RawAnimation IDLE     = RawAnimation.begin().thenLoop("animation.echo.look_at_target");
    private static final RawAnimation MOVING   = RawAnimation.begin().thenLoop("animation.echo.move");
    private static final RawAnimation SPEAKING = RawAnimation.begin().thenLoop("animation.echo.talk_pulse");

    /** How far behind and above the player the orb tries to sit. */
    private static final double FOLLOW_DISTANCE = 2.2;
    private static final double FOLLOW_HEIGHT   = 1.4;
    /** Beyond this the orb simply teleports rather than trailing forever. */
    private static final double TELEPORT_DISTANCE = 24.0;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** Ticks left in the "currently answering" pulse. */
    private int speakingTicks = 0;
    private boolean moving = false;

    public EchoOrbEntity(EntityType<? extends EchoOrbEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setInvulnerable(true);
        this.setSilent(true);
        this.setPersistenceRequired();
        this.noPhysics = true;
        // Movement is driven from tick(); the goal system would only fight it.
        this.setNoAi(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 10.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void registerGoals() {
        // Intentionally empty — see the class comment.
    }

    // ------------------------------------------------------------------ //
    //  Behaviour                                                           //
    // ------------------------------------------------------------------ //

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide()) {
            tickParticles();
            return;
        }

        if (speakingTicks > 0) speakingTicks--;

        Player owner = this.level().getNearestPlayer(this, TELEPORT_DISTANCE * 2);
        if (owner == null) {
            moving = false;
            return;
        }

        // Sit just behind the player's shoulder, on the side they are not looking.
        Vec3 look = owner.getLookAngle();
        Vec3 target = owner.position()
                .subtract(look.x * FOLLOW_DISTANCE, 0, look.z * FOLLOW_DISTANCE)
                .add(0, FOLLOW_HEIGHT, 0);

        double distance = this.position().distanceTo(target);
        if (distance > TELEPORT_DISTANCE) {
            this.setPos(target.x, target.y, target.z);
            moving = false;
        } else if (distance > 0.25) {
            // Ease toward the target so the orb drifts instead of snapping.
            Vec3 step = target.subtract(this.position()).scale(0.18);
            this.setPos(this.getX() + step.x, this.getY() + step.y, this.getZ() + step.z);
            moving = distance > 0.6;
        } else {
            moving = false;
        }

        // A gentle bob so it never looks frozen.
        double bob = Math.sin(this.tickCount / 12.0) * 0.012;
        this.setPos(this.getX(), this.getY() + bob, this.getZ());

        this.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, owner.getEyePosition());
        this.setYHeadRot(this.getYRot());
        this.setDeltaMovement(Vec3.ZERO);
    }

    private void tickParticles() {
        if (this.random.nextInt(speakingTicks > 0 ? 2 : 12) != 0) return;
        this.level().addParticle(
                speakingTicks > 0 ? ParticleTypes.END_ROD : ParticleTypes.GLOW,
                this.getX() + (this.random.nextDouble() - 0.5) * 0.4,
                this.getY() + 0.25 + (this.random.nextDouble() - 0.5) * 0.3,
                this.getZ() + (this.random.nextDouble() - 0.5) * 0.4,
                0.0, 0.01, 0.0);
    }

    /** Make the orb pulse for a moment — called when ECHO is answering. */
    public void pulse() {
        this.speakingTicks = 40;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide()) {
            player.sendSystemMessage(Component.literal(EchoStyle.info(
                    "I'm right here. Just talk to me in chat.")));
            pulse();
        }
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ //
    //  Nothing can knock it around, damage it, or clean it up              //
    // ------------------------------------------------------------------ //

    @Override public boolean isPushable()              { return false; }
    @Override public boolean isPickable()              { return true; }
    @Override public boolean canBeLeashed()            { return false; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public void push(net.minecraft.world.entity.Entity entity) { /* immovable by design */ }

    // ------------------------------------------------------------------ //
    //  GeckoLib                                                            //
    // ------------------------------------------------------------------ //

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<EchoOrbEntity>("main", 6, state -> {
            if (speakingTicks > 0) return state.setAndContinue(SPEAKING);
            return state.setAndContinue(moving ? MOVING : IDLE);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
