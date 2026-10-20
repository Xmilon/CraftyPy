package net.xmilon.craftpy.python;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.project.ModuleLoader;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.util.CraftPyExecutors;

/**
 * High-level facade over the CPython workers. All blocking operations run on the shared worker pool so the game
 * thread is never blocked.
 * <p>
 * Every owner (a project {@code <id>}, a module {@code module:<id>}) runs in its OWN worker process. The host knows who
 * a worker is because it started it, so a script cannot speak as another owner, read another owner's memory or reach
 * another owner's folders, whatever it does inside its own interpreter. Each worker can read only its own code folder
 * and the folders of the modules it imports (which are loaded into it as libraries). Editor tools (completion,
 * validation) use one more worker that reads nothing.
 */
public final class PyRuntime {
    private static final long DEFAULT_TIMEOUT_MS = 20_000;
    /** The worker of the editor tools: completion, hover, validation, ping. No project code runs in it. */
    static final String TOOLS = "__tools__";
    /** The worker of console lines typed without a project. */
    static final String CONSOLE = "__console__";

    private static PyRuntime instance;
    /** False from the world's shutdown until the next start: no worker is started meanwhile, so none is left behind. */
    private static volatile boolean open = true;

    private final Path workerScript;
    private final Map<String, Worker> workers = new ConcurrentHashMap<>();
    /** Player name + window id -> the owner that opened it, so a click only reaches that owner's worker. */
    private final Map<String, String> windowOwners = new ConcurrentHashMap<>();

    /** One owner's worker process and what the host knows of it. */
    private static final class Worker {
        final String owner;
        final RpcClient rpc;
        final List<Path> roots;
        volatile Set<String> events = Set.of();
        volatile int scheduled;

        Worker(String owner, RpcClient rpc, List<Path> roots) {
            this.owner = owner;
            this.rpc = rpc;
            this.roots = roots;
        }
    }

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

    /** The owner string of a project ({@code module = false}) or module. */
    public static String owner(String id, boolean module) {
        return (module ? "module:" : "") + id;
    }

    // ------------------------------------------------------------------
    // Workers
    // ------------------------------------------------------------------

    /** The folder an owner's code lives in, or null for the tools and console workers. */
    private static Path codeDir(String owner) {
        if (owner.startsWith("module:")) {
            return PyForgePaths.cache().resolve(owner.substring("module:".length()));
        }
        if (owner.equals(TOOLS) || owner.equals(CONSOLE)) {
            return null;
        }
        return PyForgePaths.projects().resolve(owner);
    }

    /** The modules an owner imports (dependencies first): they are loaded into its worker as libraries. */
    private static List<String> librariesOf(String owner) {
        List<String> direct;
        if (owner.startsWith("module:")) {
            direct = ModuleLoader.dependenciesOf(owner.substring("module:".length()));
        } else {
            var manager = net.xmilon.craftpy.project.ProjectManager.get();
            var meta = manager == null || owner.equals(TOOLS) || owner.equals(CONSOLE) ? null : manager.metadataOf(owner);
            direct = meta == null || meta.dependencies == null ? List.of() : meta.dependencies;
        }
        List<String> libraries = new ArrayList<>(ModuleLoader.libraries(direct));
        if (owner.startsWith("module:")) {
            libraries.remove(owner.substring("module:".length()));
        }
        return libraries;
    }

    /** The only folders an owner's worker may read (besides Python itself and worker.py). */
    private static List<Path> readRoots(String owner, List<String> libraries) {
        List<Path> roots = new ArrayList<>();
        Path own = codeDir(owner);
        if (own != null) {
            roots.add(own.toAbsolutePath().normalize());
        }
        for (String library : libraries) {
            roots.add(PyForgePaths.cache().resolve(library).toAbsolutePath().normalize());
        }
        return List.copyOf(roots);
    }

    /**
     * The owner's worker, started on first use. A worker started with other read roots (its dependencies changed) is
     * replaced, since a running worker cannot be given new folders.
     */
    private Worker worker(String owner) {
        return worker(owner, readRoots(owner, librariesOf(owner)));
    }

    private synchronized Worker worker(String owner, List<Path> roots) {
        Worker current = workers.get(owner);
        if (current != null && current.roots.equals(roots)) {
            return current;
        }
        if (current != null) {
            current.rpc.stop();
        }
        if (!open) {
            throw new IllegalStateException("CraftPy is stopping: no Python worker is started");
        }
        String logName = "python_worker_" + owner.replaceAll("[^a-z0-9_]", "_") + ".log";
        Path python = pythonRequired();
        RpcClient rpc = new RpcClient(python, script(python), PyForgePaths.logs().resolve(logName));
        StringBuilder joined = new StringBuilder();
        for (Path root : roots) {
            if (!joined.isEmpty()) {
                joined.append(java.io.File.pathSeparatorChar);
            }
            joined.append(root);
        }
        rpc.setEnvironment(Map.of("CRAFTPY_READ_ROOTS", joined.toString(), "CRAFTPY_OWNER", owner));
        // the worker starts in the runtime folder, not the owner's: on Windows a process's folder cannot be deleted
        // every reverse call is answered as the owner this process was started for, whatever the script sends
        rpc.setHostHandler((method, params) -> HostBridge.handle(owner, method, params));
        rpc.start();
        Worker created = new Worker(owner, rpc, roots);
        workers.put(owner, created);
        return created;
    }

    /** The owner's worker if it runs, else null (nothing to tell a worker that was never started). */
    private Worker existing(String owner) {
        return workers.get(owner);
    }

    private List<Worker> running() {
        return List.copyOf(workers.values());
    }

    private void stopWorker(String owner) {
        Worker removed;
        synchronized (this) {
            removed = workers.remove(owner);
        }
        if (removed != null) {
            removed.rpc.stop();
        }
        windowOwners.values().removeIf(owner::equals);
    }

    /** Loads the owner's libraries into its worker: each module's names, without its handlers (they run in its own worker). */
    private void loadLibraries(Worker worker, List<String> libraries) throws IOException {
        for (String library : libraries) {
            JsonObject params = new JsonObject();
            params.addProperty("id", library);
            params.addProperty("dir", PyForgePaths.cache().resolve(library).toAbsolutePath().toString());
            params.addProperty("library", true);
            JsonObject result = worker.rpc.call("load_module", params, DEFAULT_TIMEOUT_MS);
            if (result.has("error") && result.get("error").isJsonPrimitive()) {
                CraftPyLog.LOGGER.warn("Library {} could not be loaded for {}: {}", library, worker.owner,
                        result.get("error").getAsString());
            }
        }
    }

    /** Remembers what a reply says about the worker: pending timers and the events it has handlers for. */
    private static JsonObject note(Worker worker, JsonObject result) {
        if (result == null) {
            return null;
        }
        if (result.has("scheduled") && result.get("scheduled").isJsonPrimitive()) {
            worker.scheduled = result.get("scheduled").getAsInt();
        }
        if (result.has("events") && result.get("events").isJsonArray()) {
            Set<String> events = new HashSet<>();
            result.getAsJsonArray("events").forEach(e -> events.add(e.getAsString()));
            worker.events = Set.copyOf(events);
            result.remove("events");
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Projects and modules
    // ------------------------------------------------------------------

    /** Loads a persistent module from the given directory into its own worker. */
    public CompletableFuture<JsonObject> loadModule(String id, Path dir) {
        return CraftPyExecutors.supplyAsync(() -> {
            String owner = owner(id, true);
            try {
                List<String> libraries = librariesOf(owner);
                Worker worker = worker(owner, readRoots(owner, libraries));
                loadLibraries(worker, libraries);
                JsonObject params = new JsonObject();
                params.addProperty("id", id);
                params.addProperty("dir", dir.toAbsolutePath().toString());
                JsonObject result = note(worker, worker.rpc.call("load_module", params, DEFAULT_TIMEOUT_MS));
                if (result.has("idle") && result.get("idle").getAsBoolean()) {
                    // content only (blocks, items, recipes): nothing can call it again, so its process is not kept;
                    // events and calls skip an owner without a worker, and a reload starts a new one
                    stopWorker(owner);
                    CraftPyLog.LOGGER.info("Module {} only declares content: its Python worker was stopped", id);
                }
                return result;
            } catch (Exception e) {
                CraftPyLog.LOGGER.error("Failed to load Python module: " + id, e);
                return new JsonObject();
            }
        }, CraftPyExecutors.rpc());
    }

    /** Sends an interrupt request to every worker to stop running code. */
    public CompletableFuture<Void> interrupt() {
        return CraftPyExecutors.runAsync(() -> {
            for (Worker worker : running()) {
                try {
                    worker.rpc.call("interrupt", new JsonObject(), 500);
                } catch (Exception e) {
                    CraftPyLog.LOGGER.warn("Interrupt of {} failed: {}", worker.owner, e.getMessage());
                }
            }
        }, CraftPyExecutors.rpc());
    }

    /**
     * Stops a project's or module's worker: its handlers, timers and windows end with the process. {@code module}
     * selects the module owner namespace.
     */
    public CompletableFuture<Void> unloadOwner(String id, boolean module) {
        return CraftPyExecutors.runAsync(() -> stopWorker(owner(id, module)), CraftPyExecutors.rpc());
    }

    /** Calls a Python function {@code owner}'s script handed to Java (see the Java bridge). Completes with {value} or {error}. */
    public CompletableFuture<JsonObject> callFunction(String owner, int functionId, JsonArray args) {
        return CraftPyExecutors.supplyAsync(() -> {
            JsonObject params = new JsonObject();
            params.addProperty("id", functionId);
            params.add("args", args);
            Worker worker = existing(owner);
            if (worker == null) {
                JsonObject failure = new JsonObject();
                failure.addProperty("error", "that Python function was unloaded");
                return failure;
            }
            try {
                return worker.rpc.call("call_fn", params, 5000);
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
            Worker worker = existing(owner(projectId, false));
            if (worker == null) {
                return null;
            }
            try {
                JsonObject params = new JsonObject();
                params.addProperty("owner", projectId);
                note(worker, worker.rpc.call("revert_owner", params, DEFAULT_TIMEOUT_MS));
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
                return worker(TOOLS).rpc.call(method, params, 2000);
            } catch (Exception e) {
                return new JsonObject();
            }
        }, CraftPyExecutors.rpc());
    }

    /** A read-only analysis on the tools worker (no project code runs there), e.g. {@code window_list}. */
    public CompletableFuture<JsonObject> tool(String method, JsonObject params) {
        return CraftPyExecutors.supplyAsync(() -> {
            try {
                return worker(TOOLS).rpc.call(method, params, DEFAULT_TIMEOUT_MS);
            } catch (Exception e) {
                JsonObject failure = new JsonObject();
                failure.addProperty("error", String.valueOf(e.getMessage()));
                return failure;
            }
        }, CraftPyExecutors.rpc());
    }

    /** Handler profiler data (call counts and timings per handler / timer) of every worker; {@code reset} clears it. */
    public CompletableFuture<JsonObject> profile(boolean reset) {
        return CraftPyExecutors.supplyAsync(() -> {
            List<JsonObject> rows = new ArrayList<>();
            try {
                for (Worker worker : running()) {
                    JsonObject result = worker.rpc.call("profile", new JsonObject(), DEFAULT_TIMEOUT_MS);
                    if (result.has("rows") && result.get("rows").isJsonArray()) {
                        result.getAsJsonArray("rows").forEach(row -> rows.add(row.getAsJsonObject()));
                    }
                    if (reset) {
                        worker.rpc.call("profile_reset", new JsonObject(), DEFAULT_TIMEOUT_MS);
                    }
                }
            } catch (Exception e) {
                JsonObject failure = new JsonObject();
                failure.addProperty("error", String.valueOf(e.getMessage()));
                return failure;
            }
            rows.sort((a, b) -> Double.compare(number(b, "total_ms"), number(a, "total_ms")));
            JsonArray out = new JsonArray();
            rows.stream().limit(200).forEach(out::add);
            JsonObject result = new JsonObject();
            result.add("rows", out);
            return result;
        }, CraftPyExecutors.rpc());
    }

    private static double number(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsDouble() : 0;
    }

    /**
     * Runs the {@code test*} functions of the project's {@code code/test_*.py} files ({@code file} only when not blank).
     * The reply has {@code results}: file, name, line, ok, message, ms per test.
     */
    public CompletableFuture<JsonObject> runTests(String projectId, JsonObject sources, String file) {
        return CraftPyExecutors.supplyAsync(() -> {
            JsonObject params = new JsonObject();
            params.addProperty("project", projectId);
            params.add("sources", sources == null ? new JsonObject() : sources);
            params.addProperty("file", file == null ? "" : file);
            try {
                String owner = owner(projectId, false);
                List<String> libraries = librariesOf(owner);
                Worker worker = worker(owner, readRoots(owner, libraries));
                loadLibraries(worker, libraries);
                return note(worker, worker.rpc.call("run_tests", params, 60_000));
            } catch (Exception e) {
                JsonObject failure = new JsonObject();
                failure.addProperty("error", String.valueOf(e.getMessage()));
                return failure;
            }
        }, CraftPyExecutors.rpc());
    }

    /** The debugger: {@code cmd} set / hits / clear with the project's breakpoints; see {@code _debug} in worker.py. */
    public CompletableFuture<JsonObject> debug(JsonObject params) {
        return CraftPyExecutors.supplyAsync(() -> {
            try {
                String project = params.has("project") ? params.get("project").getAsString() : "";
                if (project.isBlank()) {
                    throw new IOException("no project");
                }
                return worker(owner(project, false)).rpc.call("debug", params, DEFAULT_TIMEOUT_MS);
            } catch (Exception e) {
                JsonObject failure = new JsonObject();
                failure.addProperty("error", String.valueOf(e.getMessage()));
                return failure;
            }
        }, CraftPyExecutors.rpc());
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /** A window {@code owner} opened for {@code player}: its clicks go to that owner's worker only. */
    void noteWindow(String owner, String player, String windowId) {
        windowOwners.put(player + "|" + windowId, owner);
    }

    /**
     * Triggers a Python event in every worker with a handler for it, one after the other: a later worker sees the
     * damage or state an earlier one set, and the replies are merged (logs and results joined, any cancel cancels).
     */
    public CompletableFuture<JsonObject> triggerEvent(String eventName, JsonObject context) {
        return CraftPyExecutors.supplyAsync(() -> {
            List<Worker> targets = new ArrayList<>();
            if ("http_response".equals(eventName)) {
                String owner = context.has("owner") ? context.get("owner").getAsString() : "";
                Worker worker = existing(owner);
                if (worker != null) {
                    targets.add(worker);
                }
            } else if ("ui_event".equals(eventName)) {
                String player = context.has("player") && context.get("player").isJsonObject()
                        && context.getAsJsonObject("player").has("name")
                        ? context.getAsJsonObject("player").get("name").getAsString() : "";
                String window = context.has("window") ? context.get("window").getAsString() : "";
                String owner = windowOwners.get(player + "|" + window);
                Worker worker = owner == null ? null : existing(owner);
                if (worker != null) {
                    targets.add(worker);
                }
            } else {
                for (Worker worker : running()) {
                    if (worker.events.contains(eventName)) {
                        targets.add(worker);
                    }
                }
            }
            JsonObject merged = new JsonObject();
            JsonArray log = new JsonArray();
            JsonArray results = new JsonArray();
            int handlers = 0;
            double ms = 0;
            JsonObject current = context;
            for (Worker worker : targets) {
                try {
                    JsonObject params = new JsonObject();
                    params.addProperty("event", eventName);
                    params.add("context", current);
                    JsonObject result = note(worker, worker.rpc.call("trigger_event", params, DEFAULT_TIMEOUT_MS));
                    if (result == null) {
                        continue;
                    }
                    if (result.has("log") && result.get("log").isJsonArray()) {
                        log.addAll(result.getAsJsonArray("log"));
                    }
                    if (result.has("results") && result.get("results").isJsonArray()) {
                        results.addAll(result.getAsJsonArray("results"));
                    }
                    handlers += result.has("handlers") ? result.get("handlers").getAsInt() : 0;
                    ms += number(result, "ms");
                    for (String key : List.of("cancel", "damage", "state", "global_state")) {
                        if (result.has(key)) {
                            merged.add(key, result.get(key));
                        }
                    }
                    if (result.has("damage") || result.has("state") || result.has("global_state")) {
                        current = current.deepCopy(); // the next worker sees what this one changed
                        for (String key : List.of("damage", "state", "global_state")) {
                            if (result.has(key)) {
                                current.add(key, result.get(key));
                            }
                        }
                    }
                } catch (Exception e) {
                    CraftPyLog.LOGGER.error("Failed to trigger Python event {} in {}", eventName, worker.owner, e);
                }
            }
            merged.add("log", log);
            merged.add("results", results);
            merged.addProperty("handlers", handlers);
            merged.addProperty("ms", ms);
            merged.addProperty("scheduled", running().stream().mapToInt(w -> w.scheduled).sum());
            return merged;
        }, CraftPyExecutors.rpc());
    }

    /** True while a worker has pending scheduler tasks (drives tick events). */
    public boolean hasScheduledTasks() {
        for (Worker worker : workers.values()) {
            if (worker.scheduled > 0) {
                return true;
            }
        }
        return false;
    }

    /** Delivers a server tick to every worker with pending timers. */
    public CompletableFuture<Void> tick(long tick) {
        return CraftPyExecutors.runAsync(() -> {
            for (Worker worker : running()) {
                if (worker.scheduled <= 0) {
                    continue;
                }
                try {
                    JsonObject params = new JsonObject();
                    params.addProperty("tick", tick);
                    JsonObject result = note(worker, worker.rpc.call("tick", params, 5000));
                    if (result != null && result.has("log") && result.get("log").isJsonArray()) {
                        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                        for (var line : result.getAsJsonArray("log")) {
                            CraftPyLog.LOGGER.info("[Python timer] {}", line.getAsString());
                            net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(server, "[timer] " + line.getAsString());
                        }
                    }
                } catch (Exception e) {
                    worker.scheduled = 0;
                }
            }
        }, CraftPyExecutors.rpc());
    }

    private Path pythonRequired() {
        Path python = PythonDiscovery.pythonExecutable();
        if (python == null) {
            throw new IllegalStateException(
                    "No Python: CraftPy ships one for Windows, macOS and Linux (x64 / ARM64); it could not be unpacked or"
                            + " this computer is not one of those (see the log). Install CPython 3.13+ or set pythonPath in config.");
        }
        return python;
    }

    /** Copies worker.py from the classpath into pyforge/runtime/, refreshing it
     *  whenever the bundled script differs (so mod updates ship new worker code). */
    private Path compiled;
    private Path compiledFor;

    /**
     * The worker to start with {@code python}: worker.py compiled once per game start (worker.pyc next to it). Started
     * from source, every worker would compile its 7000 lines again and keep about 20 MB of that; from the compiled file
     * a worker needs about half. Falls back to worker.py when compiling fails.
     */
    public synchronized Path script(Path python) {
        if (compiled != null && python.equals(compiledFor) && Files.isRegularFile(compiled)) {
            return compiled;
        }
        Path out = workerScript.resolveSibling("worker.pyc");
        try {
            Files.deleteIfExists(out); // never run a file of another start (or another Python version)
            Process process = new ProcessBuilder(python.toString(), "-I", "-B", "-c",
                    "import py_compile, sys; py_compile.compile(sys.argv[1], cfile=sys.argv[2], doraise=True)",
                    workerScript.toString(), out.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0
                    && Files.isRegularFile(out)) {
                compiled = out;
                compiledFor = python;
                return out;
            }
            process.destroyForcibly();
            CraftPyLog.LOGGER.warn("Could not compile worker.py; workers start from the source (more memory each)");
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("Could not compile worker.py: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return workerScript;
    }

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
                JsonObject result = worker(TOOLS).rpc.call("ping", new JsonObject(), DEFAULT_TIMEOUT_MS);
                return "Python " + result.get("version").getAsString() + " OK, CraftPy Python API "
                        + (result.has("api") ? result.get("api").getAsString() : "?");
            } catch (Exception e) {
                CraftPyLog.LOGGER.error("Python ping failed", e);
                return "Python unavailable: " + e.getMessage();
            }
        }, CraftPyExecutors.rpc());
    }

    /** Runs compile() over the supplied source files on the worker. */
    public CompletableFuture<List<ValidationProblem>> validateProject(String projectId, List<String> pyFiles,
            List<String> contents) {
        return validateProject(projectId, "both", pyFiles, contents);
    }

    /** {@code side}: the project's side, so API names of the other side are reported. */
    public CompletableFuture<List<ValidationProblem>> validateProject(String projectId, String side, List<String> pyFiles,
            List<String> contents) {
        return CraftPyExecutors.supplyAsync(() -> {
            List<ValidationProblem> problems = new ArrayList<>();
            int count = Math.min(pyFiles.size(), contents.size());
            for (int i = 0; i < count; i++) {
                try {
                    JsonObject params = new JsonObject();
                    params.addProperty("file", pyFiles.get(i));
                    params.addProperty("code", contents.get(i));
                    params.addProperty("side", side);
                    JsonObject result = worker(TOOLS).rpc.call("validate", params, DEFAULT_TIMEOUT_MS);
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
                String owner = projectId == null ? CONSOLE : owner(projectId, false);
                List<String> libraries = librariesOf(owner);
                Worker worker = worker(owner, readRoots(owner, libraries));
                loadLibraries(worker, libraries); // fresh each run: a module updated since is imported as it is now
                return Declarations.parse(note(worker, worker.rpc.call("run_main", params, DEFAULT_TIMEOUT_MS)), fileName);
            } catch (Exception e) {
                CraftPyLog.LOGGER.error("run_main failed for {}", projectId, e);
                return Declarations.error("Worker failed: " + e.getMessage());
            }
        }, CraftPyExecutors.rpc());
    }

    /** Evaluates a console line in the persistent REPL namespace on the worker. */
    public CompletableFuture<EvalResult> evaluate(String projectId, String code, String session) {
        return evaluate(projectId, code, session, null);
    }

    /** {@code player}: the name of who typed the line (the console's {@code me}); null outside the game. */
    public CompletableFuture<EvalResult> evaluate(String projectId, String code, String session, String player) {
        return CraftPyExecutors.supplyAsync(() -> {
            JsonObject params = new JsonObject();
            params.addProperty("code", code == null ? "" : code);
            params.addProperty("session", session == null ? "default" : session);
            if (projectId != null) {
                params.addProperty("project", projectId);
            }
            if (player != null) {
                params.addProperty("player", player);
            }
            try {
                Worker worker = worker(projectId == null ? CONSOLE : owner(projectId, false));
                JsonObject result = note(worker, worker.rpc.call("eval", params, DEFAULT_TIMEOUT_MS));
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

    /** Clears the persistent REPL namespace in every worker. */
    public CompletableFuture<Void> resetConsole(String session) {
        return CraftPyExecutors.runAsync(() -> {
            for (Worker worker : running()) {
                try {
                    JsonObject params = new JsonObject();
                    if (session != null) params.addProperty("session", session);
                    worker.rpc.call("reset_console", params, DEFAULT_TIMEOUT_MS);
                } catch (Exception e) {
                    CraftPyLog.LOGGER.warn("reset_console failed: {}", e.getMessage());
                }
            }
        }, CraftPyExecutors.rpc());
    }

    /** The owners with a running worker (for the profiler and tests). */
    public Set<String> runningOwners() {
        return new LinkedHashSet<>(workers.keySet());
    }

    public record EvalResult(List<String> output, String value, String error, int errorLine) {
    }

    /** A world (or server) starts: workers may run again. */
    public static void open() {
        open = true;
    }

    /** The world (or server) closes: every worker of it stops, and none starts until {@link #open}. */
    public static void shutdown() {
        open = false;
        PyRuntime current = instance;
        instance = null;
        if (current != null) {
            for (Worker worker : current.running()) {
                worker.rpc.stop();
            }
            current.workers.clear();
        }
    }
}
