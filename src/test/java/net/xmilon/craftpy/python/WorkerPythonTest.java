package net.xmilon.craftpy.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Runs src/test/python/test_worker.py (the worker's Python API tests); skipped when no Python is on PATH. */
class WorkerPythonTest {

    @Test
    void workerApiTestsPass() throws Exception {
        Path script = Path.of("src", "test", "python", "test_worker.py");
        assumeTrue(Files.isRegularFile(script), "test script not found");
        String python = findPython();
        assumeTrue(python != null, "no python on PATH");

        Process process = new ProcessBuilder(python, script.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "python test timed out");
        assertEquals(0, process.exitValue(), output);
    }

    private static String findPython() {
        for (String candidate : new String[] { "python", "python3", "py" }) {
            try {
                Process probe = new ProcessBuilder(candidate, "-c", "import sys; assert sys.version_info >= (3, 10)")
                        .redirectErrorStream(true).start();
                probe.getInputStream().readAllBytes();
                if (probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // try the next name
            }
        }
        return null;
    }
}
