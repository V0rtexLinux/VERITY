package com.mod.verity.echo;

import com.mod.verity.VerityMod;
import com.mod.verity.echo.goal.EchoBefriendPlayerGoal;
import com.mod.verity.echo.goal.EchoFollowOwnerGoal;
import com.mod.verity.echo.goal.EchoSocializeGoal;
import com.geckolib.animatable.GeoAnimatable;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Echo — the friendly virtual-assistant companion.
 *
 * Distinct from the horror {@code VerityEntity}: Echo is small, friendly,
 * non-hostile, follows its owner, and — when it detects another Echo
 * belonging to another mod-having player nearby — socialises with it
 * (see {@link EchoSocializeGoal}). It also slowly befriends any nearby
 * player, owner or not (see {@link EchoBefriendPlayerGoal}).
 *
 * Bound to the {@code echo_core} item: using the item spawns this entity;
 * sneak-right-clicking Echo by its owner "recalls" it back into an item
 * (see {@link EchoCoreItem}).
 */
public class EchoEntity extends PathfinderMob implements GeoAnimatable {

    // ------------------------------------------------------------------ //
    //  GeckoLib                                                            //
    // ------------------------------------------------------------------ //
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("animation.verity.idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("animation.verity.walk");

    // ------------------------------------------------------------------ //
    //  Friendship codec — shared by owner-players and other Echoes         //
    // ------------------------------------------------------------------ //
    private static final Codec<Map<UUID, Integer>> FRIENDSHIP_CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT);

    // ------------------------------------------------------------------ //
    //  State                                                               //
    // ------------------------------------------------------------------ //
    private UUID ownerUUID = null;
    private final Map<UUID, Integer> playerFriendship = new HashMap<>();
    private final Map<UUID, Integer> echoFriendship = new HashMap<>();
    private int socializeCooldown = 0;
    private float bobPhase;

    public EchoEntity(EntityType<? extends PathfinderMob> type, Level world) {
        super(type, world);
        this.setNoGravity(true);
        this.bobPhase = (float) (Math.random() * Math.PI * 2);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.35)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.FLYING_SPEED, 0.4);
    }

    // ------------------------------------------------------------------ //
    //  Goals                                                               //
    // ------------------------------------------------------------------ //
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new EchoSocializeGoal(this));
        this.goalSelector.addGoal(3, new EchoFollowOwnerGoal(this, 1.0, 3.0f, 10.0f));
        this.goalSelector.addGoal(4, new EchoBefriendPlayerGoal(this));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
    }

    // ------------------------------------------------------------------ //
    //  Tick                                                                //
    // ------------------------------------------------------------------ //
    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide()) {
            bobPhase += 0.1f;
            this.setDeltaMovement(this.getDeltaMovement().x, Math.sin(bobPhase) * 0.01, this.getDeltaMovement().z);
            if (this.tickCount % 8 == 0) {
                this.level().addParticle(ParticleTypes.END_ROD,
                        this.getX(), this.getY() + 0.5, this.getZ(),
                        0, 0.01, 0);
            }
            return;
        }

        if (socializeCooldown > 0) socializeCooldown--;
    }

    // ------------------------------------------------------------------ //
    //  Interaction — greet, or recall (sneak + click by owner)             //
    // ------------------------------------------------------------------ //
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        boolean isOwner = ownerUUID != null && ownerUUID.equals(player.getUUID());

        if (isOwner && player.isCrouching()) {
            recallToItem(player);
            return InteractionResult.SUCCESS;
        }

        if (isOwner) {
            player.sendSystemMessage(Component.literal("§b[Echo]§r " +
                    EchoDialogue.pick(EchoDialogue.GREETING, this.tickCount / 100L)));
            increasePlayerFriendship(player, 2);
        } else {
            int before = getPlayerFriendship(player.getUUID());
            increasePlayerFriendship(player, 4);
            long seed = EchoDialogue.singleSeed(player.getUUID(), this.tickCount / 100L);
            String bank = before < 5
                    ? EchoDialogue.pick(EchoDialogue.STRANGER_GREETING, seed)
                    : EchoDialogue.pick(EchoDialogue.GREETING, seed);
            player.sendSystemMessage(Component.literal("§b[Echo]§r " + bank));
        }
        return InteractionResult.SUCCESS;
    }

    private void recallToItem(Player player) {
        ItemStack echoCore = new ItemStack(VerityMod.ECHO_CORE_ITEM);
        boolean added = player.addItem(echoCore);
        if (!added) {
            // Inventory full: keep the entity alive rather than lose it.
            player.sendSystemMessage(Component.literal("§c[Echo]§r §7O teu inventário está cheio — não consigo voltar."));
            return;
        }
        player.sendSystemMessage(Component.literal("§b[Echo]§r " +
                EchoDialogue.pick(EchoDialogue.RECALL, this.tickCount / 100L)));
        if (this.level() instanceof ServerLevel level) {
            level.playSound(null, this.blockPosition(), SoundEvents.ALLAY_ITEM_TAKEN,
                    SoundSource.NEUTRAL, 0.8f, 1.3f);
            level.sendParticles(ParticleTypes.END_ROD, this.getX(), this.getY() + 0.5, this.getZ(),
                    12, 0.2, 0.3, 0.2, 0.05);
        }
        this.discard();
    }

    // ------------------------------------------------------------------ //
    //  Damage — no fall damage, no drowning (it's a floating spirit)       //
    // ------------------------------------------------------------------ //
    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        return false;
    }

    // ------------------------------------------------------------------ //
    //  Owner / friendship accessors                                        //
    // ------------------------------------------------------------------ //
    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID uuid) { this.ownerUUID = uuid; }

    public int getSocializeCooldown() { return socializeCooldown; }
    public void setSocializeCooldown(int ticks) { this.socializeCooldown = ticks; }

    public int getPlayerFriendship(UUID playerId) { return playerFriendship.getOrDefault(playerId, 0); }
    public void increasePlayerFriendship(Player player, int amount) {
        playerFriendship.merge(player.getUUID(), amount, (a, b) -> Math.min(100, a + b));
    }

    public int getEchoFriendship(UUID otherEchoId) { return echoFriendship.getOrDefault(otherEchoId, 0); }
    public void increaseEchoFriendship(UUID otherEchoId, int amount) {
        echoFriendship.merge(otherEchoId, amount, (a, b) -> Math.min(100, a + b));
    }

    // ------------------------------------------------------------------ //
    //  NBT                                                                 //
    // ------------------------------------------------------------------ //
    @Override
    public void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read("OwnerUUID", UUIDUtil.CODEC).ifPresent(uuid -> ownerUUID = uuid);
        input.read("PlayerFriendship", FRIENDSHIP_CODEC).ifPresent(m -> {
            playerFriendship.clear();
            playerFriendship.putAll(m);
        });
        input.read("EchoFriendship", FRIENDSHIP_CODEC).ifPresent(m -> {
            echoFriendship.clear();
            echoFriendship.putAll(m);
        });
    }

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (ownerUUID != null) {
            output.store("OwnerUUID", UUIDUtil.CODEC, ownerUUID);
        }
        output.store("PlayerFriendship", FRIENDSHIP_CODEC, playerFriendship);
        output.store("EchoFriendship", FRIENDSHIP_CODEC, echoFriendship);
    }

    // ------------------------------------------------------------------ //
    //  GeckoLib                                                            //
    // ------------------------------------------------------------------ //
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>("controller", 5, state ->
                state.setAndContinue(state.isMoving() ? WALK_ANIM : IDLE_ANIM)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
