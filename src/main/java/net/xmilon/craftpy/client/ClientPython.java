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
 * The Python of the client: a second worker process on the player's computer that runs client-side modules and projects
 * (HUD, keys, windows). It is the same worker.py in client mode, answered by {@link ClientHost}, so it can draw and read
 * what the client knows but cannot change the world or reach the server. Everything here runs on one background thread:
 * the game thread only queues work and never waits for Python.
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
    /** False while no timer or client_tick handler exists: then no tick is sent at all (the worker says so in each reply). */
    private volatile boolean ticking = true;
    private final Set<String> loadedModules = new HashSet<>();
    private volatile RpcClient rpc;
    private volatile boolean active;
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
            try {
                var meta = net.xmilon.craftpy.util.Json.COMPACT.fromJson(
                        Files.readString(dir.resolve("project.json"), StandardCharsets.UTF_8), net.xmilon.craftpy.project.ProjectMetadata.class);
                if (meta != null) {
                    net.xmilon.craftpy.security.PermissionStore.declareOwn(projectId, meta.permissions);
                }
            } catch (IOException | RuntimeException unreadable) {
                net.xmilon.craftpy.security.PermissionStore.declareOwn(projectId, null);
            }
            JsonObject params = new JsonObject();
            params.addProperty("project", projectId);
            params.addProperty("file", "code/main.py");
            params.addProperty("code", Files.readString(dir.resolve("code/main.py"), StandardCharsets.UTF_8));
            params.addProperty("dir", dir.toAbsolutePath().toString());
            JsonObject files = new JsonObject();
            sourcesOf(dir).forEach(files::addProperty);
            params.add("sources", files);
            JsonObject result = call("run_main", params, 20_000);
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
            for (ModuleInfo info : ModuleLoader.list()) {
                if (ModuleSide.of(info.side) == ModuleSide.CLIENT && info.enabled && info.primary && info.error == null) {
                    wanted.add(info.id);
                    if (!loadedModules.contains(info.id)) {
                        loadModule(info);
                    }
                }
            }
            for (String id : new ArrayList<>(loadedModules)) {
                if (!wanted.contains(id)) {
                    JsonObject params = new JsonObject();
                    params.addProperty("owner", "module:" + id);
                    call("unload_owner", params, 5_000);
                    loadedModules.remove(id);
                    CraftPyLog.LOGGER.info("Client module {} unloaded", id);
                }
            }
        });
    }

    private void loadModule(ModuleInfo info) throws IOException {
        Path dir = PyForgePaths.cache().resolve("client").resolve(info.id);
        net.xmilon.craftpy.project.ProjectManager.deleteRecursively(dir);
        try (ZipFile zip = new ZipFile(info.path)) {
            ModuleArchive.extract(zip, dir, name -> true);
        }
        JsonObject params = new JsonObject();
        params.addProperty("id", info.id);
        params.addProperty("dir", dir.toAbsolutePath().toString());
        JsonObject result = call("load_module", params, 20_000);
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
        if (!active || !ticking || !tickQueued.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("tick", tickCounter.incrementAndGet());
                report(logOf(call("tick", params, 2_000)));
            } catch (IOException | RuntimeException e) {
                problem(e);
            } finally {
                tickQueued.set(false);
            }
        });
    }

    /** A client event ({@code chat_received}, {@code screen_open}, {@code client_join}, {@code client_leave}). */
    public void event(String name, JsonObject context) {
        if (!active || queued.get() > MAX_QUEUED) {
            return;
        }
        submit(() -> {
            JsonObject params = new JsonObject();
            params.addProperty("event", name);
            params.add("context", context);
            report(logOf(call("trigger_event", params, 2_000)));
        });
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
            RpcClient current = rpc;
            rpc = null;
            loadedModules.clear();
            if (current != null) {
                current.stop();
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

    private JsonObject call(String method, JsonObject params, long timeoutMs) throws IOException {
        RpcClient client = rpc;
        if (client == null) {
            PyRuntime.get(); // writes worker.py next to the server's
            Path python = PythonDiscovery.pythonExecutable();
            if (python == null) {
                throw new IOException("No Python for client-side code");
            }
            client = new RpcClient(python, PyForgePaths.runtime().resolve("worker.py"),
                    PyForgePaths.logs().resolve("client_worker.log"));
            client.setEnvironment(Map.of("CRAFTPY_MODE", "client"));
            client.setHostHandler(ClientHost::handle);
            client.start();
            rpc = client;
            active = true;
        }
        JsonObject result = client.call(method, params, timeoutMs);
        if (result != null && result.has("ticking")) {
            ticking = result.get("ticking").getAsBoolean();
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
