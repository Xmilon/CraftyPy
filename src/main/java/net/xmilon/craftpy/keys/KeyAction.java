package net.xmilon.craftpy.keys;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.xmilon.craftpy.client.KeyCombo;

/** Descriptions only: no Python code or callable crosses the network. */
public record KeyAction(String id, String name, String category, String combo) {
    public KeyAction {
        if (id == null || !id.matches("[a-zA-Z0-9_.:+-]{1,128}")) throw new IllegalArgumentException("Invalid key action ID");
        if (name == null || name.isBlank() || name.length() > 128) throw new IllegalArgumentException("Invalid key action name");
        if (category == null || category.isBlank() || category.length() > 80) throw new IllegalArgumentException("Invalid key category");
        if (name.chars().anyMatch(Character::isISOControl) || category.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Key names and categories must be single-line text");
        combo = KeyCombo.normalize(combo);
    }

    public JsonObject json() {
        JsonObject out = new JsonObject();
        out.addProperty("id", id); out.addProperty("name", name); out.addProperty("category", category); out.addProperty("combo", combo);
        return out;
    }

    public static List<KeyAction> parse(JsonArray array) {
        if (array.size() > 32) throw new IllegalArgumentException("At most 32 key actions per owner");
        List<KeyAction> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : array) {
            JsonObject o = element.getAsJsonObject();
            KeyAction action = new KeyAction(o.get("id").getAsString(), o.get("name").getAsString(),
                    o.get("category").getAsString(), o.get("combo").getAsString());
            if (!ids.add(action.id())) throw new IllegalArgumentException("Repeated key action ID: " + action.id());
            out.add(action);
        }
        return List.copyOf(out);
    }
}
