package net.xmilon.craftpy.server;

import net.xmilon.craftpy.CraftPyLog;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.xmilon.craftpy.Config;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.network.RequestPayload;
import net.xmilon.craftpy.network.ResponsePayload;
import net.xmilon.craftpy.python.PyRuntime;
import net.xmilon.craftpy.util.CraftPyExecutors;
import net.xmilon.craftpy.util.Json;

/**
 * Server-thread entry point for player requests. Only scheduling and result
 * marshalling happen here; the actual IO runs on the worker pool.
 */
public final class ProjectRequestHandler {
    private ProjectRequestHandler() {
    }

    /**
     * Forwards a click / value change to the worker as the internal {@code ui_event}. The worker only
     * accepts tokens that belong to a window it opened for exactly this player.
     */
    private static void windowEvent(ServerPlayer player, String data) {
        try {
            com.google.gson.JsonObject event = com.google.gson.JsonParser.parseString(data).getAsJsonObject();
            com.google.gson.JsonObject context = new com.google.gson.JsonObject();
            context.add("player", net.xmilon.craftpy.lifecycle.EventBridge.wrapPlayer(player));
            for (String key : new String[] { "window", "token", "value", "values" }) {
                if (event.has(key)) {
                    context.add(key, event.get(key));
                }
            }
            net.xmilon.craftpy.python.PyRuntime.get().triggerEvent("ui_event", context).thenAccept(result -> {
                if (result != null && result.has("log") && result.get("log").isJsonArray()) {
                    result.getAsJsonArray("log").forEach(line -> net.xmilon.craftpy.network.CraftPyNetwork
                            .broadcastConsole(player.server, "[window] " + line.getAsString()));
                }
            });
        } catch (RuntimeException e) {
            net.xmilon.craftpy.CraftPyLog.LOGGER.warn("Bad window event from {}: {}", player.getName().getString(), e.toString());
        }
    }

    public static CompletableFuture<ResponsePayload> handle(ServerPlayer player, RequestPayload request) {
        if (request.action() == ProjectAction.WINDOW_EVENT) {
            // Windows belong to the player who sees them, not to the IDE: no IDE permission needed.
            windowEvent(player, request.data());
            return CompletableFuture.completedFuture(null);
        }
        ProjectManager manager = ProjectManager.get();
        if (manager == null) {
            return CompletableFuture.completedFuture(fail(request.action(), "Project manager not ready"));
        }
        if (!isAllowed(player)) {
            return CompletableFuture.completedFuture(fail(request.action(),
                    "You do not have permission to use CraftPy (requires permission level "
                            + Config.PERMISSION_LEVEL.get() + ")"));
        }

        ProjectAction action = request.action();
        String id = request.projectId();
        switch (action) {
            case PING:
                return CompletableFuture.completedFuture(new ResponsePayload(ProjectAction.PING, true, "pong", ""));
            case LIST:
                return manager.listProjects().thenApply(list -> new ResponsePayload(ProjectAction.LIST, true, "",
                        Json.COMPACT.toJson(list)));
            case CREATE:
                return createFromRequest(manager, request.data())
                        .thenApply(result -> fromResult(ProjectAction.CREATE, result));
            case DELETE:
                return manager.deleteProject(id).thenApply(result -> fromResult(ProjectAction.DELETE, result));
            case RENAME:
                return manager.renameProject(id, request.data()).thenApply(result -> fromResult(ProjectAction.RENAME, result));
            case DUPLICATE:
                return manager.duplicateProject(id).thenApply(result -> fromResult(ProjectAction.DUPLICATE, result));
            case LOAD:
                if (!Config.ENABLE_SCRIPTING.get()) {
                    return CompletableFuture.completedFuture(fail(action, "Scripting is disabled in the config"));
                }
                if (!ProjectManager.isValidId(id)) {
                    return CompletableFuture.completedFuture(fail(action, "Invalid project id"));
                }
                net.xmilon.craftpy.lifecycle.ProjectController.get().load(id, player.getServer(), loadDebug(request.data()));
                return CompletableFuture.completedFuture(ResponsePayload.success(ProjectAction.LOAD,
                        "Loading " + id + " ...", id));
            case UNLOAD:
                if (!ProjectManager.isValidId(id)) {
                    return CompletableFuture.completedFuture(fail(action, "Invalid project id"));
                }
                net.xmilon.craftpy.lifecycle.ProjectController.get().unload(id, player.getServer());
                return CompletableFuture.completedFuture(ResponsePayload.success(ProjectAction.UNLOAD,
                        "Unloaded " + id, id));
            case LIST_FILES:
                return manager.listFiles(id).thenApply(files -> new ResponsePayload(ProjectAction.LIST_FILES, true, "",
                        Json.COMPACT.toJson(files)));
            case READ_FILE:
                return manager.readFile(id, request.data())
                        .thenApply(read -> read.success()
                                ? new ResponsePayload(ProjectAction.READ_FILE, true, "", Json.COMPACT.toJson(read))
                                : new ResponsePayload(ProjectAction.READ_FILE, false, read.message(), ""));
            case READ_ASSET:
                if (!ProjectManager.isValidId(id)) {
                    return CompletableFuture.completedFuture(fail(action, "Invalid project id"));
                }
                return manager.readBinary(id, request.data()).thenApply(read -> {
                    if (!read.success()) {
                        return fail(action, read.message());
                    }
                    String encoded = java.util.Base64.getEncoder().encodeToString(read.data());
                    return ResponsePayload.success(ProjectAction.READ_ASSET, "",
                            Json.COMPACT.toJson(new AssetDownload(read.path(), read.data().length, encoded)));
                });
            case WRITE_FILE: {
                FileWrite file = Json.COMPACT.fromJson(request.data(), FileWrite.class);
                if (file == null || file.path == null) {
                    return CompletableFuture.completedFuture(fail(action, "Malformed write request"));
                }
                return manager.writeFile(id, file.path, file.content == null ? "" : file.content)
                        .thenApply(result -> {
                            if (result.success() && Config.HOT_RELOAD_ON_SAVE.get() && Config.ENABLE_SCRIPTING.get()
                                    && isReloadTrigger(file.path)) {
                                net.xmilon.craftpy.lifecycle.ProjectController.get().scheduleReload(id, player.getServer());
                            }
                            return fromResult(ProjectAction.WRITE_FILE, result);
                        });
            }
            case VALIDATE:
                if (!Config.ENABLE_SCRIPTING.get()) {
                    return CompletableFuture.completedFuture(fail(action, "Scripting is disabled in the config"));
                }
                if (!ProjectManager.isValidId(id)) {
                    return CompletableFuture.completedFuture(fail(action, "Invalid project id"));
                }
                return validateAndRespond(id);
            case UPLOAD_ASSET: {
                if (!ProjectManager.isValidId(id)) {
                    return CompletableFuture.completedFuture(uploadReply(false, "Invalid project id", ""));
                }
                AssetUpload upload = Json.COMPACT.fromJson(request.data(), AssetUpload.class);
                if (upload == null || upload.data == null) {
                    return CompletableFuture.completedFuture(uploadReply(false, "Malformed upload request", ""));
                }
                long max = (long) Math.min(Config.MAX_ASSET_SIZE_KB.get(), Config.MAX_PACKET_SIZE_KB.get()) * 1024;
                String refused = UploadRules.check(upload.path, upload.chunk, upload.total, max);
                if (refused != null) {
                    return CompletableFuture.completedFuture(uploadReply(false, refused, String.valueOf(upload.path)));
                }
                long now = System.currentTimeMillis();
                PENDING_UPLOADS.values().removeIf(old -> now - old.created > PENDING_UPLOAD_MS);
                String key = player.getUUID() + "|" + id + "|" + upload.path;
                PendingUpload pending = PENDING_UPLOADS.compute(key,
                        (k, existing) -> existing == null || existing.parts.length != upload.total ? new PendingUpload(upload.total)
                                : existing);
                synchronized (pending) {
                    if (pending.parts[upload.chunk] == null) {
                        pending.parts[upload.chunk] = upload.data;
                        pending.received++;
                    }
                    if (pending.received < upload.total) {
                        // Intermediate chunk: acknowledge silently (no reply packet).
                        return CompletableFuture.completedFuture(null);
                    }
                }
                PENDING_UPLOADS.remove(key);
                StringBuilder assembled = new StringBuilder();
                for (String part : pending.parts) {
                    if (part == null) {
                        return CompletableFuture.completedFuture(uploadReply(false, "Incomplete upload", upload.path));
                    }
                    assembled.append(part);
                }
                byte[] bytes;
                try {
                    bytes = java.util.Base64.getDecoder().decode(assembled.toString());
                } catch (IllegalArgumentException e) {
                    return CompletableFuture.completedFuture(uploadReply(false, "Invalid base64 payload", upload.path));
                }
                if (bytes.length > max) {
                    return CompletableFuture.completedFuture(uploadReply(false,
                            "File too large (" + bytes.length / 1024 + " KB, limit " + max / 1024 + " KB)", upload.path));
                }
                return manager.writeBinary(id, upload.path, bytes)
                        .thenApply(result -> {
                            if (result.success() && net.xmilon.craftpy.registry.SlotRegistry.isProjectActive(id)) {
                                refreshPacksAfterAssetChange(player.getServer());
                            }
                            return uploadReply(result.success(), result.message(), upload.path);
                        });
            }
            case EVAL: {
                if (!Config.ENABLE_SCRIPTING.get()) {
                    return CompletableFuture.completedFuture(fail(action, "Scripting is disabled in the config"));
                }
                if (!PyRuntime.available()) {
                    return CompletableFuture.completedFuture(fail(action,
                            "No supported Python runtime found. Install CPython 3.13+ or set pythonPath in config."));
                }
                String code = request.data();
                String session = "default";
                try {
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(request.data()).getAsJsonObject();
                    if (obj.has("code")) code = obj.get("code").getAsString();
                    if (obj.has("session")) session = obj.get("session").getAsString();
                } catch (Exception ignored) {}
                
                return PyRuntime.get().evaluate(id, code, session).thenApply(ProjectRequestHandler::evalResponse);
            }
            case CREATE_ENTRY: {
                EntryOp op = Json.COMPACT.fromJson(request.data(), EntryOp.class);
                if (op == null || op.path == null || op.path.isBlank()) {
                    return CompletableFuture.completedFuture(fail(action, "Malformed create request"));
                }
                return manager.createEntry(id, op.path, op.type).thenApply(result -> fromResult(action, result));
            }
            case DELETE_ENTRY: {
                EntryOp op = Json.COMPACT.fromJson(request.data(), EntryOp.class);
                if (op == null || op.path == null || op.path.isBlank()) {
                    return CompletableFuture.completedFuture(fail(action, "Malformed delete request"));
                }
                return manager.deleteEntry(id, op.path).thenApply(result -> fromResult(action, result));
            }
            case RENAME_ENTRY: {
                RenameOp op = Json.COMPACT.fromJson(request.data(), RenameOp.class);
                if (op == null || op.from == null || op.to == null) {
                    return CompletableFuture.completedFuture(fail(action, "Malformed rename request"));
                }
                return manager.renameEntry(id, op.from, op.to).thenApply(result -> fromResult(action, result));
            }
            case TOOL:
                return ProjectTools.handle(manager, id, request.data());
            case INTERRUPT: {
                return PyRuntime.get().interrupt().thenApply(v -> new ResponsePayload(action, true, "Interrupted", ""));
            }
            case FIND_DEFINITION:
                return manager.findDefinition(id, request.data())
                        .thenApply(result -> new ResponsePayload(ProjectAction.FIND_DEFINITION, true, "",
                                Json.COMPACT.toJson(result)));
            case BUILD: {
                if (!ProjectManager.isValidId(id)) {
                    return CompletableFuture.completedFuture(fail(action, "Invalid project id"));
                }
                final net.xmilon.craftpy.project.ModuleBuilder.Request build;
                try {
                    build = net.xmilon.craftpy.project.ModuleBuilder.Request.parse(request.data());
                } catch (RuntimeException e) {
                    return CompletableFuture.completedFuture(fail(action, "Malformed build request"));
                }
                return CraftPyExecutors.supplyAsync(() -> {
                    try {
                        var result = net.xmilon.craftpy.project.ModuleBuilder.build(id, build);
                        String message = "Built module '" + result.moduleId() + "' " + result.version() + " to "
                                + result.export().toAbsolutePath();
                        if (result.installed() != null) {
                            if (result.replaced()) {
                                net.xmilon.craftpy.project.ModuleLoader.reload(result.moduleId());
                                message += " - replaced the installed module";
                            } else {
                                net.xmilon.craftpy.project.ModuleLoader.loadModule(result.installed());
                                message += " - installed as a new module";
                            }
                        }
                        return new ResponsePayload(ProjectAction.BUILD, true, message, result.moduleId());
                    } catch (java.io.IOException e) {
                        return fail(ProjectAction.BUILD, "Build failed: " + e.getMessage());
                    }
                }, CraftPyExecutors.io());
            }
            case MODULES_LIST:
                return CraftPyExecutors.supplyAsync(() -> new ResponsePayload(ProjectAction.MODULES_LIST, true, "",
                        Json.COMPACT.toJson(net.xmilon.craftpy.project.ModuleLoader.list())), CraftPyExecutors.io());
            case MODULE_README: {
                final String requestedPath = request.data() == null ? "" : request.data();
                return CraftPyExecutors.supplyAsync(() -> {
                    com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
                    obj.addProperty("path", requestedPath);
                    obj.addProperty("readme", net.xmilon.craftpy.project.ModuleLoader.readmeFor(requestedPath));
                    return ResponsePayload.success(ProjectAction.MODULE_README, "", Json.COMPACT.toJson(obj));
                }, CraftPyExecutors.io());
            }
            case MODULE_SET_ENABLED: {
                ModuleToggle toggle = Json.COMPACT.fromJson(request.data(), ModuleToggle.class);
                if (toggle == null || toggle.id == null || toggle.id.isBlank()) {
                    return CompletableFuture.completedFuture(fail(action, "Malformed module toggle"));
                }
                final String moduleId = toggle.id;
                final boolean enabled = toggle.enabled;
                return CraftPyExecutors.runAsync(
                        () -> net.xmilon.craftpy.project.ModuleLoader.setEnabled(moduleId, enabled),
                        CraftPyExecutors.io())
                        .thenApply(v -> ResponsePayload.success(action,
                                (enabled ? "Enabled " : "Disabled ") + moduleId, ""));
            }
            case MODULE_SET_ALL: {
                final boolean enabled = Boolean.parseBoolean(request.data());
                return CraftPyExecutors.runAsync(
                        () -> net.xmilon.craftpy.project.ModuleLoader.setAllEnabled(enabled),
                        CraftPyExecutors.io())
                        .thenApply(v -> ResponsePayload.success(action,
                                (enabled ? "Enabled" : "Disabled") + " all modules", ""));
            }
            case MODULE_RELOAD: {
                final String moduleId = request.data();
                return CraftPyExecutors.supplyAsync(() -> {
                    try {
                        if ("*".equals(moduleId)) {
                            int count = 0;
                            for (var module : new java.util.ArrayList<>(net.xmilon.craftpy.project.ModuleLoader.loadedModules())) {
                                if (net.xmilon.craftpy.project.ModuleLoader.reload(module.id)) {
                                    count++;
                                }
                            }
                            return ResponsePayload.success(action, "Reloaded " + count + " module(s)", "");
                        }
                        boolean ok = net.xmilon.craftpy.project.ModuleLoader.reload(moduleId);
                        return ok ? ResponsePayload.success(action, "Reloaded module " + moduleId, "")
                                : fail(action, "Module not found: " + moduleId);
                    } catch (java.io.IOException e) {
                        return fail(action, "Reload failed: " + e.getMessage());
                    }
                }, CraftPyExecutors.io());
            }
            case MODULE_DELETE: {
                ModulePath modulePath = Json.COMPACT.fromJson(request.data(), ModulePath.class);
                final String path = modulePath == null ? null : modulePath.path;
                if (path == null || path.isBlank()) {
                    return CompletableFuture.completedFuture(fail(action, "Malformed delete request"));
                }
                return CraftPyExecutors.supplyAsync(() -> {
                    try {
                        boolean ok = net.xmilon.craftpy.project.ModuleLoader.delete(java.nio.file.Path.of(path));
                        return ok ? ResponsePayload.success(action, "Deleted " + path, "")
                                : fail(action, "Module file not found: " + path);
                    } catch (java.io.IOException e) {
                        return fail(action, "Delete failed: " + e.getMessage());
                    }
                }, CraftPyExecutors.io());
            }
            default:
                return CompletableFuture.completedFuture(fail(action, "Unsupported action: " + action));
        }
    }

    private static ResponsePayload evalResponse(PyRuntime.EvalResult result) {
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        com.google.gson.JsonArray output = new com.google.gson.JsonArray();
        for (String line : result.output()) {
            output.add(line);
        }
        out.add("output", output);
        out.add("value", result.value() == null ? com.google.gson.JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(result.value()));
        if (result.error() != null) {
            com.google.gson.JsonObject error = new com.google.gson.JsonObject();
            error.addProperty("message", result.error());
            error.addProperty("line", result.errorLine());
            out.add("error", error);
        } else {
            out.add("error", com.google.gson.JsonNull.INSTANCE);
        }
        return new ResponsePayload(ProjectAction.EVAL, true, "", Json.COMPACT.toJson(out));
    }

    /** CREATE data is a plain project name, or {@code {"name": ..., "template": ...}} from the gallery. */
    private static CompletableFuture<ProjectManager.OpResult> createFromRequest(ProjectManager manager, String data) {
        if (data != null && data.startsWith("{")) {
            try {
                com.google.gson.JsonObject object = com.google.gson.JsonParser.parseString(data).getAsJsonObject();
                String name = object.has("name") ? object.get("name").getAsString() : "";
                String template = object.has("template") ? object.get("template").getAsString() : "";
                return manager.createProject(name, template);
            } catch (RuntimeException ignored) {
                // not JSON after all: treat the whole text as a name
            }
        }
        return manager.createProject(data);
    }

    private static boolean loadDebug(String data) {
        if (data == null || data.isBlank()) {
            return false;
        }
        try {
            com.google.gson.JsonObject object = com.google.gson.JsonParser.parseString(data).getAsJsonObject();
            return object.has("debug") && object.get("debug").getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public record EntryOp(String path, String type) {
    }

    public record RenameOp(String from, String to) {
    }

    public record FileWrite(String path, String content) {
    }

    public record AssetUpload(String path, int chunk, int total, String data) {
    }

    public record ModuleToggle(String id, boolean enabled) {
    }

    public record ModulePath(String path) {
    }

    public record AssetDownload(String path, int size, String data) {
    }

    /**
     * Buffers an in-flight chunked upload. Keyed by player + project + path so
     * concurrent uploads from different players/paths never mix. Accessed under
     * the buffer's own monitor.
     */
    private static final class PendingUpload {
        final String[] parts;
        int received;
        final long created = System.currentTimeMillis();

        PendingUpload(int total) {
            this.parts = new String[total];
        }
    }

    private static final java.util.Map<String, PendingUpload> PENDING_UPLOADS = new java.util.concurrent.ConcurrentHashMap<>();

    /** An upload whose last chunk never came is forgotten after this long. */
    private static final long PENDING_UPLOAD_MS = 60_000;

    /**
     * Validates every .py file of a project on the Python worker (async, off the
     * server thread). Returns one ResponsePayload holding the problem list.
     */
    private static CompletableFuture<ResponsePayload> validateAndRespond(String projectId) {
        ProjectManager manager = ProjectManager.get();
        if (manager == null) {
            return CompletableFuture.completedFuture(fail(ProjectAction.VALIDATE, "Project manager not ready"));
        }
        if (!PyRuntime.available()) {
            return CompletableFuture.completedFuture(fail(ProjectAction.VALIDATE,
                    "No supported Python runtime found. Install CPython 3.13+ or set pythonPath in config."));
        }
        return manager.listFiles(projectId).thenCompose(files -> {
            List<String> pyFiles = new ArrayList<>();
            for (String file : files) {
                if (file.endsWith(".py")) {
                    pyFiles.add(file);
                }
            }
            if (pyFiles.isEmpty()) {
                List<net.xmilon.craftpy.python.ValidationProblem> problems = List.of();
                return CompletableFuture.completedFuture(new ResponsePayload(ProjectAction.VALIDATE, true, "No .py files",
                        Json.COMPACT.toJson(problems)));
            }
            List<CompletableFuture<ProjectManager.FileRead>> reads = new ArrayList<>();
            for (String file : pyFiles) {
                reads.add(manager.readFile(projectId, file));
            }
            return CompletableFuture.allOf(reads.toArray(new CompletableFuture[0])).thenCompose(ignored -> {
                List<String> contents = new ArrayList<>();
                for (CompletableFuture<ProjectManager.FileRead> read : reads) {
                    ProjectManager.FileRead result = read.join();
                    contents.add(result.success() ? result.content() : "");
                }
                return PyRuntime.get().validateProject(projectId, pyFiles, contents)
                        .thenApply(problems -> {
                            List<net.xmilon.craftpy.python.ValidationProblem> all = new ArrayList<>(problems);
                            // ids no installed mod provides (typos, content of a mod that is gone)
                            var view = net.xmilon.craftpy.catalog.ModCatalog.get().view();
                            var own = net.xmilon.craftpy.analysis.IdChecker.ownNames(contents);
                            for (int i = 0; i < pyFiles.size(); i++) {
                                all.addAll(net.xmilon.craftpy.analysis.IdChecker.check(pyFiles.get(i), contents.get(i), view, projectId, own));
                            }
                            return new ResponsePayload(ProjectAction.VALIDATE, true, "", Json.COMPACT.toJson(all));
                        });
            });
        }).exceptionally(error -> {
            CraftPyLog.LOGGER.error("Validation failed", error);
            return fail(ProjectAction.VALIDATE, "Python validation failed: " + error.getMessage());
        });
    }

    private static boolean isAllowed(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null && server.isSingleplayer() && server.isSingleplayerOwner(player.getGameProfile())) {
            return true;
        }
        return player.hasPermissions(Config.PERMISSION_LEVEL.get());
    }

    /** Files whose change needs a run: code, windows drawn in the designer, and assets. */
    static boolean isReloadTrigger(String path) {
        String lower = path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith("code/") && lower.endsWith(".py"))
                || (lower.startsWith("ui/") && lower.endsWith(".json"))
                || lower.startsWith("assets/");
    }

    private static ResponsePayload fromResult(ProjectAction action, ProjectManager.OpResult result) {
        if (result.success()) {
            return new ResponsePayload(action, true, result.message(), result.projectId() == null ? "" : result.projectId());
        }
        return fail(action, result.message());
    }

    /** The reply to the last chunk of an upload (or to a refused one); the path lets the client match it to its save. */
    private static ResponsePayload uploadReply(boolean success, String message, String path) {
        return new ResponsePayload(ProjectAction.UPLOAD_ASSET, success, message, path);
    }

    private static ResponsePayload fail(ProjectAction action, String message) {
        return new ResponsePayload(action, false, message, "");
    }

    /**
     * After a project asset changes, rebuild the dynamic packs off-thread and
     * reload resources so the new texture/model/sound takes effect immediately.
     */
    private static void refreshPacksAfterAssetChange(MinecraftServer server) {
        if (server == null) {
            return;
        }
        CraftPyExecutors.runAsync(net.xmilon.craftpy.dynamics.PackBuilder::rebuild).thenRun(() -> server.execute(() -> {
            server.reloadResources(server.getPackRepository().getSelectedIds())
                    .thenRun(() -> {
                        net.xmilon.craftpy.network.CraftPyNetwork.broadcastActive(server);
                        net.xmilon.craftpy.network.CraftPyNetwork.broadcastProjectList(server);
                    })
                    .exceptionally(error -> {
                        CraftPyLog.LOGGER.error("Resource reload after asset change failed", error);
                        return null;
                    });
        }));
    }
}
