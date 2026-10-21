package net.xmilon.craftpy.lifecycle;

import net.xmilon.craftpy.CraftPyLog;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.server.MinecraftServer;
import net.xmilon.craftpy.Config;
import net.xmilon.craftpy.dynamics.PackBuilder;
import net.xmilon.craftpy.network.CraftPyNetwork;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.project.ModuleChanges;
import net.xmilon.craftpy.python.Declarations;
import net.xmilon.craftpy.python.PyRuntime;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.util.CraftPyExecutors;

/**
 * The LOAD / UNLOAD lifecycle (server-authoritative).
 *
 * <pre>
 * load:   read main.py -> run on worker -> allocate slots &amp; map
 *         definitions -> rebuild packs -> mark loaded -> reload data + sync client
 * unload: detach definitions (mapping persists) -> rebuild packs (placeholders)
 *         -> mark unloaded -> reload data + sync client
 * </pre>
 *
 * Blocking steps run on the worker pool; the game thread receives only
 * completion callbacks. On a failed load the previous known-good state is kept
 * (rollback on failure).
 */
public final class ProjectController {
    private static final ProjectController INSTANCE = new ProjectController();

    /** Projects whose worker run is in flight; saves arriving meanwhile are folded into one more run. */
    private final java.util.Set<String> loading = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> loadAgain = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Long> loadRevisions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, CompletableFuture<Void>> loadCompletions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, ModuleChanges.Snapshot> appliedProjectSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, CompletableFuture<Boolean>> moduleReloadCompletions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, java.util.concurrent.ScheduledFuture<?>> pendingReloads =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService debounce =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "craftpy-hot-reload");
                thread.setDaemon(true);
                return thread;
            });

    private ProjectController() {
    }

    public static ProjectController get() {
        return INSTANCE;
    }

    public void load(String projectId, MinecraftServer server) {
        load(projectId, server, false);
    }

    public synchronized void load(String projectId, MinecraftServer server, boolean debug) {
        ProjectManager sides = ProjectManager.get();
        if (sides != null) {
            net.xmilon.craftpy.project.ProjectMetadata meta = sides.metadataOf(projectId);
            if (meta != null) {
                net.xmilon.craftpy.security.PermissionStore.declareProject(projectId, meta.permissions);
                String warning = net.xmilon.craftpy.security.PermissionLabels.warning(
                        net.xmilon.craftpy.security.ModulePermissions.clean(meta.permissions));
                if (!warning.isEmpty()) {
                    boolean own = net.xmilon.craftpy.security.PermissionStore.isTrusted(projectId);
                    net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(server, "[" + projectId + "] "
                            + warning.replace("This module", "This project").replace(" — continue at your own risk!", "")
                            + (own ? " (your own project: allowed as declared in project.json)"
                                    : net.xmilon.craftpy.security.PermissionStore.fullyApproved(projectId) ? " (approved)"
                                    : " (NOT made on this computer, so nothing is allowed yet: read its code, then"
                                            + " /craftpy project trust " + projectId + ")"));
                }
            }
        }
        if (sides != null && sides.sideOf(projectId) == net.xmilon.craftpy.project.ModuleSide.CLIENT) {
            CraftPyLog.LOGGER.info("Project {} is client-side: it runs on the player's computer, not in the world", projectId);
            return;
        }
        if (!loading.add(projectId)) {
            loadAgain.add(projectId); // run once more when the current run is done
            return;
        }
        if (!Config.get(Config.ENABLE_SCRIPTING)) {
            loading.remove(projectId);
            CraftPyLog.LOGGER.warn("CraftPy load blocked: scripting disabled in config");
            return;
        }
        if (!PyRuntime.available()) {
            loading.remove(projectId);
            CraftPyLog.LOGGER.warn("CraftPy load aborted: no supported Python runtime found");
            return;
        }
        ProjectManager manager = ProjectManager.get();
        if (manager == null) {
            loading.remove(projectId);
            return;
        }
        long revision = loadRevisions.getOrDefault(projectId, 0L);
        var completion = new CompletableFuture<Void>();
        loadCompletions.put(projectId, completion);
        ModuleChanges.Snapshot snapshot = projectSnapshot(projectId);
        manager.setLastError(projectId, null);

        manager.readFile(projectId, "code/main.py")
                .thenCompose(read -> {
                    if (!read.success()) {
                        return CompletableFuture.completedFuture(Declarations.error(read.message()));
                    }
                    return manager.readSources(projectId).thenCompose(sources -> revision != loadRevisions.getOrDefault(projectId, 0L)
                            ? CompletableFuture.completedFuture(Declarations.error("Superseded by module reload"))
                            : PyRuntime.get().runMain(projectId, read.content(), "code/main.py", debug, sources));
                })
                .thenAccept(declarations -> {
                    Runnable finish = () -> {
                        try {
                            if (revision == loadRevisions.getOrDefault(projectId, 0L)) {
                                finishLoad(projectId, declarations, server);
                                rememberProjectSnapshot(projectId, snapshot);
                            }
                            else if (loadCompletions.get(projectId) == completion) loading.remove(projectId);
                        } finally {
                            completion.complete(null); loadCompletions.remove(projectId, completion);
                        }
                    };
                    if (server != null) {
                        server.execute(finish);
                    } else {
                        finish.run();
                    }
                })
                .exceptionally(error -> {
                    if (loadCompletions.get(projectId) == completion) loading.remove(projectId);
                    CraftPyLog.LOGGER.error("Load pipeline failed for {}", projectId, error);
                    manager.markLoaded(projectId, false);
                    manager.setLastError(projectId, "Load pipeline failed: " + error.getMessage());
                    net.xmilon.craftpy.network.CraftPyNetwork.notifyEditors(server, projectId + " failed - details in the IDE console", true);
                    if (server != null) {
                        server.execute(() -> {});
                    }
                    completion.complete(null); loadCompletions.remove(projectId, completion);
                    return null;
                });
    }

    /** Runs on the server thread. */
    private void finishLoad(String projectId, Declarations declarations, MinecraftServer server) {
        loading.remove(projectId);
        if (loadAgain.remove(projectId)) {
            // Saved again while this run was going: this result is already stale.
            PyRuntime.get().revert(projectId);
            load(projectId, server);
            return;
        }
        try {
            ProjectManager manager = ProjectManager.get();
            if (declarations == null || declarations.failed()) {
                String message = declarations == null ? "worker returned nothing"
                        : declarations.error() + (declarations.errorLine() >= 0
                                ? " (line " + (declarations.errorLine() + 1) + ")" : "");
                if (SlotRegistry.declarations(projectId) != null) {
                    // Rollback: keep last known good definitions running.
                    CraftPyLog.LOGGER.warn("Project {} reload failed, keeping previous version: {}", projectId, message);
                    manager.setLastError(projectId, "Reload failed - previous version kept: " + message);
                    net.xmilon.craftpy.network.CraftPyNetwork.notifyEditors(server, projectId + " failed - details in the IDE console", true);
                } else {
                    manager.markLoaded(projectId, false);
                    manager.setLastError(projectId, message);
                    net.xmilon.craftpy.network.CraftPyNetwork.notifyEditors(server, projectId + " failed - details in the IDE console", true);
                    CraftPyLog.LOGGER.error("Project {} failed to load: {}", projectId, message);
                }
                net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(server,
                        "[" + projectId + "] ERROR: " + message);
                afterChange(server);
                return;
            }

            boolean failed = false;
            List<String> notes = new ArrayList<>();
            int blocks = 0;
            int items = 0;
            int mobs = 0;
            int effects = 0;
            for (Declarations.Content content : declarations.contents()) {
                switch (content.kind()) {
                    case "block" -> blocks++;
                    case "item" -> items++;
                    case "entity" -> mobs++;
                    case "effect" -> effects++;
                    default -> {
                    }
                }
            }
            if (mobs > 0 && declarations.contents().stream().anyMatch(c -> "entity".equals(c.kind())
                    && c.definition().has("spawns"))) {
                notes.add("natural spawns of new or changed mobs start the next time the world is opened");
            }
            try {
                SlotRegistry.applyAll(projectId, declarations);
            } catch (RuntimeException e) {
                // Nothing was applied: the previous version keeps its content, and the worker puts back its handlers.
                failed = true;
                CraftPyLog.LOGGER.error("Applying content of {} failed, previous version kept", projectId, e);
                PyRuntime.get().revert(projectId);
                String message = (e.getMessage() == null ? e.toString() : e.getMessage());
                if (SlotRegistry.declarations(projectId) != null) {
                    manager.setLastError(projectId, "Reload failed - previous version kept: " + message);
                    net.xmilon.craftpy.network.CraftPyNetwork.notifyEditors(server, projectId + " failed - details in the IDE console", true);
                } else {
                    manager.markLoaded(projectId, false);
                    manager.setLastError(projectId, message);
                    net.xmilon.craftpy.network.CraftPyNetwork.notifyEditors(server, projectId + " failed - details in the IDE console", true);
                }
                net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(server,
                        "[" + projectId + "] ERROR: " + message);
                return;
            }

            if (!failed) {
                if (declarations.requiresRestart()) {
                    manager.markRequiresRestart(projectId, true);
                    net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(server,
                            "[" + projectId + "] THIS PROJECT DECLARES A FULL GAME RESTART IS REQUIRED.");
                    net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(server,
                            "[" + projectId + "] Changes that affect registries cannot be hot-reloaded; run /craftpy game restart when ready.");
                }
                try {
                    net.xmilon.craftpy.command.ProjectCommandRegistry.register(projectId, declarations);
                } catch (Exception e) {
                    CraftPyLog.LOGGER.warn("Failed to register project commands for {}", projectId, e);
                }
                manager.markLoaded(projectId, true);
                manager.setLastError(projectId, null);
                net.xmilon.craftpy.network.CraftPyNetwork.notifyEditors(server,
                        projectId + " loaded: " + blocks + " block(s), " + items + " item(s)"
                                + (mobs > 0 ? ", " + mobs + " mob(s)" : "") + (effects > 0 ? ", " + effects + " effect(s)" : ""),
                        false);
                CraftPyLog.LOGGER.info("Loaded project {} ({} block(s), {} item(s), {} datapack entr{}), scripts: {}",
                        projectId, blocks, items, declarations.datapack().size(),
                        declarations.datapack().size() == 1 ? "y" : "ies", declarations.log());
                for (String note : notes) {
                    CraftPyLog.LOGGER.info("[{}] {}", projectId, note);
                    net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(server, "[" + projectId + "] " + note);
                }
                for (String line : declarations.log()) {
                    net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(server, "[" + projectId + "] " + line);
                }
            }
            afterChange(server);
        } catch (RuntimeException e) {
            CraftPyLog.LOGGER.error("Unexpected error while finishing load of {}", projectId, e);
            afterChange(server);
        }
    }

    /**
     * Debounced reload after a save (config {@code hotReloadOnSave}): a burst of saves makes one run, and only for
     * projects that are loaded. Safe to call from any thread.
     */
    public void scheduleReload(String projectId, MinecraftServer server) {
        ProjectManager manager = ProjectManager.get();
        var project = manager == null ? null : manager.getProject(projectId);
        if (project == null || !project.isLoaded() || server == null) {
            return;
        }
        var previous = pendingReloads.put(projectId, debounce.schedule(() -> {
            pendingReloads.remove(projectId);
            server.execute(() -> load(projectId, server));
        }, 700, java.util.concurrent.TimeUnit.MILLISECONDS));
        if (previous != null) {
            previous.cancel(false);
        }
    }

    public void unload(String projectId, MinecraftServer server) {
        ProjectManager manager = ProjectManager.get();
        if (manager == null) {
            return;
        }
        try {
            net.xmilon.craftpy.command.ProjectCommandRegistry.unregister(projectId);
        } catch (Exception e) {
            CraftPyLog.LOGGER.warn("Failed to unregister project commands for {}", projectId, e);
        }
        manager.markLoaded(projectId, false);
        appliedProjectSnapshots.remove(projectId);
        SlotRegistry.releaseProject(projectId);
        PyRuntime.get().unloadOwner(projectId, false);
        manager.setLastError(projectId, null);
        afterChange(server);
    }

    private void afterChange(MinecraftServer server) {
        refreshPacks(server);
    }

    public java.util.Set<String> loadingProjects() { return java.util.Set.copyOf(loading); }

    public record ProjectChanges(java.util.Set<String> restart, java.util.Set<String> resources,
                                 java.util.Map<String, ModuleChanges.Snapshot> snapshots) { }

    /** Disk scan only; callers run this alongside the module scan, off the game/UI threads. */
    public ProjectChanges changedProjects() {
        var restart = new java.util.LinkedHashSet<String>();
        var resources = new java.util.LinkedHashSet<String>();
        var snapshots = new java.util.LinkedHashMap<String, ModuleChanges.Snapshot>();
        var manager = ProjectManager.get();
        if (manager != null) for (var project : manager.loadedProjects()) {
            ModuleChanges.Snapshot before = appliedProjectSnapshots.get(project.id()), now = projectSnapshot(project.id());
            if (now != null) snapshots.put(project.id(), now);
            if (before == null || now == null || !before.runtime().equals(now.runtime())) restart.add(project.id());
            else if (!before.resources().equals(now.resources())) resources.add(project.id());
        }
        return new ProjectChanges(java.util.Set.copyOf(restart), java.util.Set.copyOf(resources), java.util.Map.copyOf(snapshots));
    }

    private ModuleChanges.Snapshot projectSnapshot(String id) {
        var manager = ProjectManager.get();
        var project = manager == null ? null : manager.getProject(id);
        try { return project == null ? null : ModuleChanges.projectSnapshot(project.directory()); }
        catch (java.io.IOException | RuntimeException error) { return null; }
    }

    private void rememberProjectSnapshot(String id, ModuleChanges.Snapshot snapshot) {
        var manager = ProjectManager.get();
        var project = manager == null ? null : manager.getProject(id);
        if (snapshot != null && project != null && project.isLoaded() && project.lastError() == null && !loading.contains(id))
            appliedProjectSnapshots.put(id, snapshot);
    }

    public void acknowledgeProjectResources(ProjectChanges changes) {
        changes.resources().forEach(id -> rememberProjectSnapshot(id, changes.snapshots().get(id)));
    }

    public synchronized void resetModuleReloadState() {
        for (String id : loading) loadRevisions.merge(id, 1L, Long::sum);
        appliedProjectSnapshots.clear();
        loadCompletions.values().forEach(future -> future.complete(null)); loadCompletions.clear();
        moduleReloadCompletions.values().forEach(future -> future.completeExceptionally(new IllegalStateException("World closed")));
        moduleReloadCompletions.clear(); loading.clear(); loadAgain.clear();
    }

    /** Invalidates an in-flight run before a dependency's files are replaced. */
    public synchronized CompletableFuture<Void> prepareModuleReload(String id) {
        loadRevisions.merge(id, 1L, Long::sum);
        loadAgain.remove(id);
        return loadCompletions.getOrDefault(id, CompletableFuture.completedFuture(null));
    }

    /** Dependency reloads use the same declaration handling, but await the server-thread result. */
    public synchronized CompletableFuture<Boolean> reloadForModules(String id, MinecraftServer server) {
        var manager = ProjectManager.get();
        if (manager == null || !loading.add(id)) return CompletableFuture.completedFuture(false);
        ModuleChanges.Snapshot snapshot = projectSnapshot(id);
        long revision = loadRevisions.getOrDefault(id, 0L);
        var done = new CompletableFuture<Boolean>();
        moduleReloadCompletions.put(id, done);
        PyRuntime.get().unloadOwner(id, false).thenCompose(ignored -> manager.readFile(id, "code/main.py"))
                .thenCompose(read -> read.success() ? manager.readSources(id).thenCompose(sources ->
                        PyRuntime.get().runMain(id, read.content(), "code/main.py", false, sources))
                        : CompletableFuture.completedFuture(Declarations.error(read.message())))
                .thenCompose(declarations -> {
                    Runnable finish = () -> {
                        try {
                            if (revision != loadRevisions.getOrDefault(id, 0L)) throw new IllegalStateException("World or dependencies changed");
                            finishLoad(id, declarations, server);
                            rememberProjectSnapshot(id, snapshot);
                            var project = manager.getProject(id);
                            done.complete(!declarations.failed() && project != null && project.isLoaded() && project.lastError() == null);
                        } catch (Exception error) { done.completeExceptionally(error); }
                    };
                    if (server == null) finish.run(); else server.execute(finish);
                    return done;
                }).whenComplete((result, error) -> {
                    if (error != null) done.completeExceptionally(error);
                    if (moduleReloadCompletions.remove(id, done)) loading.remove(id);
                });
        return done;
    }

    private static int packBatches;
    private static boolean packsPending;

    public static synchronized void beginPackBatch() { packBatches++; }

    public static synchronized CompletableFuture<?> endPackBatch() {
        if (--packBatches == 0 && packsPending) {
            packsPending = false;
            if (packServer == null) return CraftPyExecutors.runAsync(PackBuilder::rebuild);
            return PACKS.requestAndWait();
        }
        return CompletableFuture.completedFuture(null);
    }

    private static volatile MinecraftServer packServer;
    private static final net.xmilon.craftpy.util.Debouncer PACKS =
            new net.xmilon.craftpy.util.Debouncer("pack-refresh", 300, ProjectController::rebuildAndReload);

    /**
     * Rebuilds the generated packs off-thread, reloads the server's data and tells the clients to reload theirs. Requests
     * close together (a load per active project at start, several asset uploads) are folded into one rebuild.
     */
    public static synchronized void refreshPacks(MinecraftServer server) {
        if (packBatches > 0) { packServer = server; packsPending = true; return; }
        if (server == null) {
            CraftPyExecutors.runAsync(PackBuilder::rebuild);
            return;
        }
        packServer = server;
        PACKS.request();
    }

    private static CompletableFuture<?> rebuildAndReload() {
        MinecraftServer server = packServer;
        if (server == null || !server.isRunning()) {
            return CompletableFuture.completedFuture(null);
        }
        return CraftPyExecutors.runAsync(PackBuilder::rebuild)
                .thenCompose(ignored -> server.submit(() -> {
                    // The generated folder may have appeared after the repository's initial discovery.
                    server.getPackRepository().reload();
                    return server.reloadResources(server.getPackRepository().getSelectedIds());
                }))
                .thenCompose(reload -> reload)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        CraftPyLog.LOGGER.error("Pack rebuild or datapack reload failed", error);
                        return;
                    }
                    server.execute(() -> {
                        CraftPyNetwork.broadcastActive(server);
                        CraftPyNetwork.broadcastProjectList(server);
                    });
                });
    }

    public static void startup(MinecraftServer server) {
        SlotRegistry.init();
        // Packs are rebuilt off the game thread; the first reload picks them up.
        CraftPyExecutors.runAsync(PackBuilder::rebuild).thenRun(() -> {
            if (server != null) {
                server.execute(() -> CraftPyNetwork.broadcastActive(server));
            }
        });
    }
}
