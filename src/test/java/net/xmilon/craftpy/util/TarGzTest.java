package net.xmilon.craftpy.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TarGzTest {

    @Test
    void unpacksFilesFoldersLongNamesAndLinks(@TempDir Path dir) throws IOException {
        String deep = "python/lib/" + "a".repeat(120) + "/mod.py";
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        entry(tar, "python/", '5', "", new byte[0]);
        entry(tar, "python/bin/python3.13", '0', "", "binary".getBytes(StandardCharsets.UTF_8));
        entry(tar, "python/bin/python3", '2', "python3.13", new byte[0]);
        entry(tar, "././@LongLink", 'L', "", (deep + "\0").getBytes(StandardCharsets.UTF_8));
        entry(tar, "ignored", '0', "", "x = 1".getBytes(StandardCharsets.UTF_8));
        String pax = "path=python/lib/pax_named.txt\n";
        String record = (pax.length() + 3) + " " + pax; // the length counts itself
        entry(tar, "PaxHeader", 'x', "", record.getBytes(StandardCharsets.UTF_8));
        entry(tar, "ignored-too", '0', "", "pax".getBytes(StandardCharsets.UTF_8));
        entry(tar, "python/../../evil.txt", '0', "", "no".getBytes(StandardCharsets.UTF_8));
        entry(tar, "python/bin/escape", '2', "../../../outside", new byte[0]);
        tar.write(new byte[1024]);

        TarGz.extract(new ByteArrayInputStream(gzip(tar.toByteArray())), dir.resolve("out"), 1);
        Path out = dir.resolve("out");
        assertEquals("binary", Files.readString(out.resolve("bin/python3.13")));
        assertEquals("binary", Files.readString(out.resolve("bin/python3"))); // a link, or a copy where links are not allowed
        assertEquals("x = 1", Files.readString(out.resolve(deep.substring("python/".length()))));
        assertEquals("pax", Files.readString(out.resolve("lib/pax_named.txt")));
        assertFalse(Files.exists(dir.resolve("evil.txt")));
        assertFalse(Files.exists(out.resolve("bin/escape")));
    }

    @Test
    void theCallersStreamStaysOpenForTheHash(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        entry(tar, "python/a.txt", '0', "", "a".getBytes(StandardCharsets.UTF_8));
        tar.write(new byte[1024]);
        boolean[] closed = {false};
        var in = new java.io.FilterInputStream(new ByteArrayInputStream(gzip(tar.toByteArray()))) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        TarGz.extract(in, dir, 1);
        assertFalse(closed[0]);
        in.transferTo(java.io.OutputStream.nullOutputStream()); // BundledPython hashes the rest of the file
    }

    @Test
    void namesOutsideTheFolderAreRefused(@TempDir Path dir) {
        assertNull(TarGz.resolve(dir, "python/../../x", 1));
        assertNull(TarGz.resolve(dir, "/etc/passwd", 0));
        assertNull(TarGz.resolve(dir, "C:/Windows/x", 0));
        assertNull(TarGz.resolve(dir, "python/", 1));
        assertTrue(TarGz.resolve(dir, "./python/bin/x", 1).endsWith(Path.of("bin", "x")));
    }

    @Test
    void bigSizesUseBase256() {
        byte[] header = new byte[512];
        header[124] = (byte) 0x80;
        header[135] = 1;
        header[134] = 2; // 0x0201
        assertEquals(0x0201, TarGz.number(header, 124, 12));
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static void entry(ByteArrayOutputStream tar, String name, char type, String link, byte[] data) throws IOException {
        byte[] header = new byte[512];
        put(header, 0, name.length() > 100 ? name.substring(0, 100) : name);
        put(header, 100, "0000755");
        put(header, 108, "0000000");
        put(header, 116, "0000000");
        put(header, 124, String.format("%011o", data.length));
        put(header, 136, "00000000000");
        header[156] = (byte) type;
        put(header, 157, link);
        put(header, 257, "ustar");
        put(header, 263, "00");
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        put(header, 148, String.format("%06o", sum));
        header[154] = 0;
        tar.write(header);
        tar.write(data);
        int pad = (512 - data.length % 512) % 512;
        tar.write(new byte[pad]);
    }

    private static void put(byte[] header, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }
}
