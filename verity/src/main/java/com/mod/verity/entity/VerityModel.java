package com.mod.verity.entity;

import com.mod.verity.VerityMod;
import com.mod.verity.state.VerityWorldState;
import net.minecraft.util.Identifier;
import net.minecraft.server.level.ServerLevel;
import software.bernie.geckolib.model.GeoEntityModel;

/**
 * GeckoLib model selector for Verity — switches geometry and texture based on stage.
 *
 * Stage 1 → sphere (smiley face texture)
 * Stage 2 → sphere (toothy grin / poker-face texture)
 * Stage 3 → sphere (wide eyes, darker texture)
 * Stage 4 → sphere (distorted / glitched texture)
 * Stage 5 → cave_dweller (full humanoid horror form)
 *
 * Updated for GeckoLib 5.x + Mojang mappings (ResourceLocation replaces Identifier).
 */
public class VerityModel extends GeoEntityModel<VerityEntity> {

    private static final Identifier MODEL_SPHERE       = id("geo/verity_sphere.geo.json");
    private static final Identifier MODEL_CAVE_DWELLER = id("geo/verity_cave_dweller.geo.json");

    private static final Identifier TEX_STAGE1 = id("textures/entity/verity_sphere_stage1.png");
    private static final Identifier TEX_STAGE2 = id("textures/entity/verity_sphere_stage2.png");
    private static final Identifier TEX_STAGE3 = id("textures/entity/verity_sphere_stage3.png");
    private static final Identifier TEX_STAGE4 = id("textures/entity/verity_sphere_stage4.png");
    private static final Identifier TEX_STAGE5 = id("textures/entity/verity_cave_dweller.png");

    private static final Identifier ANIMATIONS  = id("animations/verity.animation.json");

    @Override
    public Identifier getModelResource(VerityEntity entity) {
        return getStage(entity) >= 5 ? MODEL_CAVE_DWELLER : MODEL_SPHERE;
    }

    @Override
    public Identifier getTextureResource(VerityEntity entity) {
        return switch (getStage(entity)) {
            case 1  -> TEX_STAGE1;
            case 2  -> TEX_STAGE2;
            case 3  -> TEX_STAGE3;
            case 4  -> TEX_STAGE4;
            default -> TEX_STAGE5;
        };
    }

    @Override
    public Identifier getAnimationResource(VerityEntity entity) {
        return ANIMATIONS;
    }

    private int getStage(VerityEntity entity) {
        if (!entity.level().isClientSide()) {
            return VerityWorldState.getOrCreate(
                    (ServerLevel) entity.level()
            ).getCurrentStage();
        }
        // Client-side: default to 1 (DataTracker sync for stage to be added later)
        return 1;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(VerityMod.MOD_ID, path);
    }
}
