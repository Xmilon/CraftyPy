package net.xmilon.craftpy.project;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExamplePackagingTest {
    @TempDir Path root;
    @Test void packagingIsDeterministicAndIncludesLayouts() throws Exception {
        Path first=root.resolve("first.cpm"), second=root.resolve("second.cpm");
        ModuleBuilder.build(Path.of("examples/task_journal"),first); ModuleBuilder.build(Path.of("examples/task_journal"),second);
        assertArrayEquals(Files.readAllBytes(first),Files.readAllBytes(second));
        try(var zip=new java.util.zip.ZipFile(first.toFile())) {
            assertNotNull(zip.getEntry("module.json")); assertNotNull(zip.getEntry("ui/journal.json"));
            assertNotNull(zip.getEntry("code/main.py")); assertNotNull(zip.getEntry("README.md"));
            assertTrue(zip.stream().noneMatch(e -> e.getName().contains("__pycache__")));
        }
    }
    @Test void catalogHasExactly96CoreAnd24GatedTemplatesWithCompleteCompanions() {
        var all=BuildingBlocks.all(); assertEquals(120,all.size());
        assertEquals(96,all.stream().filter(b->b.mods().isEmpty()).count());
        assertEquals(24,all.stream().filter(b->!b.mods().isEmpty()).count());
        for(var block:all) {
            assertEquals(block.mods().isEmpty() && block.capabilities().isEmpty(),block.available(java.util.Set.of(),java.util.Set.of()));
            block.files("example", "demo").forEach((path,text)-> {
                assertFalse(path.contains("{{")); assertFalse(text.contains("{{")); assertTrue(path.startsWith("ui/") || path.startsWith("assets/"));
                assertNotNull(com.google.gson.JsonParser.parseString(text));
            });
        }
        var create=all.stream().filter(b->b.file().equals("integrations/create_motor.py")).findFirst().orElseThrow();
        assertFalse(create.available(java.util.Set.of("create"),java.util.Set.of()));
        assertTrue(create.available(java.util.Set.of("create"),java.util.Set.of("rotation.create")));
    }
}
