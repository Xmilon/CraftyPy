package net.xmilon.craftpy.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Unpacks a {@code .tar.gz} (ustar, GNU long names, pax paths): enough for the Python runtimes CraftPy ships. Java has
 * no tar reader and the game has no library for one. Entries that would land outside {@code target} are refused.
 */
public final class TarGz {
    private static final int BLOCK = 512;

    private TarGz() {
    }

    /**
     * {@code strip} leading path parts are dropped ({@code python/bin/python3} with 1 is {@code bin/python3}). The stream
     * stays open: the caller may read the rest (a hash over the whole file).
     */
    public static void extract(InputStream compressed, Path target, int strip) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        List<String[]> links = new ArrayList<>(); // made after the files, so their targets exist
        InputStream keepOpen = new java.io.FilterInputStream(compressed) {
            @Override
            public void close() {
            }
        };
        try (InputStream in = new GZIPInputStream(keepOpen, 1 << 16)) {
            byte[] header = new byte[BLOCK];
            String longName = null;
            String longLink = null;
            while (readFully(in, header)) {
                if (isZero(header)) {
                    break;
                }
                String name = longName != null ? longName : name(header);
                String linkName = longLink != null ? longLink : text(header, 157, 100);
                longName = null;
                longLink = null;
                long size = number(header, 124, 12);
                char type = (char) header[156];
                if (type == 'L' || type == 'K' || type == 'x') {
                    byte[] data = readData(in, size);
                    if (type == 'L') {
                        longName = cString(data);
                    } else if (type == 'K') {
                        longLink = cString(data);
                    } else {
                        String path = pax(data, "path");
                        String link = pax(data, "linkpath");
                        longName = path;
                        longLink = link;
                    }
                    continue;
                }
                if (type == 'g') { // global pax header: nothing CraftPy needs
                    skip(in, padded(size));
                    continue;
                }
                Path out = resolve(root, name, strip);
                if (out == null) {
                    skip(in, padded(size));
                    continue;
                }
                int mode = (int) number(header, 100, 8);
                switch (type) {
                    case '5' -> Files.createDirectories(out);
                    case '2', '1' -> {
                        links.add(new String[]{out.toString(), linkName, String.valueOf(type)});
                        skip(in, padded(size));
                    }
                    case '0', '\0', '7' -> {
                        Files.createDirectories(out.getParent());
                        try (var file = Files.newOutputStream(out)) {
                            copy(in, file, size);
                        }
                        skip(in, padded(size) - size);
                        permissions(out, mode);
                    }
                    default -> skip(in, padded(size)); // devices, fifos: not in a runtime
                }
            }
        }
        for (String[] link : links) {
            Path out = Path.of(link[0]);
            Path source = link[2].equals("1") ? resolve(root, link[1], strip) : out.getParent().resolve(link[1]).normalize();
            if (source == null || !source.startsWith(root)) {
                continue; // a link pointing outside the folder is dropped
            }
            Files.createDirectories(out.getParent());
            Files.deleteIfExists(out);
            try {
                if (link[2].equals("2")) {
                    Files.createSymbolicLink(out, Path.of(link[1]));
                } else {
                    Files.copy(source, out);
                }
            } catch (IOException | UnsupportedOperationException noLinks) {
                if (Files.isRegularFile(source)) { // Windows without the right to make links: a copy works too
                    Files.copy(source, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static Path resolve(Path root, String name, int strip) {
        String clean = name.replace('\\', '/');
        while (clean.startsWith("./")) {
            clean = clean.substring(2);
        }
        String[] parts = clean.split("/");
        if (parts.length <= strip) {
            return null;
        }
        StringBuilder rest = new StringBuilder();
        for (int i = strip; i < parts.length; i++) {
            if (parts[i].isEmpty() || parts[i].equals(".")) {
                continue;
            }
            rest.append(rest.length() == 0 ? "" : "/").append(parts[i]);
        }
        if (rest.length() == 0 || clean.startsWith("/") || clean.contains(":")) {
            return null;
        }
        Path out = root.resolve(rest.toString()).normalize();
        return out.startsWith(root) && !out.equals(root) ? out : null;
    }

    private static void permissions(Path file, int mode) {
        if ((mode & 0111) == 0) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0010) != 0) {
                perms.add(PosixFilePermission.GROUP_EXECUTE);
            }
            if ((mode & 0001) != 0) {
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
            }
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException windows) {
            // no POSIX permissions (Windows): every file can run
        }
    }

    private static String name(byte[] header) {
        String name = text(header, 0, 100);
        boolean ustar = text(header, 257, 5).equals("ustar");
        String prefix = ustar ? text(header, 345, 155) : "";
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String text(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String cString(byte[] data) {
        int end = 0;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    /** Octal text, or GNU base-256 for big files (first byte has the high bit set). */
    static long number(byte[] header, int offset, int length) {
        if ((header[offset] & 0x80) != 0) {
            long value = header[offset] & 0x7f;
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (header[offset + i] & 0xff);
            }
            return value;
        }
        String digits = text(header, offset, length).trim();
        return digits.isEmpty() ? 0 : Long.parseLong(digits, 8);
    }

    /** A pax record list: {@code "<len> key=value\n"} repeated. */
    static String pax(byte[] data, String key) {
        String all = new String(data, StandardCharsets.UTF_8);
        int at = 0;
        while (at < all.length()) {
            int space = all.indexOf(' ', at);
            if (space < 0) {
                break;
            }
            int length;
            try {
                length = Integer.parseInt(all.substring(at, space));
            } catch (NumberFormatException broken) {
                break;
            }
            // the length counts bytes; the records CraftPy reads are ASCII paths
            String record = all.substring(space + 1, Math.min(all.length(), at + length)).stripTrailing();
            if (record.startsWith(key + "=")) {
                return record.substring(key.length() + 1);
            }
            at += Math.max(length, 1);
        }
        return null;
    }

    private static long padded(long size) {
        return (size + BLOCK - 1) / BLOCK * BLOCK;
    }

    private static boolean isZero(byte[] header) {
        for (byte b : header) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = in.readNBytes(buffer, 0, buffer.length);
        if (read == 0) {
            return false;
        }
        if (read < buffer.length) {
            throw new IOException("Truncated tar archive");
        }
        return true;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > 1 << 20) {
            throw new IOException("Tar header record too large");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) size);
        copy(in, out, size);
        skip(in, padded(size) - size);
        return out.toByteArray();
    }

    private static void copy(InputStream in, java.io.OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[1 << 16];
        long left = size;
        while (left > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                throw new IOException("Truncated tar archive");
            }
            out.write(buffer, 0, read);
            left -= read;
        }
    }

    private static void skip(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("Truncated tar archive");
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }
}
