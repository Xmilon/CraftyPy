package net.xmilon.craftpy.project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleUnpackerTest {

    @AfterEach
    void home() {
        System.clearProperty("craftpy.home");
    }

    @Test
    void namesThatLeaveTheFolderOrAreNotPartOfAModuleAreRefused() {
        assertEquals("code/main.py", ModuleArchive.safeName("code/main.py"));
        assertEquals("assets/textures/a.png", ModuleArchive.safeName("assets\\textures\\a.png"));
        assertEquals("code/x.py", ModuleArchive.safeName("./code/./x.py"));
        assertEquals("README.md", ModuleArchive.safeName("README.md"));
        for (String bad : new String[]{"../mods/evil.jar", "code/../../mods/evil.jar", "/etc/passwd", "C:/Windows/x.dll",
                "code/a.py:hidden", "code/CON", "code/nul.txt", "code/a.py.", "code/a ", "mods/evil.jar", "evil.jar",
                "code/a\0.py", ""}) {
            assertNull(ModuleArchive.safeName(bad), bad);
        }
    }

    @Test
    void aModuleBecomesANewProjectAndNothingElseIsTouched(@TempDir Path dir) throws IOException {
        Path projects = Files.createDirectories(dir.resolve("projects"));
        Files.createDirectories(projects.resolve("gems")); // a project with the module's id already exists
        Files.writeString(projects.resolve("gems/keep.txt"), "mine");
        Path cpm = zip(dir.resolve("gems-1.0.cpm"),
                "module.json", "{\"id\":\"gems\",\"name\":\"Gems\\u0007\\u0000!\",\"version\":\"2.0\",\"apiVersion\":\"2.2\"}",
                "code/main.py", "print('hi')",
                "assets/textures/gem.png", "png",
                "code/run.bat", "format c:",
                "data/gems/function/x.mcfunction", "op @a",
                "../../mods/evil.jar", "jar",
                "code/main.py", "second copy");
        var result = ModuleUnpacker.unpack(cpm, projects, dir.resolve("work"));
        assertEquals("gems_2", result.projectId());
        assertEquals("gems", result.moduleId());
        Path project = projects.resolve("gems_2");
        assertEquals("print('hi')", Files.readString(project.resolve("code/main.py")));
        assertTrue(Files.isRegularFile(project.resolve("assets/textures/gem.png")));
        assertFalse(Files.exists(project.resolve("code/run.bat")));
        assertFalse(Files.exists(project.resolve("data/gems/function/x.mcfunction")));
        assertFalse(Files.exists(dir.resolve("mods/evil.jar")) || Files.exists(project.resolve("module.json")));
        String json = Files.readString(project.resolve("project.json"));
        assertTrue(json.contains("\"id\": \"gems_2\"") && json.contains("\"name\": \"Gems  !\"") && json.contains("\"apiVersion\": \"2.2\""), json);
        assertEquals("mine", Files.readString(projects.resolve("gems/keep.txt")));
        assertEquals(4, result.report().skipped().size(), result.report().skipped().toString());
        try (var left = Files.list(dir.resolve("work"))) {
            assertEquals(0, left.count()); // the work folder was moved, nothing half-written stays
        }
    }

    @Test
    void anIdThatIsNotValidFallsBackToTheFileName(@TempDir Path dir) throws IOException {
        Path projects = Files.createDirectories(dir.resolve("projects"));
        Path cpm = zip(dir.resolve("My Cool-Mod.cpm"), "module.json", "{\"id\":\"../../escape\"}", "code/main.py", "x = 1");
        var result = ModuleUnpacker.unpack(cpm, projects, dir.resolve("work"));
        assertEquals("my_cool_mod", result.projectId());
        assertNull(result.moduleId());
    }

    @Test
    void onlyModulesCraftPyFoundCanBeNamedByPath(@TempDir Path dir) throws IOException {
        System.setProperty("craftpy.home", dir.toString());
        Path modules = Files.createDirectories(dir.resolve("pyforge/modules"));
        Path real = zip(modules.resolve("a.cpm"), "module.json", "{\"id\":\"a\"}");
        Path other = Files.writeString(dir.resolve("secret.txt"), "x");
        assertEquals(real, ModuleLoader.knownModuleFile(real.toString()));
        assertEquals(real, ModuleLoader.knownModuleFile(modules.resolve("../modules/a.cpm").toString()));
        assertNull(ModuleLoader.knownModuleFile(other.toString()));
        assertNull(ModuleLoader.knownModuleFile("\0bad"));
        assertFalse(ModuleLoader.reload("../../pyforge"));
    }

    @Test
    void extractStopsAtTheSizeLimitsUsingRealBytes(@TempDir Path dir) throws IOException {
        Path cpm = dir.resolve("big.cpm");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cpm))) {
            zip.putNextEntry(new ZipEntry("assets/huge.png"));
            byte[] zeros = new byte[1 << 20];
            for (long written = 0; written <= ModuleArchive.MAX_FILE; written += zeros.length) {
                zip.write(zeros); // compresses to almost nothing: a zip bomb in small
            }
            zip.closeEntry();
        }
        try (ZipFile file = new ZipFile(cpm.toFile())) {
            IOException error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                    () -> ModuleArchive.extract(file, dir.resolve("out"), name -> true));
            assertTrue(error.getMessage().contains("larger than"), error.getMessage());
        }
    }

    private static Path zip(Path file, String... entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < entries.length; i += 2) {
                String name = entries[i];
                // ZipOutputStream refuses duplicates; the second copy gets a different spelling the reader normalises
                zip.putNextEntry(new ZipEntry(seen.add(name) ? name : "./" + name));
                zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }
}
