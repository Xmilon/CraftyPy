package net.xmilon.craftpy.client.assets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import net.xmilon.craftpy.python.PythonDiscovery;
import net.xmilon.craftpy.util.CraftPyExecutors;

/**
 * Turns a WAV into Ogg Vorbis, which is the only format Minecraft plays and which Java cannot write. It runs a short-lived
 * Python on the player's own PC (not the server's worker: that one is sandboxed and busy with game events) with the
 * {@code soundfile} package, whose wheels carry a Vorbis encoder. The package is installed only when the player asks.
 */
public final class OggEncoder {
    public enum Status {
        OK,
        /** Python runs but the soundfile package is not installed. */
        MISSING_MODULE,
        NO_PYTHON,
        FAILED
    }

    /** {@code ogg} is set only for {@link Status#OK}; {@code message} explains everything else. */
    public record Result(Status status, byte[] ogg, String message) {
    }

    static final String SCRIPT = "import soundfile as sf, sys\n"
            + "data, rate = sf.read(sys.argv[1])\n"
            + "sf.write(sys.argv[2], data, rate, format='OGG', subtype='VORBIS')\n";

    private static final int TIMEOUT_SECONDS = 90;

    private OggEncoder() {
    }

    /** Converts on a background thread; the future never fails, look at {@link Result#status()}. */
    public static CompletableFuture<Result> encode(byte[] wav) {
        return CraftPyExecutors.supplyAsync(() -> encodeNow(PythonDiscovery.pythonExecutable(), wav), CraftPyExecutors.io());
    }

    /** The same on this thread, with a given Python (null: none found). */
    public static Result encodeNow(Path python, byte[] wav) {
        if (python == null) {
            return new Result(Status.NO_PYTHON, null, "No Python 3.13+ found. Install it or set pythonPath in the CraftPy config.");
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("craftpy-ogg");
            Path in = dir.resolve("in.wav");
            Path out = dir.resolve("out.ogg");
            Files.write(in, wav);
            Run run = run(command(python, in, out));
            Result failed = classify(run.exit(), run.output());
            if (failed != null) {
                return failed;
            }
            return new Result(Status.OK, Files.readAllBytes(out), "");
        } catch (IOException e) {
            return new Result(Status.FAILED, null, "Could not run Python: " + e.getMessage());
        } finally {
            deleteQuietly(dir);
        }
    }

    /** {@code pip install --user soundfile}; run it only after the player agreed. */
    public static CompletableFuture<Result> install() {
        return CraftPyExecutors.supplyAsync(() -> {
            Path python = PythonDiscovery.pythonExecutable();
            if (python == null) {
                return new Result(Status.NO_PYTHON, null, "No Python 3.13+ found.");
            }
            try {
                Run run = run(installCommand(python));
                return run.exit() == 0 ? new Result(Status.OK, null, "")
                        : new Result(Status.FAILED, null, lastLine(run.output(), "pip failed"));
            } catch (IOException e) {
                return new Result(Status.FAILED, null, "Could not run pip: " + e.getMessage());
            }
        }, CraftPyExecutors.io());
    }

    static List<String> command(Path python, Path in, Path out) {
        return List.of(python.toString(), "-c", SCRIPT, in.toString(), out.toString());
    }

    static List<String> installCommand(Path python) {
        return List.of(python.toString(), "-m", "pip", "install", "--user", "soundfile");
    }

    /** Null when the run succeeded, otherwise the failure to show. */
    static Result classify(int exitCode, String output) {
        if (exitCode == 0) {
            return null;
        }
        if (output.contains("ModuleNotFoundError") || output.contains("No module named")) {
            return new Result(Status.MISSING_MODULE, null, "The Python package soundfile is not installed.");
        }
        return new Result(Status.FAILED, null, lastLine(output, "Python exited with code " + exitCode));
    }

    static String lastLine(String output, String fallback) {
        String last = null;
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                last = line.trim();
            }
        }
        return last == null ? fallback : last;
    }

    private record Run(int exit, String output) {
    }

    private static Run run(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        // Read on this thread while the process runs: an unread pipe would block pip's output and hang the wait.
        final StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try {
                output.append(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // the process ended; whatever was read is enough
            }
        }, "craftpy-ogg-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Run(-1, "Timed out after " + TIMEOUT_SECONDS + " s");
            }
            reader.join(2000);
            return new Run(process.exitValue(), output.toString());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Run(-1, "Interrupted");
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // a temp file left behind is harmless
        }
    }
}
