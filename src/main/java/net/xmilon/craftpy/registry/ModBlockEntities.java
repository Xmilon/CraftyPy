package net.xmilon.craftpy.registry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.block.SlottedBlockEntity;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, CraftPyLog.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SlottedBlockEntity>> SLOTTED_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("slotted_block_entity",
                    () -> BlockEntityType.Builder.of(SlottedBlockEntity::new,
                            ModBlocks.SLOT_BLOCKS.stream().map(net.neoforged.neoforge.registries.DeferredBlock::get).toArray(net.minecraft.world.level.block.Block[]::new))
                            .build(null));

    private ModBlockEntities() {
    }
}
