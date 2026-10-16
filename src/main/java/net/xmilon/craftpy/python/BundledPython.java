package net.xmilon.craftpy.python;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.util.TarGz;

/**
 * The Python that ships inside the mod jar ({@code craftpy_python/<platform>.tar.gz}, from python-build-standalone,
 * pinned and hash-checked by the build). On first use the archive of this computer is unpacked to
 * {@code pyforge/system/python/<platform>-<version>/}; later starts find it there. Players install nothing.
 */
public final class BundledPython {
    private static final String INDEX = "/craftpy_python/runtimes.properties";

    private BundledPython() {
    }

    /** The bundled Python of this computer, unpacked when needed; null when the jar has none for it. */
    public static synchronized Path executable() {
        String platform = platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        Properties index = index();
        if (platform == null || index == null || index.getProperty(platform + ".sha256") == null) {
            return null;
        }
        String version = index.getProperty("version", "unknown");
        Path home = PyForgePaths.system().resolve("python");
        Path dir = home.resolve(platform + "-" + version.replace('+', '-'));
        Path exe = dir.resolve(platform.contains("windows") ? "python.exe" : "bin/python3");
        if (Files.isRegularFile(exe)) {
            return exe;
        }
        try {
            unpack(platform, index.getProperty(platform + ".sha256"), home, dir);
        } catch (IOException | RuntimeException e) {
            CraftPyLog.LOGGER.error("Could not unpack the bundled Python to {}", dir, e);
            return null;
        }
        cleanOld(home, dir);
        return Files.isRegularFile(exe) ? exe : null;
    }

    /** Unpacks into a fresh folder and renames it into place, so a half-written runtime is never used. */
    private static void unpack(String platform, String sha256, Path home, Path dir) throws IOException {
        String resource = "/craftpy_python/" + platform + ".tar.gz";
        Path temp = home.resolve(dir.getFileName() + ".unpacking-" + ProcessHandle.current().pid());
        deleteTree(temp);
        long started = System.currentTimeMillis();
        try (InputStream raw = BundledPython.class.getResourceAsStream(resource)) {
            if (raw == null) {
                throw new IOException(resource + " is missing from the jar");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream in = new DigestInputStream(raw, digest)) {
                TarGz.extract(in, temp, 1); // the archives hold one python/ folder
                in.transferTo(java.io.OutputStream.nullOutputStream()); // the hash covers the whole file
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(sha256)) {
                throw new IOException("bundled Python " + platform + " is damaged (sha256 " + actual + ")");
            }
            try {
                Files.move(temp, dir);
            } catch (java.nio.file.FileAlreadyExistsException other) {
                // another CraftPy (the standalone IDE next to the game) unpacked it first
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            deleteTree(temp);
        }
        CraftPyLog.LOGGER.info("Unpacked the bundled Python ({}) to {} in {} ms", platform, dir,
                System.currentTimeMillis() - started);
    }

    /** Runtimes of older CraftPy versions; one still in use (Windows locks it) stays until next time. */
    private static void cleanOld(Path home, Path keep) {
        try (var entries = Files.list(home)) {
            for (Path old : entries.toList()) {
                if (!old.equals(keep) && Files.isDirectory(old) && !old.getFileName().toString().contains(".unpacking-")) {
                    deleteTree(old);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ignored) {
            // a file in use: left for the next start
        }
    }

    private static Properties index() {
        try (InputStream in = BundledPython.class.getResourceAsStream(INDEX)) {
            if (in == null) {
                return null; // a build without bundled Python (dev runs, -Pcraftpy.bundlePython=false)
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        } catch (IOException e) {
            return null;
        }
    }

    /** The python-build-standalone name of this computer, or null when none is shipped for it. */
    static String platform(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.startsWith("windows")) {
            return x64 ? "x86_64-pc-windows-msvc" : null;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return x64 ? "x86_64-apple-darwin" : arm64 ? "aarch64-apple-darwin" : null;
        }
        if (os.contains("linux")) {
            return x64 ? "x86_64-unknown-linux-gnu" : arm64 ? "aarch64-unknown-linux-gnu" : null;
        }
        return null;
    }
}
