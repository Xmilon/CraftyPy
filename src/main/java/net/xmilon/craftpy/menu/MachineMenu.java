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
 * The window of a machine block that declared {@code Screen(...)}. The {@link ScreenLayout} says where the slots are (the
 * machine's input and output, the player inventory); the server owns the contents. The numbers the screen draws (energy,
 * liquid, progress) reach the client through data slots, which carry 16 bits each, so every value is split in two.
 */
public class MachineMenu extends AbstractContainerMenu {
    private final BlockPos pos;
    private final MachineAccess machine;
    public final String title;
    public final ScreenLayout layout;
    private final int machineSlots;
    private final int[] values = new int[6]; // energy, energy capacity, fluid amount, fluid capacity, fluid id, progress %

    /** Client side: the block position, the title and the layout come in the open packet; contents arrive in slot updates. */
    public MachineMenu(int containerId, Inventory inventory, FriendlyByteBuf data) {
        this(containerId, inventory, data.readBlockPos(), null, data.readUtf(64), parse(data.readUtf(ScreenLayout.MAX_JSON + 100)));
    }

    private static ScreenLayout parse(String json) {
        try {
            return ScreenLayout.parse(com.google.gson.JsonParser.parseString(json).getAsJsonObject(),
                    ScreenLayout.defaults(true, true, true));
        } catch (RuntimeException bad) {
            return ScreenLayout.defaults(true, true, true);
        }
    }

    public MachineMenu(int containerId, Inventory inventory, BlockPos pos, MachineAccess machine, String title, ScreenLayout layout) {
        super(ModMenus.MACHINE.get(), containerId);
        this.pos = pos;
        this.machine = machine;
        this.title = title;
        this.layout = layout;
        IItemHandler items = machine != null ? machine.machineState().inventory : new ItemStackHandler(2);
        boolean[] added = new boolean[2];
        for (ScreenLayout.Piece piece : layout.pieces) {
            if (piece.type().equals("lg_slot")) {
                int index = piece.slot().equals("output") ? 1 : 0;
                if (!added[index]) {
                    added[index] = true;
                    addSlot(index == 1 ? new SlotItemHandler(items, 1, piece.x(), piece.y()) {
                        @Override
                        public boolean mayPlace(ItemStack stack) {
                            return false;
                        }
                    } : new SlotItemHandler(items, 0, piece.x(), piece.y()));
                }
            }
        }
        machineSlots = slots.size();
        for (ScreenLayout.Piece piece : layout.pieces) {
            if (piece.type().equals("lg_player")) {
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 9; column++) {
                        addSlot(new Slot(inventory, column + row * 9 + 9, piece.x() + column * 18, piece.y() + row * 18));
                    }
                }
                for (int column = 0; column < 9; column++) {
                    addSlot(new Slot(inventory, column, piece.x() + column * 18, piece.y() + 58));
                }
                break; // one player inventory
            }
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
            default -> state.progressPercent();
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

    /** 0..100: how far the machine's current recipe is. */
    public int progress() {
        return Math.max(0, Math.min(100, values[5]));
    }

    /** How full the thing a piece is bound to is, 0..1. */
    public double fraction(String bind) {
        return switch (bind) {
            case "energy" -> energyCapacity() <= 0 ? 0 : (double) energy() / energyCapacity();
            case "fluid" -> fluidCapacity() <= 0 ? 0 : (double) fluidAmount() / fluidCapacity();
            case "progress" -> progress() / 100.0;
            default -> 0;
        };
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
        if (index < machineSlots) {
            moved = moveItemStackTo(stack, machineSlots, slots.size(), true);
        } else {
            moved = machineSlots > 0 && moveItemStackTo(stack, 0, 1, false);
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
