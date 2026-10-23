package net.xmilon.craftpy.client;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.xmilon.craftpy.registry.FluidSlots;

/** Client only: how the liquids of the pool look (textures and tint come from the project's definition, read when drawn). */
public final class FluidClientExtensions {
    private FluidClientExtensions() {
    }

    public static void register(RegisterClientExtensionsEvent event) {
        var types = FluidSlots.types();
        for (int i = 0; i < types.size(); i++) {
            event.registerFluidType(extension(i), types.get(i).get());
        }
    }

    static IClientFluidTypeExtensions extension(int index) {
        return new IClientFluidTypeExtensions() {
            @Override
            public ResourceLocation getStillTexture() {
                var def = FluidSlots.def(index);
                return def != null && def.still != null ? def.still : ResourceLocation.withDefaultNamespace("block/water_still");
            }

            @Override
            public ResourceLocation getFlowingTexture() {
                var def = FluidSlots.def(index);
                return def != null && def.flowing != null ? def.flowing : ResourceLocation.withDefaultNamespace("block/water_flow");
            }

            @Override
            public ResourceLocation getOverlayTexture() {
                return ResourceLocation.withDefaultNamespace("block/water_overlay");
            }

            @Override
            public int getTintColor() {
                var def = FluidSlots.def(index);
                // own textures are drawn as they are; the water textures take the declared color
                return def == null || def.still != null ? 0xFFFFFFFF : def.color;
            }
        };
    }
}
