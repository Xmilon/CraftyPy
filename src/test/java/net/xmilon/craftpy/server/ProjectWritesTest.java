package net.xmilon.craftpy.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.xmilon.craftpy.project.ProjectManager;

class ProjectWritesTest {
    private static ProjectManager manager(Path dir) throws Exception {
        Constructor<ProjectManager> constructor = ProjectManager.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        return constructor.newInstance(dir);
    }

    @Test
    void aSaveNeverRecreatesAMissingProject(@TempDir Path dir) throws Exception {
        ProjectManager manager = manager(dir);
        assertFalse(manager.writeBinary("gone", "assets/textures/a.png", new byte[]{1}).get().success());
        assertFalse(manager.writeFile("gone", "code/main.py", "x").get().success());
        assertFalse(Files.exists(dir.resolve("gone")));
    }

    @Test
    void binaryWriteReplacesTheFileAndLeavesNoTempFile(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("demo/assets"));
        ProjectManager manager = manager(dir);
        assertTrue(manager.writeBinary("demo", "assets/textures/a.png", new byte[]{1, 2, 3}).get().success());
        assertTrue(manager.writeBinary("demo", "assets/textures/a.png", new byte[]{4, 5}).get().success());
        assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(dir.resolve("demo/assets/textures/a.png")));
        assertFalse(Files.exists(dir.resolve("demo/build/upload.tmp")));
    }

    @Test
    void previewLimitCoversNormalSoundsAndStillRefusesHugeFiles(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("demo/assets/sounds"));
        Files.write(dir.resolve("demo/assets/sounds/ok.wav"), new byte[100 * 1024]);
        Files.write(dir.resolve("demo/assets/sounds/big.wav"), new byte[ProjectManager.BINARY_READ_LIMIT + 1]);
        ProjectManager manager = manager(dir);
        assertTrue(manager.readBinary("demo", "assets/sounds/ok.wav").get().success());
        var big = manager.readBinary("demo", "assets/sounds/big.wav").get();
        assertFalse(big.success());
        assertTrue(big.message().contains("too large"));
        // the limit must fit one response: base64 grows by 4/3 and ResponsePayload allows 400000 characters
        assertTrue(ProjectManager.BINARY_READ_LIMIT / 3 * 4 < 400_000);
    }

    @Test
    void pathsWithADriveOrStreamAreRefused(@TempDir Path dir) {
        assertNull(ProjectManager.resolveSafe(dir, "assets/a.png:stream"));
        assertNull(ProjectManager.resolveSafe(dir, "C:/x.png"));
        assertEquals(dir.resolve("assets").resolve("a.png"), ProjectManager.resolveSafe(dir, "assets/a.png"));
    }
}
