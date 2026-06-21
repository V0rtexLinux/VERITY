---
name: MC 26.1.2 Fabric mod migration patterns
description: Key API changes when migrating a Fabric mod to Minecraft 26.1.2 with Fabric Loom 1.15.5
---

## Build config
- Loom plugin ID: `net.fabricmc.fabric-loom` version `1.15.5`.
- **Always include** `mappings loom.officialMojangMappings()` — without it `ResourceLocation` and other MC classes are unresolvable.
- Use `modImplementation` for fabric-loader, fabric-api, and any mod deps (GeckoLib, etc.).
- GeckoLib Maven: `https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/`
- GeckoLib artifact pattern: `software.bernie.geckolib:geckolib-fabric-${minecraft_version}:${geckolib_version}`
- Java 25 required. `options.release = 25`.

## ResourceLocation / Identifier
- Fabric/Yarn name `net.minecraft.util.Identifier` does NOT exist under Mojang mappings.
- Correct import: `net.minecraft.resources.ResourceLocation`.
- Factory: `ResourceLocation.fromNamespaceAndPath(ns, path)` (replaces `Identifier.of` and `Identifier.fromNamespaceAndPath`).
- `ResourceKey.location()` returns `ResourceLocation`; still works if ResourceLocation is properly on classpath.

## Player/Entity API
- `player.serverLevel()` removed. Cast instead: `(ServerLevel) player.level()`.
- `Entity.getServer()` removed on plain Entity. Use `((ServerLevel) entity.level()).getServer()` server-side.
- `Entity.moveTo(double,double,double,float,float)` removed — use `entity.setPos(x,y,z)` to reposition.
- `BlockPos.toImmutable()` renamed to `BlockPos.immutable()`.

## SoundEvents
- Fields like `SoundEvents.CHEST_CLOSE` are now direct `SoundEvent` (not `Holder<SoundEvent>`).
- Remove all `.value()` calls: `SoundEvents.X.value()` → `SoundEvents.X`.

## EntityType registration
- `EntityType.Builder.build(String)` removed. Now takes `ResourceKey<EntityType<T>>`.
- Create key: `ResourceKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(MOD_ID, "name"))`.
- Due to generic type invariance, use a helper with `@SuppressWarnings("unchecked")` cast to `ResourceKey<EntityType<T>>`.

## ServerLevelData
- Moved from `net.minecraft.server.level.ServerLevelData` → `net.minecraft.world.level.storage.ServerLevelData`.

## GeckoLib 5.x API
- `registerControllers(AnimatableInstanceCache)` → `registerControllers(AnimatableManager.ControllerRegistrar controllers)`.
- Add import: `software.bernie.geckolib.animatable.AnimatableManager`.
- `getAnimatableInstanceCache()` name unchanged. `AnimatableInstanceCache` still exists.

## HUD rendering
- `InGameHudRenderCallback` and `HudRenderCallback` removed.
- Use `HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id, (GuiGraphics, DeltaTracker) -> {})`.
- Package: `net.fabricmc.fabric.api.client.rendering.v1.hud`.
- `GuiGraphicsExtractor` (old Fabric wrapper) removed; use `net.minecraft.client.gui.GuiGraphics` directly.
- `client.options.hudHidden` → `client.options.hideGui`.
- `client.textRenderer` → `client.font`; `fontHeight` → `lineHeight`.

## Networking (Fabric API 0.152+)
- `PayloadTypeRegistry.playC2S().register(TYPE, CODEC)` line can be dropped; `ServerPlayNetworking.registerGlobalReceiver(TYPE, handler)` registers the type automatically.

## SavedData migration
- `SavedData.Factory` removed. Use `SavedDataType<T>` with a Codec.
- `setDirty()` still required.

## Registries
- Use `BuiltInRegistries.SOUND_EVENT` for lookups (not `Registries.SOUND_EVENT`).
