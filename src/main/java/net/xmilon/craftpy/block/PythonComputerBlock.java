package net.xmilon.craftpy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

public class PythonComputerBlock extends Block {
    public static final Component TITLE = Component.translatable("block.craftpy.python_computer");
    /** The screen faces the player who placed it. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public PythonComputerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (!level.isClientSide) {
            // a plain screen, not a container: item-list mods (JEI, EMI, REI) do not lay their panel over the IDE
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                net.xmilon.craftpy.network.CraftPyNetwork.sendTo(serverPlayer, net.xmilon.craftpy.network.ProjectAction.OPEN_IDE,
                        true, "open", "");
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
