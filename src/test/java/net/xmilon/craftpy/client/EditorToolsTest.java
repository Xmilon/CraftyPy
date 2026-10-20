package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class EditorToolsTest {
    @Test
    void jumpHistoryGoesBackAndForward() {
        JumpHistory history = new JumpHistory();
        var a = new JumpHistory.Location("a.py", 3);
        var b = new JumpHistory.Location("b.py", 10);
        var here = new JumpHistory.Location("c.py", 0);
        assertFalse(history.canBack());
        history.push(a);
        history.push(b);
        assertEquals(b, history.back(here));
        assertEquals(a, history.back(b));
        assertNull(history.back(a), "nothing before the first jump");
        assertEquals(b, history.forward());
        assertEquals(here, history.forward());
        assertNull(history.forward());
        assertEquals(List.of(here, b, a), history.recent());
    }

    @Test
    void jumpHistoryIgnoresNearbyAndDiscardsForward() {
        JumpHistory history = new JumpHistory();
        history.push(new JumpHistory.Location("a.py", 3));
        history.push(new JumpHistory.Location("a.py", 4));
        assertEquals(1, history.recent().size());
        history.back(new JumpHistory.Location("a.py", 50));
        history.push(new JumpHistory.Location("z.py", 1));
        assertFalse(history.canForward());
    }

    @Test
    void replacePreviewAndPartialApply() {
        String text = "ruby = 1\nprint(ruby)\nx = 'ruby'\n";
        List<int[]> matches = TextOps.findAll(text, "ruby", true, 0, text.length());
        List<TextOps.ReplaceRow> rows = TextOps.previewReplace(text, matches, "gem");
        assertEquals(3, rows.size());
        assertEquals("print(ruby)", rows.get(1).before());
        assertEquals("print(gem)", rows.get(1).after());
        assertEquals(2, rows.get(2).line());
        assertEquals("gem = 1\nprint(ruby)\nx = 'gem'\n",
                TextOps.replaceMatches(text, matches, new boolean[] { true, false, true }, "gem"));
    }

    @Test
    void recipePreviewShapedAndSmelting() {
        JsonObject shaped = JsonParser.parseString("""
                {"type":"shaped","result":"demo:ruby_block","count":1,"pattern":["RR","RR"],
                 "key":{"R":"demo:ruby"}}""").getAsJsonObject();
        var view = RecipePreview.describe(shaped, "demo");
        assertEquals("ruby", view.grid()[0][0]);
        assertEquals("", view.grid()[0][2]);
        assertEquals("ruby_block", view.result());
        JsonObject smelt = JsonParser.parseString("""
                {"type":"smelting","result":"demo:ruby","ingredient":"demo:ruby_ore","xp":1.0,"cook_time":200}""")
                .getAsJsonObject();
        var smeltView = RecipePreview.describe(smelt, "demo");
        assertEquals(List.of("ruby_ore"), smeltView.inputs());
        assertTrue(smeltView.note().startsWith("10.0 s"), smeltView.note());
        assertEquals("stick", RecipePreview.shortId("minecraft:stick", "demo"));
    }

    @Test
    void lootPreviewComputesChances() {
        JsonObject table = JsonParser.parseString("""
                {"id":"ruby_ore","rolls":1,"entries":[{"item":"demo:ruby","count":2,"weight":3},
                {"ref":"loot","value":"self","count":1,"weight":1}]}""").getAsJsonObject();
        var view = LootPreview.describe(table, "demo");
        assertEquals(75.0, view.entries().get(0).percent(), 0.001);
        assertEquals("the block itself", view.entries().get(1).item());
        assertEquals(25.0, view.entries().get(1).percent(), 0.001);
    }
}
