package net.xmilon.craftpy.content;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The item slots of a machine. Without a declaration it is the two slots of a processing machine (input, output). With
 * {@code Inventory(...)} it has the declared groups in order; the group's name is its role. Hands, the machine window and
 * Python reach every slot (hands only where the role allows putting things in); hoppers and pipes go through
 * {@link AutomationView}, which also looks at the sides.
 */
public class KineticInventory extends ItemStackHandler {
    private Defs.InventorySpec spec;

    public KineticInventory() { super(2); }

    public Defs.InventorySpec spec() { return spec; }

    /**
     * Applies a (new) declaration. Stored items are never lost: the handler keeps at least as many slots as the last
     * filled one, so a smaller declaration only stops new items from going there.
     */
    public void configure(Defs.InventorySpec next) {
        int wanted = next == null ? 2 : next.total();
        int highest = -1;
        for (int i = 0; i < getSlots(); i++) if (!getStackInSlot(i).isEmpty()) highest = i;
        int size = Math.max(wanted, highest + 1);
        if (size != getSlots()) {
            var old = new java.util.ArrayList<ItemStack>();
            for (int i = 0; i < getSlots(); i++) old.add(getStackInSlot(i));
            setSize(size);
            for (int i = 0; i < Math.min(old.size(), size); i++) stacks.set(i, old.get(i));
        }
        spec = next;
    }

    /** The slot number of {@code group.n}, or -1. */
    public int slotOf(String group, int n) { return InventoryMap.slotOf(spec, group, n); }

    /** The group of a slot ("input", "fuel", "output", a storage group's name), or "" beyond the declaration. */
    public String groupOf(int slot) { return InventoryMap.groupName(spec, slot); }

    /** "input", "fuel", "output", "storage", or "overflow" for a slot kept only so its items are not lost. */
    public String roleOf(int slot) { return InventoryMap.roleOf(spec, slot); }

    /** The first slot an input comes in at, or -1. */
    public int inputSlot() {
        for (int i = 0; i < getSlots(); i++) if (roleOf(i).equals("input")) return i;
        return -1;
    }

    public int outputSlot() {
        for (int i = 0; i < getSlots(); i++) if (roleOf(i).equals("output")) return i;
        return -1;
    }

    @Override public void deserializeNBT(net.minecraft.core.HolderLookup.Provider registries, net.minecraft.nbt.CompoundTag tag) {
        var fixed = tag.copy();
        // never trust the size of foreign or malformed NBT: 2 slots by default, at most the most a block can have
        fixed.putInt("Size", tag.contains("Size") ? Math.max(1, Math.min(Defs.InventorySpec.MAX_SLOTS, tag.getInt("Size"))) : 2);
        super.deserializeNBT(registries, fixed);
    }

    @Override public boolean isItemValid(int slot, ItemStack stack) {
        String role = roleOf(slot);
        if (role.equals("output") || role.equals("overflow")) return false;
        var g = InventoryMap.groupAt(spec, slot);
        return g == null || accepts(g.accepts(), stack);
    }

    static boolean accepts(java.util.List<String> accepts, ItemStack stack) {
        if (accepts.isEmpty()) return true;
        for (String entry : accepts) {
            if (entry.startsWith("#")) {
                var id = ResourceLocation.tryParse(entry.substring(1));
                if (id != null && stack.is(TagKey.create(net.minecraft.core.registries.Registries.ITEM, id))) return true;
            } else if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(
                    // "project:id" of an item the project declared is the registry id of its slot
                    net.xmilon.craftpy.registry.SlotRegistry.resolve(entry, net.xmilon.craftpy.registry.SlotRegistry.Kind.ITEM,
                            net.xmilon.craftpy.registry.SlotRegistry.Kind.BLOCK))) {
                return true;
            }
        }
        return false;
    }

    @Override public int getSlotLimit(int slot) {
        var g = InventoryMap.groupAt(spec, slot);
        return g == null ? 64 : g.maxStack();
    }

    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        // the two-slot machine: only the first slot takes items
        if (spec == null && slot != 0) return stack;
        return super.insertItem(slot, stack, simulate);
    }

    public boolean canFinish(ItemStack result) {
        int in = inputSlot(), out = outputSlot();
        if (in < 0 || out < 0) return false;
        ItemStack output = getStackInSlot(out);
        return !getStackInSlot(in).isEmpty() && !result.isEmpty()
                && (output.isEmpty() || ItemStack.isSameItemSameComponents(output, result))
                && output.getCount() + result.getCount() <= Math.min(getSlotLimit(out), result.getMaxStackSize());
    }
    public boolean finish(ItemStack result) {
        if (!canFinish(result)) return false;
        int in = inputSlot(), out = outputSlot();
        ItemStack output = getStackInSlot(out).copy();
        if (output.isEmpty()) output = result.copy(); else output.grow(result.getCount());
        extractItem(in, 1, false); setStackInSlot(out, output);
        return true;
    }
}
