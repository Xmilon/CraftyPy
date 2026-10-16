package net.xmilon.craftpy.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.xmilon.craftpy.catalog.Catalog;
import net.xmilon.craftpy.catalog.CatalogView;
import net.xmilon.craftpy.python.ValidationProblem;

class IdCheckerTest {
    private static CatalogView view() {
        Catalog vanilla = new Catalog();
        vanilla.namespace = "minecraft";
        vanilla.mod = "minecraft";
        vanilla.registries.put("item", List.of("minecraft:diamond", "minecraft:stick"));
        vanilla.registries.put("mob_effect", List.of("minecraft:speed"));
        Catalog create = new Catalog();
        create.namespace = "create";
        create.mod = "create";
        create.registries.put("item", List.of("create:cogwheel"));
        create.recipeTypes.put("create:mixing", new Catalog.RecipeType());
        return CatalogView.of(List.of(vanilla, create), Map.of(), Set.of("minecraft", "create"));
    }

    private static List<String> messages(String code) {
        return IdChecker.check("code/main.py", code, view(), "demo", IdChecker.ownNames(List.of(code))).stream()
                .map(ValidationProblem::message).toList();
    }

    @Test
    void knownIdsAndOwnContentPass() {
        assertEquals(List.of(), messages("p.give(\"minecraft:diamond\")\np.give(\"stick\")\np.give(\"create:cogwheel\")"));
        assertEquals(List.of(), messages("Item(\"gem\")\np.give(\"gem\")\np.give(\"demo:gem\")"));
        assertEquals(List.of(), messages("p.give(\"#minecraft:planks\")\nprint(\"minecraft:nothing\")"));
    }

    @Test
    void typosAndMissingModsAreWarned() {
        List<String> found = messages("p.give(\"minecraft:diamnd\")\np.give(\"irons_spellbooks:scroll\")\np.effect(\"swiftness\")");
        assertEquals(3, found.size(), found.toString());
        assertTrue(found.get(0).contains("unknown item 'minecraft:diamnd'"));
        assertTrue(found.get(1).contains("no installed mod uses the namespace 'irons_spellbooks'"));
        assertTrue(found.get(2).contains("unknown mob effect 'swiftness'") && found.get(2).contains("minecraft:swiftness"));
    }

    @Test
    void positionIsTheStartOfTheString() {
        var problems = IdChecker.check("f.py", "x = 1\np.give(\"stik\")", view(), "demo", Set.of());
        assertEquals(1, problems.get(0).line());
        assertEquals(8, problems.get(0).column());
    }

    @Test
    void recipeTypesOfMissingModsAreExplained() {
        assertEquals(List.of(), messages("recipe(\"create:mixing\")"));
        List<String> found = messages("recipe(\"mekanism:crushing\")\nrecipe(\"create:mixng\")");
        assertTrue(found.get(0).contains("needs the mod 'mekanism'"), found.toString());
        assertTrue(found.get(1).contains("unknown recipe type 'create:mixng'"), found.toString());
    }

    @Test
    void anEmptyCatalogSaysNothing() {
        assertEquals(List.of(), IdChecker.check("f.py", "p.give(\"zzz\")", CatalogView.EMPTY, "demo", Set.of()));
    }

    @org.junit.jupiter.api.Test
    void recipeKeywordsAreCheckedAgainstTheFieldsOfTheType() {
        var mixing = new net.xmilon.craftpy.catalog.Catalog.RecipeType();
        mixing.fields = java.util.List.of("ingredients", "results", "heat_requirement");
        var create = new net.xmilon.craftpy.catalog.Catalog();
        create.namespace = "create";
        create.mod = "create";
        create.recipeTypes.put("create:mixing", mixing);
        var view = net.xmilon.craftpy.catalog.CatalogView.of(java.util.List.of(create), java.util.Map.of(), java.util.Set.of("create"));
        String good = "recipe(\"create:mixing\", ingredients=[\"a\"], results=[{\"id\": \"b\"}], id=\"x\")";
        String typed = "recipe(\"create:mixing\", ingredient=[\"a\"], results=f(heat=1))";
        org.junit.jupiter.api.Assertions.assertNull(IdChecker.recipeFields(view, "create:mixing", good, good.indexOf("\",") ));
        String problem = IdChecker.recipeFields(view, "create:mixing", typed, typed.indexOf("\","));
        // only the recipe's own keyword is reported, not heat= of the nested call
        org.junit.jupiter.api.Assertions.assertTrue(problem != null && problem.contains("the field ingredient (known:"),
                String.valueOf(problem));
    }
}
