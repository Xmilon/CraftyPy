package net.xmilon.craftpy.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.dynamics.PackBuilder;

/**
 * The server's resource pack (textures, models, sounds and names of its projects and modules, never their code or data)
 * as one zip for the players who join from another computer. The same files always give the same bytes, so the hash
 * tells a player whether the copy it kept from the last visit is still right.
 */
public final class ServerPackZip {
    /** The zip and its SHA-256 (hex). */
    public record Snapshot(byte[] zip, String sha256) {
    }

    /** Larger packs are not sent (shortcut: fixed cap, a config option once someone needs more). */
    public static final long MAX_BYTES = 256L * 1024 * 1024;

    private static volatile Snapshot current;

    private ServerPackZip() {
    }

    /** The pack as last built, or null when there is none (no CraftPy content, or too large). */
    public static Snapshot current() {
        return current;
    }

    /** Zips {@link PackBuilder#resourcePackRoot()} again. IO thread. */
    public static synchronized void rebuild() {
        try {
            current = zip(PackBuilder.resourcePackRoot());
        } catch (IOException e) {
            CraftPyLog.LOGGER.error("Could not zip the resource pack for the players", e);
            current = null;
        }
    }

    /** {@code root} as a zip with entries in name order and no times, or null when it is missing or too large. */
    public static Snapshot zip(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .sorted((a, b) -> name(root, a).compareTo(name(root, b)))
                    .toList();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(name(root, file));
                entry.setTime(0L);
                out.putNextEntry(entry);
                Files.copy(file, out);
                out.closeEntry();
                if (bytes.size() > MAX_BYTES) {
                    CraftPyLog.LOGGER.warn("The resource pack for the players is over {} MB; it is not sent",
                            MAX_BYTES / (1024 * 1024));
                    return null;
                }
            }
        }
        byte[] zip = bytes.toByteArray();
        return new Snapshot(zip, sha256(zip));
    }

    private static String name(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
