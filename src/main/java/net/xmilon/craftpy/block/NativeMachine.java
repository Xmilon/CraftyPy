package net.xmilon.craftpy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.xmilon.craftpy.content.MachineAccess;
import net.xmilon.craftpy.content.MachineState;
import net.xmilon.craftpy.content.RotationNetworks;
import net.xmilon.craftpy.registry.KineticSlots;

public final class NativeMachine extends BlockEntity implements MachineAccess {
    private final MachineState state = new MachineState(this);
    private Object applied;
    public float speed;
    public String rotation = "rebuilding";
    public long solved = -1;
    public NativeMachine(BlockPos pos, BlockState state) { super(KineticSlots.TYPE.get(), pos, state); }
    public void tick() {
        if (level == null || level.isClientSide) return;
        var def = state.definition();
        if (def != applied) { applied = def; RotationNetworks.changed(level); }
        state.tick();
    }
    @Override public MachineState machineState() { return state; }
    @Override public float machineSpeed() { return rotationState().equals("ready") ? speed : 0; }
    @Override public float theoreticalSpeed() { return speed; }
    @Override public String rotationState() { return level != null && !level.isClientSide && solved != RotationNetworks.version(level) ? "rebuilding" : rotation; }
    @Override public void syncMachine() { if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
    @Override public void onLoad() { super.onLoad(); if (!level.isClientSide) RotationNetworks.add(this); }
    @Override public void setRemoved() { if (level != null && !level.isClientSide) RotationNetworks.remove(this); super.setRemoved(); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) { super.saveAdditional(tag, registries); state.save(tag, registries); tag.putFloat("CraftPySpeed", machineSpeed()); tag.putString("CraftPyRotation", rotationState()); }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries); state.load(tag, registries);
        float savedSpeed = tag.getFloat("CraftPySpeed"); speed = Float.isFinite(savedSpeed) ? savedSpeed : 0;
        String savedState = tag.getString("CraftPyRotation");
        rotation = java.util.Set.of("ready", "rebuilding", "overstressed", "unpowered", "conflict", "network_limit").contains(savedState) ? savedState : "rebuilding";
        solved = -1;
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
