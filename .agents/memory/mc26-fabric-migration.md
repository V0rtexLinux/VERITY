---
name: MC 26.1.2 Fabric mod migration patterns
description: Verified API facts for building a Fabric mod against Minecraft 26.1.2 (first unobfuscated MC release, Loom 1.15.5). Signatures below were checked with javap against the real minecraft-merged jar.
---

## CRITICAL: build setup
1. **No `mappings` line** — MC 26.1 ships unobfuscated. Adding
   `loom.officialMojangMappings()` makes Loom 1.15 try to remap an already-named
   jar and breaks the whole classpath.
2. Java 25 (`options.release = 25`, toolchain 25). MC class files are major
   version 69.
3. GeckoLib maven artifact is `geckolib-fabric-26` version `5.5.1`; the local
   jar in `libs/` works too and removes the network dependency.

## Naming — these are the ones that break builds

| Wrong (older mappings) | Correct in 26.1.2 |
| --- | --- |
| `ResourceLocation` | `net.minecraft.resources.Identifier` |
| `ResourceKey.location()` | **`ResourceKey.identifier()`** |
| `Level.getDayTime()` | **`Level.getDefaultClockTime()`** (also `getOverworldClockTime()`) |
| `MinecraftServer.getDifficulty()` | **`server.getWorldData().getDifficulty()`** |
| `Difficulty.getKey()` | **`Difficulty.getSerializedName()`** |
| `CommandSourceStack.withPermission(int)` | **`withPermission(net.minecraft.server.permissions.PermissionSet)`** — use `PermissionSet.ALL_PERMISSIONS` |
| `BlockPos.toImmutable()` | `BlockPos.immutable()` |

## Methods that no longer exist on Level
- `canSeeSky(BlockPos)` — gone. Compare against
  `level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)` or read
  `getBrightness(LightLayer.SKY, pos)`.
- `getGameRules()` — gone from `Level`. Use the `/gamerule` command instead.
- `isLoaded(BlockPos)` **does** exist and is the right guard before an
  off-thread block scan.

## Covariance worth knowing
- `ServerPlayer.level()` already returns `ServerLevel`. `(ServerLevel) player.level()`
  compiles but javac reports it as a redundant cast.

## Registries
- `Registry.getValue(Identifier)` on BLOCK / ITEM / ENTITY_TYPE returns the
  **default** value (air, air, pig) for an unknown id, never null. Use
  **`registry.containsKey(Identifier)`** to test existence.
- `Identifier.tryParse` returns null for malformed input — check before use.
- `BuiltInRegistries.SOUND_EVENT` for lookups (not `Registries.SOUND_EVENT`).

## SoundEvents field types are mixed
- Most fields are a direct `SoundEvent` (`AMETHYST_BLOCK_CHIME`,
  `EXPERIENCE_ORB_PICKUP`, `BEACON_ACTIVATE`) — do **not** call `.value()`.
- `NOTE_BLOCK_*` and `UI_BUTTON_CLICK` are `Holder.Reference<SoundEvent>` — these
  **do** need `.value()`.
- Check with javap before using one.

## Items
- `Item.Properties` requires **`.setId(ResourceKey.create(Registries.ITEM, id))`**;
  `itemIdOrThrow()` throws at construction without it.
- Tooltip override is
  `appendHoverText(ItemStack, Item.TooltipContext, TooltipDisplay, Consumer<Component>, TooltipFlag)`
  (deprecated but still the only hook). The old 4-argument signature silently
  overrides nothing.

## Entities
- `EntityType.Builder.build(ResourceKey<EntityType<?>>)` — takes the wildcard
  key, so no unchecked cast helper is needed.
- `EntityType.EntityFactory<T>.create(EntityType<T>, Level)`; a constructor
  declared `(EntityType<? extends MyEntity>, Level)` works as a method reference.
- `EntityType.create(Level, EntitySpawnReason)` to instantiate; then
  `serverLevel.addFreshEntity(entity)`.
- `Entity.getServer()` removed → `((ServerLevel) entity.level()).getServer()`.
- `Entity.moveTo(x,y,z,yaw,pitch)` removed → `entity.setPos(x,y,z)`.
- `EntityGetter.getNearestPlayer(Entity, double)` is available on `Level`.

## Threading
- `MinecraftServer.submit(Supplier<V>)` returns `CompletableFuture<V>` and is the
  right way to run world mutations from an async worker;
  `server.isSameThread()` to avoid a needless hop.

## Fabric API 0.152
- `PayloadTypeRegistry.serverboundPlay()` / `clientboundPlay()`; register the
  type **before** `registerGlobalReceiver`, and register clientbound types in
  common init so both sides share the codec.
- `ClientPlayNetworking.canSend(Type)` / `ServerPlayNetworking.canSend(player, Type)`
  is a reliable handshake for "does the other side have this mod".
- HUD: `HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id, element)`
  with `HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker)`.
  `GuiGraphicsExtractor` **does** exist in 26.1 and has `fill`, `outline`, `text`.
- `client.options.hideGui` (not `hudHidden`).

## Client options (for anything that touches settings)
`net.minecraft.client.Options` exposes `OptionInstance<T>` accessors:
`renderDistance()`, `simulationDistance()`, `framerateLimit()`, `graphicsPreset()`,
`cloudStatus()`, `cloudRange()`, `weatherRadius()`, `particles()`,
`entityShadows()`, `entityDistanceScaling()`, `biomeBlendRadius()`,
`mipmapLevels()`, `ambientOcclusion()`, `enableVsync()`, `cutoutLeaves()`,
`prioritizeChunkUpdates()`, `inactivityFpsLimit()`, `fov()`, `guiScale()`,
`gamma()`, `bobView()`, `screenEffectScale()`. Enums live at
`net.minecraft.client.GraphicsPreset`, `net.minecraft.client.CloudStatus`,
`net.minecraft.client.PrioritizeChunkUpdates`, `net.minecraft.client.InactivityFpsLimit`
and — note the package — `net.minecraft.server.level.ParticleStatus`.
`options.save()` persists to options.txt; `Minecraft.getFps()` reads the live rate.

## Verifying without a build
The loom cache holds the real jar at
`echo/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.1.2/`.
`javap -cp <that jar> <class>` answers any signature question offline. To
type-check the whole mod on a JDK older than 25, rewrite each class file's major
version from 69 to the local one in a copy of the jar and compile against that
plus small stubs for the Fabric API — this catches every real API error.
