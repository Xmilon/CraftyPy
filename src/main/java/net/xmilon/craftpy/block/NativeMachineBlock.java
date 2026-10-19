package net.xmilon.craftpy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.xmilon.craftpy.content.MachineInteractions;
import net.xmilon.craftpy.content.RotationNetworks;
import net.xmilon.craftpy.registry.KineticSlots;

public final class NativeMachineBlock extends Block implements EntityBlock {
    public NativeMachineBlock() { super(Properties.of().strength(2, 6)); registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.AXIS, Direction.Axis.Y)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(BlockStateProperties.AXIS); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return KineticSlots.configuredState(this); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new NativeMachine(pos, state); }
    @Override protected java.util.List<ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder context) { return MachineInteractions.preserveResources(super.getDrops(state, context), context); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) { return type == KineticSlots.TYPE.get() ? (world, pos, s, be) -> { if (be instanceof NativeMachine machine) machine.tick(); } : null; }
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos neighbor, boolean moving) { if (!level.isClientSide) RotationNetworks.changed(level); }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) { if (!state.is(next.getBlock())) MachineInteractions.drop(level, pos); if (!level.isClientSide) RotationNetworks.changed(level); super.onRemove(state, level, pos, next, moving); }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) { return MachineInteractions.insert(stack, level, pos, player, hand); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) { return MachineInteractions.extract(level, pos, player); }
}
