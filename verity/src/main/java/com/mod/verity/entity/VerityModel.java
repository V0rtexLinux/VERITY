package com.mod.verity.entity;

import com.mod.verity.VerityMod;
import net.minecraft.resources.Identifier;
import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;

/**
 * GeckoLib model for Verity.
 *
 * GeckoLib 5.5.1: getModelResource/getTextureResource now take GeoRenderState
 * instead of the entity instance. Static resources are used here; stage-based
 * switching can be restored once entity state is passed via GeoRenderState
 * data tickets.
 *
 * Stage 1-4 → sphere; Stage 5 → cave dweller form.
 * For now, sphere model and stage-1 texture are returned for all stages.
 */
public class VerityModel extends GeoModel<VerityEntity> {

    private static final Identifier MODEL = id("geo/verity_sphere.geo.json");
    private static final Identifier TEX   = id("textures/entity/verity_sphere_stage1.png");
    private static final Identifier ANIMS = id("animations/verity.animation.json");

    @Override
    public Identifier getModelResource(GeoRenderState state) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(GeoRenderState state) {
        return TEX;
    }

    @Override
    public Identifier getAnimationResource(VerityEntity entity) {
        return ANIMS;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(VerityMod.MOD_ID, path);
    }
}
