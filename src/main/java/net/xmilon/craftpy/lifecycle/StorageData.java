package net.xmilon.craftpy.lifecycle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.xmilon.craftpy.util.Json;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The values of Python's {@code storage}, saved with the world ({@code data/craftpy_storage.dat}). The map lives here
 * so the worker threads read and write it without waiting for the server thread; a change only marks the world data
 * dirty, and Minecraft writes it on its next save (autosave, /save-all, quitting).
 */
public final class StorageData extends SavedData {
    public static final Map<String, JsonElement> VALUES = new ConcurrentHashMap<>();
    private static volatile StorageData current;
    private static volatile MinecraftServer server;

    private StorageData() {
    }

    /** Called when a world opens: drops the values of the previous world (singleplayer) and loads this one's. */
    public static void open(MinecraftServer opened) {
        VALUES.clear();
        server = opened;
        current = opened.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(StorageData::new, StorageData::load, null), "craftpy_storage");
    }

    /** Marks the values for saving; called after every change. */
    public static void changed() {
        MinecraftServer running = server;
        StorageData data = current;
        if (running != null && data != null) {
            running.execute(data::setDirty); // the save that clears the flag runs on the server thread too
        }
    }

    static StorageData load(CompoundTag tag, HolderLookup.Provider registries) {
        StorageData data = new StorageData();
        read(tag, VALUES);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        write(VALUES, tag);
        return tag;
    }

    /** One byte array of UTF-8 JSON: a string tag stops at 65535 bytes. */
    static void write(Map<String, JsonElement> values, CompoundTag tag) {
        JsonObject all = new JsonObject();
        values.forEach(all::add);
        tag.putByteArray("json", Json.COMPACT.toJson(all).getBytes(StandardCharsets.UTF_8));
    }

    static void read(CompoundTag tag, Map<String, JsonElement> into) {
        byte[] bytes = tag.getByteArray("json");
        if (bytes.length == 0) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                parsed.getAsJsonObject().entrySet().forEach(entry -> into.put(entry.getKey(), entry.getValue()));
            }
        } catch (RuntimeException broken) {
            net.xmilon.craftpy.CraftPyLog.LOGGER.error("craftpy_storage.dat could not be read; storage starts empty", broken);
        }
    }
}
