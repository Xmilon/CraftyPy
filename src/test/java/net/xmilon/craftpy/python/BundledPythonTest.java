package net.xmilon.craftpy.python;

import net.xmilon.craftpy.util.TarGz;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BundledPythonTest {

    @Test
    void platformNames() {
        assertEquals("x86_64-pc-windows-msvc", BundledPython.platform("Windows 11", "amd64"));
        assertEquals("x86_64-unknown-linux-gnu", BundledPython.platform("Linux", "amd64"));
        assertEquals("aarch64-unknown-linux-gnu", BundledPython.platform("Linux", "aarch64"));
        assertEquals("aarch64-apple-darwin", BundledPython.platform("Mac OS X", "aarch64"));
        assertEquals("x86_64-apple-darwin", BundledPython.platform("Mac OS X", "x86_64"));
        assertNull(BundledPython.platform("Windows 10", "x86"));
        assertNull(BundledPython.platform("FreeBSD", "amd64"));
    }

    /** The real archive of this computer (after gradlew downloadPython): it unpacks and the Python in it runs. */
    @Test
    void theShippedArchiveUnpacksAndRuns(@TempDir Path dir) throws Exception {
        String platform = BundledPython.platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        Path archive = Path.of("build/python-runtimes", platform + ".tar.gz");
        assumeTrue(platform != null && Files.isRegularFile(archive), "no downloaded runtime for this computer");
        try (InputStream in = Files.newInputStream(archive)) {
            TarGz.extract(in, dir, 1);
        }
        Path exe = dir.resolve(platform.contains("windows") ? "python.exe" : "bin/python3");
        assertTrue(Files.isRegularFile(exe), exe.toString());
        Process process = new ProcessBuilder(exe.toString(), "-c", "import ssl, sqlite3, json; print('ok', __import__('sys').version_info[:2])")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), output);
        assertEquals("ok (3, 13)", output);
    }

    /** Another platform's archive (links in it) still unpacks completely on this computer. */
    @Test
    void aLinuxArchiveUnpacksHereToo(@TempDir Path dir) throws Exception {
        Path archive = Path.of("build/python-runtimes/x86_64-unknown-linux-gnu.tar.gz");
        assumeTrue(Files.isRegularFile(archive), "not downloaded");
        try (InputStream in = Files.newInputStream(archive)) {
            TarGz.extract(in, dir, 1);
        }
        assertTrue(Files.isRegularFile(dir.resolve("bin/python3.13")));
        assertTrue(Files.exists(dir.resolve("bin/python3")));
        assertTrue(Files.isRegularFile(dir.resolve("lib/python3.13/json/__init__.py")));
    }
}
