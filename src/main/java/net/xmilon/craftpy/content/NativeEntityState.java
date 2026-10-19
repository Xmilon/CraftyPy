package net.xmilon.craftpy.content;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.entity.Entity;
import net.xmilon.craftpy.util.Json;

/** Per-entity, per-owner state is saved by the entity's normal persistent-data lifecycle. Server thread only. */
public final class NativeEntityState {
    private NativeEntityState() { }
    public static JsonObject get(Entity entity, String owner) {
        return read(entity.getPersistentData(), owner);
    }
    static JsonObject read(net.minecraft.nbt.CompoundTag persistentData, String owner) {
        String text = persistentData.getCompound("craftpy:state").getString(owner);
        return text.isEmpty() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
    }
    public static void put(Entity entity, String owner, JsonObject state) {
        write(entity.getPersistentData(), owner, state);
    }
    static void write(net.minecraft.nbt.CompoundTag persistentData, String owner, JsonObject state) {
        String text = Json.COMPACT.toJson(state);
        if (text.length() > 16384) throw new IllegalArgumentException("Entity state exceeds 16 KiB per owner");
        var tag = persistentData.getCompound("craftpy:state"); tag.putString(owner, text);
        persistentData.put("craftpy:state", tag);
    }
}
