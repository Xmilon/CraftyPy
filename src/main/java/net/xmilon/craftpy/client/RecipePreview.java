package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Turns a declared recipe (the JSON the worker produced) into something drawable (46). */
public final class RecipePreview {
    /**
     * @param grid   3x3 cell labels for shaped recipes, otherwise null
     * @param inputs ingredient names for everything that is not a grid
     */
    public record View(String title, String type, String[][] grid, List<String> inputs, String result, String note) {
    }

    private RecipePreview() {
    }

    public static View describe(JsonObject recipe, String projectId) {
        String type = string(recipe, "type");
        String result = name(recipe.get("result"), projectId);
        int count = recipe.has("count") && recipe.get("count").isJsonPrimitive() ? recipe.get("count").getAsInt() : 1;
        String resultText = count > 1 ? result + " x" + count : result;
        String title = result + " (" + label(type) + ")";
        switch (type) {
            case "shaped" -> {
                JsonObject keys = recipe.has("key") && recipe.get("key").isJsonObject() ? recipe.getAsJsonObject("key")
                        : recipe.has("keys") && recipe.get("keys").isJsonObject() ? recipe.getAsJsonObject("keys")
                        : new JsonObject();
                String[][] grid = new String[3][3];
                for (String[] row : grid) {
                    Arrays.fill(row, "");
                }
                if (recipe.has("pattern") && recipe.get("pattern").isJsonArray()) {
                    int r = 0;
                    for (JsonElement rowElement : recipe.getAsJsonArray("pattern")) {
                        String row = rowElement.getAsString();
                        for (int c = 0; c < row.length() && c < 3 && r < 3; c++) {
                            char ch = row.charAt(c);
                            grid[r][c] = ch == ' ' ? "" : keys.has(String.valueOf(ch))
                                    ? name(keys.get(String.valueOf(ch)), projectId) : "?" + ch;
                        }
                        r++;
                    }
                }
                return new View(title, type, grid, List.of(), resultText, "");
            }
            case "shapeless" -> {
                List<String> inputs = new ArrayList<>();
                if (recipe.has("ingredients") && recipe.get("ingredients").isJsonArray()) {
                    for (JsonElement e : recipe.getAsJsonArray("ingredients")) {
                        inputs.add(name(e, projectId));
                    }
                }
                return new View(title, type, null, inputs, resultText, "Any arrangement in the crafting grid");
            }
            case "smelting", "blasting", "smoking", "campfire" -> {
                double xp = recipe.has("xp") && recipe.get("xp").isJsonPrimitive() ? recipe.get("xp").getAsDouble() : 0;
                int ticks = recipe.has("cook_time") && recipe.get("cook_time").isJsonPrimitive()
                        ? recipe.get("cook_time").getAsInt() : 0;
                return new View(title, type, null, List.of(name(recipe.get("ingredient"), projectId)), resultText,
                        String.format(java.util.Locale.ROOT, "%.1f s, %.2f xp", ticks / 20.0, xp));
            }
            case "stonecutting" -> {
                return new View(title, type, null, List.of(name(recipe.get("ingredient"), projectId)), resultText, "");
            }
            case "smithing" -> {
                return new View(title, type, null, List.of(name(recipe.get("template"), projectId),
                        name(recipe.get("base"), projectId), name(recipe.get("addition"), projectId)), resultText,
                        "Template + base + addition");
            }
            default -> {
                return new View(title, type, null, List.of(), resultText, "Unknown recipe type");
            }
        }
    }

    static String label(String type) {
        return switch (type) {
            case "shaped" -> "shaped crafting";
            case "shapeless" -> "shapeless crafting";
            case "smelting" -> "furnace";
            case "blasting" -> "blast furnace";
            case "smoking" -> "smoker";
            case "campfire" -> "campfire";
            case "stonecutting" -> "stonecutter";
            case "smithing" -> "smithing table";
            default -> type;
        };
    }

    /** "ruby_rush:ruby" -> "ruby" for the project's own content, "minecraft:stick" -> "stick". */
    public static String name(JsonElement element, String projectId) {
        if (element == null || element.isJsonNull()) {
            return "?";
        }
        String id;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            id = object.has("item") ? object.get("item").getAsString()
                    : object.has("id") ? object.get("id").getAsString()
                    : object.has("tag") ? "#" + object.get("tag").getAsString() : "?";
        } else {
            id = element.getAsString();
        }
        return shortId(id, projectId);
    }

    public static String shortId(String id, String projectId) {
        if (id.startsWith("minecraft:")) {
            return id.substring("minecraft:".length());
        }
        if (projectId != null && id.startsWith(projectId + ":")) {
            return id.substring(projectId.length() + 1);
        }
        return id;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
}
