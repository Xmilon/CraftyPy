package net.xmilon.craftpy.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.xmilon.craftpy.entity.PropEntity;

/**
 * Draws a prop as its item's model at full block size, standing on the ground and turned the way it was put down: a
 * Blockbench "Java Block/Item" model of 16 units is one block, its bottom on the floor.
 */
public final class PropRenderer extends EntityRenderer<PropEntity> {
    private final ItemRenderer items;

    public PropRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.items = context.getItemRenderer();
        this.shadowRadius = 0.3f;
    }

    @Override
    public void render(PropEntity prop, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        if (prop.item().isEmpty()) return;
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180f - prop.getYRot()));
        pose.translate(0, 0.5, 0); // the model's 0..16 box is centred on the origin; its bottom goes on the ground
        items.renderStatic(prop.item(), ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY, pose, buffers, prop.level(), prop.getId());
        pose.popPose();
        super.render(prop, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(PropEntity prop) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
