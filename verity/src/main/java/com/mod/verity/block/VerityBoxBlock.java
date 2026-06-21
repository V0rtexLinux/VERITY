package com.mod.verity.block;

import com.mod.verity.VerityMod;
import com.mod.verity.entity.VerityEntity;
import com.mod.verity.state.VerityWorldState;
import com.mod.verity.voice.VerityVoiceManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Verity's Box — right-click to spawn Verity (Stage 1) or seal him back inside.
 *
 * Migrated to Mojang mappings (MC 26.1.2).
 */
public class VerityBoxBlock extends Block {

    private static final VoxelShape SHAPE = Block.box(1.6, 0.8, 1.6, 14.4, 13.6, 14.4);

    public VerityBoxBlock(BlockBehaviour.Properties settings) {
        super(settings);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // ------------------------------------------------------------------ //
    //  Interaction                                                         //
    // ------------------------------------------------------------------ //
    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos,
                                  Player player, InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide()) return InteractionResult.SUCCESS;

        ServerLevel serverLevel = (ServerLevel) world;
        VerityWorldState ws = VerityWorldState.getOrCreate(serverLevel);

        ItemStack heldItem = player.getItemInHand(hand);
        if (heldItem.getItem() == VerityMod.VERITY_ORB_ITEM) {
            heldItem.shrink(1);
            ws.setCurrentStage(1);
            ws.setVerityLost(false);
            ws.resetCalmTicks();
            double r = 1000;
            serverLevel.getEntitiesOfClass(VerityEntity.class,
                    new AABB(pos.getX() - r, pos.getY() - r, pos.getZ() - r,
                             pos.getX() + r, pos.getY() + r, pos.getZ() + r),
                    e -> true)
                    .forEach(e -> e.discard());
            world.playSound(null, pos, SoundEvents.CHEST_CLOSE.value(), SoundSource.BLOCKS, 1f, 0.8f);
            // Private: only the player sealing him sees the message
            player.sendSystemMessage(Component.literal("§a[Verity]§r §7...sealed."));
            return InteractionResult.SUCCESS;
        }

        boolean verityExists = !serverLevel.getEntitiesOfClass(VerityEntity.class,
                new AABB(pos.getX() - 200, pos.getY() - 200, pos.getZ() - 200,
                         pos.getX() + 200, pos.getY() + 200, pos.getZ() + 200),
                e -> true).isEmpty();
        if (verityExists) {
            player.sendSystemMessage(Component.literal("§e[Verity]§r §7Already out..."));
            return InteractionResult.SUCCESS;
        }

        openBox(serverLevel, pos, player);
        return InteractionResult.SUCCESS;
    }

    private void openBox(ServerLevel world, BlockPos pos, Player player) {
        world.playSound(null, pos, SoundEvents.CHEST_OPEN.value(), SoundSource.BLOCKS, 1f, 0.6f);
        world.sendParticles(ParticleTypes.END_ROD,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                20, 0.3, 0.3, 0.3, 0.1);

        VerityEntity verity = VerityMod.VERITY.create(world);
        if (verity == null) {
            VerityMod.LOGGER.error("[Verity] Failed to create VerityEntity!");
            return;
        }
        verity.moveTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 0, 0);
        world.addFreshEntity(verity);

        VerityWorldState ws = VerityWorldState.getOrCreate(world);
        ws.setPlayerHome(player.getX(), player.getY(), player.getZ());

        VerityVoiceManager.playVoiceSound(world, pos, "hello");

        player.sendSystemMessage(Component.literal(
                "§e[Verity]§r §fHellooo! I am Verity, your personal assistant. Ask me anything!"));
        VerityMod.LOGGER.info("[Verity] Spawned at {}", pos);
    }

    // ------------------------------------------------------------------ //
    //  Ambient particles (client-side)                                     //
    // ------------------------------------------------------------------ //
    @Override
    public void animateTick(BlockState state, Level world, BlockPos pos, RandomSource random) {
        if (random.nextInt(4) == 0) {
            world.addParticle(ParticleTypes.END_ROD,
                    pos.getX() + 0.3 + random.nextDouble() * 0.4,
                    pos.getY() + 0.9,
                    pos.getZ() + 0.3 + random.nextDouble() * 0.4,
                    0, 0.05, 0);
        }
    }
}
