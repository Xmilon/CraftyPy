package net.xmilon.craftpy.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Loaded only behind the optional-mod check. No Create types escape this package. */
public final class CreateIntegration {
    private CreateIntegration() { }
    public static net.xmilon.craftpy.content.MachineBackend backend() {
        return new net.xmilon.craftpy.content.MachineBackend("create", CreateIntegration::block, CreateIntegration::entity, CreateIntegration::wrench);
    }
    public static Block block(int index) { return new MachineBlock(); }
    public static BlockEntity entity(BlockPos pos, BlockState state) { return new KineticMachine(pos, state); }
    public static net.minecraft.world.InteractionResult wrench(net.minecraft.world.item.context.UseOnContext context) {
        var state = context.getLevel().getBlockState(context.getClickedPos());
        return ((com.simibubi.create.content.equipment.wrench.IWrenchable) state.getBlock()).onWrenched(state, context);
    }
}
