package net.xmilon.craftpy.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

/** The worker's declaration payload must map onto every supported kind. */
class DeclarationsTest {

    private static JsonObject content(String id) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        entry.add("definition", new JsonObject());
        return entry;
    }

    private static JsonArray array(JsonObject... entries) {
        JsonArray array = new JsonArray();
        for (JsonObject entry : entries) {
            array.add(entry);
        }
        return array;
    }

    private static JsonObject declarations() {
        JsonObject declarations = new JsonObject();
        declarations.add("blocks", array(content("ruby_block")));
        declarations.add("items", array(content("ruby")));
        declarations.add("entities", array(content("golem")));
        declarations.add("effects", array(content("glow")));
        JsonObject handler = new JsonObject();
        handler.addProperty("event", "block_break");
        handler.addProperty("every", 20);
        declarations.add("handlers", array(handler));

        JsonObject lang = new JsonObject();
        lang.addProperty("item.project.ruby", "Ruby");
        declarations.add("lang", lang);

        declarations.add("advancements", array(content("get_ruby")));
        declarations.add("sounds", array(content("chime")));
        declarations.add("particles", array(content("spark")));
        declarations.add("enchantments", array(content("sharp")));
        declarations.add("raw", array(content("worldgen/feature/ruby")));
        declarations.add("recipes", array(content("r1")));
        declarations.add("loot_tables", array(content("l1")));
        declarations.add("tags", array(content("t1")));
        return declarations;
    }

    @Test
    void parsesAllContentAndDatapackKinds() {
        JsonObject result = new JsonObject();
        result.add("declarations", declarations());
        result.add("log", new JsonArray());

        Declarations parsed = Declarations.parse(result, "main.py");
        assertFalse(parsed.failed());

        assertEquals(1, parsed.contents().stream().filter(c -> c.kind().equals("block")).count());
        assertEquals(1, parsed.contents().stream().filter(c -> c.kind().equals("item")).count());
        assertEquals(1, parsed.contents().stream().filter(c -> c.kind().equals("entity")).count());
        assertEquals(1, parsed.contents().stream().filter(c -> c.kind().equals("effect")).count());

        for (String kind : new String[] { "recipe", "loot_table", "tag", "advancement", "sound", "particle",
                "enchantment", "raw", "lang" }) {
            assertTrue(parsed.datapack().stream().anyMatch(e -> e.kind().equals(kind)),
                    "missing datapack kind " + kind);
        }
    }

    @Test
    void missingKeysDoNotThrow() {
        JsonObject result = new JsonObject();
        JsonObject minimal = new JsonObject();
        for (String key : new String[] { "blocks", "items", "entities", "effects", "handlers", "recipes",
                "loot_tables", "tags", "advancements", "sounds", "particles", "enchantments", "raw" }) {
            minimal.add(key, new JsonArray());
        }
        result.add("declarations", minimal);
        result.add("log", new JsonArray());
        assertFalse(Declarations.parse(result, "main.py").failed());
    }

    /** Regression: {@code @on("block_break")} sends "every": null, which used to fail the whole module. */
    @Test
    void handlerWithNullEveryLoads() {
        JsonObject handler = new JsonObject();
        handler.addProperty("event", "block_break");
        handler.add("every", com.google.gson.JsonNull.INSTANCE);
        JsonObject decl = new JsonObject();
        decl.add("handlers", array(handler));
        decl.add("blocks", array(content("ruby_block")));
        JsonObject result = new JsonObject();
        result.add("declarations", decl);

        Declarations parsed = Declarations.parse(result, "main.py");
        assertFalse(parsed.failed());
        assertEquals(1, parsed.handlers().size());
        assertEquals(null, parsed.handlers().get(0).every());
        assertEquals(1, parsed.contents().size());
    }
}
