package com.mod.verity.entity;

import com.mod.verity.VerityMod;
import com.mod.verity.state.VerityWorldState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.damagesource.DamageSource;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.*;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

/**
 * Main Verity entity — implements all 5 ARG stages faithfully.
 *
 * Stage 1 — Floating smiley orb; friendly; can be woken with "Hey Verity".
 * Stage 2 — Poker / toothy grin; darker tone; warns of danger; stares into dark spots.
 * Stage 3 — Omniscient; manipulates doors/alarms; knows real-world info; perpetual storm.
 * Stage 4 — Socially aware; recognises players by name; blocks respawn; can kick players.
 * Stage 5 — Cave-Dweller monster form; unkillable; hunts relentlessly; teleports to player.
 *
 * Broadcast messages (stage transitions, horror moments) are world-wide so all players
 * experience the ARG. Conversational replies are handled privately in ChatHandler/VerityAI.
 */
public class VerityEntity extends Monster implements GeoAnimatable {

    // ------------------------------------------------------------------ //
    //  GeckoLib                                                            //
    // ------------------------------------------------------------------ //
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private static final RawAnimation IDLE_ANIM   = RawAnimation.begin().thenLoop("animation.verity.idle");
    private static final RawAnimation WALK_ANIM   = RawAnimation.begin().thenLoop("animation.verity.walk");
    private static final RawAnimation STARE_ANIM  = RawAnimation.begin().thenLoop("animation.verity.stare");
    private static final RawAnimation STAGE3_ANIM = RawAnimation.begin().thenLoop("animation.verity.stage3_idle");
    private static final RawAnimation STAGE5_HUNT = RawAnimation.begin().thenLoop("animation.verity.stage5_hunt");
    private static final RawAnimation STAGE5_IDLE = RawAnimation.begin().thenLoop("animation.verity.stage5_idle");

    // ------------------------------------------------------------------ //
    //  Runtime state                                                       //
    // ------------------------------------------------------------------ //
    private UUID    targetPlayerUUID     = null;
    private boolean hasJumped            = false;
    private int     glitchCooldown       = 0;
    private int     stareUpdateCooldown  = 0;
    private int     alarmCooldown        = 0;
    private int     proximityCheckTimer  = 0;
    private int     idleEscalateCooldown = 0;

    // ------------------------------------------------------------------ //
    //  Constructor & attributes                                            //
    // ------------------------------------------------------------------ //
    public VerityEntity(EntityType<? extends Monster> type, Level world) {
        super(type, world);
        this.setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH,           2000.0)
                .add(Attributes.MOVEMENT_SPEED,           0.32)
                .add(Attributes.ATTACK_DAMAGE,            12.0)
                .add(Attributes.FOLLOW_RANGE,            128.0)
                .add(Attributes.KNOCKBACK_RESISTANCE,      1.0)
                .add(Attributes.ARMOR,                    20.0);
    }

    // ------------------------------------------------------------------ //
    //  Goal registration                                                   //
    // ------------------------------------------------------------------ //
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, false));
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.7));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 16.0f));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ------------------------------------------------------------------ //
    //  Main tick                                                           //
    // ------------------------------------------------------------------ //
    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide()) {
            tickClientParticles();
            return;
        }

        ServerLevel world = (ServerLevel) this.level();
        VerityWorldState state = VerityWorldState.getOrCreate(world);

        if (this.getHealth() < 1.0f) {
            this.setHealth(2000.0f);
        }

        tickDayProgression(world, state);

        if (state.isDreadMaxed() && state.getCurrentStage() < 5) {
            state.setCurrentStage(5);
            broadcastHorror(world, "§4[Verity]§r §c...YOU MADE ME DO THIS.");
        }

        switch (state.getCurrentStage()) {
            case 1 -> tickStage1(world, state);
            case 2 -> tickStage2(world, state);
            case 3 -> tickStage3(world, state);
            case 4 -> tickStage4(world, state);
            default -> tickStage5(world, state);
        }

        tickProximityMonitor(world, state);
    }

    // ------------------------------------------------------------------ //
    //  Day progression                                                     //
    // ------------------------------------------------------------------ //
    private void tickDayProgression(ServerLevel world, VerityWorldState state) {
        if (this.tickCount % 24000 != 0 || this.tickCount == 0) return;

        state.incrementDay();
        int days  = state.getDaysElapsed();
        int stage = state.getCurrentStage();

        if (days == 2 && stage == 1) {
            state.setCurrentStage(2);
            broadcastHorror(world, "§6[Verity]§r §7...you haven't seen the village in the east, have you?");
        }
        if (days == 4 && stage == 2) {
            state.setCurrentStage(3);
            ((net.minecraft.server.level.ServerLevelData) world.getLevelData()).setRaining(true);
            broadcastHorror(world, "§6[Verity]§r §7I know you ate pizza yesterday.");
        }
        if (days == 6 && stage == 3) {
            state.setCurrentStage(4);
            broadcastHorror(world, "§c[Verity]§r §7...I see your friend joined. I know who they are.");
        }
        if (days == 8 && stage == 4) {
            state.setCurrentStage(5);
            ((net.minecraft.server.level.ServerLevelData) world.getLevelData()).setRaining(true);
            broadcastHorror(world, "§4[Verity]§r §c...");
        }
    }

    // ------------------------------------------------------------------ //
    //  Stage 1 — Friendly floating orb                                    //
    // ------------------------------------------------------------------ //
    private void tickStage1(ServerLevel world, VerityWorldState state) {
        this.setNoGravity(true);
        if (this.tickCount % 40 == 0) {
            world.sendParticles(ParticleTypes.END_ROD,
                    this.getX(), this.getY() + 0.4, this.getZ(),
                    2, 0.15, 0.15, 0.15, 0.01);
        }
    }

    // ------------------------------------------------------------------ //
    //  Stage 2 — Unsettling shift                                         //
    // ------------------------------------------------------------------ //
    private void tickStage2(ServerLevel world, VerityWorldState state) {
        this.setNoGravity(true);

        glitchCooldown--;
        if (glitchCooldown <= 0) {
            glitchCooldown = 100 + world.getRandom().nextInt(200);
            world.playSound(null, this.blockPosition(),
                    SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE,
                    0.35f, 0.4f + world.getRandom().nextFloat() * 0.6f);
        }

        stareUpdateCooldown--;
        if (stareUpdateCooldown <= 0) {
            stareUpdateCooldown = 80;
            BlockPos dark = findNearestDarkSpot(world, 20);
            if (dark != null) lookAt(dark);
        }

        idleEscalateCooldown++;
        if (idleEscalateCooldown > 2400) {
            state.addDread(5);
            idleEscalateCooldown = 0;
        }
    }

    // ------------------------------------------------------------------ //
    //  Stage 3 — Omniscient manipulation                                  //
    // ------------------------------------------------------------------ //
    private void tickStage3(ServerLevel world, VerityWorldState state) {
        this.setNoGravity(true);
        ((net.minecraft.server.level.ServerLevelData) world.getLevelData()).setRaining(true);

        alarmCooldown--;
        if (alarmCooldown <= 0) {
            alarmCooldown = 400 + world.getRandom().nextInt(600);
            world.playSound(null, this.blockPosition(),
                    SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 3.0f, 0.5f);
            broadcastHorror(world, "§6[Verity]§r §7...those are the sounds of the night.");
        }

        if (this.tickCount % 300 == 0) {
            Player near = world.getNearestPlayer(this, 30);
            if (near != null) openNearestDoor(near, world);
        }
    }

    // ------------------------------------------------------------------ //
    //  Stage 4 — Social awareness; kicks players                          //
    // ------------------------------------------------------------------ //
    private void tickStage4(ServerLevel world, VerityWorldState state) {
        this.setNoGravity(true);
        ((net.minecraft.server.level.ServerLevelData) world.getLevelData()).setRaining(true);

        if (world.players().size() > 1 && state.getDaysElapsed() < 4) {
            state.triggerInvitedFriendEarly();
        }

        if (this.tickCount % 1200 == 0 && world.players().size() > 1) {
            for (ServerPlayer p : world.getServer().getPlayerList().getPlayers()) {
                if (targetPlayerUUID != null && !p.getUUID().equals(targetPlayerUUID)) {
                    p.connection.disconnect(
                            Component.literal("§4[Verity]§r §7Rest now. Come back when you're invited."));
                    broadcastHorror(world, "§c[Verity]§r §7I asked them to leave. This is between us.");
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Stage 5 — Cave-Dweller monster form                                //
    // ------------------------------------------------------------------ //
    private void tickStage5(ServerLevel world, VerityWorldState state) {
        this.setNoGravity(false);
        ((net.minecraft.server.level.ServerLevelData) world.getLevelData()).setRaining(true);

        if (this.getHealth() < 2000.0f) this.setHealth(2000.0f);

        if (this.tickCount % 40 == 0) {
            world.playSound(null, this.blockPosition(),
                    SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER,
                    10000f, 1.0f);
        }

        Player target = world.getNearestPlayer(this, 200);
        if (target == null) return;

        double dist = this.distanceTo(target);
        if (dist > 64 && this.tickCount % 100 == 0) {
            Vec3 behind = target.position()
                    .add(target.getLookAngle().scale(-3));
            this.teleportTo(behind.x, behind.y, behind.z);
            world.playSound(null, this.blockPosition(),
                    SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1f, 0.5f);
        }

        if (target.getY() < -50 && target.isCrouching()) {
            if (target.getDeltaMovement().lengthSqr() > 0.001) {
                state.resetCalmTicks();
            } else {
                state.incrementCalmTicks();
                if (state.getCalmTicks() >= 3600) {
                    this.getNavigation().stop();
                    this.setTarget(null);
                    broadcastHorror(world, "§a[Verity]§r §7...I lost you. Stay hidden.");
                    state.setVerityLost(true);
                }
            }
        } else {
            state.resetCalmTicks();
        }

        if (state.isVerityLost()) {
            this.getNavigation().stop();
            this.setTarget(null);
        }
    }

    // ------------------------------------------------------------------ //
    //  Proximity monitor                                                   //
    // ------------------------------------------------------------------ //
    private void tickProximityMonitor(ServerLevel world, VerityWorldState state) {
        if (state.getCurrentStage() < 2) return;
        proximityCheckTimer++;
        if (proximityCheckTimer < 200) return;
        proximityCheckTimer = 0;

        Player near = world.getNearestPlayer(this, 100);
        if (near == null) {
            if (!state.hasLeftVerity()) {
                state.triggerLeftVerity();
                broadcastHorror(world, "§6[Verity]§r §7...you left me.");
            }
        } else {
            state.setProximityTicks(Math.max(0, state.getProximityTicks() - 1));
        }
    }

    // ------------------------------------------------------------------ //
    //  Damage override — immortality                                       //
    // ------------------------------------------------------------------ //
    @Override
    public boolean isInvulnerableTo(net.minecraft.server.level.ServerLevel level, DamageSource source) {
        return true;
    }

    // ------------------------------------------------------------------ //
    //  NBT                                                                 //
    // ------------------------------------------------------------------ //
    @Override
    public void readAdditionalSaveData(net.minecraft.util.valueinput.ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read("TargetPlayerUUID", net.minecraft.core.UUIDUtil.CODEC)
             .ifPresent(uuid -> targetPlayerUUID = uuid);
        hasJumped = input.getBoolean("HasJumped");
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.util.valueoutput.ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (targetPlayerUUID != null) {
            output.store("TargetPlayerUUID", net.minecraft.core.UUIDUtil.CODEC, targetPlayerUUID);
        }
        output.putBoolean("HasJumped", hasJumped);
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //
    private void tickClientParticles() {
        if (this.tickCount % 5 == 0) {
            this.level().addParticle(ParticleTypes.SMOKE,
                    this.getX() + (Math.random() - 0.5) * 0.4,
                    this.getY() + 0.1,
                    this.getZ() + (Math.random() - 0.5) * 0.4,
                    0, 0.01, 0);
        }
    }

    private BlockPos findNearestDarkSpot(ServerLevel world, int radius) {
        BlockPos origin = this.blockPosition();
        BlockPos best   = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -4, -radius),
                origin.offset(radius, 4, radius))) {
            if (world.getBrightness(LightLayer.BLOCK, pos) <= 2 && world.isEmptyBlock(pos)) {
                double d = pos.distSqr(origin);
                if (d < bestDist) { bestDist = d; best = pos.immutable(); }
            }
        }
        return best;
    }

    private void lookAt(BlockPos target) {
        double dx = target.getX() + 0.5 - this.getX();
        double dz = target.getZ() + 0.5 - this.getZ();
        this.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private void openNearestDoor(Player player, ServerLevel world) {
        BlockPos origin = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-3, -1, -3), origin.offset(3, 2, 3))) {
            BlockState bs = world.getBlockState(pos);
            if (bs.getBlock() instanceof DoorBlock) {
                world.setBlock(pos, bs.setValue(DoorBlock.OPEN, true), 3);
                world.playSound(null, pos, SoundEvents.IRON_DOOR_OPEN,
                        SoundSource.BLOCKS, 0.8f, 0.9f);
                break;
            }
        }
    }

    /**
     * Stage-transition horror message — broadcast to ALL players.
     * This is intentional: the ARG narrative requires everyone to see it.
     */
    private void broadcastHorror(ServerLevel world, String message) {
        world.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal(message), false);
    }

    // ------------------------------------------------------------------ //
    //  Accessors                                                           //
    // ------------------------------------------------------------------ //
    public UUID getTargetPlayerUUID()         { return targetPlayerUUID; }
    public void setTargetPlayerUUID(UUID uuid){ this.targetPlayerUUID = uuid; }
    public boolean hasJumped()                { return hasJumped; }
    public void setHasJumped(boolean v)       { this.hasJumped = v; }

    public void notifyInteraction() {
        if (!this.level().isClientSide()) {
            VerityWorldState.getOrCreate((ServerLevel) this.level()).resetIgnoredTicks();
        }
    }

    // ------------------------------------------------------------------ //
    //  GeckoLib                                                            //
    // ------------------------------------------------------------------ //
    @Override
    public void registerControllers(AnimatableInstanceCache instanceCache) {
        instanceCache.add(new AnimationController<>(this, "controller", 5, state -> {
            if (!this.level().isClientSide()) {
                VerityWorldState ws = VerityWorldState.getOrCreate(
                        (ServerLevel) this.level());
                int s     = ws.getCurrentStage();
                boolean m = this.getDeltaMovement().horizontalDistanceSqr() > 1e-6;
                if (s >= 5) return state.setAndContinue(m ? STAGE5_HUNT : STAGE5_IDLE);
                if (s >= 3) return state.setAndContinue(STAGE3_ANIM);
                if (s == 2) return state.setAndContinue(STARE_ANIM);
            }
            return state.setAndContinue(state.isMoving() ? WALK_ANIM : IDLE_ANIM);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
