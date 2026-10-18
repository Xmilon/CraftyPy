package net.xmilon.craftpy.server;

import com.google.gson.JsonObject;
import net.xmilon.craftpy.catalog.CatalogView;
import net.xmilon.craftpy.project.ModuleInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModMapTest {

    @Test
    void nodesAndTheEdgesBetweenThem() {
        var mods = List.of(
                new ModMap.Mod("create", "Create", "6.0", List.of(new ModMap.Dependency("flywheel", "required"),
                        new ModMap.Dependency("minecraft", "required"), new ModMap.Dependency("jei", "optional"))),
                new ModMap.Mod("flywheel", "Flywheel", "1.0", List.of()),
                new ModMap.Mod("jei", "JEI", "19", List.of()));
        ModuleInfo module = new ModuleInfo();
        module.id = "gems";
        module.name = "Gems";
        module.dependencies = List.of("create", "other_module");
        var out = ModMap.build(mods, List.of(module),
                Map.of("mashup", Map.of("code/main.py", "recipe('create:mixing', ingredients=[{'item': 'mekanism:ingot_osmium'}],"
                        + " results=[{'id': 'mashup:alloy'}])\nx = 'minecraft:stone'\n")),
                Map.of("gems", Map.of("code/main.py", "give('create:cogwheel')")), CatalogView.EMPTY);
        var edges = out.getAsJsonArray("edges");
        assertTrue(has(edges, "mod:create", "mod:flywheel", "required"));
        assertTrue(has(edges, "mod:create", "mod:jei", "optional"));
        assertTrue(has(edges, "module:gems", "mod:create", "required"));
        assertTrue(has(edges, "module:gems", "module:other_module", "required"));
        assertTrue(has(edges, "module:gems", "mod:create", "uses"));
        assertTrue(has(edges, "project:mashup", "mod:create", "uses"));
        assertTrue(has(edges, "project:mashup", "missing:mekanism", "uses")); // named but not installed
        assertTrue(!has(edges, "mod:create", "mod:minecraft", "required"));
        var kinds = new java.util.HashMap<String, String>();
        out.getAsJsonArray("nodes").forEach(n -> kinds.put(n.getAsJsonObject().get("key").getAsString(), n.getAsJsonObject().get("kind").getAsString()));
        assertEquals("missing", kinds.get("missing:mekanism"));
        assertEquals("missing", kinds.get("module:other_module"));
        assertEquals("project", kinds.get("project:mashup"));
    }

    @Test
    void factsImportsProjectDependenciesAndSharedEvents() {
        ModuleInfo jukebox = new ModuleInfo();
        jukebox.id = "jukebox";
        jukebox.description = "Plays music";
        var perms = new net.xmilon.craftpy.security.ModulePermissions();
        perms.java = true;
        var facts = Map.of(
                "module:jukebox", new ModMap.Facts("Plays music", "me", "client", "loaded", perms, "needed", List.of(),
                        Map.of("item", 2), List.of("/jukebox:play"), List.of("player_join"), null, true),
                "project:party", new ModMap.Facts("A party", "", "both", "loaded", null, "none",
                        List.of("jukebox>=1.0"), Map.of(), List.of(), List.of("player_join", "block_break"), null, false));
        var out = ModMap.build(List.of(), List.of(jukebox),
                Map.of("party", Map.of("code/main.py", "from modules.jukebox import songs, VOLUME as V\nimport modules.lights\n")),
                Map.of(), CatalogView.EMPTY, facts);
        var edges = out.getAsJsonArray("edges");
        assertTrue(has(edges, "project:party", "module:jukebox", "required"));
        assertTrue(has(edges, "project:party", "module:jukebox", "imports"));
        assertTrue(has(edges, "project:party", "module:lights", "imports"));
        for (var e : edges) {
            var edge = e.getAsJsonObject();
            if (edge.get("kind").getAsString().equals("imports") && edge.get("to").getAsString().equals("module:jukebox")) {
                assertEquals("[\"songs\",\"VOLUME\"]", edge.get("names").toString());
            }
        }
        JsonObject node = null;
        for (var n : out.getAsJsonArray("nodes")) {
            if (n.getAsJsonObject().get("key").getAsString().equals("module:jukebox")) {
                node = n.getAsJsonObject();
            }
        }
        assertEquals("Plays music", node.get("description").getAsString());
        assertTrue(node.getAsJsonObject("permissions").get("java").getAsBoolean());
        assertTrue(node.get("changed").getAsBoolean());
        assertEquals("/jukebox:play", node.getAsJsonArray("commands").get(0).getAsString());
        var event = node.getAsJsonArray("events").get(0).getAsJsonObject();
        assertEquals("player_join", event.get("event").getAsString());
        assertEquals("project:party", event.getAsJsonArray("also").get(0).getAsString());
    }

    private static boolean has(com.google.gson.JsonArray edges, String from, String to, String kind) {
        for (var e : edges) {
            JsonObject edge = e.getAsJsonObject();
            if (edge.get("from").getAsString().equals(from) && edge.get("to").getAsString().equals(to)
                    && edge.get("kind").getAsString().equals(kind)) {
                return true;
            }
        }
        return false;
    }
}
