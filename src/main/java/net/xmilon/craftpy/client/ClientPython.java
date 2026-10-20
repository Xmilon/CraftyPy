package net.xmilon.craftpy.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.xmilon.craftpy.Config;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.project.ModuleArchive;
import net.xmilon.craftpy.project.ModuleInfo;
import net.xmilon.craftpy.project.ModuleLoader;
import net.xmilon.craftpy.project.ModuleSide;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.python.PyRuntime;
import net.xmilon.craftpy.python.PythonDiscovery;
import net.xmilon.craftpy.python.RpcClient;

/**
 * The Python of the client: worker processes on the player's computer that run client-side modules and projects
 * (HUD, keys, windows). It is the same worker.py in client mode, answered by {@link ClientHost}, so it can draw and read
 * what the client knows but cannot change the world or reach the server. As on the server, every owner (a project, a
 * {@code module:<id>}) has its own worker, so one module cannot pose as another or read its memory or folders.
 * Everything here runs on one background thread: the game thread only queues work and never waits for Python.
 */
public final class ClientPython {
    private static final ClientPython INSTANCE = new ClientPython();
    private static final int MAX_QUEUED = 200;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "craftpy-client-python");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean tickQueued = new AtomicBoolean();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger tickCounter = new AtomicInteger();
    private final Set<String> loadedModules = new HashSet<>();
    /** Owner -> its worker. Only the executor thread changes it. */
    private final Map<String, ClientWorker> workers = new java.util.concurrent.ConcurrentHashMap<>();
    /** Player window id -> the owner that opened it. */
    private final Map<String, String> windowOwners = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean active;

    private static final class ClientWorker {
        final RpcClient rpc;
        /** False while no timer or client_tick handler exists: then no tick is sent (the worker says so in each reply). */
        volatile boolean ticking = true;
        volatile Set<String> events = Set.of();

        ClientWorker(RpcClient rpc) {
            this.rpc = rpc;
        }
    }
    private String lastProblem = "";
    private long lastProblemAt;

    private ClientPython() {
    }

    public static ClientPython get() {
        return INSTANCE;
    }

    /** True when client-side Python may run: switched on in the config and a Python exists. */
    public boolean available() {
        return Config.get(Config.ENABLE_CLIENT_PYTHON) && Config.get(Config.ENABLE_SCRIPTING)
                && PythonDiscovery.pythonExecutable() != null;
    }

    // -- entry points (any thread) ------------------------------------------------------------------------------------

    /** Runs a client-side project from this computer's pyforge/projects folder; the output goes to the IDE console. */
    public void runProject(String projectId) {
        if (!available()) {
            report(List.of("Client-side Python is off or no Python was found (see the config: enableClientPython)."));
            return;
        }
        submit(() -> {
            Path dir = PyForgePaths.projects().resolve(projectId);
            if (!Files.isDirectory(dir.resolve("code")) || !Files.isRegularFile(dir.resolve("code/main.py"))) {
                report(List.of("Client project '" + projectId + "' is not on this computer (pyforge/projects/" + projectId
                        + "): client projects run from the folder of the game you are playing."));
                return;
            }
            List<String> dependencies = List.of();
            try {
                var meta = net.xmilon.craftpy.util.Json.COMPACT.fromJson(
                        Files.readString(dir.resolve("project.json"), StandardCharsets.UTF_8), net.xmilon.craftpy.project.ProjectMetadata.class);
                if (meta != null) {
                    net.xmilon.craftpy.security.PermissionStore.declareProject(projectId, meta.permissions);
                    dependencies = meta.dependencies == null ? List.of() : meta.dependencies;
                }
            } catch (IOException | RuntimeException unreadable) {
                net.xmilon.craftpy.security.PermissionStore.declareProject(projectId, null);
            }
            ClientWorker worker = worker(projectId, dir, libraries(dependencies));
            JsonObject params = new JsonObject();
            params.addProperty("project", projectId);
            params.addProperty("file", "code/main.py");
            params.addProperty("code", Files.readString(dir.resolve("code/main.py"), StandardCharsets.UTF_8));
            params.addProperty("dir", dir.toAbsolutePath().toString());
            JsonObject files = new JsonObject();
            sourcesOf(dir).forEach(files::addProperty);
            params.add("sources", files);
            JsonObject result = call(worker, "run_main", params, 20_000);
            List<String> lines = new ArrayList<>(logOf(result));
            if (result.has("error") && result.get("error").getAsBoolean()) {
                lines.add("[client] " + projectId + " failed: " + (result.has("message") ? result.get("message").getAsString() : "error")
                        + (result.has("file") ? " (" + result.get("file").getAsString() + ", line "
                        + (result.get("line").getAsInt() + 1) + ")" : ""));
            } else {
                lines.add("[client] " + projectId + " runs on this computer");
            }
            report(lines);
        });
    }

    /** Loads the enabled client modules and unloads the ones that were switched off or deleted. */
    public void loadModules() {
        if (!available()) {
            return;
        }
        submit(() -> {
            Set<String> wanted = new HashSet<>();
            List<ModuleInfo> toLoad = new ArrayList<>();
            clientModules.clear();
            for (ModuleInfo info : ModuleLoader.list()) {
                if (ModuleSide.of(info.side) == ModuleSide.CLIENT && info.enabled && info.primary && info.error == null) {
                    wanted.add(info.id);
                    clientModules.put(info.id, info);
                    if (!loadedModules.contains(info.id)) {
                        toLoad.add(info);
                    }
                }
            }
            for (ModuleInfo info : toLoad) { // all unpacked first: a module's worker loads its dependencies from their folders
                extract(info);
            }
            for (ModuleInfo info : toLoad) {
                loadModule(info);
            }
            for (String id : new ArrayList<>(loadedModules)) {
                if (!wanted.contains(id)) {
                    stopWorker("module:" + id);
                    loadedModules.remove(id);
                    CraftPyLog.LOGGER.info("Client module {} unloaded", id);
                }
            }
        });
    }

    /** Enabled client modules by id, as last listed (to find what a module or project imports). */
    private final Map<String, ModuleInfo> clientModules = new java.util.concurrent.ConcurrentHashMap<>();

    private static Path moduleDir(String id) {
        return PyForgePaths.cache().resolve("client").resolve(id);
    }

    private static void extract(ModuleInfo info) throws IOException {
        Path dir = moduleDir(info.id);
        net.xmilon.craftpy.project.ProjectManager.deleteRecursively(dir);
        try (ZipFile zip = new ZipFile(info.path)) {
            ModuleArchive.extract(zip, dir, name -> true);
        }
    }

    /** The enabled client modules {@code direct} imports, each after the ones it imports itself. */
    private List<String> libraries(java.util.Collection<String> direct) {
        Map<String, List<String>> graph = new java.util.LinkedHashMap<>();
        java.util.Deque<String> todo = new java.util.ArrayDeque<>();
        direct.forEach(d -> todo.add(d.split("[<>=!~ ;]", 2)[0].trim()));
        while (!todo.isEmpty()) {
            String id = todo.poll();
            ModuleInfo info = clientModules.get(id);
            if (info == null || graph.containsKey(id) || !Files.isDirectory(moduleDir(id))) {
                continue;
            }
            List<String> deps = new ArrayList<>();
            for (String dep : info.dependencies) {
                deps.add(dep.split("[<>=!~ ;]", 2)[0].trim());
            }
            graph.put(id, deps);
            todo.addAll(deps);
        }
        return net.xmilon.craftpy.analysis.ModuleGraph.loadOrder(graph);
    }

    private void loadModule(ModuleInfo info) throws IOException {
        Path dir = moduleDir(info.id);
        List<String> libraries = new ArrayList<>(libraries(info.dependencies));
        libraries.remove(info.id);
        ClientWorker worker = worker("module:" + info.id, dir, libraries);
        JsonObject params = new JsonObject();
        params.addProperty("id", info.id);
        params.addProperty("dir", dir.toAbsolutePath().toString());
        JsonObject result = call(worker, "load_module", params, 20_000);
        loadedModules.add(info.id);
        List<String> lines = new ArrayList<>(logOf(result));
        if (result.has("error") && result.get("error").getAsBoolean()) {
            lines.add("[client module " + info.id + "] failed: " + (result.has("message") ? result.get("message").getAsString() : "error"));
        }
        report(lines);
        CraftPyLog.LOGGER.info("Client module {} loaded", info.id);
    }

    /** Every game tick; at most one tick waits in the queue, a busy worker just skips ticks. */
    public void onTick() {
        if (!active || workers.values().stream().noneMatch(w -> w.ticking) || !tickQueued.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("tick", tickCounter.incrementAndGet());
                for (ClientWorker worker : List.copyOf(workers.values())) {
                    if (worker.ticking) {
                        try {
                            report(logOf(call(worker, "tick", params, 2_000)));
                        } catch (IOException | RuntimeException e) {
                            problem(e);
                        }
                    }
                }
            } finally {
                tickQueued.set(false);
            }
        });
    }

    /**
     * A client event ({@code chat_received}, {@code screen_open}, {@code client_join}, {@code client_leave}): it goes to
     * each worker with a handler for it. A web response, a key and a window click go to their owner's worker only.
     */
    public void event(String name, JsonObject context) {
        if (!active || queued.get() > MAX_QUEUED) {
            return;
        }
        submit(() -> {
            JsonObject params = new JsonObject();
            params.addProperty("event", name);
            params.add("context", context);
            List<ClientWorker> targets = new ArrayList<>();
            switch (name) {
                case "http_response" -> addOwner(targets, context.has("owner") ? context.get("owner").getAsString() : "");
                case "key" -> ClientHost.ownersOf(context.has("combo") ? context.get("combo").getAsString() : "")
                        .forEach(owner -> addOwner(targets, owner));
                case "ui_event" -> addOwner(targets, windowOwners.getOrDefault(
                        context.has("window") ? context.get("window").getAsString() : "", ""));
                default -> workers.values().stream().filter(w -> w.events.contains(name)).forEach(targets::add);
            }
            for (ClientWorker worker : targets) {
                report(logOf(call(worker, "trigger_event", params, 2_000)));
            }
        });
    }

    private void addOwner(List<ClientWorker> targets, String owner) {
        ClientWorker worker = workers.get(owner);
        if (worker != null) {
            targets.add(worker);
        }
    }

    /** A window {@code owner} opened: its clicks go to that owner's worker only (ClientHost calls this). */
    void noteWindow(String owner, String windowId) {
        windowOwners.put(windowId, owner);
    }

    public void key(String combo) {
        JsonObject context = new JsonObject();
        context.addProperty("combo", combo);
        event("key", context);
    }

    /** A click in a window opened by client-side Python (WindowFragment routes it here). */
    public void windowEvent(String json) {
        if (!active) {
            return;
        }
        try {
            JsonObject event = JsonParser.parseString(json).getAsJsonObject();
            JsonObject context = new JsonObject();
            JsonObject who = new JsonObject();
            var player = net.minecraft.client.Minecraft.getInstance().player;
            who.addProperty("name", player == null ? "" : player.getGameProfile().getName());
            who.addProperty("uuid", player == null ? "" : player.getUUID().toString());
            context.add("player", who);
            for (String key : new String[]{"window", "token", "value", "values"}) {
                if (event.has(key)) {
                    context.add(key, event.get(key));
                }
            }
            event("ui_event", context);
        } catch (RuntimeException bad) {
            CraftPyLog.LOGGER.warn("Bad client window event: {}", bad.toString());
        }
    }

    /** Leaving the world: stop the worker and take everything it drew off the screen. */
    public void shutdown() {
        active = false;
        ClientHost.reset();
        submit(() -> {
            loadedModules.clear();
            windowOwners.clear();
            for (String owner : List.copyOf(workers.keySet())) {
                stopWorker(owner);
            }
        });
    }

    // -- plumbing ---------------------------------------------------------------------------------------------------

    private interface Work {
        void run() throws IOException;
    }

    private void submit(Work work) {
        queued.incrementAndGet();
        executor.execute(() -> {
            try {
                work.run();
            } catch (IOException | RuntimeException e) {
                problem(e);
            } finally {
                queued.decrementAndGet();
            }
        });
    }

    /**
     * A fresh worker for {@code owner} that may read only {@code dir} and its libraries' folders, with the libraries
     * loaded (their names only). Runs replace the owner's previous worker: nothing of the last run survives.
     */
    private ClientWorker worker(String owner, Path dir, List<String> libraries) throws IOException {
        stopWorker(owner);
        PyRuntime runtime = PyRuntime.get(); // writes worker.py next to the server's
        Path python = PythonDiscovery.pythonExecutable();
        if (python == null) {
            throw new IOException("No Python for client-side code");
        }
        StringBuilder roots = new StringBuilder(dir.toAbsolutePath().normalize().toString());
        for (String library : libraries) {
            roots.append(java.io.File.pathSeparatorChar).append(moduleDir(library).toAbsolutePath().normalize());
        }
        RpcClient client = new RpcClient(python, runtime.script(python),
                PyForgePaths.logs().resolve("client_worker_" + owner.replaceAll("[^a-z0-9_]", "_") + ".log"));
        client.setEnvironment(Map.of("CRAFTPY_MODE", "client", "CRAFTPY_READ_ROOTS", roots.toString(),
                "CRAFTPY_OWNER", owner));
        client.setHostHandler((method, params) -> ClientHost.handle(owner, method, params));
        client.start();
        ClientWorker worker = new ClientWorker(client);
        workers.put(owner, worker);
        active = true;
        for (String library : libraries) {
            JsonObject params = new JsonObject();
            params.addProperty("id", library);
            params.addProperty("dir", moduleDir(library).toAbsolutePath().toString());
            params.addProperty("library", true);
            report(logOf(call(worker, "load_module", params, 20_000)));
        }
        return worker;
    }

    private void stopWorker(String owner) {
        ClientWorker removed = workers.remove(owner);
        if (removed != null) {
            removed.rpc.stop();
        }
        windowOwners.values().removeIf(owner::equals);
        ClientHost.forget(owner);
    }

    private static JsonObject call(ClientWorker worker, String method, JsonObject params, long timeoutMs) throws IOException {
        JsonObject result = worker.rpc.call(method, params, timeoutMs);
        if (result != null && result.has("ticking")) {
            worker.ticking = result.get("ticking").getAsBoolean();
        }
        if (result != null && result.has("events") && result.get("events").isJsonArray()) {
            Set<String> events = new HashSet<>();
            result.getAsJsonArray("events").forEach(e -> events.add(e.getAsString()));
            worker.events = Set.copyOf(events);
        }
        return result;
    }

    private static List<String> logOf(JsonObject result) {
        List<String> lines = new ArrayList<>();
        if (result != null && result.has("log") && result.get("log").isJsonArray()) {
            JsonArray log = result.getAsJsonArray("log");
            for (JsonElement line : log) {
                lines.add(line.getAsString());
            }
        }
        return lines;
    }

    /** Output of client code goes to the IDE console (at most 20 lines at a time, so a noisy tick cannot flood it). */
    private void report(List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        List<String> shown = lines.size() > 20 ? lines.subList(0, 20) : lines;
        IdeHost.get().execute(() -> {
            ClientProjectStore store = ClientProjectStore.getInstance();
            shown.forEach(store::append);
        });
    }

    /** One failure message per few seconds, not one per tick. */
    private void problem(Exception e) {
        String text = e.getMessage() == null ? e.toString() : e.getMessage();
        long now = System.currentTimeMillis();
        if (text.equals(lastProblem) && now - lastProblemAt < 5_000) {
            return;
        }
        lastProblem = text;
        lastProblemAt = now;
        CraftPyLog.LOGGER.warn("Client Python: {}", text);
        report(List.of("[client] " + text));
    }

    private static Map<String, String> sourcesOf(Path projectDir) throws IOException {
        Map<String, String> sources = new TreeMap<>();
        Path code = projectDir.resolve("code");
        long budget = 4L * 1024 * 1024;
        try (Stream<Path> walk = Files.walk(code)) {
            for (Path file : walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".py")).sorted().toList()) {
                String relative = projectDir.relativize(file).toString().replace('\\', '/');
                long size = Files.size(file);
                if (relative.equals("code/main.py") || sources.size() >= 400 || size > budget) {
                    continue;
                }
                budget -= size;
                sources.put(relative, Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return sources;
    }
}
