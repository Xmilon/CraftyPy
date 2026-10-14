package net.xmilon.craftpy.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.xmilon.craftpy.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Client-side settings persisted to {@code pyforge/settings.json}.
 */
public final class ClientSettings {
    private static final Path SETTINGS_FILE = net.xmilon.craftpy.project.PyForgePaths.root().resolve("settings.json");

    // Settings keys
    public static final String AUTO_COMPLETION_ENABLED = "editor.autoCompletion.enabled";
    public static final String AUTO_COMPLETION_HOVER_DETAILS = "editor.autoCompletion.hoverDetails";
    public static final String AUTO_COMPLETION_SMART_FILL = "editor.autoCompletion.smartFill";
    public static final String AUTO_COMPLETION_SHOW_SNIPPETS = "editor.autoCompletion.showSnippets";
    public static final String THEME = "editor.theme";
    public static final String FONT_SIZE = "editor.fontSize";
    public static final String LINE_HEIGHT = "editor.lineHeight";
    public static final String SHOW_LINE_NUMBERS = "editor.showLineNumbers";
    public static final String WORD_WRAP = "editor.wordWrap";
    public static final String MINIMAP_ENABLED = "editor.minimap.enabled";

    // Default values
    private static final Map<String, Object> DEFAULTS = Map.ofEntries(
            Map.entry(AUTO_COMPLETION_ENABLED, true),
            Map.entry(AUTO_COMPLETION_HOVER_DETAILS, true),
            Map.entry(AUTO_COMPLETION_SMART_FILL, true),
            Map.entry(AUTO_COMPLETION_SHOW_SNIPPETS, true),
            Map.entry(THEME, "dark"),
            Map.entry(FONT_SIZE, 11),
            Map.entry(LINE_HEIGHT, 11),
            Map.entry(SHOW_LINE_NUMBERS, true),
            Map.entry(WORD_WRAP, false),
            Map.entry(MINIMAP_ENABLED, false)
    );

    private static final Map<String, Object> VALUES = new HashMap<>();

    static {
        load();
    }

    private ClientSettings() {
    }

    private static void load() {
        VALUES.clear();
        VALUES.putAll(DEFAULTS);
        if (Files.isRegularFile(SETTINGS_FILE)) {
            try {
                JsonElement element = Json.readTree(SETTINGS_FILE);
                if (element != null && element.isJsonObject()) {
                    JsonObject obj = element.getAsJsonObject();
                    for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                        VALUES.put(entry.getKey(), jsonToValue(entry.getValue()));
                    }
                }
            } catch (IOException e) {
                // Ignore, use defaults
            }
        }
    }

    private static Object jsonToValue(JsonElement element) {
        if (element.isJsonPrimitive()) {
            var prim = element.getAsJsonPrimitive();
            if (prim.isBoolean()) return prim.getAsBoolean();
            if (prim.isNumber()) return prim.getAsNumber();
            if (prim.isString()) return prim.getAsString();
        }
        return element.toString();
    }

    private static JsonElement valueToJson(Object value) {
        if (value instanceof Boolean) return new com.google.gson.JsonPrimitive((Boolean) value);
        if (value instanceof Number) return new com.google.gson.JsonPrimitive((Number) value);
        if (value instanceof String) return new com.google.gson.JsonPrimitive((String) value);
        return new com.google.gson.JsonPrimitive(value.toString());
    }

    public static void save() {
        try {
            Files.createDirectories(SETTINGS_FILE.getParent());
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, Object> entry : VALUES.entrySet()) {
                obj.add(entry.getKey(), valueToJson(entry.getValue()));
            }
            Json.writeString(SETTINGS_FILE, Json.COMPACT.toJson(obj));
        } catch (IOException e) {
            // Ignore
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        Object val = VALUES.get(key);
        return val instanceof Boolean ? (Boolean) val : defaultValue;
    }

    public static int getInt(String key, int defaultValue) {
        Object val = VALUES.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    public static String getString(String key, String defaultValue) {
        Object val = VALUES.get(key);
        return val instanceof String ? (String) val : defaultValue;
    }

    public static void set(String key, Object value) {
        VALUES.put(key, value);
        save();
    }

    public static void setBoolean(String key, boolean value) {
        set(key, value);
    }

    public static void setInt(String key, int value) {
        set(key, value);
    }

    public static void setString(String key, String value) {
        set(key, value);
    }

    /** Returns all settings as a map for UI rendering. */
    public static Map<String, Object> getAll() {
        return new HashMap<>(VALUES);
    }
}