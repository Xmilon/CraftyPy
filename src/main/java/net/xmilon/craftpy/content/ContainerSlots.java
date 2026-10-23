package net.xmilon.craftpy.content;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Slots that move liquid and energy between items and a block or window, through NeoForge's item capabilities, so buckets,
 * tank items and batteries of every mod work: a slot of the group {@code fluid_in} pours a filled container into the tanks,
 * {@code fluid_out} fills an empty one from them, {@code energy_in} drains a charged item into the energy store and
 * {@code energy_out} charges one from it. A slot works on one item at a time (the container stays in its slot, filled or
 * emptied), like the tank slots of Mekanism's machines.
 */
public final class ContainerSlots {
    /** Energy moved per tick and slot. */
    static final int ENERGY_RATE = 10_000;

    private ContainerSlots() {
    }

    /** True when the declaration has a slot of one of these groups (so the work is skipped for every other block). */
    public static boolean any(Defs.InventorySpec spec) {
        if (spec == null) return false;
        for (Defs.InventorySpec.Group group : spec.groups) {
            switch (group.role()) {
                case "fluid_in", "fluid_out", "energy_in", "energy_out" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    /** One tick of every such slot; {@code tanks} or {@code energy} may be null when there are none. True when something moved. */
    public static boolean tick(KineticInventory inventory, IFluidHandler tanks, IEnergyStorage energy) {
        boolean moved = false;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || stack.getCount() != 1) continue;
            switch (inventory.roleOf(slot)) {
                case "fluid_in" -> {
                    if (tanks == null) break;
                    var done = FluidUtil.tryEmptyContainer(stack, tanks, Integer.MAX_VALUE, null, true);
                    if (done.isSuccess()) {
                        inventory.setStackInSlot(slot, done.getResult());
                        moved = true;
                    }
                }
                case "fluid_out" -> {
                    if (tanks == null) break;
                    var done = FluidUtil.tryFillContainer(stack, tanks, Integer.MAX_VALUE, null, true);
                    if (done.isSuccess()) {
                        inventory.setStackInSlot(slot, done.getResult());
                        moved = true;
                    }
                }
                case "energy_in", "energy_out" -> {
                    if (energy == null) break;
                    // a copy carries the item's energy; the slot gets it back with the new amount
                    ItemStack copy = stack.copy();
                    IEnergyStorage item = copy.getCapability(Capabilities.EnergyStorage.ITEM);
                    if (item == null) break;
                    boolean in = inventory.roleOf(slot).equals("energy_in");
                    IEnergyStorage from = in ? item : energy, to = in ? energy : item;
                    int amount = to.receiveEnergy(from.extractEnergy(ENERGY_RATE, true), true);
                    if (amount > 0) {
                        to.receiveEnergy(from.extractEnergy(amount, false), false);
                        inventory.setStackInSlot(slot, copy);
                        moved = true;
                    }
                }
                default -> {
                }
            }
        }
        return moved;
    }
}
