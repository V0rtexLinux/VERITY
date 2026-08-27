package com.mod.echo;

import com.mod.echo.ai.EchoBrain;
import com.mod.echo.ai.LocalAI;
import com.mod.echo.ai.ToolRegistry;
import com.mod.echo.config.EchoConfig;
import com.mod.echo.entity.EchoOrbEntity;
import com.mod.echo.event.ChatHandler;
import com.mod.echo.item.EchoCoreItem;
import com.mod.echo.net.SettingsRequestPayload;
import com.mod.echo.net.VoiceQueryPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ECHO — a local, offline AI assistant that lives in your Minecraft world.
 *
 * Everything runs on the player's own machine: the language model is served by
 * whatever local runtime they have (Ollama, LM Studio, llama.cpp, Jan,
 * KoboldCpp), and no account, API key or network service is involved.
 *
 * This class is the common entry point — it registers content, wires the chat
 * and networking hooks, and kicks off model discovery in the background so
 * world loading is never blocked on it.
 */
public class EchoMod implements ModInitializer {

    public static final String MOD_ID = "echo";
    public static final Logger LOGGER = LoggerFactory.getLogger("ECHO");

    // ------------------------------------------------------------------ //
    //  Content                                                             //
    // ------------------------------------------------------------------ //

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static final ResourceKey<EntityType<?>> ORB_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, id("echo_orb"));

    /** The floating companion orb. Harmless, invulnerable, purely a presence. */
    public static final EntityType<EchoOrbEntity> ECHO_ORB = EntityType.Builder
            .<EchoOrbEntity>of(EchoOrbEntity::new, MobCategory.MISC)
            .sized(0.5f, 0.5f)
            .eyeHeight(0.25f)
            .clientTrackingRange(10)
            .updateInterval(1)
            .fireImmune()
            .noSummon()
            .noLootTable()
            .build(ORB_KEY);

    /** Right-click to call or dismiss the orb. */
    public static final Item ECHO_CORE = new EchoCoreItem(
            new Item.Properties()
                    .stacksTo(1)
                    .rarity(net.minecraft.world.item.Rarity.RARE)
                    .setId(ResourceKey.create(Registries.ITEM, id("echo_core"))));

    // ------------------------------------------------------------------ //
    //  Initialisation                                                      //
    // ------------------------------------------------------------------ //

    @Override
    public void onInitialize() {
        LOGGER.info("ECHO starting up.");

        EchoConfig.load();

        Registry.register(BuiltInRegistries.ENTITY_TYPE, ORB_KEY, ECHO_ORB);
        Registry.register(BuiltInRegistries.ITEM, id("echo_core"), ECHO_CORE);
        FabricDefaultAttributeRegistry.register(ECHO_ORB, EchoOrbEntity.createAttributes());

        ChatHandler.register();
        VoiceQueryPayload.registerServer();
        SettingsRequestPayload.registerCommon();

        registerPlayerEvents();

        // Model discovery can take a while (it may even download a model), so it
        // runs off-thread; chat commands work immediately either way.
        LocalAI.initialize().thenAccept(ready -> {
            if (ready) {
                LOGGER.info("Ready — {} on {}, {} server-side tools.",
                        LocalAI.getModel(), LocalAI.getBackendName(), ToolRegistry.count());
            } else {
                LOGGER.warn("No local model available yet.\n{}", LocalAI.setupHelp());
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(EchoMod::shutdown, "echo-shutdown"));

        LOGGER.info("ECHO initialised.");
    }

    private static void registerPlayerEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> ChatHandler.welcome(handler.player)));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                EchoBrain.clear(handler.player.getUUID().toString()));

        // Remembering where a player fell is genuinely useful, so it is recorded
        // on every death; the death itself is never interfered with.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player) {
                try {
                    ChatHandler.recordDeath(player);
                } catch (Exception e) {
                    LOGGER.debug("Could not record a death location: {}", e.toString());
                }
            }
            return true;
        });
    }

    public static void shutdown() {
        LOGGER.info("ECHO shutting down.");
        EchoBrain.clearAll();
        LocalAI.shutdown();
    }
}
