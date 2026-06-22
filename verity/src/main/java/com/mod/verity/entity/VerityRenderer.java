package com.mod.verity.entity;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import com.geckolib.renderer.GeoEntityRenderer;

/**
 * Client-side renderer for VerityEntity using GeckoLib's GeoEntityRenderer.
 *
 * Migrated to Mojang mappings (MC 26.1.2):
 *   EntityRendererFactory.Context → EntityRendererProvider.Context
 */
public class VerityRenderer extends GeoEntityRenderer<VerityEntity> {

    public VerityRenderer(EntityRendererProvider.Context context) {
        super(context, new VerityModel());
        this.shadowRadius = 0.5f;
    }
}
