package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** A declared loot table as a chance table (47): what drops, how many, and how likely per roll. */
public final class LootPreview {
    public record Entry(String item, int count, int weight, double percent) {
    }

    public record View(String title, int rolls, List<Entry> entries) {
    }

    private LootPreview() {
    }

    public static View describe(JsonObject table, String projectId) {
        String id = table.has("id") && table.get("id").isJsonPrimitive() ? table.get("id").getAsString() : "?";
        int rolls = number(table, "rolls", 1);
        List<Entry> raw = new ArrayList<>();
        int total = 0;
        if (table.has("entries") && table.get("entries").isJsonArray()) {
            for (JsonElement element : table.getAsJsonArray("entries")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                int weight = Math.max(1, number(entry, "weight", 1));
                int count = Math.max(1, number(entry, "count", 1));
                boolean self = entry.has("ref") && entry.has("value") && entry.get("value").isJsonPrimitive()
                        && entry.get("value").getAsString().equals("self");
                String item = self ? "the block itself" : RecipePreview.name(entry.get("item"), projectId);
                raw.add(new Entry(item, count, weight, 0));
                total += weight;
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (Entry e : raw) {
            entries.add(new Entry(e.item(), e.count(), e.weight(), total == 0 ? 0 : 100.0 * e.weight() / total));
        }
        return new View("Drops of " + id, rolls, entries);
    }

    private static int number(JsonObject object, String key, int fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isNumber()
                ? object.get(key).getAsInt() : fallback;
    }
}
