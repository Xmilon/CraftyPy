package net.xmilon.craftpy.compat.create;

import java.util.List;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.xmilon.craftpy.content.*;
import net.xmilon.craftpy.registry.KineticSlots;

public final class KineticMachine extends GeneratingKineticBlockEntity implements MachineAccess {
    private final MachineState state = new MachineState(this);
    public final KineticInventory inventory = state.inventory;
    private KineticDef applied;
    public KineticMachine(BlockPos pos, BlockState state) { super(KineticSlots.TYPE.get(), pos, state); }
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) { }
    private KineticDef definition() { return state.rotation(); }
    boolean processing() { return state.processing(); }
    @Override public MachineState machineState() { return state; }
    @Override public float machineSpeed() { return getSpeed(); }
    @Override public float theoreticalSpeed() { return getTheoreticalSpeed(); }
    @Override public String rotationState() { return isOverStressed() ? "overstressed" : "ready"; }
    @Override public void syncMachine() { sendData(); }
    @Override public float getGeneratedSpeed() { var def = definition(); return def == null ? 0 : def.speed(); }
    @Override public float calculateAddedStressCapacity() { var def = definition(); return lastCapacityProvided = def == null ? 0 : def.capacity(); }
    @Override public float calculateStressApplied() { var def = definition(); return lastStressApplied = def == null ? 0 : def.impact(); }
    @Override public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;
        var def = definition();
        if (!java.util.Objects.equals(def, applied)) {
            applied = def; updateGeneratedRotation();
            if (hasNetwork()) { getOrCreateNetwork().updateStressFor(this, calculateStressApplied()); getOrCreateNetwork().updateStress(); }
        }
        state.tick();
    }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) { super.write(tag, registries, clientPacket); state.save(tag, registries); }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) { super.read(tag, registries, clientPacket); state.load(tag, registries); }
}
