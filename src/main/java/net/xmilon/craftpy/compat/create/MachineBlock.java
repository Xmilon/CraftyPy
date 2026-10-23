package net.xmilon.craftpy.compat.create;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.xmilon.craftpy.registry.KineticSlots;

final class MachineBlock extends KineticBlock implements IBE<BlockEntity>, IWrenchable {
    MachineBlock() { super(Properties.of().strength(2, 6).noOcclusion()); registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.AXIS, Direction.Axis.Y)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(BlockStateProperties.AXIS); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return KineticSlots.configuredState(this); }
    @Override public Direction.Axis getRotationAxis(BlockState state) { return state.getValue(BlockStateProperties.AXIS); }
    @Override public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) { return world.getBlockEntity(pos) instanceof KineticMachine machine && machine.machineState().rotation() != null && face.getAxis() == getRotationAxis(state); }
    @Override public Class<BlockEntity> getBlockEntityClass() { return BlockEntity.class; }
    @Override protected java.util.List<ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder context) { return net.xmilon.craftpy.content.MachineInteractions.preserveResources(super.getDrops(state, context), context); }
    @Override public BlockEntityType<?> getBlockEntityType() { return KineticSlots.TYPE.get(); }
    @Override public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == KineticSlots.TYPE.get() ? (world, pos, s, be) -> { if (be instanceof KineticMachine machine) machine.tick(); } : null;
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock())) net.xmilon.craftpy.content.MachineInteractions.drop(level, pos);
        super.onRemove(state, level, pos, next, moving);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return net.xmilon.craftpy.content.MachineInteractions.insert(stack, level, pos, player, hand);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return net.xmilon.craftpy.content.MachineInteractions.use(level, pos, player, this);
    }
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        var shape = net.xmilon.craftpy.content.MachineInteractions.shape(this);
        return shape != null ? shape : super.getShape(state, level, pos, context);
    }
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        return getShape(state, level, pos, context);
    }
}
