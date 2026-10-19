package net.xmilon.craftpy.registry;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.block.SlottedItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CraftPyLog.MODID);

    public static final DeferredItem<BlockItem> PYTHON_COMPUTER = ITEMS.registerSimpleBlockItem(ModBlocks.PYTHON_COMPUTER);
    public static final DeferredItem<BlockItem> UNLOADED_BLOCK = ITEMS.registerSimpleBlockItem(ModBlocks.UNLOADED_BLOCK);
    public static final DeferredItem<net.xmilon.craftpy.block.MachineWrenchItem> MACHINE_WRENCH = ITEMS.registerItem(
            "machine_wrench", net.xmilon.craftpy.block.MachineWrenchItem::new, new Item.Properties().stacksTo(1));

    public static final DeferredItem<net.xmilon.craftpy.block.PythonWrenchItem> PYTHON_WRENCH = ITEMS.registerItem(
            "python_wrench", net.xmilon.craftpy.block.PythonWrenchItem::new, new Item.Properties().stacksTo(1));

    /** One spawn egg for every Python mob: the stack's entity data names the mob (see ModEntities.egg). */
    public static final DeferredItem<net.neoforged.neoforge.common.DeferredSpawnEggItem> SPAWN_EGG = ITEMS.register("spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(() -> ModEntities.MOBS.get(0).get(),
                    0x3d8f5a, 0xc9e86b, new Item.Properties()));

    /** Block items backing the generic block slot pool. */
    public static final List<DeferredItem<BlockItem>> SLOT_BLOCK_ITEMS = new ArrayList<>();
    /** Standalone items for the generic item slot pool. */
    public static final List<DeferredItem<SlottedItem>> SLOT_ITEMS = new ArrayList<>();

    static {
        for (var block : ModBlocks.SLOT_BLOCKS) {
            SLOT_BLOCK_ITEMS.add(ITEMS.registerSimpleBlockItem(block));
        }
        for (var block : KineticSlots.BLOCKS) ITEMS.registerSimpleBlockItem(block);
        for (int i = 0; i < SlotRegistry.itemSlotCount(); i++) {
            final int index = i;
            SLOT_ITEMS.add(ITEMS.register("pcitem_" + i, () -> {
                SlottedItem item = new SlottedItem(index, new Item.Properties());
                SlotRegistry.registerItem(index, item);
                return item;
            }));
        }
    }

    private ModItems() {
    }
}
