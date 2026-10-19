package net.xmilon.craftpy.content;

import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Startup adapter boundary. New native families reuse MachineAccess/MachineState and the same Python declarations. */
public record MachineBackend(String id, IntFunction<Block> blocks,
                             BiFunction<BlockPos, BlockState, BlockEntity> entities,
                             Function<UseOnContext, InteractionResult> wrench) {
    private static final class Selection {
        static final MachineBackend BACKEND = net.xmilon.craftpy.project.RuntimeFeatures.createAvailable()
                ? net.xmilon.craftpy.compat.create.CreateIntegration.backend()
                : new MachineBackend("craftpy", index -> new net.xmilon.craftpy.block.NativeMachineBlock(),
                    net.xmilon.craftpy.block.NativeMachine::new, MachineBackend::rotateNative);
    }
    public static MachineBackend selected() { return Selection.BACKEND; }
    private static InteractionResult rotateNative(UseOnContext context) {
        var level = context.getLevel(); var pos = context.getClickedPos();
        level.setBlock(pos, level.getBlockState(pos).cycle(BlockStateProperties.AXIS), 3); RotationNetworks.changed(level);
        return InteractionResult.CONSUME;
    }
}
