package net.xmilon.craftpy.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.xmilon.craftpy.content.MachineAccess;
import net.xmilon.craftpy.registry.ModMenus;

/**
 * The window of a machine block that declared {@code Screen(...)}: its two item slots (input, output), your inventory, and the
 * numbers the screen draws (energy, fluid). The server owns the contents; the numbers reach the client through data slots,
 * which carry 16 bits each, so every value is split in two.
 */
public class MachineMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176, HEIGHT = 166;
    public static final int INPUT_X = 26, INPUT_Y = 20, OUTPUT_X = 26, OUTPUT_Y = 56;

    private final BlockPos pos;
    private final MachineAccess machine;
    public final String title;
    public final boolean showEnergy, showFluid, showSlots;
    private final int[] values = new int[6]; // energy, energy capacity, fluid amount, fluid capacity, fluid id, progress

    /** Client side: the block position and the flags come in the open packet; the contents arrive in slot updates. */
    public MachineMenu(int containerId, Inventory inventory, FriendlyByteBuf data) {
        this(containerId, inventory, data.readBlockPos(), null, data.readUtf(64), data.readBoolean(), data.readBoolean(),
                data.readBoolean());
    }

    public MachineMenu(int containerId, Inventory inventory, BlockPos pos, MachineAccess machine, String title,
                       boolean energy, boolean fluid, boolean slots) {
        super(ModMenus.MACHINE.get(), containerId);
        this.pos = pos;
        this.machine = machine;
        this.title = title;
        this.showEnergy = energy;
        this.showFluid = fluid;
        this.showSlots = slots;
        IItemHandler items = machine != null ? machine.machineState().inventory : new ItemStackHandler(2);
        addSlot(new SlotItemHandler(items, 0, INPUT_X, INPUT_Y));
        addSlot(new SlotItemHandler(items, 1, OUTPUT_X, OUTPUT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 84 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 142));
        }
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            // two data slots per value (low and high 16 bits)
            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return machine == null ? values[index] & 0xFFFF : fresh(index) & 0xFFFF;
                }

                @Override
                public void set(int value) {
                    values[index] = values[index] & 0xFFFF0000 | value & 0xFFFF;
                }
            });
            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return machine == null ? values[index] >>> 16 : fresh(index) >>> 16;
                }

                @Override
                public void set(int value) {
                    values[index] = values[index] & 0xFFFF | value << 16;
                }
            });
        }
    }

    private int fresh(int index) {
        var state = machine.machineState();
        return switch (index) {
            case 0 -> state.energy.getEnergyStored();
            case 1 -> state.energy.getMaxEnergyStored();
            case 2 -> state.fluid.getFluidAmount();
            case 3 -> state.fluid.getCapacity();
            case 4 -> BuiltInRegistries.FLUID.getId(state.fluid.getFluid().getFluid());
            default -> 0;
        };
    }

    public int energy() {
        return values[0];
    }

    public int energyCapacity() {
        return values[1];
    }

    public int fluidAmount() {
        return values[2];
    }

    public int fluidCapacity() {
        return values[3];
    }

    public net.minecraft.world.level.material.Fluid fluid() {
        return BuiltInRegistries.FLUID.byId(values[4]);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        boolean moved;
        if (index < 2) {
            moved = moveItemStackTo(stack, 2, slots.size(), true);
        } else {
            moved = moveItemStackTo(stack, 0, 1, false);
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        if (machine == null) {
            return true;
        }
        return player.level().getBlockEntity(pos) == machine && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64;
    }
}
