package net.xmilon.craftpy.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.xmilon.craftpy.analysis.ProjectScan;
import net.xmilon.craftpy.lifecycle.EventTrace;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.project.ProjectTemplates;

class ServerToolsTest {
    @Test
    void fitDropsRowsUntilTheReplyFits() {
        JsonObject body = new JsonObject();
        JsonArray rows = new JsonArray();
        for (int i = 0; i < 2000; i++) {
            rows.add("row number " + i + " with some padding text to make it longer");
        }
        body.add("rows", rows);
        ProjectTools.fit(body, "rows");
        assertTrue(body.toString().length() <= 30_000, "length " + body.toString().length());
        assertTrue(body.get("rowsCut").getAsInt() > 0);
        assertEquals(2000, rows.size() + body.get("rowsCut").getAsInt());
    }

    @Test
    void nearestSuggestsTypos() {
        assertEquals("ruby_ore", ProjectTools.nearest("ruby_oer", List.of("ruby", "ruby_ore")));
        assertNull(ProjectTools.nearest("zzzzzz", List.of("ruby", "ruby_ore")));
    }

    @Test
    void tracerRecordsAndFinishesEvents() {
        EventTrace.clear();
        EventTrace.setEnabled(false);
        assertEquals(-1, EventTrace.begin("chat", new JsonObject(), true));
        EventTrace.setEnabled(true);
        JsonObject context = JsonParser.parseString("{\"player\":{\"name\":\"Dev\"},\"message\":\"hi\"}").getAsJsonObject();
        long seq = EventTrace.begin("chat", context, true);
        long unhandled = EventTrace.begin("block_break", new JsonObject(), false);
        assertEquals(2, EventTrace.since(0).size());
        assertEquals("player=Dev message=hi", EventTrace.since(0).get(0).summary());
        assertEquals(-1, EventTrace.since(0).get(0).handlers());
        EventTrace.finish(seq, 2, 0.5);
        assertEquals(2, EventTrace.since(0).get(0).handlers());
        assertEquals(1, EventTrace.since(seq).size());
        assertEquals(0, EventTrace.since(unhandled).size());
        EventTrace.setEnabled(false);
        EventTrace.clear();
    }

    @Test
    void templatesAllExistAndFillTheProjectId() {
        for (ProjectTemplates.Template template : ProjectTemplates.ALL) {
            String source = ProjectTemplates.source(template.id(), "my_pack");
            assertFalse(source.contains("{{id}}"), template.id());
            assertTrue(source.contains("from craftpy import *"), template.id());
        }
        assertTrue(ProjectTemplates.source("nope", "x").contains("Block("), "unknown ids fall back to the starter");
    }

    @Test
    void renameEverywhereWritesFilesAndMovesAssets(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("demo");
        Files.createDirectories(project.resolve("code"));
        Files.createDirectories(project.resolve("assets/textures"));
        Files.writeString(project.resolve("code/main.py"),
                "from craftpy import *\nruby = Block(\"ruby\", texture=\"ruby.png\")\n", StandardCharsets.UTF_8);
        Files.write(project.resolve("assets/textures/ruby.png"), new byte[] { 1, 2, 3 });

        Constructor<ProjectManager> constructor = ProjectManager.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        ProjectManager manager = constructor.newInstance(dir);

        ProjectManager.Snapshot snapshot = manager.snapshot("demo").get();
        assertEquals(List.of("assets/textures/ruby.png", "code/main.py"), snapshot.paths());
        assertEquals(1, snapshot.texts().size());

        ProjectScan.RenamePlan plan = ProjectScan.renameId(snapshot.paths(), snapshot.texts(), "ruby", "garnet");
        assertTrue(plan.ok(), plan.error());
        assertTrue(manager.applyRename("demo", plan).get().success());
        assertTrue(Files.readString(project.resolve("code/main.py")).contains("garnet = Block(\"garnet\", texture=\"garnet.png\")"));
        assertTrue(Files.exists(project.resolve("assets/textures/garnet.png")));
        assertFalse(Files.exists(project.resolve("assets/textures/ruby.png")));
    }

    @Test
    void addPartWritesTheFileAndImportsItInMain(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("demo");
        Files.createDirectories(project.resolve("code"));
        Files.writeString(project.resolve("code/main.py"), "from craftpy import *\nimport items\n", StandardCharsets.UTF_8);
        Constructor<ProjectManager> constructor = ProjectManager.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        ProjectManager manager = constructor.newInstance(dir);

        JsonObject request = new JsonObject();
        request.addProperty("op", "add_part");
        request.addProperty("module", "shop");
        request.addProperty("content", "from craftpy import *\n");
        request.addProperty("patchMain", true);
        var reply = ProjectTools.handle(manager, "demo", request.toString()).get();
        assertTrue(reply.success(), reply.message());
        assertEquals("from craftpy import *\n", Files.readString(project.resolve("code/shop.py")));
        assertEquals("from craftpy import *\nimport items\nimport shop\n", Files.readString(project.resolve("code/main.py")));

        var again = ProjectTools.handle(manager, "demo", request.toString()).get();
        assertFalse(again.success(), "an existing file is never overwritten");
        request.addProperty("module", "../evil");
        assertFalse(ProjectTools.handle(manager, "demo", request.toString()).get().success());
    }

    @Test
    void grepFindsTextAndTodos() {
        var snapshot = new ProjectManager.Snapshot(List.of("code/main.py", "code/a.py"), new java.util.LinkedHashMap<>(java.util.Map.of(
                "code/main.py", "import a\n# TODO: more blocks\nx = Gem\n",
                "code/a.py", "gem = 1\n# fixme later\n")));
        var found = com.google.gson.JsonParser.parseString(ProjectTools.grep(snapshot, "gem", false).json()).getAsJsonObject();
        assertEquals(2, found.getAsJsonArray("rows").size(), "case-insensitive");
        var todos = com.google.gson.JsonParser.parseString(ProjectTools.grep(snapshot, "", true).json()).getAsJsonObject();
        assertEquals(2, todos.getAsJsonArray("rows").size());
        var none = com.google.gson.JsonParser.parseString(ProjectTools.grep(snapshot, " ", false).json()).getAsJsonObject();
        assertEquals(0, none.getAsJsonArray("rows").size(), "a blank query finds nothing");
    }

    @Test
    void deletedEntriesGoToTheTrashAndComeBack(@TempDir Path dir) throws Exception {
        Path projects = dir.resolve("projects");
        Path project = projects.resolve("demo");
        Files.createDirectories(project.resolve("code/pkg"));
        Files.writeString(project.resolve("code/a.py"), "a = 1\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("code/pkg/b.py"), "b = 2\n", StandardCharsets.UTF_8);
        Constructor<ProjectManager> constructor = ProjectManager.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        ProjectManager manager = constructor.newInstance(projects);

        assertTrue(manager.deleteEntry("demo", "code/a.py").get().success());
        assertTrue(manager.deleteEntry("demo", "code/pkg").get().success());
        assertFalse(Files.exists(project.resolve("code/a.py")));
        assertTrue(Files.isDirectory(dir.resolve("system/trash/demo")), "kept next to the projects, not inside one");

        assertTrue(manager.restoreEntry("demo", "code/pkg").get().success());
        assertEquals("b = 2\n", Files.readString(project.resolve("code/pkg/b.py")));
        assertTrue(manager.restoreEntry("demo", "code/a.py").get().success());
        assertEquals("a = 1\n", Files.readString(project.resolve("code/a.py")));
        assertFalse(manager.restoreEntry("demo", "code/a.py").get().success(), "it exists again: nothing to overwrite");
        assertFalse(manager.restoreEntry("demo", "../outside").get().success());

        assertEquals("x = 1\n", net.xmilon.craftpy.analysis.PyImports.removeImport("x = 1\n", "shop"));
        assertEquals("import a\n", net.xmilon.craftpy.analysis.PyImports.removeImport("import a\nimport shop\n", "shop"));
    }
}
