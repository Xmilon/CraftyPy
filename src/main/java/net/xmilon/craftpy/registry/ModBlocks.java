package net.xmilon.craftpy.registry;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.block.PythonComputerBlock;
import net.xmilon.craftpy.block.SlottedBlock;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CraftPy.MODID);

    public static final DeferredBlock<PythonComputerBlock> PYTHON_COMPUTER = BLOCKS.register(
            "python_computer",
            () -> new PythonComputerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final DeferredBlock<Block> UNLOADED_BLOCK = BLOCKS.register(
            "unloaded_block",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_MAGENTA)
                    .strength(0.0F)));

    /**
     * The generic block slot pool. {@code SlotRegistry.BLOCK_CAP} entries are
     * pre-registered at startup; behavior is assigned at load time. The config
     * {@code slotPool.blocks} value is clamped to this hard cap.
     */
    public static final List<DeferredBlock<SlottedBlock>> SLOT_BLOCKS = new ArrayList<>();
    static {
        for (int i = 0; i < SlotRegistry.BLOCK_CAP; i++) {
            final int index = i;
            SLOT_BLOCKS.add(BLOCKS.register("pcblock_" + i, () -> {
                SlottedBlock block = new SlottedBlock(index, BlockBehaviour.Properties.of());
                SlotRegistry.registerBlock(index, block);
                return block;
            }));
        }
    }

    private ModBlocks() {
    }
}
