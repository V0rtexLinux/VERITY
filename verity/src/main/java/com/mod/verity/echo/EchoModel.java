package com.mod.verity.echo;

import com.mod.verity.VerityMod;
import net.minecraft.resources.Identifier;
import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;

/**
 * GeckoLib model for Echo. Reuses Verity's sphere geometry (a friendly
 * floating orb fits Echo just as well) with the stage-1 "friendly" texture,
 * and its idle/walk animation set — no new binary assets required.
 */
public class EchoModel extends GeoModel<EchoEntity> {

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
    public Identifier getAnimationResource(EchoEntity entity) {
        return ANIMS;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(VerityMod.MOD_ID, path);
    }
}
