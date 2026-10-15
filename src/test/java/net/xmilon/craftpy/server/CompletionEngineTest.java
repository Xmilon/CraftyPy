package net.xmilon.craftpy.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.xmilon.craftpy.analysis.IdKinds;
import net.xmilon.craftpy.analysis.StringContext;
import net.xmilon.craftpy.catalog.Catalog;
import net.xmilon.craftpy.catalog.CatalogView;

class CompletionEngineTest {
    private static CatalogView view() {
        Catalog vanilla = new Catalog();
        vanilla.namespace = "minecraft";
        vanilla.mod = "minecraft";
        vanilla.registries.put("item", List.of("minecraft:diamond", "minecraft:stick"));
        vanilla.registries.put("mob_effect", List.of("minecraft:speed", "minecraft:strength"));
        vanilla.tags.put("item", List.of("minecraft:planks"));
        Catalog iron = new Catalog();
        iron.namespace = "irons_spellbooks";
        iron.mod = "irons_spellbooks";
        iron.registries.put("mob_effect", List.of("irons_spellbooks:evasion"));
        iron.registries.put("attribute", List.of("irons_spellbooks:fire_spell_power"));
        iron.classes = List.of("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
        iron.recipeTypes.put("irons_spellbooks:alchemist_cauldron_fill", new Catalog.RecipeType());
        return CatalogView.of(List.of(vanilla, iron), Map.of(), Set.of("minecraft", "irons_spellbooks"));
    }

    private static List<String> names(JsonObject reply) {
        JsonArray array = reply.getAsJsonArray("suggestions");
        return array.asList().stream().map(e -> e.getAsString()).toList();
    }

    /** Completes at the end of the last line of {@code code}. */
    private static JsonObject at(String code) {
        String[] lines = code.split("\n", -1);
        return CompletionEngine.complete(code, lines.length - 1, lines[lines.length - 1].length(), view(), Map.of());
    }

    @Test
    void contextOfAStringInACall() {
        var call = StringContext.at("p.effect(\"spe", 13).get();
        assertEquals("effect", call.callee());
        assertEquals("p.effect", call.chain());
        assertEquals(0, call.argument());
        assertEquals("spe", call.typed());
        var keyword = StringContext.at("on.block_break(block=\"mine", 25).get();
        assertEquals("block", keyword.keyword());
        var second = StringContext.at("shapeless(\"a\", \"b\", \"c", 21).get();
        assertEquals(2, second.argument());
        assertTrue(StringContext.at("x = 1", 3).isEmpty());
        assertTrue(StringContext.at("print(f\"{a}", 11).isEmpty(), "f-strings are code");
    }

    @Test
    void aListInsideACallBelongsToTheCallsKeyword() {
        var call = StringContext.at("Block(\"x\", drops=[\"a\", \"b", 25).get();
        assertEquals("Block", call.callee());
        assertEquals("drops", call.keyword());
        assertEquals("item", IdKinds.kindFor(call));
    }

    @Test
    void kindsFollowTheCallAndArgument() {
        assertEquals("mob_effect", IdKinds.kindFor(StringContext.at("player.effect(\"", 15).get()));
        assertEquals("item", IdKinds.kindFor(StringContext.at("shaped(\"", 8).get()));
        assertEquals("item", IdKinds.kindFor(StringContext.at("shaped(\"a\", \"RR\", R=\"", 21).get()));
        assertNull(IdKinds.kindFor(StringContext.at("shaped(\"a\", \"R", 14).get()), "the pattern is not an id");
        assertEquals("recipe_type", IdKinds.kindFor(StringContext.at("recipe(\"", 8).get()));
        assertEquals("class", IdKinds.kindFor(StringContext.at("java.cls(\"", 10).get()));
        assertNull(IdKinds.kindFor(StringContext.at("print(\"", 7).get()));
    }

    @Test
    void idsAreOfferedFromTheInstalledMods() {
        assertEquals(List.of("minecraft:speed"), names(at("p.effect(\"spe")));
        assertEquals(List.of("minecraft:speed", "minecraft:strength", "irons_spellbooks:evasion"), names(at("p.effect(\"")));
        assertEquals(List.of("irons_spellbooks:evasion"), names(at("p.effect(\"irons_spellbooks:")));
        assertEquals(List.of("irons_spellbooks:fire_spell_power"), names(at("Attributes(\"fire")));
        assertEquals("irons_spellbooks:fire_spell_power", at("Attributes(\"fire").getAsJsonArray("items").get(0).getAsJsonObject()
                .get("n").getAsString());
        assertEquals("mob_effect - irons_spellbooks",
                at("p.effect(\"irons").getAsJsonArray("items").get(0).getAsJsonObject().get("d").getAsString());
    }

    @Test
    void tagsAndClassesAndOwnContent() {
        assertEquals(List.of("#minecraft:planks"), names(at("shapeless(\"x\", \"#pla")));
        assertEquals(List.of("io.redspace.ironsspellbooks.api.registry.SpellRegistry"), names(at("java.cls(\"io.redspace")));
        JsonObject own = CompletionEngine.complete("give(\"gem", 0, 9, view(), Map.of("item", List.of("welcome:gem")));
        assertEquals("welcome:gem", own.getAsJsonArray("suggestions").get(0).getAsString());
    }

    @Test
    void eventNamesAreOfferedInOn() {
        assertEquals(List.of("player_join", "player_leave"), names(at("@on(\"player_")).subList(0, 2));
    }

    @Test
    void outsideStringsTheWorkerAnswersAndUnknownStringsOfferNothing() {
        assertNull(at("p.eff"));
        assertEquals(0, at("print(\"hel").getAsJsonArray("items").size());
    }

    @Test
    void hoverOnAnIdTellsTheModOrThatItIsUnknown() {
        String code = "p.effect(\"irons_spellbooks:evasion\")\np.effect(\"nope:missing\")\nx = \"text\"";
        var known = CompletionEngine.hover(code, 0, 12, view(), Map.of());
        assertEquals("irons_spellbooks:evasion", known.get("title").getAsString());
        assertTrue(known.get("doc").getAsString().contains("irons_spellbooks"), known.toString());
        var unknown = CompletionEngine.hover(code, 1, 12, view(), Map.of());
        assertTrue(unknown.get("doc").getAsString().startsWith("No "), unknown.toString());
        assertNull(CompletionEngine.hover(code, 2, 6, view(), Map.of()), "a plain string is not an id");
        assertNull(CompletionEngine.hover(code, 0, 2, view(), Map.of()), "outside strings the worker answers");
    }
}
