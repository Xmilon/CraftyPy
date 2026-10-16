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

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.util.CraftPyExecutors;

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
    private final java.util.concurrent.atomic.AtomicInteger scheduledTasks = new java.util.concurrent.atomic.AtomicInteger();

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

    /** Loads a persistent module from the given directory. */
    public CompletableFuture<JsonObject> loadModule(String id, Path dir) {
        return CraftPyExecutors.supplyAsync(() -> {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("id", id);
                params.addProperty("dir", dir.toAbsolutePath().toString());
                JsonObject result = client().call("load_module", params, DEFAULT_TIMEOUT_MS);
                noteSchedule(result);
                return result;
            } catch (Exception e) {
                CraftPyLog.LOGGER.error("Failed to load Python module: " + id, e);
                return new JsonObject();
            }
        }, CraftPyExecutors.rpc());
    }


    /** Sends an interrupt request to the worker to stop running code. */
    public CompletableFuture<Void> interrupt() {
        return CraftPyExecutors.runAsync(() -> {
            try {
                client().call("interrupt", new JsonObject(), 500);
            } catch (Exception e) {
                CraftPyLog.LOGGER.warn("Interrupt failed: {}", e.getMessage());
            }
        }, CraftPyExecutors.rpc());
    }

    /**
     * Drops everything a project or module registered in the worker (handlers, timers, declarations),
     * so a stopped project stops reacting to events. {@code module} selects the module owner namespace.
     */
    public CompletableFuture<Void> unloadOwner(String id, boolean module) {
        return CraftPyExecutors.runAsync(() -> {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("owner", (module ? "module:" : "") + id);
                noteSchedule(client().call("unload_owner", params, DEFAULT_TIMEOUT_MS));
            } catch (Exception e) {
                CraftPyLog.LOGGER.warn("Failed to unload Python owner {}: {}", id, e.toString());
            }
        }, CraftPyExecutors.rpc());
    }

    /** Calls a Python function the script handed to Java (see the Java bridge). Completes with {value} or {error}. */
    public CompletableFuture<JsonObject> callFunction(int functionId, com.google.gson.JsonArray args) {
        return CraftPyExecutors.supplyAsync(() -> {
            JsonObject params = new JsonObject();
            params.addProperty("id", functionId);
            params.add("args", args);
            try {
                return client().call("call_fn", params, 5000);
            } catch (Exception e) {
                JsonObject failure = new JsonObject();
                failure.addProperty("error", String.valueOf(e.getMessage()));
                return failure;
            }
        }, CraftPyExecutors.rpc());
    }

    /** Puts back what the project registered before its last run (the host could not apply the new version). */
    public CompletableFuture<Void> revert(String projectId) {
        return CraftPyExecutors.supplyAsync(() -> {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("owner", projectId);
                client().call("revert_owner", params, DEFAULT_TIMEOUT_MS);
            } catch (Exception e) {
                CraftPyLog.LOGGER.warn("Failed to revert Python owner {}: {}", projectId, e.toString());
            }
            return null;
        }, CraftPyExecutors.rpc());
    }

    /** Editor assist: runs a read-only worker query ("complete" or "hover") with a short timeout. */
    public CompletableFuture<JsonObject> assist(String method, String code, int line, int column) {
        return assist(method, code, line, column, null);
    }

    /** As above; {@code sources} (path to text of the project's other .py files) lets it follow imports between files. */
    public CompletableFuture<JsonObject> assist(String method, String code, int line, int column, JsonObject sources) {
        return CraftPyExecutors.supplyAsync(() -> {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("code", code);
                params.addProperty("line", line);
                params.addProperty("column", column);
                if (sources != null) {
                    params.add("sources", sources);
                }
                return client().call(method, params, 2000);
            } catch (Exception e) {
                return new JsonObject();
            }
        }, CraftPyExecutors.rpc());
    }

    /** Handler profiler data (call counts and timings per handler / timer); {@code reset} clears it afterwards. */
    public CompletableFuture<JsonObject> profile(boolean reset) {
        return CraftPyExecutors.supplyAsync(() -> {
            try {
                JsonObject result = client().call("profile", new JsonObject(), DEFAULT_TIMEOUT_MS);
                if (reset) {
                    client().call("profile_reset", new JsonObject(), DEFAULT_TIMEOUT_MS);
                }
                return result;
            } catch (Exception e) {
                JsonObject failure = new JsonObject();
                failure.addProperty("error", String.valueOf(e.getMessage()));
                return failure;
            }
        }, CraftPyExecutors.rpc());
    }

    /** Triggers a registered Python event handler. */
    public CompletableFuture<JsonObject> triggerEvent(String eventName, JsonObject context) {
        return net.xmilon.craftpy.util.CraftPyExecutors.supplyAsync(() -> {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("event", eventName);
                params.add("context", context);
                JsonObject result = client().call("trigger_event", params, DEFAULT_TIMEOUT_MS);
                noteSchedule(result);
                return result;
            } catch (Exception e) {
                CraftPyLog.LOGGER.error("Failed to trigger Python event: " + eventName, e);
                return new JsonObject();
            }
        }, net.xmilon.craftpy.util.CraftPyExecutors.rpc());
    }

    private synchronized RpcClient client() {
        if (client == null) {
            RpcClient created = new RpcClient(pythonRequired(), workerScript,
                    PyForgePaths.logs().resolve("python_worker.log"));
            created.setHostHandler(HostBridge::handle);
            created.start();
            client = created;
        }
        return client;
    }

    /** True while the worker has pending scheduler tasks (drives tick events). */
    public boolean hasScheduledTasks() {
        return scheduledTasks.get() > 0;
    }

    private void noteSchedule(JsonObject result) {
        if (result != null && result.has("scheduled") && result.get("scheduled").isJsonPrimitive()) {
            scheduledTasks.set(result.get("scheduled").getAsInt());
        }
    }

    /** Delivers a server tick to the worker so scheduled tasks can run. */
    public CompletableFuture<Void> tick(long tick) {
        return CraftPyExecutors.runAsync(() -> {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("tick", tick);
                JsonObject result = client().call("tick", params, 5000);
                noteSchedule(result);
                if (result != null && result.has("log") && result.get("log").isJsonArray()) {
                    var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                    for (var line : result.getAsJsonArray("log")) {
                        CraftPyLog.LOGGER.info("[Python timer] {}", line.getAsString());
                        net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(server, "[timer] " + line.getAsString());
                    }
                }
            } catch (Exception e) {
                scheduledTasks.set(0);
            }
        }, CraftPyExecutors.rpc());
    }

    private Path pythonRequired() {
        Path python = PythonDiscovery.pythonExecutable();
        if (python == null) {
            throw new IllegalStateException(
                    "No supported Python found. Install CPython 3.13+ (the craftpy-installer does it with --install-python)"
                            + " or set a path in pyforge config (pythonPath).");
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
                    CraftPyLog.LOGGER.error("worker.py missing from mod resources");
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
            CraftPyLog.LOGGER.info("Installed worker.py ({} bytes) at {}", bundled.length, workerScript);
        } catch (IOException e) {
            CraftPyLog.LOGGER.error("Failed to install worker.py", e);
        }
    }

    /**
     * Pings the worker and returns its version string, or an error message.
     */
    public CompletableFuture<String> ping() {
        return CraftPyExecutors.supplyAsync(() -> {
            try {
                JsonObject result = client().call("ping", new JsonObject(), DEFAULT_TIMEOUT_MS);
                return "Python " + result.get("version").getAsString() + " OK";
            } catch (Exception e) {
                CraftPyLog.LOGGER.error("Python ping failed", e);
                return "Python unavailable: " + e.getMessage();
            }
        }, CraftPyExecutors.rpc());
    }

    /** Runs compile() over the supplied source files on the worker. */
    public CompletableFuture<List<ValidationProblem>> validateProject(String projectId, List<String> pyFiles,
            List<String> contents) {
        return CraftPyExecutors.supplyAsync(() -> {
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
        }, CraftPyExecutors.rpc());
    }

    /** Runs main.py on the worker and collects declarations. */
    public CompletableFuture<Declarations> runMain(String projectId, String code, String fileName) {
        return runMain(projectId, code, fileName, false);
    }

    /** Runs main.py on the worker and collects declarations; debug adds a trace. */
    public CompletableFuture<Declarations> runMain(String projectId, String code, String fileName, boolean debug) {
        return runMain(projectId, code, fileName, debug, java.util.Map.of());
    }

    /** As above, with the project's other code files so main.py can import them. */
    public CompletableFuture<Declarations> runMain(String projectId, String code, String fileName, boolean debug,
                                                   java.util.Map<String, String> sources) {
        return CraftPyExecutors.supplyAsync(() -> {
            JsonObject params = new JsonObject();
            JsonObject files = new JsonObject();
            sources.forEach(files::addProperty);
            params.add("sources", files);
            params.addProperty("file", fileName);
            params.addProperty("code", code == null ? "" : code);
            params.addProperty("project", projectId);
            params.addProperty("dir", projectId == null ? "" : PyForgePaths.projects().resolve(projectId).toString());
            params.addProperty("debug", debug);
            try {
                JsonObject result = client().call("run_main", params, DEFAULT_TIMEOUT_MS);
                noteSchedule(result);
                return Declarations.parse(result, fileName);
            } catch (Exception e) {
                CraftPyLog.LOGGER.error("run_main failed for {}", projectId, e);
                return Declarations.error("Worker failed: " + e.getMessage());
            }
        }, CraftPyExecutors.rpc());
    }

    /** Evaluates a console line in the persistent REPL namespace on the worker. */
    public CompletableFuture<EvalResult> evaluate(String projectId, String code, String session) {
        return CraftPyExecutors.supplyAsync(() -> {
            JsonObject params = new JsonObject();
            params.addProperty("code", code == null ? "" : code);
            params.addProperty("session", session == null ? "default" : session);
            if (projectId != null) {
                params.addProperty("project", projectId);
            }
            try {
                JsonObject result = client().call("eval", params, DEFAULT_TIMEOUT_MS);
                noteSchedule(result);
                List<String> output = new ArrayList<>();
                if (result.has("output") && result.get("output").isJsonArray()) {
                    for (com.google.gson.JsonElement element : result.getAsJsonArray("output")) {
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
        }, CraftPyExecutors.rpc());
    }

    /** Clears the worker's persistent REPL namespace. */
    public CompletableFuture<Void> resetConsole(String session) {
        return CraftPyExecutors.runAsync(() -> {
            try {
                JsonObject params = new JsonObject();
                if (session != null) params.addProperty("session", session);
                client().call("reset_console", params, DEFAULT_TIMEOUT_MS);
            } catch (Exception e) {
                CraftPyLog.LOGGER.warn("reset_console failed: {}", e.getMessage());
            }
        }, CraftPyExecutors.rpc());
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