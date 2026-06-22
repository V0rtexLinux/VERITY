---
name: MC 26.1.2 Fabric mod migration patterns
description: Authoritative API facts for migrating/building a Fabric mod for Minecraft 26.1.2 (first unobfuscated MC release, Loom 1.15.5)
---

## CRITICAL: Why the build fails — root causes
1. **Mappings line must be ABSENT** — MC 26.1 ships unobfuscated. Adding `mappings loom.officialMojangMappings()` makes Loom 1.15 try to remap an unobfuscated JAR and breaks the entire classpath.
2. **GeckoLib artifact ID is `geckolib-fabric-26`** (not `geckolib-fabric-26.1.2`, not `geckolib-fabric-1.21.x`). Version is `5.5.1` (latest `5.5.2` as of Jun 19 2026).

## Correct build.gradle template (Loom 1.15.5 + MC 26.1.2)
```groovy
plugins { id 'net.fabricmc.fabric-loom' version '1.15.5' }

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    // NO mappings line — 26.1 is unobfuscated

    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
    modImplementation "software.bernie.geckolib:geckolib-fabric-26:${project.geckolib_version}"

    implementation "com.google.code.gson:gson:2.10.1"
}
```
- `modImplementation` IS available in Loom 1.15 (use it for mod dependencies)
- `implementation` for plain JVM libs (Gson, etc.)
- Java 25 required

## ResourceLocation (correct class name in MC 26.1 Mojang mappings)
- `net.minecraft.resources.ResourceLocation` — this IS the Mojang-official name ✓
- Yarn name was `net.minecraft.util.Identifier` (don't use this)
- Factory: `ResourceLocation.fromNamespaceAndPath(ns, path)` ✓
- `ResourceLocation.tryParse(str)` still exists ✓
- `ResourceKey.location()` still returns `ResourceLocation` ✓

## SoundEvents in MC 26.1
- SoundEvents fields are DIRECT `SoundEvent` (not `Holder<SoundEvent>`)
- Do NOT call `.value()` — `SoundEvents.CHEST_CLOSE` is already a `SoundEvent`
- This was cleaned up in MC 26.1 unobfuscated release

## Player/Entity API changes
- `player.serverLevel()` removed → use `(ServerLevel) player.level()`
- `Entity.getServer()` removed → use `((ServerLevel) entity.level()).getServer()` server-side
- `Entity.moveTo(x,y,z,yaw,pitch)` removed → use `entity.setPos(x,y,z)`
- `BlockPos.toImmutable()` → `BlockPos.immutable()`

## EntityType registration (MC 26.1)
- `EntityType.Builder.build(String)` removed — takes `ResourceKey<EntityType<T>>`
- Create key: `ResourceKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(MOD_ID, "name"))`
- Due to Java generics, use `@SuppressWarnings("unchecked")` helper method to cast `ResourceKey<EntityType<?>>` to `ResourceKey<EntityType<T>>`

## ServerLevelData
- Moved to `net.minecraft.world.level.storage.ServerLevelData`
- (was `net.minecraft.server.level.ServerLevelData`)

## GeckoLib 5.x API
- `registerControllers(AnimatableInstanceCache)` → `registerControllers(AnimatableManager.ControllerRegistrar controllers)`
- Import: `software.bernie.geckolib.animatable.AnimatableManager`
- `getAnimatableInstanceCache()` name unchanged ✓
- GeoEntityModel methods (`getModelResource`, `getTextureResource`, `getAnimationResource`) return `ResourceLocation`
- GeckoLib reads Bedrock-format `.geo.json` and `.animation.json` natively — no conversion needed

## Fabric API 0.152 Networking
- `PayloadTypeRegistry.playC2S()` → `PayloadTypeRegistry.serverboundPlay()`
- `PayloadTypeRegistry.playS2C()` → `PayloadTypeRegistry.clientboundPlay()`
- Registration via `serverboundPlay().register(TYPE, CODEC)` is still required BEFORE `registerGlobalReceiver`

## HUD rendering (Fabric API 0.152)
- `GuiGraphicsExtractor` removed — use `net.minecraft.client.gui.GuiGraphics` directly
- `client.options.hudHidden` → `client.options.hideGui`
- Callback for `HudElementRegistry.attachElementBefore` takes `(GuiGraphics, DeltaTracker)`

## SavedData migration
- `SavedData.Factory` removed — use `SavedDataType<T>` with a Codec
- `setDirty()` still required

## Registries
- Use `BuiltInRegistries.SOUND_EVENT` for lookups (not `Registries.SOUND_EVENT`)
- `Registries.ENTITY_TYPE` is a ResourceKey<Registry<EntityType<?>>>` — used with ResourceKey.create()
