package net.xmilon.craftpy.dynamics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.xmilon.craftpy.util.Json;
import net.xmilon.craftpy.registry.SlotRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the JSON shapes CraftPy writes for the main declarative
 * datagen types (recipes, loot tables, tags). Pure JVM tests: no Minecraft
 * types, no filesystem side effects beyond a temp dir.
 */
class PackBuilderRecipesTest {

    private static Path tmp() {
        try {
            return Files.createTempDirectory("craftpy_packbuilder_");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void shapelessRecipe_hasCorrectTypeAndResult() throws Exception {
        SlotRegistry.clearAll();
        for (int i = 0; i < 7; i++) SlotRegistry.applyItem("dummy", "item" + i, new JsonObject());
        SlotRegistry.applyItem("testproj", "ppl tin_plate", new JsonObject()); // pcitem_7

        JsonObject raw = new JsonObject();
        raw.addProperty("type", "shapeless");
        raw.addProperty("result", "project:ppl tin_plate");
        JsonArray ingredients = new JsonArray();
        ingredients.add("minecraft:iron_ingot");
        ingredients.add("minecraft:gold_ingot");
        raw.add("ingredients", ingredients);
        raw.addProperty("category", "misc");

        Path root = tmp();
        Path data = root.resolve("data").resolve("testproj");
        PackBuilder.writeRecipe(data, "testproj", raw);

        JsonObject written = readJson(data.resolve("recipe/project_ppl_tin_plate.json"));
        assertEquals("minecraft:crafting_shapeless", written.get("type").getAsString());
        JsonObject result = written.getAsJsonObject("result");
        assertEquals("craftpy:pcitem_7", result.get("id").getAsString());
    }

    @Test
    void smeltingRecipe_preservesExperienceAndCookTime() throws Exception {
        SlotRegistry.clearAll();
        for (int i = 0; i < 2; i++) SlotRegistry.applyBlock("dummy", "block" + i, new JsonObject());
        SlotRegistry.applyBlock("testproj", "bb copper_ore", new JsonObject()); // pcblock_2
        for (int i = 0; i < 3; i++) SlotRegistry.applyItem("dummy", "item" + i, new JsonObject());
        SlotRegistry.applyItem("testproj", "bb copper_ingot", new JsonObject()); // pcitem_3

        JsonObject raw = new JsonObject();
        raw.addProperty("type", "smelting");
        raw.addProperty("ingredient", "project:bb copper_ore");
        raw.addProperty("result", "project:bb copper_ingot");
        raw.addProperty("xp", 0.5f);
        raw.addProperty("cook_time", 100);

        Path root = tmp();
        Path data = root.resolve("data").resolve("testproj");
        PackBuilder.writeRecipe(data, "testproj", raw);

        JsonObject written = readJson(data.resolve("recipe/project_bb_copper_ore.json"));
        assertEquals("minecraft:smelting", written.get("type").getAsString());
        assertEquals("craftpy:pcblock_2", written.getAsJsonObject("ingredient").get("item").getAsString());
        assertEquals("craftpy:pcitem_3", written.getAsJsonObject("result").get("id").getAsString());
        assertEquals(0.5f, written.get("experience").getAsFloat());
        assertEquals(100, written.get("cookingtime").getAsInt());
    }

    @Test
    void smeltingRecipe_hasShapedIngredientWrapper() throws Exception {
        SlotRegistry.clearAll();
        JsonObject raw = new JsonObject();
        raw.addProperty("type", "smelting");
        raw.addProperty("ingredient", "minecraft:iron_ore");
        raw.addProperty("result", "minecraft:iron_ingot");

        Path root = tmp();
        Path data = root.resolve("data").resolve("testproj");
        PackBuilder.writeRecipe(data, "testproj", raw);

        JsonObject written = readJson(data.resolve("recipe/minecraft_iron_ore.json"));
        JsonObject ingredient = written.getAsJsonObject("ingredient");
        assertEquals("minecraft:iron_ore", ingredient.get("item").getAsString());
    }

    @Test
    void lootTable_rewritesEntriesAndUsesBlockType() throws Exception {
        SlotRegistry.clearAll();
        for (int i = 0; i < 4; i++) SlotRegistry.applyItem("dummy", "item" + i, new JsonObject());
        SlotRegistry.applyItem("testproj", "bb ruby", new JsonObject()); // pcitem_4

        JsonObject raw = new JsonObject();
        raw.addProperty("id", "project:bb ruby_block_loot");
        JsonArray entries = new JsonArray();
        entries.add("project:bb ruby");
        entries.add("minecraft:stick");
        raw.add("entries", entries);

        Path root = tmp();
        Path data = root.resolve("data").resolve("testproj");
        PackBuilder.writeLootTable(data, "testproj", raw);

        JsonObject written = readJson(data.resolve("loot_table/project_bb_ruby_block_loot.json"));
        assertEquals("minecraft:block", written.get("type").getAsString());
        JsonArray poolEntries = written.getAsJsonArray("pools").get(0).getAsJsonObject()
                .getAsJsonArray("entries");
        assertEquals("craftpy:pcitem_4", poolEntries.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("minecraft:stick", poolEntries.get(1).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void tag_replacesAndRewritesValues() throws Exception {
        SlotRegistry.clearAll();
        for (int i = 0; i < 2; i++) SlotRegistry.applyBlock("dummy", "block" + i, new JsonObject());
        SlotRegistry.applyBlock("testproj", "bb ruby", new JsonObject()); // pcblock_2

        JsonObject raw = new JsonObject();
        raw.addProperty("id", "project:bb example");
        raw.addProperty("replace", true);
        JsonArray values = new JsonArray();
        values.add("project:bb ruby");
        values.add("minecraft:oak_log");
        raw.add("values", values);
        raw.addProperty("type", "block");

        Path root = tmp();
        Path data = root.resolve("data").resolve("testproj");
        PackBuilder.writeTag(data, "testproj", raw);

        JsonObject written = readJson(data.resolve("tags/block/project_bb_example.json"));
        assertTrue(written.get("replace").getAsBoolean());
        JsonArray vals = written.getAsJsonArray("values");
        assertEquals("craftpy:pcblock_2", vals.get(0).getAsString());
        assertEquals("minecraft:oak_log", vals.get(1).getAsString());
    }

    @Test
    void writeRecipe_unknownTypeFallsBackToShapeless() throws Exception {
        SlotRegistry.clearAll();
        for (int i = 0; i < 8; i++) SlotRegistry.applyItem("dummy", "item" + i, new JsonObject());
        SlotRegistry.applyItem("testproj", "ppl plate", new JsonObject()); // pcitem_8

        JsonObject raw = new JsonObject();
        raw.addProperty("type", "not_a_real_type");
        raw.addProperty("result", "project:ppl plate");
        JsonArray ingredients = new JsonArray();
        ingredients.add("minecraft:stick");
        raw.add("ingredients", ingredients);

        Path root = tmp();
        Path data = root.resolve("data").resolve("testproj");
        PackBuilder.writeRecipe(data, "testproj", raw);

        JsonObject written = readJson(data.resolve("recipe/project_ppl_plate.json"));
        assertEquals("minecraft:crafting_shapeless", written.get("type").getAsString());
    }

    @Test
    void writeRecipe_shaped_rewritesKeysAndResult() throws Exception {
        SlotRegistry.clearAll();
        for (int i = 0; i < 4; i++) SlotRegistry.applyItem("dummy", "item" + i, new JsonObject());
        SlotRegistry.applyItem("testproj", "bb ruby", new JsonObject()); // pcitem_4

        JsonObject raw = new JsonObject();
        raw.addProperty("type", "shaped");
        raw.addProperty("result", "project:bb ruby");
        JsonArray pattern = new JsonArray();
        pattern.add("RR");
        pattern.add("RR");
        raw.add("pattern", pattern);
        JsonObject keys = new JsonObject();
        JsonObject rKey = new JsonObject();
        rKey.addProperty("item", "minecraft:red_dye");
        keys.add("R", rKey);
        raw.add("keys", keys);
        raw.addProperty("category", "equipment");

        Path root = tmp();
        Path data = root.resolve("data").resolve("testproj");
        PackBuilder.writeRecipe(data, "testproj", raw);

        JsonObject written = readJson(data.resolve("recipe/project_bb_ruby.json"));
        assertEquals("minecraft:crafting_shaped", written.get("type").getAsString());
        JsonObject redKey = written.getAsJsonObject("keys").getAsJsonObject("R");
        assertEquals("minecraft:red_dye", redKey.get("item").getAsString());
        assertEquals("craftpy:pcitem_4", written.getAsJsonObject("result").get("id").getAsString());
        assertEquals("equipment", written.get("category").getAsString());
    }

    private static JsonObject readJson(Path path) {
        try {
            return Json.GSON.fromJson(Files.readString(path), JsonObject.class);
        } catch (Exception e) {
            throw new AssertionError("failed to read " + path, e);
        }
    }
}
