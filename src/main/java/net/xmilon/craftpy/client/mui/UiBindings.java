package net.xmilon.craftpy.client.mui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The designer follows the worker's binding schema; preview never calls Python or evaluates expressions. */
final class UiBindings {
    private UiBindings() { }

    static Map<String, String> fields(String type) {
        Map<String, String> out = new LinkedHashMap<>();
        switch (type) {
            case "label", "button" -> out.put("text", "text");
            case "text_input", "text_area" -> { out.put("value", "text"); out.put("hint", "text"); }
            case "checkbox" -> { out.put("label", "text"); out.put("checked", "bool"); }
            case "slider" -> { out.put("value", "number"); out.put("min", "number"); out.put("max", "number"); }
            case "progress" -> { out.put("value", "number"); out.put("max", "number"); }
            case "dropdown" -> { out.put("options", "list"); out.put("selected", "index"); }
            case "list" -> { out.put("items", "list"); out.put("selected", "index"); }
            case "image" -> { out.put("source", "text"); media(out, false); }
            case "video" -> { out.put("source", "text"); out.put("audio", "text"); media(out, true); }
            case "audio" -> { out.put("source", "text"); media(out, true); }
            case "item_icon" -> out.put("item", "text");
            default -> { }
        }
        out.put("visible", "bool"); out.put("enabled", "bool");
        return out;
    }

    /** What a window can set on a picture, clip or sound while it runs; each value is acted on when it changes. */
    private static void media(Map<String, String> out, boolean sound) {
        out.put("playing", "bool");
        out.put("position", "number"); // seconds
        out.put("speed", "number");
        out.put("opacity", "number");
        out.put("restart", "number"); // any new value starts again from the beginning
        if (sound) {
            out.put("volume", "number");
            out.put("muted", "bool");
        }
    }

    static boolean valid(JsonElement value, String expected) {
        if (value == null || value.isJsonNull()) return false;
        if (expected.equals("list")) return value.isJsonArray() && value.getAsJsonArray().size() <= 1024
                && java.util.stream.StreamSupport.stream(value.getAsJsonArray().spliterator(), false).allMatch(v -> valid(v, "text"));
        if (!value.isJsonPrimitive()) return false;
        var p = value.getAsJsonPrimitive();
        return switch (expected) {
            case "text" -> p.isString() && p.getAsString().length() <= 8192;
            case "bool" -> p.isBoolean();
            case "number" -> p.isNumber() && Double.isFinite(p.getAsDouble()) && Math.abs(p.getAsDouble()) <= 1e9;
            case "index" -> p.isNumber() && p.getAsBigDecimal().scale() <= 0 && p.getAsDouble() >= -1 && p.getAsDouble() <= 1000000;
            default -> false;
        };
    }

    /** Shallow copy retains original child identities for designer selection and drag operations. */
    static JsonObject node(JsonObject node, JsonObject data, List<String> problems) {
        JsonObject out = new JsonObject(); node.entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
        if (!node.has("bind")) return out;
        if (!node.get("bind").isJsonObject()) { problems.add("bind must be an object"); return out; }
        Map<String, String> fields = fields(WindowFragment.str(node, "type", ""));
        for (var binding : node.getAsJsonObject("bind").entrySet()) {
            String prop = binding.getKey(); JsonElement raw = binding.getValue();
            String where = WindowFragment.str(node, "id", WindowFragment.str(node, "type", "widget")) + ".bind." + prop;
            if (!fields.containsKey(prop)) { problems.add(where + ": not a bindable property"); continue; }
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString() || raw.getAsString().isEmpty() || raw.getAsString().length() > 128) {
                problems.add(where + ": choose a data dictionary key (1-128 characters)"); continue;
            }
            String key = raw.getAsString();
            if (!data.has(key)) { problems.add(where + ": missing sample key '" + key + "' (showing static value)"); continue; }
            if (!valid(data.get(key), fields.get(prop))) { problems.add(where + ": '" + key + "' requires " + fields.get(prop)); continue; }
            out.add(prop, data.get(key).deepCopy());
        }
        return out;
    }
}
