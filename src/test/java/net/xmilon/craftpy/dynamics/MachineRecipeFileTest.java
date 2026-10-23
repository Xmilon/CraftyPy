package net.xmilon.craftpy.dynamics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.xmilon.craftpy.python.Declarations;
import net.xmilon.craftpy.registry.SlotRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** machine_recipe(...) becomes a craftpy:machine recipe naming the project's items and liquids by their slots. */
class MachineRecipeFileTest {
    @TempDir
    Path folder;

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void itemsTagsChancesAndLiquidsAreMapped() throws Exception {
        SlotRegistry.clearAll();
        try {
            SlotRegistry.applyAll("dw", Declarations.parse(json("""
                    {"declarations": {
                      "items": [{"id": "dust", "definition": {"name": "Dust"}}],
                      "fluids": [{"id": "sap", "definition": {"name": "Sap", "color": "#00ff00", "density": 1000, "viscosity": 1000,
                                  "temperature": 300, "light": 0, "effects": []}}]}}"""), "code/main.py"));
            String dust = SlotRegistry.resolve("dw:dust", SlotRegistry.Kind.ITEM);
            String sap = SlotRegistry.resolve("dw:sap", SlotRegistry.Kind.FLUID);
            PackBuilder.writeRecipe(folder, "dw", json("""
                    {"type": "machine", "id": "grind_diorite", "machine": "minecraft:furnace",
                     "inputs": [{"ingredient": {"item": "minecraft:diorite"}, "count": 2}, {"ingredient": {"tag": "c:dusts"}, "count": 1}],
                     "outputs": [{"result": {"id": "dw:dust", "count": 2}, "chance": 1.0}, {"result": {"id": "dw:dust", "count": 1}, "chance": 0.25}],
                     "fluid_input": {"fluid": "#c:lava", "amount": 100}, "fluid_output": {"fluid": "dw:sap", "amount": 250},
                     "energy": 400, "time": 80}"""));
            JsonObject recipe = json(Files.readString(folder.resolve("recipe/grind_diorite.json")));
            assertEquals("craftpy:machine", recipe.get("type").getAsString());
            assertFalse(recipe.has("neoforge:conditions"), "CraftPy's own type needs no mod_loaded condition");
            assertEquals("minecraft:furnace", recipe.get("machine").getAsString());
            var inputs = recipe.getAsJsonArray("inputs");
            assertEquals("minecraft:diorite", inputs.get(0).getAsJsonObject().getAsJsonObject("ingredient").get("item").getAsString());
            assertEquals(2, inputs.get(0).getAsJsonObject().get("count").getAsInt());
            assertEquals("c:dusts", inputs.get(1).getAsJsonObject().getAsJsonObject("ingredient").get("tag").getAsString());
            var outputs = recipe.getAsJsonArray("outputs");
            assertEquals(dust, outputs.get(1).getAsJsonObject().getAsJsonObject("result").get("id").getAsString());
            assertEquals(0.25f, outputs.get(1).getAsJsonObject().get("chance").getAsFloat());
            assertEquals("#c:lava", recipe.getAsJsonObject("fluid_input").get("fluid").getAsString(), "a liquid tag stays a tag");
            assertEquals(sap, recipe.getAsJsonObject("fluid_output").get("fluid").getAsString());
            assertEquals(400, recipe.get("energy").getAsInt());
            assertEquals(80, recipe.get("time").getAsInt());        } finally {
            SlotRegistry.clearAll();
        }
    }
}
