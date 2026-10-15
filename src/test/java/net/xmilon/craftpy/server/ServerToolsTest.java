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
}
