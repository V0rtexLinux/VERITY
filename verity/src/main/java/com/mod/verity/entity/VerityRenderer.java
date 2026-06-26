package com.mod.verity.entity;

import com.geckolib.renderer.GeoEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * Client-side renderer for VerityEntity using GeckoLib's GeoEntityRenderer.
 *
 * Migrated to Mojang mappings (MC 26.1.2):
 *   EntityRendererFactory.Context → EntityRendererProvider.Context
 */
public class VerityRenderer extends GeoEntityRenderer<VerityEntity, EntityRenderState> {

    public VerityRenderer(EntityRendererProvider.Context context) {
        super(context, new VerityModel());
    }
}
