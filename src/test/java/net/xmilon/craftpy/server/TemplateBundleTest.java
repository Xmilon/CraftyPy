package net.xmilon.craftpy.server;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import net.xmilon.craftpy.project.ProjectManager;

class TemplateBundleTest {
    @Test void companionCollisionsLeaveTheWholeBundleAndMainUnchanged(@TempDir Path root) throws Exception {
        Path projects=root.resolve("projects"), demo=projects.resolve("demo"); Files.createDirectories(demo.resolve("code")); Files.createDirectories(demo.resolve("ui"));
        Files.writeString(demo.resolve("project.json"),"{\"id\":\"demo\",\"side\":\"both\"}");
        Files.writeString(demo.resolve("code/main.py"),"# Preserve this main file\n"); Files.writeString(demo.resolve("ui/panel.json"),"{\"user\":true}");
        var constructor=ProjectManager.class.getDeclaredConstructor(Path.class); constructor.setAccessible(true); var manager=constructor.newInstance(projects);
        var request=new com.google.gson.JsonObject(); request.addProperty("op","add_part"); request.addProperty("template","ui/bound_counter.py"); request.addProperty("module","panel");
        var result=ProjectTools.handle(manager,"demo",request.toString()).get();
        assertFalse(result.success(), result.message());
        assertFalse(Files.exists(demo.resolve("code/panel.py"))); assertEquals("{\"user\":true}",Files.readString(demo.resolve("ui/panel.json")));
        assertEquals("# Preserve this main file\n",Files.readString(demo.resolve("code/main.py")));
        request.addProperty("module","new_panel"); result=ProjectTools.handle(manager,"demo",request.toString()).get();
        assertTrue(result.success(), result.message());
        assertTrue(Files.exists(demo.resolve("code/new_panel.py"))); assertTrue(Files.exists(demo.resolve("ui/new_panel.json")));
    }
}
