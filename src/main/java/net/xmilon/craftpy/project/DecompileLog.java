package net.xmilon.craftpy.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.reflect.TypeToken;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.util.Json;

/**
 * Which modules were decompiled, into which project, and what their .cpm was then
 * ({@code pyforge/system/decompiled.json}). The Modules page compares that hash with the file installed now, so a module
 * rebuilt from its decompiled project (or replaced in any other way) shows as changed.
 */
public final class DecompileLog {
    public record Entry(String sha256, String project, long time) {
    }

    private static Map<String, Entry> entries;

    private DecompileLog() {
    }

    private static Path file() {
        return PyForgePaths.system().resolve("decompiled.json");
    }

    private static Map<String, Entry> entries() {
        if (entries == null) {
            entries = new TreeMap<>();
            try {
                if (Files.isRegularFile(file())) {
                    Map<String, Entry> read = Json.COMPACT.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
                            new TypeToken<Map<String, Entry>>() { }.getType());
                    if (read != null) {
                        entries.putAll(read);
                    }
                }
            } catch (IOException | RuntimeException broken) {
                CraftPyLog.LOGGER.warn("decompiled.json could not be read: {}", broken.toString());
            }
        }
        return entries;
    }

    /** {@code moduleId} was decompiled into {@code projectId} while its file had {@code sha256}; replaces an older entry. */
    public static synchronized void record(String moduleId, String sha256, String projectId) {
        entries().put(moduleId, new Entry(sha256 == null ? "" : sha256, projectId, System.currentTimeMillis()));
        save();
    }

    public static synchronized void forget(String moduleId) {
        if (entries().remove(moduleId) != null) {
            save();
        }
    }

    /** The entry of a module, or null when it was never decompiled. */
    public static synchronized Entry of(String moduleId) {
        return entries().get(moduleId);
    }

    /** True when the module was decompiled and its file is not the one it was decompiled from any more. */
    public static synchronized boolean changed(String moduleId, String currentSha256) {
        Entry entry = entries().get(moduleId);
        return entry != null && currentSha256 != null && !entry.sha256().isEmpty() && !entry.sha256().equals(currentSha256);
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), Json.GSON.toJson(entries), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("decompiled.json could not be saved: {}", e.toString());
        }
    }

    /** For tests: read the file again next time. */
    static synchronized void reset() {
        entries = null;
    }
}
