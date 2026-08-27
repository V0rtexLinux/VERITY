package com.mod.echo.entity;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;
import com.mod.echo.EchoMod;
import net.minecraft.resources.Identifier;

/**
 * GeckoLib model bindings for the companion orb.
 *
 * GeckoLib 5.x resolves the geometry and texture from the render state rather
 * than the entity, so these are plain constants; the animation file is still
 * looked up per animatable.
 */
public class EchoOrbModel extends GeoModel<EchoOrbEntity> {

    private static final Identifier MODEL     = id("geo/echo_orb.geo.json");
    private static final Identifier TEXTURE   = id("textures/entity/echo_orb.png");
    private static final Identifier ANIMATION = id("animations/echo.animation.json");

    @Override
    public Identifier getModelResource(GeoRenderState state) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(GeoRenderState state) {
        return TEXTURE;
    }

    @Override
    public Identifier getAnimationResource(EchoOrbEntity animatable) {
        return ANIMATION;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(EchoMod.MOD_ID, path);
    }
}
