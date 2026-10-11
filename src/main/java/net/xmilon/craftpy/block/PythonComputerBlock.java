package net.xmilon.craftpy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.xmilon.craftpy.menu.ComputerMenu;

public class PythonComputerBlock extends Block {
    public static final Component TITLE = Component.translatable("block.craftpy.python_computer");

    public PythonComputerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (!level.isClientSide) {
            player.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, p) -> new ComputerMenu(containerId, inventory), TITLE), pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
