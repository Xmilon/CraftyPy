package net.xmilon.craftpy.project;

import net.xmilon.craftpy.CraftPyLog;
import com.google.gson.JsonObject;

import net.xmilon.craftpy.python.Declarations;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.util.CraftPyExecutors;
import net.xmilon.craftpy.util.Json;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads .cpm (CraftPy Module) files into the game.
 *
 * Modules live in {@code pyforge/modules/} (installed ones and the ones the IDE builds). Which ones are on belongs to
 * the world: {@code <world>/craftpy/modules.json} lists them, and a new world starts with none. On a server you join,
 * the server's world decides for its modules and {@code pyforge/system/servers/<address>.json} on your computer for
 * your client-side ones (also none at first). Without a world (the standalone IDE) the old
 * {@code pyforge/system/modules.json} is used.
 */
public final class ModuleLoader {
    private static final Object LOCK = new Object();

    private static final Map<String, ModuleBuilder.ModuleMetadata> LOADED_MODULES = new HashMap<>();
    private static final Map<String, Path> MODULE_FILES = new HashMap<>();
    /** Modules disabled at runtime but still resident in the Python worker. */
    private static final Set<String> PENDING_UNLOAD = new LinkedHashSet<>();
    private static final Map<String, ModuleChanges.Snapshot> APPLIED = new HashMap<>();
    private static final Map<String, String> LOAD_ERRORS = new HashMap<>();
    private static final java.util.concurrent.atomic.AtomicBoolean APPLYING = new java.util.concurrent.atomic.AtomicBoolean();
    private static long stateRevision;
    private static boolean resourcesPending;
    private static final Set<String> PENDING_PROJECTS = new LinkedHashSet<>();
    private static final Set<CompletableFuture<?>> SERVER_WORK = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static ModuleState state;
    private static boolean stateLoaded = false;
    /** The world's (or server's) list; null: the global file of the standalone IDE. */
    private static Path worldStateFile;

    /** Which modules are on. */
    public static class ModuleState {
        /** Ids that are on (a world's list). Null in the old global file, which lists only {@link #disabled}. */
        public List<String> enabled;
        /** Old global file: ids the user turned off; everything else is on. */
        public List<String> disabled = new ArrayList<>();
    }

    /**
     * Uses the list of a world or server from now on. When the file does not exist yet, a new world ({@code fresh})
     * starts with every module off; an older world keeps what was on before this list existed (the global file).
     */
    public static void useStateFile(Path file, boolean fresh) {
        synchronized (LOCK) {
            if (!Objects.equals(worldStateFile, file)) {
                stateRevision++;
                cancelServerWork();
                LOADED_MODULES.clear(); MODULE_FILES.clear(); APPLIED.clear(); LOAD_ERRORS.clear(); PENDING_UNLOAD.clear();
                resourcesPending = false;
                PENDING_PROJECTS.clear();
            }
            if (!Files.isRegularFile(file)) {
                List<String> on = new ArrayList<>();
                if (!fresh) {
                    worldStateFile = null;
                    stateLoaded = false;
                    loadState();
                    for (Path module : scanAll()) {
                        try {
                            String id = readInfo(module).id;
                            if (id != null && isEnabledState(id) && !on.contains(id)) {
                                on.add(id);
                            }
                        } catch (IOException ignored) {
                            // an unreadable file is not a module that was on
                        }
                    }
                }
                ModuleState initial = new ModuleState();
                initial.enabled = on;
                try {
                    Json.write(file, initial);
                } catch (IOException e) {
                    CraftPyLog.LOGGER.error("Failed to write {}", file, e);
                }
                CraftPyLog.LOGGER.info("Module list of this world created ({}): {}", fresh ? "new world, all off" : "kept", on);
            }
            worldStateFile = file;
            stateLoaded = false;
            loadState();
        }
    }

    /**
     * Back to the global file (the world closed). What was loaded belongs to that world and its Python worker, which
     * stops with it: forgotten, so the next world loads its own modules.
     */
    public static void useGlobalState() {
        synchronized (LOCK) {
            stateRevision++;
            cancelServerWork();
            worldStateFile = null;
            stateLoaded = false;
            LOADED_MODULES.clear();
            MODULE_FILES.clear();
            PENDING_UNLOAD.clear();
            APPLIED.clear();
            LOAD_ERRORS.clear();
            resourcesPending = false;
            PENDING_PROJECTS.clear();
        }
    }

    /** The unpacked {@code data/} folder of a loaded module, or null. */
    public static Path dataOf(String id) {
        synchronized (LOCK) {
            if (!LOADED_MODULES.containsKey(id)) {
                return null;
            }
            Path data = PyForgePaths.cache().resolve(id).resolve("data");
            return Files.isDirectory(data) ? data : null;
        }
    }

    /** True when {@code id} is running as a module now. */
    public static boolean isLoaded(String id) {
        synchronized (LOCK) {
            return LOADED_MODULES.containsKey(id);
        }
    }

    /**
     * The loaded modules a project or module importing {@code direct} needs, each after the ones it needs itself
     * ("foo>=1.2" names "foo"). Every owner runs in its own Python worker, so these are loaded into that worker too.
     */
    public static List<String> libraries(java.util.Collection<String> direct) {
        synchronized (LOCK) {
            Map<String, List<String>> graph = new java.util.LinkedHashMap<>();
            java.util.Deque<String> todo = new java.util.ArrayDeque<>();
            direct.forEach(d -> todo.add(dependencyId(d)));
            while (!todo.isEmpty()) {
                String id = todo.poll();
                ModuleBuilder.ModuleMetadata meta = LOADED_MODULES.get(id);
                if (meta == null || graph.containsKey(id)) {
                    continue;
                }
                List<String> deps = new ArrayList<>();
                for (String dep : meta.dependencies == null ? new String[0] : meta.dependencies) {
                    if (dep != null && !dep.isBlank()) {
                        deps.add(dependencyId(dep));
                    }
                }
                graph.put(id, deps);
                todo.addAll(deps);
            }
            return net.xmilon.craftpy.analysis.ModuleGraph.loadOrder(graph);
        }
    }

    /** The dependencies a loaded module declares (empty when it is not loaded). */
    public static List<String> dependenciesOf(String id) {
        synchronized (LOCK) {
            ModuleBuilder.ModuleMetadata meta = LOADED_MODULES.get(id);
            return meta == null || meta.dependencies == null ? List.of()
                    : java.util.Arrays.stream(meta.dependencies).filter(d -> d != null && !d.isBlank()).toList();
        }
    }

    /** SHA-256 of current bytes; file timestamps alone cannot establish unchanged executable content. */
    public static String sha256(Path path) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                for (int read; (read = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, read);
                }
            }
            String hash = java.util.HexFormat.of().formatHex(digest.digest());
            return hash;
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String dependencyId(String dependency) {
        return dependency.split("[<>=!~ ;]", 2)[0].trim();
    }

    /** True while a world's or server's list is used. */
    public static boolean usesWorldState() {
        return worldStateFile != null;
    }

    public static void discoverAndLoad() {
        synchronized (LOCK) {
            loadState();
            // read every module first, so the ones others import (`from modules.<id> import ...`) load before them
            Map<String, Path> fileOf = new java.util.LinkedHashMap<>();
            Map<String, List<String>> dependencies = new java.util.LinkedHashMap<>();
            for (Path file : scanAll()) {
                try {
                    ModuleInfo info = readInfo(file);
                    if (info.error != null) {
                        CraftPyLog.LOGGER.warn("Skipping unreadable module {}: {}", file, info.error);
                        continue;
                    }
                    if (!info.enabled) {
                        continue;
                    }
                    if (fileOf.containsKey(info.id) || LOADED_MODULES.containsKey(info.id)) {
                        CraftPyLog.LOGGER.info("Module {} already loaded from another file, skipping {}", info.id, file);
                        continue;
                    }
                    fileOf.put(info.id, file);
                    dependencies.put(info.id, info.dependencies);
                } catch (Exception e) {
                    CraftPyLog.LOGGER.error("Failed to read module {}", file, e);
                }
            }
            for (String id : net.xmilon.craftpy.analysis.ModuleGraph.loadOrder(dependencies)) {
                try {
                    loadModule(fileOf.get(id));
                } catch (Exception e) {
                    CraftPyLog.LOGGER.error("Failed to load module {}", fileOf.get(id), e);
                }
            }
        }
    }

    /** Existing callers use the same selective apply as the Modules page. Never starts a second apply. */
    public static boolean reload(String id) throws IOException {
        if (!ProjectManager.isValidId(id)) return false;
        if (findFileById(id) == null) return false;
        applyChanges();
        return true;
    }

    /** Saves the desired enabled state. Running code changes only on explicit apply. */
    public static void setEnabled(String id, boolean enabled) {
        if (!ProjectManager.isValidId(id)) return;
        synchronized (LOCK) {
            loadState();
            var previous = new ModuleState();
            previous.enabled = state.enabled == null ? null : new ArrayList<>(state.enabled);
            previous.disabled = new ArrayList<>(state.disabled);
            if (state.enabled != null) {
                state.enabled.remove(id);
                if (enabled) state.enabled.add(id);
            } else if (enabled) state.disabled.remove(id);
            else if (!state.disabled.contains(id)) state.disabled.add(id);
            try { saveState(); }
            catch (java.io.UncheckedIOException error) { state = previous; throw error; }
        }
    }

    public static long stateRevision() { synchronized (LOCK) { return stateRevision; } }

    public static void setEnabled(String id, boolean enabled, long revision) {
        synchronized (LOCK) { checkRevision(revision); setEnabled(id, enabled); }
    }

    public static void setAllEnabled(boolean enabled, long revision) {
        synchronized (LOCK) { checkRevision(revision); setAllEnabled(enabled); }
    }

    /** Bulk enable/disable every discovered module. */
    public static void setAllEnabled(boolean enabled) {
        List<Path> files = scanAll();
        Set<String> ids = new LinkedHashSet<>();
        for (Path file : files) {
            try {
                ids.add(readInfo(file).id);
            } catch (IOException ignored) {
            }
        }
        for (String id : ids) {
            setEnabled(id, enabled);
        }
    }

    /** One awaited apply for all changed owners; unchanged Python processes are never touched. */
    public static CompletableFuture<ModuleChanges.Result> applyChanges() {
        if (!APPLYING.compareAndSet(false, true))
            return CompletableFuture.failedFuture(new IllegalStateException("A module reload is already running"));
        final long revision = stateRevision();
        return CraftPyExecutors.supplyAsync(() -> {
            checkRevision(revision);
            long start = System.nanoTime();
            var failures = new ArrayList<String>();
            int restarted = 0, loaded = 0, stopped = 0;
            Map<String, ModuleChanges.Snapshot> previous;
            synchronized (LOCK) { previous = Map.copyOf(APPLIED); }
            Map<String, ModuleInfo> files = new LinkedHashMap<>();
            Map<String, ModuleChanges.Snapshot> wanted = new LinkedHashMap<>();
            for (ModuleInfo info : list()) {
                if (!info.primary || !info.enabled || !ModuleSide.of(info.side).runsOnServer()) continue;
                files.put(info.id, info);
                try {
                    if (info.error != null && !LOAD_ERRORS.containsKey(info.id)) throw new IOException(info.error);
                    wanted.put(info.id, ModuleChanges.snapshot(info));
                } catch (IOException | RuntimeException error) {
                    failures.add(info.id + ": " + error.getMessage());
                    if (previous.containsKey(info.id)) wanted.put(info.id, previous.get(info.id));
                }
            }
            var plan = ModuleChanges.compare(previous, wanted);
            Set<String> changed = new LinkedHashSet<>(plan.restart()); changed.addAll(plan.removed());
            var manager = ProjectManager.get();
            var projectChanges = net.xmilon.craftpy.lifecycle.ProjectController.get().changedProjects();
            Set<String> affectedProjects = new LinkedHashSet<>();
            affectedProjects.addAll(projectChanges.restart());
            synchronized (LOCK) { affectedProjects.addAll(PENDING_PROJECTS); }
            if (manager != null) for (var project : manager.loadedProjects()) {
                var meta = manager.metadataOf(project.id());
                if (meta != null && meta.dependencies != null && meta.dependencies.stream()
                        .map(ModuleLoader::dependencyId).anyMatch(changed::contains)) affectedProjects.add(project.id());
            }
            if (manager != null) for (String project : net.xmilon.craftpy.lifecycle.ProjectController.get().loadingProjects()) {
                var meta = manager.metadataOf(project);
                if (meta != null && meta.dependencies != null && meta.dependencies.stream()
                        .map(ModuleLoader::dependencyId).anyMatch(changed::contains)) affectedProjects.add(project);
            }
            net.xmilon.craftpy.lifecycle.ProjectController.beginPackBatch();
            try {
                for (String project : affectedProjects) {
                    checkRevision(revision);
                    var previousLoad = net.xmilon.craftpy.lifecycle.ProjectController.get().prepareModuleReload(project);
                    net.xmilon.craftpy.python.PyRuntime.get().unloadOwner(project, false).join();
                    previousLoad.join();
                    synchronized (LOCK) { PENDING_PROJECTS.add(project); }
                }
                for (String id : changed) {
                    checkRevision(revision);
                    if (!previous.containsKey(id) && !isLoaded(id)) continue;
                    net.xmilon.craftpy.python.PyRuntime.get().unloadOwner(id, true).join();
                    synchronized (LOCK) {
                        LOADED_MODULES.remove(id); MODULE_FILES.remove(id); APPLIED.remove(id);
                        if (plan.removed().contains(id)) PENDING_UNLOAD.add(id);
                    }
                    runOnServer(() -> {
                        net.xmilon.craftpy.command.ProjectCommandRegistry.unregister(id);
                        SlotRegistry.releaseProject(id);
                    }).join();
                    if (plan.removed().contains(id)) stopped++;
                }
                var dependencies = new LinkedHashMap<String, List<String>>();
                wanted.forEach((id, snapshot) -> dependencies.put(id, snapshot.dependencies()));
                Set<String> failedIds = new HashSet<>();
                for (String id : net.xmilon.craftpy.analysis.ModuleGraph.loadOrder(dependencies)) {
                    checkRevision(revision);
                    if (!plan.restart().contains(id) && !plan.resources().contains(id)) continue;
                    ModuleInfo info = files.get(id);
                    try {
                        String problem = ModuleChanges.dependencyProblem(id, wanted);
                        if (!problem.isEmpty()) throw new IOException(problem);
                        for (String dependency : info.dependencies) {
                            String dep = dependencyId(dependency);
                            if (!wanted.containsKey(dep) || failedIds.contains(dep))
                                throw new IOException("Dependency is disabled, missing or failed: " + dep);
                        }
                        Path cache = PyForgePaths.cache().resolve(id);
                        if (plan.restart().contains(id)) {
                            if (Files.isDirectory(cache)) ProjectManager.deleteRecursively(cache);
                            loadModule(Path.of(info.path)).join();
                            if (!isLoaded(id)) throw new IOException("Module did not load");
                            if (previous.containsKey(id)) restarted++; else loaded++;
                        } else {
                            replaceResources(info, wanted.get(id), cache);
                            synchronized (LOCK) { APPLIED.put(id, wanted.get(id)); }
                        }
                    } catch (Exception error) {
                        failedIds.add(id);
                        String message = error.getCause() == null ? error.getMessage() : error.getCause().getMessage();
                        failures.add(id + ": " + message);
                        synchronized (LOCK) { LOAD_ERRORS.put(id, message); }
                    }
                }
                if (manager != null) {
                    for (String project : affectedProjects) {
                        checkRevision(revision);
                        var meta = manager.metadataOf(project);
                        try {
                            if (meta == null) throw new IOException("Project no longer exists");
                            if (meta.dependencies != null && meta.dependencies.stream().map(ModuleLoader::dependencyId)
                                    .anyMatch(dep -> !isLoaded(dep))) {
                                runOnServer(() -> net.xmilon.craftpy.lifecycle.ProjectController.get()
                                        .unload(project, ServerLifecycleHooks.getCurrentServer())).join();
                                throw new IOException("Dependency is disabled, missing or failed");
                            }
                            boolean ok = net.xmilon.craftpy.lifecycle.ProjectController.get()
                                    .reloadForModules(project, ServerLifecycleHooks.getCurrentServer()).join();
                            if (!ok) throw new IOException("Project reload failed");
                            restarted++;
                            synchronized (LOCK) { PENDING_PROJECTS.remove(project); }
                        } catch (Exception error) {
                            failures.add("project " + project + ": " + error.getMessage());
                        }
                    }
                }
                synchronized (LOCK) {
                    resourcesPending |= !changed.isEmpty() || !plan.resources().isEmpty() || !projectChanges.resources().isEmpty();
                    if (resourcesPending) triggerModuleSync("*");
                }
            } finally {
                try {
                    net.xmilon.craftpy.lifecycle.ProjectController.endPackBatch().join();
                    synchronized (LOCK) {
                        if (revision == stateRevision) {
                            net.xmilon.craftpy.lifecycle.ProjectController.get().acknowledgeProjectResources(projectChanges);
                            resourcesPending = false;
                        }
                    }
                }
                catch (RuntimeException error) {
                    failures.add("Resources: " + error.getMessage());
                    synchronized (LOCK) { if (revision == stateRevision) resourcesPending = true; }
                }
            }
            var result = new ModuleChanges.Result(restarted, plan.kept().size(), loaded, stopped, failures,
                    (System.nanoTime() - start) / 1_000_000);
            CraftPyLog.LOGGER.info("Modules: {}", result.summary());
            return result;
        }, CraftPyExecutors.io()).whenComplete((result, error) -> APPLYING.set(false));
    }

    private static CompletableFuture<Void> runOnServer(Runnable work) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) { work.run(); return CompletableFuture.completedFuture(null); }
        final long revision;
        synchronized (LOCK) { revision = stateRevision; }
        return trackServerWork(server.submit(() -> { checkRevision(revision); work.run(); }));
    }

    private static <T> CompletableFuture<T> trackServerWork(CompletableFuture<T> future) {
        SERVER_WORK.add(future);
        return future.whenComplete((ignored, error) -> SERVER_WORK.remove(future));
    }

    private static void cancelServerWork() {
        for (var future : List.copyOf(SERVER_WORK)) future.completeExceptionally(new IllegalStateException("World changed during reload"));
    }

    private static void checkRevision(long revision) {
        synchronized (LOCK) {
            if (revision != stateRevision) throw new IllegalStateException("World changed during module reload");
        }
    }

    private static void replaceResources(ModuleInfo info, ModuleChanges.Snapshot expected, Path cache) throws IOException {
        Path stage = Files.createTempDirectory(PyForgePaths.cache(), "module-resources-");
        List<String> moved = new ArrayList<>(), installed = new ArrayList<>();
        boolean keepBackup = false;
        try {
            try (var zip = new ZipFile(info.path)) {
                ModuleArchive.extract(zip, stage, name -> name.startsWith("assets/") || name.startsWith("data/"));
            }
            if (!expected.equals(ModuleChanges.snapshot(info))) throw new IOException("Module changed during RELOAD; retry");
            Files.createDirectories(cache);
            try {
                for (String folder : List.of("assets", "data")) {
                    Path target = cache.resolve(folder), replacement = stage.resolve(folder);
                    if (Files.exists(target)) { Files.move(target, stage.resolve(folder + ".previous")); moved.add(folder); }
                    if (Files.exists(replacement)) { Files.move(replacement, target); installed.add(folder); }
                }
            } catch (IOException error) {
                try {
                    for (String folder : installed) ProjectManager.deleteRecursively(cache.resolve(folder));
                    for (String folder : moved) Files.move(stage.resolve(folder + ".previous"), cache.resolve(folder));
                } catch (IOException restore) {
                    keepBackup = true;
                    error.addSuppressed(restore);
                    throw new IOException("Could not restore resources; backup retained at " + stage, error);
                }
                throw error;
            }
        } finally { if (!keepBackup) ProjectManager.deleteRecursively(stage); }
    }

    public record Listing(List<ModuleInfo> modules, List<String> pendingProjects) { }

    public static Listing listing() {
        var changes = net.xmilon.craftpy.lifecycle.ProjectController.get().changedProjects();
        Set<String> projects = new LinkedHashSet<>(changes.restart()); projects.addAll(changes.resources());
        synchronized (LOCK) { projects.addAll(PENDING_PROJECTS); }
        return new Listing(list(), List.copyOf(projects));
    }

    /** Deletes a .cpm file from disk and forgets it if it was loaded. */
    public static boolean delete(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        synchronized (LOCK) {
            String id = null;
            for (Map.Entry<String, Path> entry : new HashMap<>(MODULE_FILES).entrySet()) {
                if (entry.getValue().equals(file)) {
                    id = entry.getKey();
                    break;
                }
            }
            String declared = id;
            if (declared == null) {
                try {
                    declared = readInfo(file).id;
                } catch (IOException unreadable) {
                    declared = null;
                }
            }
            Files.delete(file);
            if (declared != null && findFileById(declared) == null) {
                DecompileLog.forget(declared); // the module is gone: nothing left to compare with
            }
            return true;
        }
    }

    /** True when a disable/delete is still pending a restart to take full effect. */
    public static boolean restartRequired() {
        synchronized (LOCK) {
            return !PENDING_UNLOAD.isEmpty();
        }
    }

    /**
     * Scans every .cpm under {@code pyforge/modules/} and returns a description of each,
     * including enable/load state and duplicate-id information.
     */
    public static List<ModuleInfo> list() {
        synchronized (LOCK) {
            loadState();
            List<Path> files = scanAll();
            List<ModuleInfo> infos = new ArrayList<>();
            for (Path file : files) {
                try {
                    ModuleInfo info = readInfo(file);
                    info.loaded = MODULE_FILES.containsKey(info.id) && file.equals(MODULE_FILES.get(info.id))
                            && LOADED_MODULES.containsKey(info.id);
                    info.pendingRestart = PENDING_UNLOAD.contains(info.id);
                    // The README is fetched per-module to keep the list packet small.
                    info.readme = "";
                    infos.add(info);
                } catch (IOException e) {
                    ModuleInfo info = new ModuleInfo();
                    info.path = file.toAbsolutePath().toString();
                    info.id = fileNameId(file);
                    info.name = info.id;
                    info.source = sourceOf(file);
                    info.error = e.getMessage();
                    infos.add(info);
                }
            }
            markDuplicates(infos);
            Map<String, ModuleChanges.Snapshot> wanted = new LinkedHashMap<>();
            Set<String> present = new HashSet<>();
            for (var info : infos) {
                if (!info.primary || !ModuleSide.of(info.side).runsOnServer()) continue;
                present.add(info.id);
                if (info.enabled) {
                    try { wanted.put(info.id, ModuleChanges.snapshot(info)); }
                    catch (IOException | RuntimeException error) { info.error = error.getMessage(); }
                }
            }
            var changes = ModuleChanges.compare(APPLIED, wanted);
            for (var info : infos) {
                if (!info.primary || !ModuleSide.of(info.side).runsOnServer()) continue;
                info.pendingReload = changes.restart().contains(info.id) || changes.resources().contains(info.id)
                        || changes.removed().contains(info.id);
                info.pendingReason = !info.pendingReload ? "" : !info.enabled ? "Disable on RELOAD"
                        : APPLIED.containsKey(info.id) ? "Files, dependencies or permissions changed" : "Enable on RELOAD";
                if (LOAD_ERRORS.containsKey(info.id)) info.error = LOAD_ERRORS.get(info.id);
                if (info.enabled && info.error != null) {
                    info.pendingReload = true;
                    info.pendingReason = info.error;
                }
                if (resourcesPending) { info.pendingReload = true; info.pendingReason = "Resources need RELOAD"; }
            }
            for (var entry : APPLIED.entrySet()) {
                if (present.contains(entry.getKey())) continue;
                var missing = new ModuleInfo();
                missing.id = entry.getKey(); missing.name = entry.getKey(); missing.path = entry.getValue().path();
                missing.loaded = true; missing.enabled = false; missing.pendingReload = true;
                missing.pendingReason = "File removed; unload on RELOAD";
                infos.add(missing);
            }
            return infos;
        }
    }

    /** Loads a single .cpm into the runtime (used at startup and on enable). */
    public static CompletableFuture<Void> loadModule(Path path) throws IOException {
        synchronized (LOCK) {
            long revision = stateRevision;
            String sourceHash = sha256(path);
            if (sourceHash == null) throw new IOException("Cannot read module " + path);
            try (ZipFile zip = new ZipFile(path.toFile())) {
                ZipEntry metaEntry = zip.getEntry("module.json");
                if (metaEntry == null) {
                    throw new IOException("Missing module.json in " + path);
                }

                ModuleBuilder.ModuleMetadata meta;
                try (InputStream is = zip.getInputStream(metaEntry)) {
                    meta = Json.COMPACT.fromJson(new String(is.readAllBytes()), ModuleBuilder.ModuleMetadata.class);
                }
                if (meta == null || meta.id == null || meta.id.isBlank()) {
                    meta = new ModuleBuilder.ModuleMetadata();
                    meta.id = fileNameId(path);
                    meta.name = meta.id;
                }
                if (!ProjectManager.isValidId(meta.id)) {
                    // it names the cache folder (and its namespace): "../x" would point outside pyforge/
                    throw new IOException("'" + meta.id + "' is not a valid module id (lowercase letters, digits, _)");
                }

                if (LOADED_MODULES.containsKey(meta.id)) {
                    CraftPyLog.LOGGER.info("Module {} already loaded, skipping.", meta.id);
                    return CompletableFuture.completedFuture(null);
                }
                String unavailable = RuntimeFeatures.unavailable(meta.requiredMods, meta.requiredCapabilities);
                if (!unavailable.isEmpty()) { CraftPyLog.LOGGER.info("Module {} inactive: {}", meta.id, unavailable); return CompletableFuture.completedFuture(null); }
                if (!ModuleSide.of(meta.side).runsOnServer()) {
                    // resources only: each player's CraftPy applies it (dynamics.ClientModulePack)
                    CraftPyLog.LOGGER.info("Module {} is client-side: nothing runs on the server", meta.id);
                    return CompletableFuture.completedFuture(null);
                }

                ModuleInfo info = readInfo(path);
                if (!sourceHash.equals(info.sha256)) throw new IOException("Module changed during RELOAD; retry");
                ModuleChanges.Snapshot snapshot = ModuleChanges.snapshot(info);
                LOAD_ERRORS.remove(meta.id);
                // Extract code/assets/data to a persistent cache for the current session
                Path cacheDir = PyForgePaths.cache().resolve(meta.id);
                extractZip(zip, cacheDir);
                if (!sourceHash.equals(sha256(path))) throw new IOException("Module changed during RELOAD; retry");

                LOADED_MODULES.put(meta.id, meta);
                String owner = "module:" + meta.id;
                net.xmilon.craftpy.security.PermissionStore.declare(owner, meta.permissions, sha256(path));
                String warning = net.xmilon.craftpy.security.PermissionLabels.warning(
                        net.xmilon.craftpy.security.ModulePermissions.clean(meta.permissions));
                if (!warning.isEmpty()) {
                    String state = net.xmilon.craftpy.security.PermissionStore.changedSinceApproval(owner)
                            ? " (this module CHANGED since it was approved: review it again on the Modules page)"
                            : net.xmilon.craftpy.security.PermissionStore.fullyApproved(owner) ? ""
                            : " (not approved yet: Modules page)";
                    net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(ServerLifecycleHooks.getCurrentServer(),
                            "[module " + meta.id + "] " + warning + state);
                }
                MODULE_FILES.put(meta.id, path);
                CraftPyLog.LOGGER.info("Loaded module: {} ({})", meta.name, meta.id);

                // Wire into the Python runtime as a persistent module and apply declarations to slots
                final String moduleId = meta.id;
                final ModuleBuilder.ModuleMetadata loading = meta;
                CompletableFuture<JsonObject> future = net.xmilon.craftpy.python.PyRuntime.get().loadModule(moduleId, cacheDir);
                return future.thenCompose(result -> {
                    if (result == null || !result.has("declarations"))
                        return CompletableFuture.failedFuture(new IOException("Module returned no declarations"));
                    Declarations declarations = Declarations.parse(result, "module:" + moduleId);
                    if (declarations.failed())
                        return CompletableFuture.failedFuture(new IOException(declarations.error()));
                    var completion = new CompletableFuture<Void>();
                    trackServerWork(completion);
                    Runnable apply = () -> {
                        try {
                            synchronized (LOCK) {
                                checkRevision(revision);
                                if (LOADED_MODULES.get(moduleId) != loading) throw new IOException("Module load was superseded");
                            }
                            applyModuleDeclarations(moduleId, declarations);
                            synchronized (LOCK) {
                                APPLIED.put(moduleId, snapshot); LOAD_ERRORS.remove(moduleId); PENDING_UNLOAD.remove(moduleId);
                            }
                            completion.complete(null);
                        } catch (Exception error) { completion.completeExceptionally(error); }
                    };
                    var server = ServerLifecycleHooks.getCurrentServer();
                    if (server == null) apply.run(); else server.execute(apply);
                    return completion;
                }).whenComplete((ignored, error) -> {
                    if (error != null) {
                        boolean current;
                        synchronized (LOCK) {
                            current = revision == stateRevision && LOADED_MODULES.get(moduleId) == loading;
                            if (current) {
                                LOAD_ERRORS.put(moduleId, error.getMessage() == null ? error.toString() : error.getMessage());
                                LOADED_MODULES.remove(moduleId); MODULE_FILES.remove(moduleId);
                            }
                        }
                        if (current) net.xmilon.craftpy.python.PyRuntime.get().unloadOwner(moduleId, true);
                        CraftPyLog.LOGGER.error("Failed to load module {}", moduleId, error);
                    }
                });
            }
        }
    }

    /** Applies a module's declarations to the slot registry (like a project). */
    private static void applyModuleDeclarations(String moduleId, Declarations declarations) {
        try {
            SlotRegistry.applyAll(moduleId, declarations);
            try {
                net.xmilon.craftpy.command.ProjectCommandRegistry.register(moduleId, net.xmilon.craftpy.python.PyRuntime.owner(moduleId, true), declarations);
            } catch (Exception e) {
                CraftPyLog.LOGGER.warn("Failed to register module commands for {}", moduleId, e);
            }

            // Rebuild packs and sync to clients (like ProjectController.afterChange)
            triggerModuleSync(moduleId);
        } catch (RuntimeException e) {
            CraftPyLog.LOGGER.error("Slot allocation failed for module {}", moduleId, e);
            net.xmilon.craftpy.python.PyRuntime.get().revertOwner(moduleId, true);
            throw e;
        }
    }

    /** Rebuilds dynamic packs and notifies clients after a module change (folded with other changes). */
    private static void triggerModuleSync(String moduleId) {
        net.xmilon.craftpy.lifecycle.ProjectController.refreshPacks(ServerLifecycleHooks.getCurrentServer());
    }

    /**
     * The assets folder of a running module (unpacked in the cache), or null when {@code id} is not a module or ships
     * no assets ({@code server} side). The pack builder reads projects from their folder and modules from here.
     */
    public static Path assetsOf(String id) {
        synchronized (LOCK) {
            ModuleBuilder.ModuleMetadata meta = LOADED_MODULES.get(id);
            if (meta == null || !ModuleSide.of(meta.side).hasAssets()) {
                return null;
            }
            return PyForgePaths.cache().resolve(id).resolve("assets");
        }
    }

    /** True when {@code id} is a running server-side module (its assets stay out of the players' pack). */
    public static boolean isServerOnly(String id) {
        synchronized (LOCK) {
            ModuleBuilder.ModuleMetadata meta = LOADED_MODULES.get(id);
            return meta != null && ModuleSide.of(meta.side) == ModuleSide.SERVER;
        }
    }

    /** Reads a .cpm's metadata and README without loading it into the runtime. */
    public static ModuleInfo readInfo(Path path) throws IOException {        ModuleInfo info = new ModuleInfo();
        info.path = path.toAbsolutePath().toString();
        info.source = sourceOf(path);
        try {
            info.sizeBytes = Files.size(path);
            info.modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry metaEntry = zip.getEntry("module.json");
            if (metaEntry == null) {
                throw new IOException("Missing module.json");
            }
            ModuleBuilder.ModuleMetadata meta;
            try (InputStream is = zip.getInputStream(metaEntry)) {
                meta = Json.COMPACT.fromJson(new String(is.readAllBytes()), ModuleBuilder.ModuleMetadata.class);
            }
            if (meta != null) {
                info.id = meta.id == null || meta.id.isBlank() ? fileNameId(path) : meta.id;
                info.name = meta.name == null || meta.name.isBlank() ? info.id : meta.name;
                info.version = meta.version == null ? "" : meta.version;
                info.author = meta.author == null ? "" : meta.author;
                info.description = meta.description == null ? "" : meta.description;
                info.side = ModuleSide.of(meta.side).id();
                String unavailable = RuntimeFeatures.unavailable(meta.requiredMods, meta.requiredCapabilities);
                if (!unavailable.isEmpty()) info.error = unavailable;
                info.permissions = net.xmilon.craftpy.security.ModulePermissions.clean(meta.permissions);
                if (meta.dependencies != null) {
                    info.dependencies = new ArrayList<>(Arrays.asList(meta.dependencies));
                }
                var cleaned = ModuleApp.clean(meta.apps);
                info.apps = cleaned.apps();
                info.appProblems = new ArrayList<>(cleaned.problems());
                info.icon = ModuleApp.icon(meta.icon);
                if (!info.icon.isEmpty() && !ModuleApp.isItem(info.icon) && !validPng(zip, info.icon)) {
                    info.appProblems.add("Icon '" + info.icon + "' is not a PNG of at most 256 KiB and 256 x 256 under assets/");
                    info.icon = "";
                }
            } else {
                info.id = fileNameId(path);
                info.name = info.id;
            }
            info.readme = readReadme(zip);
        }
        if (info.id == null || info.id.isBlank()) {
            info.id = fileNameId(path);
        }
        info.enabled = isEnabledState(info.id);
        Path loaded;
        synchronized (LOCK) {
            loaded = MODULE_FILES.get(info.id);
        }
        // a duplicate .cpm with the same id must not stand in for the file that runs
        String hash = loaded == null || loaded.toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize()) ? sha256(path) : null;
        info.sha256 = hash == null ? "" : hash;
        if (hash != null) net.xmilon.craftpy.security.PermissionStore.declare("module:" + info.id, info.permissions, hash);
        info.permissionWarning = net.xmilon.craftpy.security.PermissionLabels.warning(info.permissions);
        info.permissionsApproved = net.xmilon.craftpy.security.PermissionStore.fullyApproved("module:" + info.id);
        info.changedSinceApproval = net.xmilon.craftpy.security.PermissionStore.changedSinceApproval("module:" + info.id);
        DecompileLog.Entry decompiled = DecompileLog.of(info.id);
        if (decompiled != null) {
            info.decompiledTo = decompiled.project();
            info.changedSinceDecompile = DecompileLog.changed(info.id, sha256(path));
        }
        if (!ProjectManager.isValidId(info.id)) {
            info.error = "'" + info.id + "' is not a valid module id: this module is not loaded";
        }
        return info;
    }

    /** An icon file of the archive: a real PNG, small, so a module cannot make the Hub decode a huge image. */
    static boolean validPng(ZipFile zip, String icon) {
        ZipEntry entry = zip.getEntry("assets/" + icon);
        if (entry == null || entry.isDirectory() || entry.getSize() > 256 * 1024) {
            return false;
        }
        try (InputStream is = zip.getInputStream(entry)) {
            byte[] head = is.readNBytes(24);
            if (head.length < 24 || (head[0] & 0xFF) != 0x89 || head[1] != 'P' || head[2] != 'N' || head[3] != 'G') {
                return false;
            }
            int w = java.nio.ByteBuffer.wrap(head, 16, 4).getInt(), h = java.nio.ByteBuffer.wrap(head, 20, 4).getInt();
            return w > 0 && h > 0 && w <= 256 && h <= 256;
        } catch (IOException e) {
            return false;
        }
    }

    private static String readReadme(ZipFile zip) {
        for (String candidate : new String[] { "README.md", "readme.md", "README.MD", "Readme.md" }) {
            ZipEntry entry = zip.getEntry(candidate);
            if (entry != null) {
                try (InputStream is = zip.getInputStream(entry)) {
                    byte[] bytes = is.readAllBytes();
                    String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    return text.length() > 20000 ? text.substring(0, 20000) + "\n\n(truncated)" : text;
                } catch (IOException ignored) {
                    return "";
                }
            }
        }
        return "";
    }

    /** Reads just the bundled README (markdown) of a .cpm, or "" when absent. */
    public static String readmeFor(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) {
            return "";
        }
        Path path = Path.of(absolutePath);
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            return readReadme(zip);
        } catch (IOException e) {
            CraftPyLog.LOGGER.error("Failed to read README from {}", path, e);
            return "";
        }
    }

    /** Finds the enabled .cpm file that declares {@code id} (first match wins). */
    public static Path findFileById(String id) {        if (id == null) {
            return null;
        }
        synchronized (LOCK) {
            Path running = MODULE_FILES.get(id);
            if (running != null && Files.isRegularFile(running)) return running;
        }
        for (Path file : scanAll()) {
            try {
                ModuleInfo info = readInfo(file);
                if (id.equals(info.id)) {
                    return file;
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    public static Collection<ModuleBuilder.ModuleMetadata> loadedModules() {
        synchronized (LOCK) {
            return List.copyOf(LOADED_MODULES.values());
        }
    }

    // ------------------------------------------------------------------
    // Scanning helpers
    // ------------------------------------------------------------------

    /**
     * All .cpm files under pyforge/modules/, sorted by path for deterministic order. Files dropped in
     * the old pyforge/imports/ are moved there first; nothing else is ever a module source.
     */
    public static List<Path> scanAll() {
        collectImports();
        List<Path> out = new ArrayList<>();
        walk(PyForgePaths.modules(), out);
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    /**
     * pyforge/imports/ and pyforge/exports/ are gone (modules live in modules/, builds go there too). Worlds made before
     * that: .cpm files dropped in imports/ are moved into modules/ (a name already there stays put) and the empty
     * folders are removed. exports/ only held copies of built modules; a non-empty one is left alone, never deleted.
     */
    private static void collectImports() {
        Path imports = PyForgePaths.root().resolve("imports");
        if (Files.isDirectory(imports)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(imports, "*.cpm")) {
                Files.createDirectories(PyForgePaths.modules());
                for (Path file : stream) {
                    Path target = PyForgePaths.modules().resolve(file.getFileName());
                    if (Files.exists(target)) {
                        CraftPyLog.LOGGER.warn("imports/{} left in place: modules/ already has a file with that name",
                                file.getFileName());
                    } else {
                        Files.move(file, target);
                        CraftPyLog.LOGGER.info("Moved module {} from the old imports/ folder into modules/", file.getFileName());
                    }
                }
            } catch (IOException e) {
                CraftPyLog.LOGGER.error("Error moving modules out of {}", imports, e);
            }
        }
        for (String old : new String[]{"imports", "exports"}) {
            try {
                Files.deleteIfExists(PyForgePaths.root().resolve(old)); // only succeeds when the folder is empty
            } catch (IOException notEmpty) {
                // files are still there: leave them for the user
            }
        }
    }

    private static void walk(Path dir, List<Path> out) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    walk(entry, out);
                } else if (entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cpm")) {
                    out.add(entry);
                }
            }
        } catch (IOException e) {
            CraftPyLog.LOGGER.error("Error scanning modules in {}", dir, e);
        }
    }

    /** A module is untrusted: names that leave the folder, devices, oversized content are refused (ModuleArchive). */
    private static void extractZip(ZipFile zip, Path target) throws IOException {
        ModuleArchive.Report report = ModuleArchive.extract(zip, target, name -> true);
        if (!report.skipped().isEmpty()) {
            CraftPyLog.LOGGER.warn("Module {}: left out {}", target.getFileName(), report.skipped());
        }
    }

    /**
     * The module file at {@code raw} when it is one of the modules CraftPy found under pyforge/ - requests from the IDE
     * name modules by path, and a path that is not one of them (any other file on the computer) is refused.
     */
    public static Path knownModuleFile(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path wanted;
        try {
            wanted = Path.of(raw).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            return null;
        }
        for (Path file : scanAll()) {
            if (file.toAbsolutePath().normalize().equals(wanted)) {
                return file;
            }
        }
        return null;
    }

    private static String sourceOf(Path file) {
        Path root = PyForgePaths.root().toAbsolutePath();
        Path abs = file.toAbsolutePath();
        try {
            Path rel = root.relativize(abs);
            Path parent = rel.getParent();
            return parent == null ? "(root)" : parent.toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return "(external)";
        }
    }

    private static String fileNameId(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Marks all but one file per id as duplicates (the first enabled wins). */
    static void markDuplicates(List<ModuleInfo> infos) {
        Map<String, Integer> count = new HashMap<>();
        for (ModuleInfo info : infos) {
            count.merge(info.id, 1, Integer::sum);
        }
        for (ModuleInfo info : infos) {
            // Unique ids are trivially their own primary; duplicated ids start
            // unselected and are resolved below.
            info.primary = count.getOrDefault(info.id, 0) <= 1;
        }
        Set<String> selected = new HashSet<>();
        for (ModuleInfo info : infos) if (info.loaded && count.getOrDefault(info.id, 0) > 1 && selected.add(info.id)) info.primary = true;
        // Prefer the actual running file, then the first enabled file.
        for (ModuleInfo info : infos) {
            if (count.getOrDefault(info.id, 0) > 1 && info.enabled && selected.add(info.id)) {
                info.primary = true;
            }
        }
        // Second pass: ids where every file is disabled still get one primary.
        for (ModuleInfo info : infos) {
            if (count.getOrDefault(info.id, 0) > 1 && !selected.contains(info.id)) {
                selected.add(info.id);
                info.primary = true;
            }
        }
        // Everything else that shares a duplicated id is a duplicate.
        for (ModuleInfo info : infos) {
            if (count.getOrDefault(info.id, 0) > 1 && !info.primary) {
                info.duplicate = true;
            }
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private static Path stateFile() {
        return worldStateFile != null ? worldStateFile : PyForgePaths.moduleStateFile();
    }

    private static void loadState() {
        if (stateLoaded) {
            return;
        }
        stateLoaded = true;
        state = new ModuleState();
        Path file = stateFile();
        if (Files.isRegularFile(file)) {
            try {
                ModuleState loaded = Json.read(file, ModuleState.class);
                if (loaded != null) {
                    state = loaded;
                }
            } catch (IOException e) {
                CraftPyLog.LOGGER.error("Failed to read {}", file, e);
            }
        }
        if (state.disabled == null) {
            state.disabled = new ArrayList<>();
        }
        if (worldStateFile != null && state.enabled == null) {
            state.enabled = new ArrayList<>();
        }
    }

    private static void saveState() {
        try {
            Json.write(stateFile(), state);
        } catch (IOException e) {
            CraftPyLog.LOGGER.error("Failed to write {}", stateFile(), e);
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static boolean isEnabledState(String id) {
        loadState();
        return state.enabled != null ? state.enabled.contains(id) : !state.disabled.contains(id);
    }
}
