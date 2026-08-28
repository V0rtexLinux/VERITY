package com.mod.verity.echo;

import com.geckolib.renderer.GeoEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Client-side renderer for {@link EchoEntity} using GeckoLib's GeoEntityRenderer. */
public class EchoRenderer extends GeoEntityRenderer<EchoEntity, EntityRenderState> {

    public EchoRenderer(EntityRendererProvider.Context context) {
        super(context, new EchoModel());
    }
}
