package net.xmilon.craftpy.lifecycle;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.xmilon.craftpy.util.NbtJson;

public class GlobalState extends SavedData {
    private CompoundTag data = new CompoundTag();

    public static GlobalState get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(GlobalState::new, GlobalState::load, null), "craftpy_global_state"
        );
    }

    private GlobalState() {}

    private GlobalState(CompoundTag tag) {
        this.data = tag.getCompound("data");
    }

    public static GlobalState load(CompoundTag tag, HolderLookup.Provider registries) {
        return new GlobalState(tag);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("data", data);
        return tag;
    }

    public CompoundTag getData() {
        return data;
    }

    public void setData(CompoundTag data) {
        this.data = data;
        setDirty();
    }
}
