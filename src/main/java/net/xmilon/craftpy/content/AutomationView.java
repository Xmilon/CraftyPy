package net.xmilon.craftpy.content;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * What a hopper, pipe or other mod sees of a machine's slots from one side: items go only into input, fuel and storage slots
 * (and only what the group accepts), and come out only of output and storage slots. A group's {@code sides=} replaces the
 * defaults, which are like a furnace: input from the top and the sides, fuel from the sides, output from the bottom and the
 * sides, storage from everywhere.
 */
public final class AutomationView implements IItemHandler {
    private final KineticInventory inventory;
    private final Direction side;

    public AutomationView(KineticInventory inventory, Direction side) {
        this.inventory = inventory;
        this.side = side;
    }

    private boolean canInsert(int slot) { return InventoryMap.canInsert(inventory.spec(), slot, side); }

    private boolean canExtract(int slot) { return InventoryMap.canExtract(inventory.spec(), slot, side); }

    @Override public int getSlots() { return inventory.getSlots(); }
    @Override public ItemStack getStackInSlot(int slot) { return inventory.getStackInSlot(slot); }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return canInsert(slot) ? inventory.insertItem(slot, stack, simulate) : stack;
    }
    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return canExtract(slot) ? inventory.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
    }
    @Override public int getSlotLimit(int slot) { return inventory.getSlotLimit(slot); }
    @Override public boolean isItemValid(int slot, ItemStack stack) { return canInsert(slot) && inventory.isItemValid(slot, stack); }
}
