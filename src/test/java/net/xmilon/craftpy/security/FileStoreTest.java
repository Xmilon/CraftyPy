package net.xmilon.craftpy.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileStoreTest {
    private Path home;

    @BeforeEach
    void home(@TempDir Path dir) {
        home = dir;
        System.setProperty("craftpy.home", dir.toString());
        PermissionStore.forget();
    }

    @AfterEach
    void clear() {
        System.clearProperty("craftpy.home");
        PermissionStore.forget();
    }

    @Test
    void writeReadListDeleteInsideTheOwnFolder() throws IOException {
        FileStore.write("notes", "day1.txt", "hello");
        FileStore.write("notes", "sub/day2.txt", "world");
        assertEquals("hello", FileStore.read("notes", "day1.txt", null));
        assertTrue(FileStore.exists("notes", "sub/day2.txt", null));
        assertEquals(List.of("day1.txt", "sub/day2.txt"), FileStore.list("notes", null));
        FileStore.write("notes", "day1.txt", "changed");
        assertEquals("changed", FileStore.read("notes", "day1.txt", null));
        assertTrue(FileStore.delete("notes", "day1.txt"));
        assertFalse(FileStore.exists("notes", "day1.txt", null));
        assertTrue(Files.isRegularFile(home.resolve("pyforge/data/notes/sub/day2.txt")));
    }

    @Test
    void everyOwnerHasItsOwnFolder() throws IOException {
        FileStore.write("a", "x.txt", "from a");
        FileStore.write("module:a", "x.txt", "from module a");
        assertEquals("from a", FileStore.read("a", "x.txt", null));
        assertEquals("from module a", FileStore.read("module:a", "x.txt", null));
        assertThrows(IOException.class, () -> FileStore.read("b", "x.txt", null));
    }

    @Test
    void namesThatLeaveTheFolderOrBreakTheSystemAreRefused() throws IOException {
        FileStore.write("a", "ok.txt", "x");
        for (String bad : new String[]{"../secret.txt", "a/../../x", "/etc/passwd", "C:/Windows/x", "C:\\x", "x:stream", ".hidden", "a/.", "con",
                "nul.txt", "x.", "x ", "", "a//b", "a/b/c/d/e.txt", "x".repeat(200), "bad|name", "new\nline"}) {
            assertThrows(IllegalArgumentException.class, () -> FileStore.cleanName(bad), bad);
            assertThrows(RuntimeException.class, () -> FileStore.write("a", bad, "x"), bad);
        }
        assertFalse(Files.exists(home.resolve("pyforge/data/secret.txt")));
    }

    @Test
    void limitsOnFileSizeAndCount() throws IOException {
        int maxKb = AccessConfig.get(AccessConfig.FILES_MAX_FILE_KB);
        assertThrows(IOException.class, () -> FileStore.write("big", "huge.txt", "x".repeat(maxKb * 1024 + 1)));
        FileStore.write("big", "ok.txt", "x".repeat(maxKb * 1024));
        // the quota counts what is there already
        int quotaMb = AccessConfig.get(AccessConfig.FILES_QUOTA_MB);
        int files = quotaMb * 1024 / maxKb + 2;
        IOException full = assertThrows(IOException.class, () -> {
            for (int i = 0; i < files; i++) {
                FileStore.write("big", "f" + i + ".txt", "x".repeat(maxKb * 1024));
            }
        });
        assertTrue(full.getMessage().contains("full"), full.getMessage());
    }

    @Test
    void linksThatLeadOutAreNotFollowed() throws IOException {
        FileStore.write("l", "ok.txt", "inside");
        Path outside = Files.writeString(home.resolve("outside.txt"), "secret");
        Path link = home.resolve("pyforge/data/l/escape.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException noRight) {
            assumeTrue(false, "this computer does not allow creating links here");
        }
        assertThrows(IOException.class, () -> FileStore.read("l", "escape.txt", null));
        assertThrows(IOException.class, () -> FileStore.write("l", "escape.txt", "overwritten"));
        assertEquals("secret", Files.readString(outside));
        assertEquals(List.of("ok.txt"), FileStore.list("l", null));
    }

    @Test
    void sharedFoldersMustBeListedInTheConfig() {
        IOException error = assertThrows(IOException.class, () -> FileStore.read("a", "x.txt", "music"));
        assertTrue(error.getMessage().contains("no shared folder"), error.getMessage());
    }

    @Test
    void permissionsDecideBeforeAnyOfThisRuns() {
        PermissionStore.declare("module:f", new ModulePermissions(List.of(), true));
        assertFalse(PermissionStore.filesAllowed("module:f"));
        PermissionStore.approve("module:f", new ModulePermissions(List.of(), true));
        assertTrue(PermissionStore.filesAllowed("module:f"));
        assertFalse(PermissionStore.filesAllowed("module:other"));
    }
}
