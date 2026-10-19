package net.xmilon.craftpy.content;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Both automation and hand insertion use the same two native slots. */
public class KineticInventory extends ItemStackHandler {
    public KineticInventory() { super(2); }
    @Override public void deserializeNBT(net.minecraft.core.HolderLookup.Provider registries, net.minecraft.nbt.CompoundTag tag) {
        var fixed = tag.copy(); fixed.putInt("Size", 2);
        // Native machine inventories always have two slots, including when reading foreign or malformed NBT.
        super.deserializeNBT(registries, fixed);
    }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return slot == 0 ? super.insertItem(slot, stack, simulate) : stack;
    }
    public boolean canFinish(ItemStack result) {
        ItemStack output = getStackInSlot(1);
        return !getStackInSlot(0).isEmpty() && !result.isEmpty()
                && (output.isEmpty() || ItemStack.isSameItemSameComponents(output, result))
                && output.getCount() + result.getCount() <= Math.min(getSlotLimit(1), result.getMaxStackSize());
    }
    public boolean finish(ItemStack result) {
        if (!canFinish(result)) return false;
        ItemStack output = getStackInSlot(1).copy();
        if (output.isEmpty()) output = result.copy(); else output.grow(result.getCount());
        extractItem(0, 1, false); setStackInSlot(1, output);
        return true;
    }
}
