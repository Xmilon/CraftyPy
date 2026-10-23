package net.xmilon.craftpy.menu;

import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.xmilon.craftpy.content.KineticInventory;
import net.xmilon.craftpy.content.MachineAccess;
import net.xmilon.craftpy.lifecycle.EventBridge;
import net.xmilon.craftpy.registry.KineticSlots;
import net.xmilon.craftpy.registry.SlotRegistry;

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
    @Override public int tankCount() { return machine.machineState().tanks.count(); }
    @Override public int fluidAmount(int tank) { return machine.machineState().tanks.tank(tank).getFluidAmount(); }
    @Override public int fluidCapacity(int tank) { return machine.machineState().tanks.tank(tank).getCapacity(); }
    @Override public int fluidId(int tank) { return BuiltInRegistries.FLUID.getId(machine.machineState().tanks.tank(tank).getFluid().getFluid()); }
    @Override public int progressPercent() { return machine.machineState().progressPercent(); }
    @Override public Map<String, JsonPrimitive> values() { return machine.machineState().values(); }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(pos) == machine
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64;
    }

    /** The content id is {@code project:id}; a module's Python runs as {@code module:project}. */
    @Override
    public String owner() {
        String content = EventBridge.projectId(SlotRegistry.holderFor(SlotRegistry.Kind.KINETIC,
                KineticSlots.index(((BlockEntity) machine).getBlockState().getBlock())));
        int colon = content == null ? -1 : content.indexOf(':');
        if (colon < 0) return "";
        String project = content.substring(0, colon);
        return net.xmilon.craftpy.project.ModuleLoader.isLoaded(project) ? "module:" + project : project;
    }

    @Override
    public void describe(JsonObject context) {
        BlockEntity entity = (BlockEntity) machine;
        context.add("block", EventBridge.wrapBlock(entity.getBlockState()));
        EventBridge.addPosition(context, pos, entity.getLevel());
    }
}
