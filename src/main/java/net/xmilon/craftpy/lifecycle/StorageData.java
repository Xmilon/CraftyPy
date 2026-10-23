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
 * dirty, and Minecraft writes it on its next save (autosave, /save-all, quitting). NeoForge writes the file to a temporary
 * file first and moves it over the old one, so a crash never leaves half a file. A file that cannot be read is copied aside
 * ({@code craftpy_storage.dat.unreadable-<time>}) before anything is saved over it.
 */
public final class StorageData extends SavedData {
    public static final Map<String, JsonElement> VALUES = new ConcurrentHashMap<>();
    private static volatile StorageData current;
    private static volatile MinecraftServer server;
    /** Set when the file was there and was read; false after open() means Minecraft could not read it. */
    private static volatile boolean read;

    private StorageData() {
    }

    /** Called when a world opens: drops the values of the previous world (singleplayer) and loads this one's. */
    public static void open(MinecraftServer opened) {
        VALUES.clear();
        server = opened;
        read = false;
        java.nio.file.Path file = file(opened);
        current = opened.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(StorageData::new, StorageData::load, null), "craftpy_storage");
        if (!read && java.nio.file.Files.isRegularFile(file)) {
            keepAside(file, "Minecraft could not read it");
        }
    }

    /** The file of the storage in a world's save folder. */
    public static java.nio.file.Path file(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve("craftpy_storage.dat");
    }

    /** Copies an unreadable storage file next to it, so the next save does not destroy what may still be rescued. */
    private static void keepAside(java.nio.file.Path file, String why) {
        java.nio.file.Path aside = file.resolveSibling(file.getFileName() + ".unreadable-" + System.currentTimeMillis());
        try {
            java.nio.file.Files.copy(file, aside);
            net.xmilon.craftpy.CraftPyLog.LOGGER.error("craftpy_storage.dat: {}; storage starts empty, the file was kept as {}",
                    why, aside.getFileName());
        } catch (java.io.IOException copy) {
            net.xmilon.craftpy.CraftPyLog.LOGGER.error("craftpy_storage.dat: {}, and it could not be copied aside ({})", why, copy.toString());
        }
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
        read = read(tag, VALUES);
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

    /** Fills {@code into} from a saved tag; false when the tag holds something that is not readable storage. */
    static boolean read(CompoundTag tag, Map<String, JsonElement> into) {
        byte[] bytes = tag.getByteArray("json");
        if (bytes.length == 0) {
            return true;
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                parsed.getAsJsonObject().entrySet().forEach(entry -> into.put(entry.getKey(), entry.getValue()));
                return true;
            }
        } catch (RuntimeException broken) {
            net.xmilon.craftpy.CraftPyLog.LOGGER.error("craftpy_storage.dat holds broken JSON", broken);
        }
        return false;
    }
}
