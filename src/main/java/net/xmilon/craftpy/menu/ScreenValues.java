package net.xmilon.craftpy.menu;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The free values Python sets on a machine or a window ({@code world.machine_set}, {@code ui.screen_set}): at most 32 keys, a
 * key is a lowercase word, a value is a finite number or a text of at most 256 characters. Everything that comes from Python goes
 * through {@link #clean}.
 */
public final class ScreenValues {
    public static final int MAX_KEYS = 32, MAX_TEXT = 256;

    private ScreenValues() {
    }

    public static boolean validKey(String key) {
        return key != null && key.matches("[a-z][a-z0-9_]{0,24}");
    }

    /** Merges {@code changes} into {@code target}; false (and nothing changed) when a key or value is not allowed. */
    public static boolean merge(Map<String, JsonPrimitive> target, JsonObject changes) {
        Map<String, JsonPrimitive> next = new LinkedHashMap<>(target);
        for (Map.Entry<String, JsonElement> entry : changes.entrySet()) {
            JsonPrimitive value = clean(entry.getValue());
            if (!validKey(entry.getKey()) || value == null) {
                return false;
            }
            next.put(entry.getKey(), value);
        }
        if (next.size() > MAX_KEYS) {
            return false;
        }
        target.clear();
        target.putAll(next);
        return true;
    }

    /** A finite number, or a text cut to {@link #MAX_TEXT}; null for anything else. */
    public static JsonPrimitive clean(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            double number = primitive.getAsDouble();
            return Double.isFinite(number) ? primitive : null;
        }
        if (primitive.isString()) {
            String text = primitive.getAsString();
            return new JsonPrimitive(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
        }
        return primitive.isBoolean() ? new JsonPrimitive(primitive.getAsBoolean() ? 1 : 0) : null;
    }

    public static JsonObject toJson(Map<String, JsonPrimitive> values) {
        JsonObject out = new JsonObject();
        values.forEach(out::add);
        return out;
    }

    public static double number(JsonPrimitive value) {
        if (value == null) return 0;
        if (value.isNumber()) return value.getAsDouble();
        try {
            return Double.parseDouble(value.getAsString());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** How a value reads in a label: whole numbers without decimals, others with one. */
    public static String text(JsonPrimitive value) {
        if (value == null) return "";
        if (value.isNumber()) {
            double number = value.getAsDouble();
            return number == Math.rint(number) && Math.abs(number) < 1e15 ? Long.toString((long) number) : String.format(java.util.Locale.ROOT, "%.1f", number);
        }
        return value.getAsString();
    }
}
