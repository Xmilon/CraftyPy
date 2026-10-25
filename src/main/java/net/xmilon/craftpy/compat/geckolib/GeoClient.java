package net.xmilon.craftpy.compat.geckolib;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.xmilon.craftpy.registry.ModItems;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/** Client only, only with GeckoLib installed: draws the project items that have a {@code geo} model. */
public final class GeoClient {
    private GeoClient() {
    }

    public static void register(RegisterClientExtensionsEvent event) {
        for (var entry : ModItems.SLOT_ITEMS) {
            if (!(entry.get() instanceof GeoSlottedItem item)) continue;
            event.registerItem(new IClientItemExtensions() {
                private GeoItemRenderer<GeoSlottedItem> renderer;

                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (renderer == null) renderer = new Renderer();
                    return renderer;
                }
            }, item);
        }
    }

    private static final class Renderer extends GeoItemRenderer<GeoSlottedItem> {
        Renderer() {
            super(new GeoModel<GeoSlottedItem>() {
                @Override
                public ResourceLocation getModelResource(GeoSlottedItem item) {
                    return item.geoModel();
                }

                @Override
                public ResourceLocation getTextureResource(GeoSlottedItem item) {
                    return item.geoTexture();
                }

                @Override
                public ResourceLocation getAnimationResource(GeoSlottedItem item) {
                    return item.geoAnimations();
                }
            });
            // <texture>_glowmask.png shines in the dark (the model's glowing parts, like an ember or a gem)
            addRenderLayer(new software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer<>(this));
        }
    }
}
