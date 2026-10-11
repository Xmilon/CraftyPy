package net.xmilon.craftpy.python;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.project.PyForgePaths;

/**
 * High-level facade over the CPython worker. All blocking operations run on the
 * shared worker pool so the game thread is never blocked. The worker is shared
 * across projects (requests are processed sequentially by CPython).
 */
public final class PyRuntime {
    private static final long DEFAULT_TIMEOUT_MS = 20_000;

    private static PyRuntime instance;

    private final Path workerScript;
    private RpcClient client;

    private PyRuntime() {
        this.workerScript = PyForgePaths.runtime().resolve("worker.py");
    }

    public static synchronized PyRuntime get() {
        if (instance == null) {
            instance = new PyRuntime();
            instance.ensureWorkerScript();
        }
        return instance;
    }

    public static synchronized boolean available() {
        return PythonDiscovery.pythonExecutable() != null;
    }

    private synchronized RpcClient client() {
        if (client == null) {
            RpcClient created = new RpcClient(pythonRequired(), workerScript,
                    PyForgePaths.logs().resolve("python_worker.log"));
            created.start();
            client = created;
        }
        return client;
    }

    private Path pythonRequired() {
        Path python = PythonDiscovery.pythonExecutable();
        if (python == null) {
            throw new IllegalStateException(
                    "No supported Python found. Install CPython 3.13+ or set a path in pyforge config (pythonPath)."
                            + " Auto-download is not bundled with this build.");
        }
        return python;
    }

    /** Copies worker.py from the classpath into pyforge/runtime/, refreshing it
     *  whenever the bundled script differs (so mod updates ship new worker code). */
    public void ensureWorkerScript() {
        try {
            Files.createDirectories(workerScript.getParent());
            byte[] bundled;
            try (InputStream in = PyRuntime.class.getResourceAsStream("/craftpy_runtime/worker.py")) {
                if (in == null) {
                    CraftPy.LOGGER.error("worker.py missing from mod resources");
                    return;
                }
                bundled = in.readAllBytes();
            }
            if (Files.isRegularFile(workerScript)) {
                byte[] existing = Files.readAllBytes(workerScript);
                if (java.util.Arrays.equals(existing, bundled)) {
                    return;
                }
            }
            Files.write(workerScript, bundled);
            CraftPy.LOGGER.info("Installed worker.py ({} bytes) at {}", bundled.length, workerScript);
        } catch (IOException e) {
            CraftPy.LOGGER.error("Failed to install worker.py", e);
        }
    }

    /**
     * Pings the worker and returns its version string, or an error message.
     */
    public CompletableFuture<String> ping() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject result = client().call("ping", new JsonObject(), DEFAULT_TIMEOUT_MS);
                return "Python " + result.get("version").getAsString() + " OK";
            } catch (Exception e) {
                CraftPy.LOGGER.error("Python ping failed", e);
                return "Python unavailable: " + e.getMessage();
            }
        }, net.xmilon.craftpy.util.CraftPyExecutors.pool());
    }

    /** Runs compile() over the supplied source files on the worker. */
    public CompletableFuture<List<ValidationProblem>> validateProject(String projectId, List<String> pyFiles,
            List<String> contents) {
        return CompletableFuture.supplyAsync(() -> {
            List<ValidationProblem> problems = new ArrayList<>();
            int count = Math.min(pyFiles.size(), contents.size());
            for (int i = 0; i < count; i++) {
                try {
                    JsonObject params = new JsonObject();
                    params.addProperty("file", pyFiles.get(i));
                    params.addProperty("code", contents.get(i));
                    JsonObject result = client().call("validate", params, DEFAULT_TIMEOUT_MS);
                    JsonArray raw = result.getAsJsonArray("problems");
                    for (JsonElement element : raw) {
                        JsonObject problem = element.getAsJsonObject();
                        problems.add(new ValidationProblem(
                                problem.get("file").getAsString(),
                                problem.get("line").getAsInt(),
                                problem.get("column").getAsInt(),
                                problem.get("severity").getAsString(),
                                problem.get("message").getAsString()));
                    }
                } catch (Exception e) {
                    problems.add(ValidationProblem.asError("Worker failed for " + pyFiles.get(i) + ": " + e.getMessage()));
                }
            }
            return problems;
        }, net.xmilon.craftpy.util.CraftPyExecutors.pool());
    }

    /** Runs main.py on the worker and collects declarations. */
    public CompletableFuture<Declarations> runMain(String projectId, String code, String fileName) {
        return runMain(projectId, code, fileName, false);
    }

    /** Runs main.py on the worker and collects declarations; debug adds a trace. */
    public CompletableFuture<Declarations> runMain(String projectId, String code, String fileName, boolean debug) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject params = new JsonObject();
            params.addProperty("file", fileName);
            params.addProperty("code", code == null ? "" : code);
            params.addProperty("project", projectId);
            params.addProperty("debug", debug);
            try {
                JsonObject result = client().call("run_main", params, DEFAULT_TIMEOUT_MS);
                return Declarations.parse(result, fileName);
            } catch (Exception e) {
                CraftPy.LOGGER.error("run_main failed for {}", projectId, e);
                return Declarations.error("Worker failed: " + e.getMessage());
            }
        }, net.xmilon.craftpy.util.CraftPyExecutors.pool());
    }

    /** Evaluates a console line in the persistent REPL namespace on the worker. */
    public CompletableFuture<EvalResult> evaluate(String projectId, String code) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject params = new JsonObject();
            params.addProperty("code", code == null ? "" : code);
            try {
                JsonObject result = client().call("eval", params, DEFAULT_TIMEOUT_MS);
                List<String> output = new ArrayList<>();
                if (result.has("output") && result.get("output").isJsonArray()) {
                    for (JsonElement element : result.getAsJsonArray("output")) {
                        output.add(element.getAsString());
                    }
                }
                String value = result.has("value") && !result.get("value").isJsonNull()
                        ? result.get("value").getAsString() : null;
                String error = null;
                int errorLine = -1;
                if (result.has("error") && !result.get("error").isJsonNull()) {
                    JsonObject err = result.getAsJsonObject("error");
                    error = err.has("message") ? err.get("message").getAsString() : "error";
                    errorLine = err.has("line") ? err.get("line").getAsInt() : -1;
                }
                return new EvalResult(output, value, error, errorLine);
            } catch (Exception e) {
                return new EvalResult(List.of(), null, "Worker failed: " + e.getMessage(), -1);
            }
        }, net.xmilon.craftpy.util.CraftPyExecutors.pool());
    }

    /** Clears the worker's persistent REPL namespace. */
    public CompletableFuture<Void> resetConsole() {
        return CompletableFuture.runAsync(() -> {
            try {
                client().call("reset_console", new JsonObject(), DEFAULT_TIMEOUT_MS);
            } catch (Exception e) {
                CraftPy.LOGGER.warn("reset_console failed: {}", e.getMessage());
            }
        }, net.xmilon.craftpy.util.CraftPyExecutors.pool());
    }

    public record EvalResult(List<String> output, String value, String error, int errorLine) {
    }

    public static void shutdown() {
        PyRuntime current = instance;
        instance = null;
        if (current != null && current.client != null) {
            current.client.stop();
            current.client = null;
        }
    }
}