package net.xmilon.craftpy.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.project.PyForgePaths;

/**
 * {@code storage} of client-side Python: values kept in {@code pyforge/system/client_storage.json} on this computer
 * (not in the world, so they follow the player to every server). At most {@link #LIMIT} characters in total.
 */
final class ClientStorage {
    static final int LIMIT = 1_000_000;
    private static Map<String, JsonElement> values;

    private ClientStorage() {
    }

    private static Path file() {
        return PyForgePaths.system().resolve("client_storage.json");
    }

    private static synchronized Map<String, JsonElement> values() {
        if (values == null) {
            values = new TreeMap<>();
            try {
                if (Files.isRegularFile(file())) {
                    JsonElement all = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8));
                    if (all.isJsonObject()) {
                        all.getAsJsonObject().entrySet().forEach(e -> values.put(e.getKey(), e.getValue()));
                    }
                }
            } catch (IOException | RuntimeException broken) {
                // kept aside, so the next save cannot overwrite what may still be rescued from it
                Path aside = file().resolveSibling("client_storage.json.unreadable-" + System.currentTimeMillis());
                try {
                    Files.copy(file(), aside);
                } catch (IOException copy) {
                    aside = null;
                }
                CraftPyLog.LOGGER.error("client_storage.json could not be read; starting empty{}: {}",
                        aside == null ? "" : " (the file was kept as " + aside.getFileName() + ")", broken.toString());
            }
        }
        return values;
    }

    static synchronized JsonObject get(String key) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        JsonElement value = values().get(key);
        if (value != null) {
            out.add("value", value);
        }
        return out;
    }

    static synchronized JsonObject set(String key, JsonElement value) {
        Map<String, JsonElement> all = values();
        JsonElement previous = all.put(key, value == null ? new JsonObject() : value);
        if (size() > LIMIT) {
            if (previous == null) {
                all.remove(key);
            } else {
                all.put(key, previous);
            }
            return ClientHost.fail("storage is full (1 MB on this computer)");
        }
        save();
        return ClientHost.ok();
    }

    /** One key, or every key that starts with {@code scopePrefix} when {@code key} is null. */
    static synchronized JsonObject clear(String key, String scopePrefix) {
        if (key != null) {
            values().remove(key);
        } else {
            values().keySet().removeIf(k -> k.startsWith(scopePrefix));
        }
        save();
        return ClientHost.ok();
    }

    private static int size() {
        int total = 0;
        for (var entry : values().entrySet()) {
            total += entry.getKey().length() + entry.getValue().toString().length();
        }
        return total;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            JsonObject all = new JsonObject();
            values().forEach(all::add);
            byte[] bytes = all.toString().getBytes(StandardCharsets.UTF_8);
            // written next to it and moved over it: a crash while saving keeps the previous file whole
            net.neoforged.neoforge.common.IOUtilities.atomicWrite(file(), out -> out.write(bytes));
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("client_storage.json could not be saved: {}", e.toString());
        }
    }

    /** For tests: forget the cached values so the next call reads the file again. */
    static synchronized void forget() {
        values = null;
    }
}
