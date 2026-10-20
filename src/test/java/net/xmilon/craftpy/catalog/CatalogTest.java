package net.xmilon.craftpy.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogTest {
    private static Catalog catalog(String namespace, String mod, String hash, String kind, String... ids) {
        Catalog catalog = new Catalog();
        catalog.namespace = namespace;
        catalog.mod = mod;
        catalog.version = "1";
        catalog.jarHash = hash;
        catalog.registries.put(kind, List.of(ids));
        return catalog;
    }

    @Test
    void aRemovedModIsHiddenButItsFileStays(@TempDir Path dir) throws Exception {
        CatalogStore store = new CatalogStore(dir);
        store.save(catalog("minecraft", "minecraft", "mc", "item", "minecraft:stick"));
        store.save(catalog("create", "create", "h1", "item", "create:cogwheel"));
        ModCatalog both = new ModCatalog(store, () -> Set.of("minecraft", "create"), Map.of());
        assertTrue(both.view().contains("item", "create:cogwheel"));

        ModCatalog without = new ModCatalog(store, () -> Set.of("minecraft"), Map.of());
        assertFalse(without.view().contains("item", "create:cogwheel"));
        assertTrue(without.view().contains("item", "minecraft:stick"));
        assertNotNull(store.load("create"), "the file is kept for when the mod comes back");
        assertTrue(store.isCurrent("create", "1", "h1"));
        assertFalse(store.isCurrent("create", "1", "changed"));
    }

    @Test
    void searchRanksPrefixThenPathThenContainsAndVanillaFirst() {
        Catalog vanilla = catalog("minecraft", "minecraft", "mc", "item", "minecraft:diamond", "minecraft:iron_ingot");
        Catalog mod = catalog("mymod", "mymod", "h", "item", "mymod:diamond_dust", "mymod:fake_diamond");
        CatalogView view = CatalogView.of(List.of(mod, vanilla), Map.of(), Set.of("minecraft", "mymod"));
        List<String> found = view.search("item", "dia", 10).stream().map(CatalogView.Entry::id).toList();
        assertEquals(List.of("minecraft:diamond", "mymod:diamond_dust", "mymod:fake_diamond"), found);
        assertEquals("mymod", view.search("item", "mymod:d", 5).get(0).mod());
        assertEquals(1, view.search("item", "", 1).size());
    }

    @Test
    void seedsFillRecipeTypesOnlyForInstalledModsAndNeverBeatTheDump() {
        Catalog seed = new Catalog();
        seed.mod = "create";
        seed.namespace = "create";
        seed.recipeTypes.put("create:mixing", new Catalog.RecipeType(0, List.of("ingredients", "results"), null, "mixer", "seed"));
        seed.recipeTypes.put("create:pressing", new Catalog.RecipeType(0, List.of("ingredients"), null, "", "seed"));
        Catalog dumped = catalog("create", "create", "h", "item", "create:cogwheel");
        dumped.recipeTypes.put("create:mixing", new Catalog.RecipeType(12, List.of("results", "heat_requirement"), null, "", "dump"));

        CatalogView with = CatalogView.of(List.of(dumped), Map.of("create", seed), Set.of("create"));
        assertEquals(12, with.recipeType("create:mixing").count);
        assertEquals("mixer", with.recipeType("create:mixing").note);
        assertTrue(with.recipeType("create:mixing").fields.containsAll(List.of("results", "heat_requirement", "ingredients")));
        assertNotNull(with.recipeType("create:pressing"));

        CatalogView without = CatalogView.of(List.of(), Map.of("create", seed), Set.of("minecraft"));
        assertNull(without.recipeType("create:pressing"));
        assertNull(without.modOf("create"));
    }

    @Test
    void shippedSeedsAreReadable() {
        Map<String, Catalog> seeds = CatalogSeeds.load();
        assertTrue(seeds.keySet().containsAll(Set.of("create", "mekanism", "irons_spellbooks", "curios")), seeds.keySet().toString());
        assertTrue(seeds.get("create").recipeTypes.containsKey("create:mixing"));
        assertEquals("seed", seeds.get("mekanism").recipeTypes.get("mekanism:crushing").source);
    }

    @Test
    void readsModIdsAndClassesFromAJarWithoutLoadingIt(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("a.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(("modLoader=\"javafml\"\n[[mods]]\nmodId=\"first\"\nversion=\"1.2\"\n[[dependencies.first]]\nmodId=\"neoforge\"\n"
                    + "[[mods]]\nmodId=\"second\"\nversion=\"${file.jarVersion}\"\n").getBytes());
            zip.closeEntry();
            for (String entry : List.of("a/B.class", "a/event/C.class", "a/D$Inner.class", "a/mixin/M.class", "a/FooEvent.class")) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.closeEntry();
            }
        }
        List<ModJars.Jar> mods = ModJars.scan(dir);
        assertEquals(List.of("first", "second"), mods.stream().map(ModJars.Jar::modId).toList());
        assertEquals("1.2", mods.get(0).version());
        assertEquals("", mods.get(1).version());
        List<String> classes = ModJars.classNames(jar);
        assertEquals(List.of("a.B", "a.event.C", "a.FooEvent"), classes);
        assertEquals(List.of("a.event.C", "a.FooEvent"), ModJars.eventNames(classes));
    }

    @Test
    void theJarHashIsRememberedBySizeAndTime(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("x.jar");
        Files.writeString(file, "one");
        CatalogStore store = new CatalogStore(dir.resolve("catalog"));
        String first = store.hashOf(file);
        assertEquals(first, store.hashOf(file));
        Files.writeString(file, "two!");
        assertFalse(first.equals(store.hashOf(file)));
    }
}
