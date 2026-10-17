package net.xmilon.craftpy.compat.geckolib;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.entity.SlottedEntity;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** Draws Python mobs whose model is a {@code .geo.json}, with GeckoLib (client only, only with GeckoLib installed). */
public class GeoSlottedRenderer extends GeoEntityRenderer<GeoSlottedEntity> {
    private static final ResourceLocation MISSING = ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    private GeoSlottedRenderer(EntityRendererProvider.Context context) {
        super(context, new GeoModel<>() {
            @Override
            public ResourceLocation getModelResource(GeoSlottedEntity entity) {
                return ResourceLocation.parse(entity.look().model());
            }

            @Override
            public ResourceLocation getTextureResource(GeoSlottedEntity entity) {
                return entity.texture(MISSING);
            }

            @Override
            public ResourceLocation getAnimationResource(GeoSlottedEntity entity) {
                ResourceLocation id = ResourceLocation.tryParse(entity.look().animation());
                return id != null ? id : ResourceLocation.fromNamespaceAndPath("craftpy", "animations/none.animation.json");
            }
        });
    }

    @SuppressWarnings("unchecked")
    public static EntityRenderer<SlottedEntity> create(EntityRendererProvider.Context context) {
        return (EntityRenderer<SlottedEntity>) (EntityRenderer<?>) new GeoSlottedRenderer(context);
    }

    /** True when GeckoLib has loaded this mob's model; otherwise it is drawn with a vanilla body. */
    public static boolean ready(SlottedEntity entity) {
        ResourceLocation model = ResourceLocation.tryParse(entity.look().model());
        return entity instanceof GeoSlottedEntity && model != null && GeckoLibCache.getBakedModels().containsKey(model);
    }

    @Override
    public void render(GeoSlottedEntity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight) {
        float scale = entity.look().scale();
        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);
        super.render(entity, yaw, partialTick, poseStack, buffer, packedLight);
        poseStack.popPose();
    }
}
