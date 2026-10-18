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
    /** Written by the installer when the player chose not to install CPython ({@code pyforge/system/no_bundled_python}). */
    public static final String OPT_OUT = "no_bundled_python";
    /** The executable whose files were verified in this game start. */
    private static Path checked;

    private BundledPython() {
    }

    /** The bundled Python of this computer, unpacked when needed; null when the jar has none for it. */
    public static synchronized Path executable() {
        if (Files.exists(PyForgePaths.system().resolve(OPT_OUT))) {
            return null; // the player said no to CPython in the installer: a Python of this computer is used
        }
        String platform = platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        Properties index = index();
        if (platform == null || index == null || index.getProperty(platform + ".sha256") == null) {
            return null;
        }
        String version = index.getProperty("version", "unknown");
        Path home = PyForgePaths.system().resolve("python");
        Path dir = home.resolve(platform + "-" + version.replace('+', '-'));
        Path exe = dir.resolve(platform.contains("windows") ? "python.exe" : "bin/python3");
        if (exe.equals(checked) && Files.isRegularFile(exe)) {
            return exe; // checked once per game start: every worker start asks for it
        }
        if (Files.isRegularFile(exe)) {
            String problem = verify(exe);
            if (problem == null) {
                checked = exe;
                return exe;
            }
            CraftPyLog.LOGGER.warn("The unpacked Python in {} {}: unpacking it again", dir, problem);
            deleteTree(dir);
            if (Files.exists(dir)) {
                if (problem.startsWith(UNCHECKED)) {
                    return exe; // unpacked by an older CraftPy and in use by another process: checked next time
                }
                CraftPyLog.LOGGER.error("The changed Python in {} could not be replaced (in use?): not starting it", dir);
                return null;
            }
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
            Path exe = temp.resolve(platform.contains("windows") ? "python.exe" : "bin/python3");
            Files.writeString(temp.resolve(MANIFEST), manifest(exe));
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

    /** Next to the unpacked runtime: the SHA-256 of the files the interpreter starts from, written at unpack time. */
    static final String MANIFEST = ".craftpy-files.sha256";
    private static final String UNCHECKED = "has no list of its files";

    /**
     * The files every start loads: the executable and the other files of its folder (on Windows python3XX.dll and the
     * runtime DLLs sit there), one {@code <sha256> <name>} line each. Links are skipped (bin/python3 points to
     * python3.13, which is listed itself).
     */
    static String manifest(Path exe) throws IOException {
        StringBuilder out = new StringBuilder();
        try (var files = Files.list(exe.getParent())) {
            for (Path file : files.filter(f -> Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !f.getFileName().toString().equals(MANIFEST)).sorted().toList()) {
                out.append(sha256(file)).append(' ').append(file.getFileName()).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Null when the files of the unpacked runtime are still the ones it was unpacked with; otherwise what is wrong. A
     * changed python.exe or DLL would run with the game's rights for every project, so it is not started.
     */
    static String verify(Path exe) {
        Path dir = exe.getParent();
        Path root = dir.getFileName() != null && dir.getFileName().toString().equals("bin") ? dir.getParent() : dir;
        Path list = root.resolve(MANIFEST);
        if (!Files.isRegularFile(list)) {
            return UNCHECKED;
        }
        try {
            String expected = Files.readString(list);
            return expected.equals(manifest(exe)) ? null : "was changed since it was unpacked";
        } catch (IOException e) {
            return "could not be checked (" + e.getMessage() + ")";
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
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
