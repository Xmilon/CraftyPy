package net.xmilon.craftpy.menu;

import java.util.Map;

import com.google.gson.JsonPrimitive;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.xmilon.craftpy.content.KineticInventory;
import net.xmilon.craftpy.content.MachineAccess;

/** A machine block as the source of its window. */
public final class MachineSource implements MenuSource {
    private final MachineAccess machine;
    private final BlockPos pos;

    public MachineSource(MachineAccess machine, BlockPos pos) {
        this.machine = machine;
        this.pos = pos;
    }

    @Override public KineticInventory inventory() { return machine.machineState().inventory; }
    @Override public int energy() { return machine.machineState().energy.getEnergyStored(); }
    @Override public int energyCapacity() { return machine.machineState().energy.getMaxEnergyStored(); }
    @Override public int fluidAmount() { return machine.machineState().fluid.getFluidAmount(); }
    @Override public int fluidCapacity() { return machine.machineState().fluid.getCapacity(); }
    @Override public int fluidId() { return BuiltInRegistries.FLUID.getId(machine.machineState().fluid.getFluid().getFluid()); }
    @Override public int progressPercent() { return machine.machineState().progressPercent(); }
    @Override public Map<String, JsonPrimitive> values() { return machine.machineState().values(); }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(pos) == machine
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64;
    }
}
