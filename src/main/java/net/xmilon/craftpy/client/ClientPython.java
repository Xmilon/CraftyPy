package net.xmilon.craftpy.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.xmilon.craftpy.Config;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.project.ModuleArchive;
import net.xmilon.craftpy.project.ModuleChanges;
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
 * Workers run on one background thread; background scans read immutable applied snapshots.
 * The game thread only queues work and never waits for Python.
 */
public final class ClientPython {
    private static final ClientPython INSTANCE = new ClientPython();
    private static final int MAX_QUEUED = 200;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "craftpy-client-python");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong sessionGeneration = new AtomicLong();
    private final AtomicBoolean tickQueued = new AtomicBoolean();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger tickCounter = new AtomicInteger();
    public record AppliedModule(String id, ModuleChanges.Snapshot snapshot, Path assets, boolean permissionsApproved, boolean changedSinceApproval) { }
    private volatile Map<String, AppliedModule> applied = Map.of();
    private volatile Map<String, String> moduleFailures = Map.of();
    private volatile Map<String, ModuleChanges.Snapshot> projectSnapshots = Map.of();
    private volatile Map<String, String> projectFailures = Map.of();
    private final Set<Path> assetCaches = new HashSet<>();
    private boolean packDirty;
    private boolean resourceReloadPending;
    /** Owner -> its worker. Only the executor thread changes it. */
    private final Map<String, ClientWorker> workers = new java.util.concurrent.ConcurrentHashMap<>();
    /** Player window id -> the owner that opened it. */
    private final Map<String, String> windowOwners = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean active;

    private static final class ClientWorker {
        final RpcClient rpc;
        final String owner;
        volatile JsonArray keys;
        /** False while no timer or client_tick handler exists: then no tick is sent (the worker says so in each reply). */
        volatile boolean ticking = true;
        volatile Set<String> events = Set.of();

        ClientWorker(RpcClient rpc, String owner) {
            this.rpc = rpc;
            this.owner = owner;
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
        synchronized (sessionGeneration) {
            long session = sessionGeneration.get();
            submit(() -> runProjectNow(projectId, session));
        }
    }

    private void runProjectNow(String projectId, long session) throws IOException {
        checkSession(session);
        ClientWorker previous = workers.get(projectId);
        try {
            if (!available()) throw new IOException("Client-side Python is off or no Python was found (see the config: enableClientPython).");
            runProjectContents(projectId, session);
            checkSession(session);
            Map<String, String> failures = new LinkedHashMap<>(projectFailures);
            failures.remove(projectId);
            projectFailures = Map.copyOf(failures);
        } catch (IOException | RuntimeException e) {
            checkSession(session);
            if (workers.get(projectId) != previous) stopWorker(projectId);
            Map<String, String> failures = new LinkedHashMap<>(projectFailures);
            failures.put(projectId, String.valueOf(e.getMessage()));
            projectFailures = Map.copyOf(failures);
            throw e;
        }
    }

    private void runProjectContents(String projectId, long session) throws IOException {
        checkSession(session);
        Path dir = PyForgePaths.projects().resolve(projectId);
        if (!Files.isDirectory(dir.resolve("code")) || !Files.isRegularFile(dir.resolve("code/main.py"))) {
            throw new IOException("Client project '" + projectId + "' is not on this computer (pyforge/projects/" + projectId
                    + "): client projects run from the folder of the game you are playing.");
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
        ModuleChanges.Snapshot started = ModuleChanges.projectSnapshot(dir);
        dependencies = started.dependencies();
        for (String dependency : libraries(dependencies)) {
            if (!applied.containsKey(dependency)) throw new IOException("Client project " + projectId + " needs " + dependency);
        }
        for (String dependency : dependencies) {
            if (!applied.containsKey(ModuleChanges.dependencyId(dependency)))
                throw new IOException("Client project " + projectId + " needs " + dependency);
        }
        ClientWorker worker = worker(projectId, dir, libraries(dependencies), session);
        JsonObject params = new JsonObject();
        params.addProperty("project", projectId);
        params.addProperty("file", "code/main.py");
        params.addProperty("code", Files.readString(dir.resolve("code/main.py"), StandardCharsets.UTF_8));
        params.addProperty("dir", dir.toAbsolutePath().toString());
        JsonObject files = new JsonObject();
        sourcesOf(dir).forEach(files::addProperty);
        params.add("sources", files);
        checkSession(session);
        JsonObject result = call(worker, "run_main", params, 20_000);
        checkSession(session);
        List<String> lines = new ArrayList<>(logOf(result));
        if (result.has("error") && result.get("error").getAsBoolean()) {
            lines.add("[client] " + projectId + " failed: " + (result.has("message") ? result.get("message").getAsString() : "error")
                    + (result.has("file") ? " (" + result.get("file").getAsString() + ", line "
                    + (result.get("line").getAsInt() + 1) + ")" : ""));
        } else {
            lines.add("[client] " + projectId + " runs on this computer");
        }
        report(lines);
        requireSuccess(result);
        ModuleChanges.Snapshot finished = ModuleChanges.projectSnapshot(dir);
        if (runtimeChanged(started, finished)) throw new IOException("Client project changed while loading; apply again");
        Map<String, ModuleChanges.Snapshot> snapshots = new LinkedHashMap<>(projectSnapshots);
        snapshots.put(projectId, started);
        checkSession(session);
        projectSnapshots = Map.copyOf(snapshots);
    }

    /** Startup entry point; ordinary module scans only describe pending changes. */
    public void loadModules() {
        applyModuleChanges().thenAccept(result -> {
            if (!result.success()) report(List.of("[client modules] " + result.summary()));
        });
    }

    /** Immutable successfully applied identities and assets; never reads desired archives. */
    public List<AppliedModule> appliedModules() {
        return List.copyOf(applied.values());
    }

    /** Background scan: fingerprints archives and exact approvals; never starts or stops workers. */
    public void describeModuleChanges(List<ModuleInfo> infos) {
        Map<String, AppliedModule> current = applied;
        Map<String, String> failures = new LinkedHashMap<>(moduleFailures);
        includeAppliedModules(infos, current);
        Map<String, ModuleChanges.Snapshot> wanted = new LinkedHashMap<>();
        for (ModuleInfo info : infos) {
            if (ModuleSide.of(info.side) != ModuleSide.CLIENT || !info.primary || !info.enabled) continue;
            snapshotCandidate(info, current, wanted, failures);
        }
        ModuleChanges.Plan plan = ModuleChanges.compare(snapshots(current), wanted);
        for (ModuleInfo info : infos) {
            if (ModuleSide.of(info.side) != ModuleSide.CLIENT) continue;
            AppliedModule old = current.get(info.id);
            info.loaded = old != null && old.snapshot().path().equals(Path.of(info.path).toAbsolutePath().normalize().toString());
            info.pendingReload = info.primary && (plan.restart().contains(info.id) || plan.resources().contains(info.id)
                    || plan.removed().contains(info.id) || failures.containsKey(info.id));
            info.pendingReason = !info.pendingReload ? "" : failures.getOrDefault(info.id,
                    !info.enabled ? "Disable or remove on reload" : old == null ? "Load on reload"
                            : plan.resources().contains(info.id) ? "Resources changed" : "Code, dependencies or permissions changed");
        }
    }

    /** Background IO scan; returns project ids with unapplied runtime/dependency changes or failed runs. */
    public List<String> pendingProjectChanges() {
        return List.copyOf(changedProjects(projectSnapshots, new LinkedHashMap<>()));
    }

    private Set<String> changedProjects(Map<String, ModuleChanges.Snapshot> previous, Map<String, String> failures) {
        Set<String> changed = new java.util.LinkedHashSet<>(projectFailures.keySet());
        Set<String> ids = new java.util.LinkedHashSet<>(previous.keySet());
        ids.addAll(changed);
        for (String id : ids) {
            try {
                ModuleChanges.Snapshot wanted = ModuleChanges.projectSnapshot(PyForgePaths.projects().resolve(id));
                if (runtimeChanged(previous.get(id), wanted)) changed.add(id);
            } catch (IOException | RuntimeException error) {
                changed.add(id);
                failures.put("project:" + id, String.valueOf(error.getMessage()));
            }
        }
        return changed;
    }

    private static boolean runtimeChanged(ModuleChanges.Snapshot before, ModuleChanges.Snapshot wanted) {
        // Project assets use the existing project resource path, rather than the client module pack.
        return before == null || !before.runtime().equals(wanted.runtime()) || !before.dependencies().equals(wanted.dependencies());
    }

    /** Keep applied identities visible even when an archive disappears or its metadata becomes unreadable. */
    private static void includeAppliedModules(List<ModuleInfo> infos, Map<String, AppliedModule> current) {
        for (AppliedModule module : current.values()) {
            ModuleInfo existing = infos.stream().filter(info -> info.id.equals(module.id())).findFirst().orElse(null);
            if (existing == null) {
                existing = infos.stream().filter(info -> info.error != null
                        && Path.of(info.path).toAbsolutePath().normalize().toString().equals(module.snapshot().path()))
                        .findFirst().orElse(null);
                if (existing != null) existing.id = module.id();
            }
            if (existing != null) {
                if (existing.error != null && Path.of(existing.path).toAbsolutePath().normalize().toString()
                        .equals(module.snapshot().path())) existing.side = ModuleSide.CLIENT.id();
                continue;
            }
            ModuleInfo missing = new ModuleInfo();
            missing.id = module.id();
            missing.name = module.id();
            missing.path = module.snapshot().path();
            missing.sha256 = module.snapshot().archive();
            missing.dependencies = module.snapshot().dependencies();
            missing.side = ModuleSide.CLIENT.id();
            missing.loaded = true;
            missing.enabled = !Files.notExists(Path.of(missing.path));
            if (missing.enabled) missing.error = "Installed client archive could not be scanned; keeping applied version";
            infos.add(missing);
        }
    }

    /** An unreadable enabled archive is a failed candidate, never an implicit disable/delete. */
    private static void snapshotCandidate(ModuleInfo info, Map<String, AppliedModule> current,
                                          Map<String, ModuleChanges.Snapshot> wanted, Map<String, String> failures) {
        try {
            if (info.error != null) throw new IOException(info.error);
            wanted.put(info.id, ModuleChanges.snapshot(info));
        } catch (IOException | RuntimeException e) {
            failures.put(info.id, String.valueOf(e.getMessage()));
            AppliedModule old = current.get(info.id);
            if (old != null) wanted.put(info.id, old.snapshot());
        }
    }

    private static Map<String, ModuleChanges.Snapshot> snapshots(Map<String, AppliedModule> modules) {
        Map<String, ModuleChanges.Snapshot> result = new LinkedHashMap<>();
        modules.forEach((id, module) -> result.put(id, module.snapshot()));
        return result;
    }

    public CompletableFuture<ModuleChanges.Result> applyModuleChanges() {
        synchronized (sessionGeneration) {
            long session = sessionGeneration.get();
            return CompletableFuture.supplyAsync(() -> applyModulesNow(session), executor);
        }
    }

    private ModuleChanges.Result applyModulesNow(long session) {
        checkSession(session);
        long start = System.nanoTime();
        Map<String, String> failures = new LinkedHashMap<>();
        Map<String, AppliedModule> before = applied;
        Map<String, AppliedModule> next = new LinkedHashMap<>(before);
        Map<String, ModuleChanges.Snapshot> wanted = new LinkedHashMap<>();
        Map<String, ModuleInfo> candidates = new LinkedHashMap<>();
        try {
            List<ModuleInfo> infos = new ArrayList<>(ModuleLoader.list());
            includeAppliedModules(infos, before);
            for (ModuleInfo info : infos) {
                if (ModuleSide.of(info.side) == ModuleSide.CLIENT && info.primary && info.enabled)
                    candidates.put(info.id, info);
            }
        } catch (RuntimeException e) {
            return new ModuleChanges.Result(0, before.size(), 0, 0, List.of("client: " + e.getMessage()), 0);
        }
        checkSession(session);
        Map<String, ModuleChanges.Snapshot> previousProjects = projectSnapshots;
        Set<String> projects = changedProjects(previousProjects, failures);
        boolean noClients = candidates.isEmpty() && before.isEmpty() && projects.isEmpty();
        if (noClients && !packDirty && !resourceReloadPending) {
            moduleFailures = Map.of();
            return new ModuleChanges.Result(0, 0, 0, 0, List.of(), (System.nanoTime() - start) / 1_000_000);
        }
        if (!noClients && !available()) return new ModuleChanges.Result(0, before.size(), 0, 0,
                List.of("client: Python is disabled or unavailable"), (System.nanoTime() - start) / 1_000_000);
        for (ModuleInfo info : candidates.values()) snapshotCandidate(info, before, wanted, failures);
        ModuleChanges.Plan plan = ModuleChanges.compare(snapshots(before), wanted);
        Set<String> affected = new HashSet<>(plan.restart());
        affected.addAll(plan.removed());
        Set<String> dependentProjects = new java.util.LinkedHashSet<>();
        for (var entry : previousProjects.entrySet()) {
            List<String> dependencies = entry.getValue().dependencies();
            if (libraries(dependencies).stream().anyMatch(affected::contains)
                    || dependencies.stream().map(ModuleChanges::dependencyId).anyMatch(affected::contains))
                dependentProjects.add(entry.getKey());
        }
        projects.addAll(dependentProjects);
        checkSession(session);
        for (String id : affected) {
            checkSession(session);
            stopWorker("module:" + id);
            next.remove(id);
        }
        // Stop importers before replacing their libraries' folders.
        dependentProjects.forEach(this::stopWorker);
        clientModules.clear();
        clientModules.putAll(candidates);
        Map<String, List<String>> graph = new LinkedHashMap<>();
        candidates.forEach((id, info) -> graph.put(id, info.dependencies));
        for (String id : candidates.keySet()) {
            String problem = ModuleChanges.dependencyProblem(id, wanted);
            if (!problem.isEmpty()) failures.putIfAbsent(id, problem);
        }
        Map<String, Path> staged = new LinkedHashMap<>();
        Set<String> changed = new HashSet<>(plan.restart());
        changed.addAll(plan.resources());
        for (String id : changed) {
            checkSession(session);
            if (failures.containsKey(id)) continue;
            try {
                Files.createDirectories(PyForgePaths.cache());
                Path stage = Files.createTempDirectory(PyForgePaths.cache(), "client-applied-");
                assetCaches.add(stage);
                try (ZipFile zip = new ZipFile(candidates.get(id).path)) {
                    ModuleArchive.extract(zip, stage, name -> plan.restart().contains(id) || name.startsWith("assets/"));
                }
                if (!wanted.get(id).equals(ModuleChanges.snapshot(candidates.get(id))))
                    throw new IOException("Archive changed during reload; retry");
                checkSession(session);
                if (plan.restart().contains(id)) {
                    net.xmilon.craftpy.project.ProjectManager.deleteRecursively(moduleDir(id));
                    copyTree(stage, moduleDir(id));
                }
                staged.put(id, stage);
            } catch (IOException | RuntimeException e) { failures.put(id, String.valueOf(e.getMessage())); }
        }
        int restarted = 0, loaded = 0;
        // Applied before and kept across worlds (see shutdown), but with no worker yet: start the worker only.
        // The unpacked code, the assets and the resource pack are already in place, so no extraction and no reload.
        Set<String> idle = new HashSet<>();
        for (String id : before.keySet()) {
            if (candidates.containsKey(id) && !workers.containsKey("module:" + id) && !plan.restart().contains(id)
                    && !plan.removed().contains(id) && !failures.containsKey(id)) idle.add(id);
        }
        for (String id : net.xmilon.craftpy.analysis.ModuleGraph.loadOrder(graph)) {
            checkSession(session);
            if (idle.contains(id) && !changed.contains(id)) {
                try {
                    for (String dep : candidates.get(id).dependencies) {
                        String dependency = ModuleChanges.dependencyId(dep);
                        if (failures.containsKey(dependency) || !next.containsKey(dependency))
                            throw new IOException("Dependency " + dependency + " did not load");
                    }
                    loadModule(candidates.get(id), session);
                    checkSession(session);
                    loaded++;
                } catch (IOException | RuntimeException e) {
                    checkSession(session);
                    stopWorker("module:" + id);
                    failures.put(id, String.valueOf(e.getMessage()));
                }
                continue;
            }
            if (!changed.contains(id) || failures.containsKey(id)) continue;
            try {
                for (String dep : candidates.get(id).dependencies) {
                    String dependency = ModuleChanges.dependencyId(dep);
                    if (failures.containsKey(dependency) || !next.containsKey(dependency))
                        throw new IOException("Dependency " + dependency + " did not load");
                }
                if (plan.restart().contains(id)) {
                    loadModule(candidates.get(id), session);
                    checkSession(session);
                    if (before.containsKey(id)) restarted++; else loaded++;
                }
                next.put(id, new AppliedModule(id, wanted.get(id), staged.get(id).resolve("assets"),
                        candidates.get(id).permissionsApproved, candidates.get(id).changedSinceApproval));
            } catch (IOException | RuntimeException e) {
                checkSession(session);
                if (plan.restart().contains(id)) stopWorker("module:" + id);
                failures.put(id, String.valueOf(e.getMessage()));
            }
        }
        assert next.keySet().stream().allMatch(id -> workers.containsKey("module:" + id) || failures.containsKey(id)) : "Applied module has no worker";
        assert next.values().stream().allMatch(module -> module.snapshot().dependencies().stream()
                .map(ModuleChanges::dependencyId).allMatch(next::containsKey)) : "Applied dependency is missing";
        boolean packChanged = resourceReloadPending;
        synchronized (net.xmilon.craftpy.dynamics.ClientModulePack.class) {
            checkSession(session);
            applied = Map.copyOf(next);
            packDirty |= !before.equals(next);
            if (packDirty) {
                try {
                    packChanged |= net.xmilon.craftpy.dynamics.ClientModulePack.refreshChecked();
                    packDirty = false;
                } catch (IOException | RuntimeException e) {
                    checkSession(session);
                    resourceFailure(before, next, plan, changed, failures, e);
                }
            }
        }
        if (packChanged) {
            try { checkSession(session); reloadResources(session).join(); checkSession(session); resourceReloadPending = false; }
            catch (RuntimeException e) {
                checkSession(session);
                synchronized (net.xmilon.craftpy.dynamics.ClientModulePack.class) {
                    checkSession(session);
                    resourceFailure(before, next, plan, changed, failures, e);
                    try { net.xmilon.craftpy.dynamics.ClientModulePack.refreshChecked(); }
                    catch (IOException | RuntimeException restore) { failures.put("client resources", "Restore failed: " + restore.getMessage()); }
                }
            }
        }
        checkSession(session);
        clientModules.clear();
        applied.forEach((id, module) -> {
            ModuleInfo info = new ModuleInfo();
            info.id = id;
            info.dependencies = module.snapshot().dependencies();
            clientModules.put(id, info);
        });
        for (String project : projects) {
            checkSession(session);
            try {
                runProjectNow(project, session);
                failures.remove("project:" + project);
            } catch (IOException | RuntimeException e) {
                checkSession(session);
                failures.put("project:" + project, String.valueOf(e.getMessage()));
            }
        }
        checkSession(session);
        projectFailures.forEach((id, error) -> failures.putIfAbsent("project:" + id, error));
        cleanupAssetCaches();
        moduleFailures = Map.copyOf(failures);
        int kept = (int) plan.kept().stream().filter(id -> !failures.containsKey(id)).count();
        return new ModuleChanges.Result(restarted, kept, loaded, plan.removed().size(),
                failures.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList(),
                (System.nanoTime() - start) / 1_000_000);
    }

    private void resourceFailure(Map<String, AppliedModule> before, Map<String, AppliedModule> next,
                                 ModuleChanges.Plan plan, Set<String> changed, Map<String, String> failures, Exception error) {
        // Resource-only updates are acknowledged only after the game accepts their pack.
        for (String id : plan.resources()) {
            if (before.containsKey(id)) next.put(id, before.get(id));
        }
        for (String id : changed.isEmpty() ? next.keySet() : changed)
            failures.putIfAbsent(id, "Resources: " + error.getMessage());
        failures.put("client resources", String.valueOf(error.getMessage()));
        applied = Map.copyOf(next);
        packDirty = true;
        resourceReloadPending = true;
    }

    private CompletableFuture<Void> reloadResources(long session) {
        checkSession(session);
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> done = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                checkSession(session);
                var screen = minecraft.screen;
                minecraft.reloadResourcePacks().whenComplete((ignored, error) -> minecraft.execute(() -> {
                    if (error != null) { done.completeExceptionally(error); return; }
                    try {
                        checkSession(session);
                        net.xmilon.craftpy.client.assets.AssetPreview.invalidate();
                        ClientPackSync.announceContent(minecraft);
                        if (screen != null && minecraft.screen != screen) minecraft.setScreen(screen);
                        done.complete(null);
                    } catch (RuntimeException e) { done.completeExceptionally(e); }
                }));
            } catch (RuntimeException e) { done.completeExceptionally(e); }
        });
        return done;
    }

    private void cleanupAssetCaches() {
        Set<Path> retained = applied.values().stream().map(module -> module.assets().getParent())
                .collect(java.util.stream.Collectors.toSet());
        for (Path cache : List.copyOf(assetCaches)) {
            if (retained.contains(cache)) continue;
            try {
                net.xmilon.craftpy.project.ProjectManager.deleteRecursively(cache);
                assetCaches.remove(cache);
            } catch (IOException | RuntimeException e) { problem(e); }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path out = target.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(out);
                else Files.copy(file, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Executor-owned dependency graph, switched to desired candidates only during an apply. */
    private final Map<String, ModuleInfo> clientModules = new java.util.concurrent.ConcurrentHashMap<>();

    private static Path moduleDir(String id) {
        return PyForgePaths.cache().resolve("client").resolve(id);
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

    private void loadModule(ModuleInfo info, long session) throws IOException {
        checkSession(session);
        Path dir = moduleDir(info.id);
        List<String> libraries = new ArrayList<>(libraries(info.dependencies));
        libraries.remove(info.id);
        ClientWorker worker = worker("module:" + info.id, dir, libraries, session);
        JsonObject params = new JsonObject();
        params.addProperty("id", info.id);
        params.addProperty("dir", dir.toAbsolutePath().toString());
        checkSession(session);
        JsonObject result = call(worker, "load_module", params, 20_000);
        checkSession(session);
        List<String> lines = new ArrayList<>(logOf(result));
        if (result.has("error") && result.get("error").getAsBoolean()) {
            lines.add("[client module " + info.id + "] failed: " + (result.has("message") ? result.get("message").getAsString() : "error"));
        }
        report(lines);
        requireSuccess(result);
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
                case "key", "app_open" -> addOwner(targets, context.has("owner") ? context.get("owner").getAsString() : "");
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

    public void key(String owner, String action) {
        JsonObject context = new JsonObject();
        context.addProperty("owner", owner);
        context.addProperty("action", action);
        event("key", context);
    }

    /** Hub: opens the window a client module's app declares, as that module. */
    public void openApp(String owner, String window) {
        JsonObject context = new JsonObject();
        JsonObject who = new JsonObject();
        var player = net.minecraft.client.Minecraft.getInstance().player;
        who.addProperty("name", player == null ? "" : player.getGameProfile().getName());
        who.addProperty("uuid", player == null ? "" : player.getUUID().toString());
        context.add("player", who);
        context.addProperty("owner", "module:" + owner); // client workers are named module:<id>
        context.addProperty("window", window);
        event("app_open", context);
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
        synchronized (sessionGeneration) {
            sessionGeneration.incrementAndGet();
            active = false;
            submit(() -> {
                // The applied modules (unpacked code, assets, resource pack) stay: the next world with the same modules
                // only starts their workers, with no resource reload. A different set is applied as a normal change.
                moduleFailures = Map.of();
                projectSnapshots = Map.of();
                projectFailures = Map.of();
                windowOwners.clear();
                for (String owner : List.copyOf(workers.keySet())) {
                    stopWorker(owner);
                }
                cleanupAssetCaches();
                active = false;
                tickQueued.set(false);
                tickCounter.set(0);
            });
        }
        ClientHost.reset();
    }

    // -- plumbing ---------------------------------------------------------------------------------------------------

    private interface Work {
        void run() throws IOException;
    }

    private void checkSession(long session) {
        if (session != sessionGeneration.get()) throw new CancellationException("Client session changed");
    }

    private void submit(Work work) {
        long session = sessionGeneration.get();
        queued.incrementAndGet();
        executor.execute(() -> {
            try {
                checkSession(session);
                work.run();
            } catch (CancellationException ignored) {
                // Shutdown's serial cleanup owns the old session's workers and snapshots.
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
    private ClientWorker worker(String owner, Path dir, List<String> libraries, long session) throws IOException {
        checkSession(session);
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
        client.setHostHandler((method, params) -> {
            checkSession(session);
            return ClientHost.handle(owner, method, params);
        });
        ClientWorker worker = new ClientWorker(client, owner);
        synchronized (sessionGeneration) {
            checkSession(session);
            client.start();
            workers.put(owner, worker);
            active = true;
        }
        try {
            for (String library : libraries) {
                checkSession(session);
                JsonObject params = new JsonObject();
                params.addProperty("id", library);
                params.addProperty("dir", moduleDir(library).toAbsolutePath().toString());
                params.addProperty("library", true);
                JsonObject result = call(worker, "load_module", params, 20_000);
                report(logOf(result));
                requireSuccess(result);
                checkSession(session);
            }
        } catch (IOException | RuntimeException e) {
            checkSession(session);
            stopWorker(owner);
            throw e;
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
        if (result != null && result.has("keys") && !result.getAsJsonArray("keys").equals(worker.keys)) {
            JsonArray descriptions = result.getAsJsonArray("keys").deepCopy();
            worker.keys = descriptions;
            IdeHost.get().execute(() -> {
                if (INSTANCE.active && INSTANCE.workers.get(worker.owner) == worker) ModuleKeys.local(worker.owner, descriptions);
            });
        }
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

    private static void requireSuccess(JsonObject result) throws IOException {
        if (result == null || result.has("error") && result.get("error").getAsBoolean())
            throw new IOException(result != null && result.has("message") ? result.get("message").getAsString() : "Python load failed");
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
