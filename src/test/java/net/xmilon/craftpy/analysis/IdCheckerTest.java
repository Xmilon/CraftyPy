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
}
