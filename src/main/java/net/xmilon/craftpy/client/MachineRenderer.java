package net.xmilon.craftpy.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.xmilon.craftpy.content.MachineAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.xmilon.craftpy.registry.KineticSlots;

/** No Flywheel visual is registered: this BER must render with either visualization mode. */
public final class MachineRenderer implements BlockEntityRenderer<BlockEntity> {
    public static ModelResourceLocation rotor(int index) { return ModelResourceLocation.standalone(ResourceLocation.parse("craftpy:block/pckinetic_" + index + "_rotor")); }
    public static void register(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(KineticSlots.TYPE.get(), context -> new MachineRenderer());
    }
    @Override public void render(BlockEntity entity, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!(entity instanceof MachineAccess machine)) return;
        if (!machine.machineState().rotates()) return;
        var axis = entity.getBlockState().getValue(BlockStateProperties.AXIS);
        var model = Minecraft.getInstance().getModelManager().getModel(rotor(KineticSlots.index(entity.getBlockState().getBlock())));
        pose.pushPose(); pose.translate(.5, .5, .5);
        switch (axis) { case X -> pose.mulPose(Axis.ZP.rotationDegrees(-90)); case Z -> pose.mulPose(Axis.XP.rotationDegrees(90)); default -> { } }
        pose.mulPose(Axis.YP.rotation((float) ((((double) entity.getLevel().getGameTime() + partial) * machine.machineSpeed() * 0.3) % 360 * Math.PI / 180)));
        pose.translate(-.5, -.5, -.5);
        for (var type : model.getRenderTypes(entity.getBlockState(), net.minecraft.util.RandomSource.create(42), ModelData.EMPTY))
            Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(type), entity.getBlockState(), model, 1, 1, 1, light, overlay, ModelData.EMPTY, type);
        pose.popPose();
    }
}
