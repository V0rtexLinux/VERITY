package com.mod.verity;

import com.mod.verity.ai.VerityAI;
import com.mod.verity.block.VerityBoxBlock;
import com.mod.verity.entity.VerityEntity;
import com.mod.verity.event.ChatHandler;
import com.mod.verity.event.FoodTracker;
import com.mod.verity.event.SessionTracker;
import com.mod.verity.item.VerityOrbItem;
import com.mod.verity.voice.VoicePacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VerityMod implements ModInitializer {

    public static final String MOD_ID = "verity";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ------------------------------------------------------------------ //
    //  Entity Type                                                         //
    // ------------------------------------------------------------------ //
    @SuppressWarnings("unchecked")
    private static <T extends net.minecraft.world.entity.Entity> ResourceKey<EntityType<T>> entityKey(String path) {
        return (ResourceKey<EntityType<T>>) (ResourceKey<?>) ResourceKey.create(
                Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, path));
    }

    public static final EntityType<VerityEntity> VERITY = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            VerityMod.<VerityEntity>entityKey("verity"),
            EntityType.Builder.<VerityEntity>of(VerityEntity::new, MobCategory.MONSTER)
                    .sized(0.6f, 0.6f)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .build(VerityMod.<VerityEntity>entityKey("verity"))
    );

    // ------------------------------------------------------------------ //
    //  Blocks                                                              //
    // ------------------------------------------------------------------ //
    public static final Block VERITY_BOX = new VerityBoxBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.GOLD)
                    .strength(2.0f)
                    .noOcclusion()
                    .lightLevel(state -> 7)
    );

    public static final Item VERITY_BOX_ITEM = new BlockItem(
            VERITY_BOX, new Item.Properties()
    );

    // ------------------------------------------------------------------ //
    //  Items                                                               //
    // ------------------------------------------------------------------ //
    public static final Item VERITY_ORB_ITEM = new VerityOrbItem(
            new Item.Properties().stacksTo(1)
    );

    /** Twixxel's Journal — contains ROT21 cipher "XJSIMNRMTRJ" = "SENDHIMHOME". */
    public static final Item TWIXXELS_JOURNAL = new Item(
            new Item.Properties().stacksTo(1)
    );

    // ------------------------------------------------------------------ //
    //  onInitialize                                                        //
    // ------------------------------------------------------------------ //
    @Override
    public void onInitialize() {
        LOGGER.info("[Verity] Initializing...");

        // --- Blocks ---
        Registry.register(BuiltInRegistries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, "verity_box"), VERITY_BOX);
        Registry.register(BuiltInRegistries.ITEM,  Identifier.fromNamespaceAndPath(MOD_ID, "verity_box"), VERITY_BOX_ITEM);

        // --- Items ---
        Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "verity_orb"),       VERITY_ORB_ITEM);
        Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "twixxels_journal"), TWIXXELS_JOURNAL);

        // --- Entity attributes ---
        FabricDefaultAttributeRegistry.register(VERITY, VerityEntity.createAttributes());

        // --- Events ---
        ChatHandler.register();
        FoodTracker.register();
        SessionTracker.register();

        // --- Network ---
        VoicePacket.registerServer();

        // --- Player join / death hooks ---
        registerPlayerJoinEvent();
        registerDeathEvent();

        // --- AI System ---
        VerityAI.initialize().thenAccept(success -> {
            if (success) {
                LOGGER.info("[Verity] AI system initialized.");
            } else {
                LOGGER.warn("[Verity] AI init failed — using fallback rule-based system.");
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("[Verity] Shutting down AI...");
            VerityAI.shutdown();
        }));

        LOGGER.info("[Verity] Initialized. He is watching.");
    }

    // ------------------------------------------------------------------ //
    //  Player join — stage-4 friend detection                             //
    // ------------------------------------------------------------------ //
    private static void registerPlayerJoinEvent() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            server.execute(() -> {
                if (server.getPlayerList().getPlayers().size() <= 1) return;
                for (ServerLevel level : server.getAllLevels()) {
                    com.mod.verity.state.VerityWorldState state =
                            com.mod.verity.state.VerityWorldState.getOrCreate(level);
                    String name = handler.player.getName().getString();
                    if (state.getCurrentStage() >= 4) {
                        // Horror broadcast: Verity knows this person (visible to all)
                        server.getPlayerList().broadcastSystemMessage(
                                Component.literal("§c[Verity]§r §7Hello, " + name + ". I know who you are."), false);
                    }
                    if (state.getCurrentStage() <= 2) {
                        state.triggerInvitedFriendEarly();
                        server.getPlayerList().broadcastSystemMessage(
                                Component.literal("§6[Verity]§r §7...you brought someone?"), false);
                    }
                }
            })
        );
    }

    // ------------------------------------------------------------------ //
    //  Player death — stage-4 respawn message                             //
    // ------------------------------------------------------------------ //
    private static void registerDeathEvent() {
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, damageAmount) -> {
            if (!(entity instanceof ServerPlayer player)) return true;
            ServerLevel level = (ServerLevel) player.level();
            com.mod.verity.state.VerityWorldState state =
                    com.mod.verity.state.VerityWorldState.getOrCreate(level);
            if (state.getCurrentStage() >= 4) {
                // Private message — only the dying player sees "...not yet"
                level.getServer().execute(() ->
                        player.sendSystemMessage(
                                Component.literal("§c[Verity]§r §7...not yet.")));
            }
            return true;
        });
    }
}
