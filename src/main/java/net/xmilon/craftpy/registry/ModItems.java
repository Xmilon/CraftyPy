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

    public static final DeferredItem<Item> PYTHON_WRENCH = ITEMS.registerSimpleItem("python_wrench",
            new Item.Properties().stacksTo(1));

    /** Block items backing the generic block slot pool. */
    public static final List<DeferredItem<BlockItem>> SLOT_BLOCK_ITEMS = new ArrayList<>();
    /** Standalone items for the generic item slot pool. */
    public static final List<DeferredItem<SlottedItem>> SLOT_ITEMS = new ArrayList<>();

    static {
        for (var block : ModBlocks.SLOT_BLOCKS) {
            SLOT_BLOCK_ITEMS.add(ITEMS.registerSimpleBlockItem(block));
        }
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