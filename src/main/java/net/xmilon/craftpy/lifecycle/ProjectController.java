package net.xmilon.craftpy.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.server.MinecraftServer;
import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.Config;
import net.xmilon.craftpy.dynamics.PackBuilder;
import net.xmilon.craftpy.network.CraftPyNetwork;
import net.xmilon.craftpy.project.ProjectManager;
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

    private final List<String> loading = new ArrayList<>();

    private ProjectController() {
    }

    public static ProjectController get() {
        return INSTANCE;
    }

    public void load(String projectId, MinecraftServer server) {
        load(projectId, server, false);
    }

    public void load(String projectId, MinecraftServer server, boolean debug) {
        if (loading.contains(projectId)) {
            return;
        }
        if (!Config.ENABLE_SCRIPTING.get()) {
            CraftPy.LOGGER.warn("CraftPy load blocked: scripting disabled in config");
            return;
        }
        if (!PyRuntime.available()) {
            CraftPy.LOGGER.warn("CraftPy load aborted: no supported Python runtime found");
            return;
        }
        ProjectManager manager = ProjectManager.get();
        if (manager == null) {
            return;
        }
        loading.add(projectId);
        manager.setLastError(projectId, null);

        manager.readFile(projectId, "code/main.py")
                .thenCompose(read -> {
                    if (!read.success()) {
                        return CompletableFuture.completedFuture(Declarations.error(read.message()));
                    }
                    return PyRuntime.get().runMain(projectId, read.content(), "code/main.py", debug);
                })
                .thenAccept(declarations -> {
                    if (server != null) {
                        server.execute(() -> finishLoad(projectId, declarations, server));
                    } else {
                        finishLoad(projectId, declarations, null);
                    }
                })
                .exceptionally(error -> {
                    loading.remove(projectId);
                    CraftPy.LOGGER.error("Load pipeline failed for {}", projectId, error);
                    manager.markLoaded(projectId, false);
                    manager.setLastError(projectId, "Load pipeline failed: " + error.getMessage());
                    if (server != null) {
                        server.execute(() -> {});
                    }
                    return null;
                });
    }

    /** Runs on the server thread. */
    private void finishLoad(String projectId, Declarations declarations, MinecraftServer server) {
        try {
            ProjectManager manager = ProjectManager.get();
            if (declarations == null || declarations.failed()) {
                String message = declarations == null ? "worker returned nothing"
                        : declarations.error() + (declarations.errorLine() >= 0
                                ? " (line " + (declarations.errorLine() + 1) + ")" : "");
                if (SlotRegistry.declarations(projectId) != null) {
                    // Rollback: keep last known good definitions running.
                    CraftPy.LOGGER.warn("Project {} reload failed, keeping previous version: {}", projectId, message);
                    manager.setLastError(projectId, "Reload failed - previous version kept: " + message);
                } else {
                    manager.markLoaded(projectId, false);
                    manager.setLastError(projectId, message);
                    CraftPy.LOGGER.error("Project {} failed to load: {}", projectId, message);
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
            try {
                for (Declarations.Content content : declarations.contents()) {
                    switch (content.kind()) {
                        case "block" -> {
                            SlotRegistry.applyBlock(projectId, content.id(), content.definition());
                            blocks++;
                        }
                        case "item" -> {
                            SlotRegistry.applyItem(projectId, content.id(), content.definition());
                            items++;
                        }
                        case "effect", "entity" -> notes.add(content.kind() + " '" + content.id()
                                + "' declared, but that slot type is not wired yet (later milestone)");
                        default -> {
                        }
                    }
                }
                SlotRegistry.flush();
                SlotRegistry.storeDeclarations(projectId, declarations);
            } catch (RuntimeException e) {
                failed = true;
                CraftPy.LOGGER.error("Slot allocation failed for {}", projectId, e);
                manager.markLoaded(projectId, false);
                manager.setLastError(projectId, e.getMessage());
                for (String key : new ArrayList<>(SlotRegistry.snapshotByHolder().keySet())) {
                    if (key.startsWith(projectId + ":")) {
                        manager.setLastError(projectId, "Slot pool exhausted: " + e.getMessage());
                        break;
                    }
                }
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
                    CraftPy.LOGGER.warn("Failed to register project commands for {}", projectId, e);
                }
                manager.markLoaded(projectId, true);
                manager.setLastError(projectId, null);
                CraftPy.LOGGER.info("Loaded project {} ({} block(s), {} item(s), {} datapack entr{}), scripts: {}",
                        projectId, blocks, items, declarations.datapack().size(),
                        declarations.datapack().size() == 1 ? "y" : "ies", declarations.log());
                for (String note : notes) {
                    CraftPy.LOGGER.info("[{}] {}", projectId, note);
                }
                for (String line : declarations.log()) {
                    net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(server, "[" + projectId + "] " + line);
                }
            }
            afterChange(server);
        } catch (RuntimeException e) {
            CraftPy.LOGGER.error("Unexpected error while finishing load of {}", projectId, e);
            afterChange(server);
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
            CraftPy.LOGGER.warn("Failed to unregister project commands for {}", projectId, e);
        }
        manager.markLoaded(projectId, false);
        SlotRegistry.releaseProject(projectId);
        PyRuntime.get().unloadOwner(projectId, false);
        manager.setLastError(projectId, null);
        afterChange(server);
    }

    private void afterChange(MinecraftServer server) {
        // Rebuild generated packs off-thread, then trigger reloads.
        CraftPyExecutors.runAsync(PackBuilder::rebuild).thenRun(() -> {
            if (server != null) {
                server.execute(() -> {
                    server.reloadResources(server.getPackRepository().getSelectedIds())
                            .thenRun(() -> {
                                CraftPyNetwork.broadcastActive(server);
                                CraftPyNetwork.broadcastProjectList(server);
                            })
                            .exceptionally(error -> {
                                CraftPy.LOGGER.error("Datapack reload failed", error);
                                return null;
                            });
                });
            }
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