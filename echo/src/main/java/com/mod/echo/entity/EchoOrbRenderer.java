package com.mod.echo.entity;

import com.geckolib.renderer.GeoEntityRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Client-side renderer for the companion orb. */
@Environment(EnvType.CLIENT)
public class EchoOrbRenderer extends GeoEntityRenderer<EchoOrbEntity, EntityRenderState> {

    public EchoOrbRenderer(EntityRendererProvider.Context context) {
        super(context, new EchoOrbModel());
    }
}
