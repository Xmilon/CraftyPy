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
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.xmilon.craftpy.entity.ThrownWeapon;

/**
 * Draws a thrown weapon as its own item, pointing the way it flies. A tool's sprite has its tip on the up-right diagonal, so
 * it is turned 45 degrees to lie along the flight; a second copy turned a quarter around its length keeps it visible from above.
 */
public final class ThrownWeaponRenderer extends EntityRenderer<ThrownWeapon> {
    private final ItemRenderer items;

    public ThrownWeaponRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.items = context.getItemRenderer();
    }

    @Override
    public void render(ThrownWeapon weapon, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        if (weapon.item().isEmpty()) return;
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTick, weapon.yRotO, weapon.getYRot()) - 90f));
        pose.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTick, weapon.xRotO, weapon.getXRot()) - 45f));
        pose.scale(1.5f, 1.5f, 1.5f);
        for (int layer = 0; layer < 2; layer++) {
            items.renderStatic(weapon.item(), ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY, pose, buffers, weapon.level(), weapon.getId());
            pose.mulPose(Axis.XP.rotationDegrees(90f));
        }
        pose.popPose();
        super.render(weapon, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(ThrownWeapon weapon) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
