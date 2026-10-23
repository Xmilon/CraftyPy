package net.xmilon.craftpy.content;

import java.util.function.Predicate;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/**
 * The liquid tanks of a machine block or a window: up to {@link #MAX} NeoForge tanks behind one fluid handler, so pipes,
 * buckets and other mods see one storage with several tanks. Liquid goes into the tank that already holds it, else into the
 * first empty tank that takes it; draining takes from the first tank that has some. A tank never loses what it holds when the
 * declaration shrinks: it only takes no more.
 */
public final class Tanks implements IFluidHandler {
    public static final int MAX = 4;

    private final FluidTank[] tanks = new FluidTank[MAX];
    private final int[] limits = new int[MAX];
    private int count;

    public Tanks(Predicate<FluidStack> allowed, Runnable changed) {
        for (int i = 0; i < MAX; i++) {
            final int index = i;
            tanks[i] = new FluidTank(0, allowed) {
                @Override
                protected void onContentsChanged() {
                    setCapacity(Math.max(getFluidAmount(), limits[index]));
                    changed.run();
                }

                @Override
                public int fill(FluidStack resource, FluidAction action) {
                    if (index >= count || resource.isEmpty()) return 0;
                    return super.fill(resource.copyWithAmount(Math.min(resource.getAmount(), Math.max(0, limits[index] - getFluidAmount()))), action);
                }
            };
        }
    }

    /** {@code count} tanks of {@code capacity} mB each (0 and 0 when the declaration has none). */
    public void configure(int count, int capacity) {
        this.count = Math.max(0, Math.min(MAX, count));
        for (int i = 0; i < MAX; i++) {
            limits[i] = i < this.count ? Math.max(0, capacity) : 0;
            tanks[i].setCapacity(Math.max(tanks[i].getFluidAmount(), limits[i]));
        }
    }

    /** Declared tanks, or more while an undeclared one still holds liquid. */
    public int count() {
        int shown = count;
        for (int i = count; i < MAX; i++) if (!tanks[i].isEmpty()) shown = i + 1;
        return shown;
    }

    public FluidTank tank(int index) {
        return tanks[Math.max(0, Math.min(MAX - 1, index))];
    }

    public boolean isEmpty() {
        for (FluidTank tank : tanks) if (!tank.isEmpty()) return false;
        return true;
    }

    @Override
    public int getTanks() {
        return Math.max(1, count());
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return tank(tank).getFluid();
    }

    @Override
    public int getTankCapacity(int tank) {
        return tank(tank).getCapacity();
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return tank < count && tank(tank).isFluidValid(stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) return 0;
        // the tank that holds this liquid first, so two tanks never split one liquid
        for (int i = 0; i < count; i++) {
            if (!tanks[i].isEmpty() && FluidStack.isSameFluidSameComponents(tanks[i].getFluid(), resource)) {
                return tanks[i].fill(resource, action);
            }
        }
        for (int i = 0; i < count; i++) {
            if (tanks[i].isEmpty()) {
                int filled = tanks[i].fill(resource, action);
                if (filled > 0) return filled;
            }
        }
        return 0;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        for (FluidTank tank : tanks) {
            if (!tank.isEmpty() && FluidStack.isSameFluidSameComponents(tank.getFluid(), resource)) {
                return tank.drain(resource, action);
            }
        }
        return FluidStack.EMPTY;
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        for (FluidTank tank : tanks) {
            if (!tank.isEmpty()) return tank.drain(maxDrain, action);
        }
        return FluidStack.EMPTY;
    }

    /** Tank 0 under {@code first} (where a single tank was always saved), the others as a list under {@code rest}. */
    public void save(CompoundTag tag, String first, String rest, HolderLookup.Provider registries) {
        tag.put(first, tanks[0].writeToNBT(registries, new CompoundTag()));
        ListTag others = new ListTag();
        for (int i = 1; i < MAX; i++) others.add(tanks[i].writeToNBT(registries, new CompoundTag()));
        tag.put(rest, others);
    }

    public void load(CompoundTag tag, String first, String rest, HolderLookup.Provider registries) {
        tanks[0].readFromNBT(registries, tag.getCompound(first));
        ListTag others = tag.getList(rest, Tag.TAG_COMPOUND);
        for (int i = 1; i < MAX; i++) {
            tanks[i].readFromNBT(registries, i - 1 < others.size() ? others.getCompound(i - 1) : new CompoundTag());
        }
        for (int i = 0; i < MAX; i++) tanks[i].setCapacity(Math.max(tanks[i].getFluidAmount(), limits[i]));
    }
}
