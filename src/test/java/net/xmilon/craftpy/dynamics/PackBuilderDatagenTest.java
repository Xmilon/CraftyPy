package net.xmilon.craftpy.dynamics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.xmilon.craftpy.python.Declarations;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.util.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the recipe/tag variants and the raw/definition datapack writers added
 * for the full Tier 1 declarative surface. Pure JVM: no Minecraft types.
 */
class PackBuilderDatagenTest {

    private static Path tmp() {
        try {
            return Files.createTempDirectory("craftpy_datagen_");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static JsonObject readJson(Path path) throws Exception {
        return Json.GSON.fromJson(Files.readString(path), JsonObject.class);
    }

    private static JsonObject simple(String type, String result, String ingredient) {
        JsonObject raw = new JsonObject();
        raw.addProperty("type", type);
        raw.addProperty("result", result);
        raw.addProperty("ingredient", ingredient);
        return raw;
    }

    @Test
    void cookingRecipesMapToVanillaTypes() throws Exception {
        SlotRegistry.clearAll();
        Path data = tmp().resolve("data").resolve("testproj");
        PackBuilder.writeRecipe(data, "testproj", simple("blasting", "minecraft:gold_ingot", "minecraft:gold_ore"));
        PackBuilder.writeRecipe(data, "testproj", simple("smoking", "minecraft:cooked_beef", "minecraft:beef"));
        PackBuilder.writeRecipe(data, "testproj", simple("campfire", "minecraft:cooked_porkchop", "minecraft:porkchop"));

        assertEquals("minecraft:blasting",
                readJson(data.resolve("recipe/minecraft_gold_ore.json")).get("type").getAsString());
        assertEquals("minecraft:smoking",
                readJson(data.resolve("recipe/minecraft_beef.json")).get("type").getAsString());
        assertEquals("minecraft:campfire_cooking",
                readJson(data.resolve("recipe/minecraft_porkchop.json")).get("type").getAsString());
    }

    @Test
    void stonecuttingAndSmithingProduceCorrectShapes() throws Exception {
        SlotRegistry.clearAll();
        Path data = tmp().resolve("data").resolve("testproj");

        JsonObject stone = simple("stonecutting", "minecraft:stone_slab", "minecraft:stone");
        stone.addProperty("id", "test/stone_slab");
        stone.addProperty("count", 2);
        PackBuilder.writeRecipe(data, "testproj", stone);
        JsonObject stoneOut = readJson(data.resolve("recipe/test/stone_slab.json"));
        assertEquals("minecraft:stonecutting", stoneOut.get("type").getAsString());
        assertEquals(2, stoneOut.getAsJsonObject("result").get("count").getAsInt());

        JsonObject smith = new JsonObject();
        smith.addProperty("type", "smithing");
        smith.addProperty("id", "test/netherite_sword");
        smith.addProperty("result", "minecraft:netherite_sword");
        smith.addProperty("template", "minecraft:netherite_upgrade_smithing_template");
        smith.addProperty("base", "minecraft:diamond_sword");
        smith.addProperty("addition", "minecraft:netherite_ingot");
        PackBuilder.writeRecipe(data, "testproj", smith);
        JsonObject smithOut = readJson(data.resolve("recipe/test/netherite_sword.json"));
        assertEquals("minecraft:smithing_transform", smithOut.get("type").getAsString());
        assertEquals("minecraft:netherite_ingot",
                smithOut.getAsJsonObject("addition").get("item").getAsString());
    }

    @Test
    void hashIngredientBecomesATagReference() throws Exception {
        SlotRegistry.clearAll();
        Path data = tmp().resolve("data").resolve("testproj");
        JsonObject raw = simple("smelting", "minecraft:charcoal", "#minecraft:logs");
        raw.addProperty("id", "test/charcoal");
        PackBuilder.writeRecipe(data, "testproj", raw);
        JsonObject out = readJson(data.resolve("recipe/test/charcoal.json"));
        assertEquals("minecraft:logs", out.getAsJsonObject("ingredient").get("tag").getAsString());
        assertFalse(out.getAsJsonObject("ingredient").has("item"));
    }

    @Test
    void tagTypeSelectsTheVanillaFolder() throws Exception {
        SlotRegistry.clearAll();
        Path data = tmp().resolve("data").resolve("testproj");
        JsonObject raw = new JsonObject();
        raw.addProperty("id", "test/mobs");
        raw.addProperty("type", "entity_type");
        JsonArray values = new JsonArray();
        values.add("minecraft:zombie");
        raw.add("values", values);
        PackBuilder.writeTag(data, "testproj", raw);
        assertTrue(Files.exists(data.resolve("tags/entity_types/test/mobs.json")));
    }

    @Test
    void rawJsonWritesFileAndBlocksTraversal() throws Exception {
        Path data = tmp().resolve("data").resolve("testproj");
        JsonObject payload = new JsonObject();
        JsonObject def = new JsonObject();
        def.addProperty("type", "minecraft:ore");
        payload.add("definition", def);
        PackBuilder.writeRawFile(data, new Declarations.DatapackEntry("raw", "worldgen/feature/ruby", payload));
        assertTrue(Files.exists(data.resolve("worldgen/feature/ruby.json")));

        // A path that escapes the datapack root must be rejected.
        PackBuilder.writeRawFile(data, new Declarations.DatapackEntry("raw", "../../escape", payload));
        Path escaped = data.getParent().getParent().resolve("escape.json");
        assertFalse(Files.exists(escaped), "traversal must be blocked: " + escaped);
    }

    @Test
    void definitionDatapackWritesDefinitionBodyOnly() throws Exception {
        Path data = tmp().resolve("data").resolve("testproj");
        JsonObject payload = new JsonObject();
        JsonObject def = new JsonObject();
        def.addProperty("parent", "minecraft:story/root");
        payload.add("definition", def);
        PackBuilder.writeDefinitionDatapack(data, "advancement",
                new Declarations.DatapackEntry("advancement", "test/get_ruby", payload));
        JsonObject out = readJson(data.resolve("advancement/test/get_ruby.json"));
        assertEquals("minecraft:story/root", out.get("parent").getAsString());
        assertFalse(out.has("definition"), "only the definition body is written");
    }

    @Test
    void lootEntryObjectCarriesCountAndWeight() throws Exception {
        SlotRegistry.clearAll();
        Path data = tmp().resolve("data").resolve("testproj");
        JsonObject raw = new JsonObject();
        raw.addProperty("id", "ruby_drops");
        JsonArray entries = new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("item", "minecraft:diamond");
        entry.addProperty("count", 3);
        entry.addProperty("weight", 5);
        entries.add(entry);
        raw.add("entries", entries);
        PackBuilder.writeLootTable(data, "testproj", raw);
        JsonObject out = readJson(data.resolve("loot_table/ruby_drops.json"));
        JsonObject poolEntry = out.getAsJsonArray("pools").get(0).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("minecraft:diamond", poolEntry.get("name").getAsString());
        assertEquals(5, poolEntry.get("weight").getAsInt());
        JsonElement countFn = poolEntry.getAsJsonArray("functions").get(0).getAsJsonObject().get("count");
        assertEquals(3, countFn.getAsInt());
    }
}
