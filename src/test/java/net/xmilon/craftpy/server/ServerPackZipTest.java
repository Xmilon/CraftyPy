package net.xmilon.craftpy.server;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.xmilon.craftpy.client.ServerPack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPackZipTest {
    @TempDir
    Path dir;

    private Path pack() throws Exception {
        Path root = dir.resolve("pack");
        Files.createDirectories(root.resolve("assets/ruby/textures/item"));
        Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"x\"}}");
        Files.write(root.resolve("assets/ruby/textures/item/ruby.png"), new byte[] { 1, 2, 3 });
        Files.writeString(root.resolve("assets/ruby/lang/en_us.json").getParent().resolve("en_us.json"), "{}",
                java.nio.file.StandardOpenOption.CREATE_NEW).toString();
        return root;
    }

    @Test
    void sameFilesGiveTheSameZip() throws Exception {
        Files.createDirectories(dir.resolve("pack/assets/ruby/lang"));
        Path root = pack();
        ServerPackZip.Snapshot first = ServerPackZip.zip(root);
        Files.setLastModifiedTime(root.resolve("pack.mcmeta"), java.nio.file.attribute.FileTime.fromMillis(12345));
        ServerPackZip.Snapshot second = ServerPackZip.zip(root);
        assertArrayEquals(first.zip(), second.zip());
        assertEquals(first.sha256(), second.sha256());
        Files.write(root.resolve("assets/ruby/textures/item/ruby.png"), new byte[] { 9 });
        assertNotEquals(first.sha256(), ServerPackZip.zip(root).sha256());
        assertNull(ServerPackZip.zip(dir.resolve("missing")));
    }

    @Test
    void unpackedPackKeepsOnlyResources() throws Exception {
        Files.createDirectories(dir.resolve("pack/assets/ruby/lang"));
        Path root = pack();
        Path out = dir.resolve("out");
        ServerPack.extract(ServerPackZip.zip(root).zip(), out);
        assertTrue(Files.isRegularFile(out.resolve("pack.mcmeta")));
        assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(out.resolve("assets/ruby/textures/item/ruby.png")));
    }

    @Test
    void harmfulEntriesAreRefusedAndNothingIsLeft() throws Exception {
        Path out = dir.resolve("out");
        for (String name : new String[] { "assets/../../evil.png", "code/main.py", "assets/x/run.exe", "assets/x/a.py",
                "/assets/x/a.png", "assets/x\\..\\..\\b.png", "C:/assets/a.png" }) {
            byte[] zip = zipOf(name);
            assertThrows(java.io.IOException.class, () -> ServerPack.extract(zip, out), name);
            assertTrue(Files.notExists(out), name);
            assertTrue(Files.notExists(dir.resolve("evil.png")), name);
        }
    }

    private static byte[] zipOf(String name) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(name));
            zip.write(new byte[] { 1 });
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
