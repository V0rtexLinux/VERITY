---
  name: MC 26.1.2 Fabric mod migration patterns
  description: Key API changes when migrating a Fabric mod from 1.20.x/1.21.x to Minecraft 26.1.2 (unobfuscated Mojang mappings)
  ---

  ## SavedData migration
  - `SavedData.Factory` removed. Use `SavedDataType<T>` with `(String id, Function<Context,T> ctor, Function<Context,Codec<T>> codec, null)`.
  - `computeIfAbsent(TYPE)` — no key string at call site; key is embedded in SavedDataType.
  - `RecordCodecBuilder` max 16 fields per `group()`; for 20+ fields nest extras into a sub-record at position 16.
  - `setDirty()` still required; the `save(CompoundTag)` override is gone.

  ## ResourceLocation
  - Import: `net.minecraft.resources.ResourceLocation` (NOT `net.minecraft.core`).
  - `ResourceKey.location()` renamed to `ResourceKey.id()` in 26.1.2.
  - `Registry.getKey(T)` still returns `@Nullable ResourceLocation` — no change, `.getPath()` is valid.

  ## HUD rendering
  - `InGameHudRenderCallback` and `HudRenderCallback` both removed.
  - Use `HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id, (GuiGraphics, DeltaTracker) -> {})`.
  - Package: `net.fabricmc.fabric.api.client.rendering.v1.hud`.
  - `client.textRenderer` → `client.font`; `fontHeight` → `lineHeight`.

  ## Entity / world
  - `Entity.getPos()` → `Entity.position()`; `getBlockPos()` → `blockPosition()`.
  - `BlockPos.up()` → `above()`.
  - `AABB.expand()` → `inflate()`.
  - `World.getEntitiesByClass` → `getEntitiesOfClass`.
  - `PlayerList.broadcast()` → `broadcastSystemMessage()`.

  ## Registries
  - `Registries.SOUND_EVENT` is a `ResourceKey`, NOT a registry instance. Use `BuiltInRegistries.SOUND_EVENT` for lookups.

  ## Build config
  - Loom plugin ID: `net.fabricmc.fabric-loom` (not `fabric-loom`).
  - No mappings line needed (26.1.2 is unobfuscated).
  - Use `implementation` (not `modImplementation`) for all deps.
  - Java 25 required.
  