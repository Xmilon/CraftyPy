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

    /**
     * Who sends a request: a player in the game, or the standalone IDE ({@link #LOCAL}: no server, so nothing that needs the
     * running game - loading into the world, modules, pack reloads - is done). {@code key} keeps uploads apart.
     */
    public record Caller(String key, String name, MinecraftServer server, boolean allowed) {
        public static final Caller LOCAL = new Caller("ide", "the IDE", null, true);

        public static Caller of(ServerPlayer player) {
            return new Caller(player.getUUID().toString(), player.getName().getString(), player.getServer(), isAllowed(player));
        }
    }

    static final String NEEDS_GAME = "Needs the game: open this project in Minecraft to run it in a world";

    public static CompletableFuture<ResponsePayload> handle(ServerPlayer player, RequestPayload request) {
        if (request.action() == ProjectAction.WINDOW_EVENT) {
            // Windows belong to the player who sees them, not to the IDE: no IDE permission needed.
            windowEvent(player, request.data());
            return CompletableFuture.completedFuture(null);
        }
        if (request.action() == ProjectAction.APP_OPEN) {
            appOpen(player, request.data());
            return CompletableFuture.completedFuture(null);
        }
        return handle(Caller.of(player), request);
    }

    /**
     * A player opened a Hub app: like a window click it needs no IDE permission, but the app must be one this server's
     * running module or project declares right now. The owner's Python then opens the declared window for that player.
     */
    private static void appOpen(ServerPlayer player, String data) {
        try {
            com.google.gson.JsonObject request = com.google.gson.JsonParser.parseString(data).getAsJsonObject();
            String owner = request.get("owner").getAsString(), app = request.get("app").getAsString();
            CraftPyExecutors.supplyAsync(() -> ServerApps.windowOf(owner, app), CraftPyExecutors.io()).thenAccept(window -> {
                if (window == null) {
                    player.server.execute(() -> player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("That app is not running on this server."), true));
                    return;
                }
                com.google.gson.JsonObject context = new com.google.gson.JsonObject();
                context.addProperty("owner", owner);
                context.addProperty("window", window);
                context.add("player", net.xmilon.craftpy.lifecycle.EventBridge.wrapPlayer(player));
                net.xmilon.craftpy.python.PyRuntime.get().triggerEvent("app_open", context).thenAccept(result -> {
                    if (result != null && result.has("log") && result.get("log").isJsonArray()) {
                        result.getAsJsonArray("log").forEach(line -> net.xmilon.craftpy.network.CraftPyNetwork
                                .broadcastConsole(player.server, "[app] " + line.getAsString()));
                    }
                });
            });
        } catch (RuntimeException e) {
            net.xmilon.craftpy.CraftPyLog.LOGGER.warn("Bad app request from {}: {}", player.getName().getString(), e.toString());
        }
    }

    public static CompletableFuture<ResponsePayload> handle(Caller caller, RequestPayload request) {
        if (request.action() == ProjectAction.WINDOW_EVENT) {
            return CompletableFuture.completedFuture(null);
        }
        ProjectManager manager = ProjectManager.get();
        if (manager == null) {
            return CompletableFuture.completedFuture(failRequest(request, "Project manager not ready"));
        }
        if (caller.server() == null && NEEDS_SERVER.contains(request.action())) {
            return CompletableFuture.completedFuture(failRequest(request, NEEDS_GAME));
        }
        if (!caller.allowed()) {
            return CompletableFuture.completedFuture(failRequest(request,
                    "You do not have permission to use CraftPy (requires permission level "
                            + Config.get(Config.PERMISSION_LEVEL) + ")"));
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
                if (!Config.get(Config.ENABLE_SCRIPTING)) {
                    return CompletableFuture.completedFuture(fail(action, "Scripting is disabled in the config"));
                }
                if (!ProjectManager.isValidId(id)) {
                    return CompletableFuture.completedFuture(fail(action, "Invalid project id"));
                }
                if (manager.sideOf(id) == net.xmilon.craftpy.project.ModuleSide.CLIENT) {
                    return CompletableFuture.completedFuture(fail(action, id + " is a client-side project: it runs on your"
                            + " computer (Run in the editor), not in the world"));
                }
                net.xmilon.craftpy.lifecycle.ProjectController.get().load(id, caller.server(), loadDebug(request.data()));
                return CompletableFuture.completedFuture(ResponsePayload.success(ProjectAction.LOAD,
                        "Loading " + id + " ...", id));
            case UNLOAD:
                if (!ProjectManager.isValidId(id)) {
                    return CompletableFuture.completedFuture(fail(action, "Invalid project id"));
                }
                net.xmilon.craftpy.lifecycle.ProjectController.get().unload(id, caller.server());
                return CompletableFuture.completedFuture(ResponsePayload.success(ProjectAction.UNLOAD,
                        "Unloaded " + id, id));
            case LIST_FILES:
                return manager.listFiles(id).thenApply(files -> new ResponsePayload(ProjectAction.LIST_FILES, true, "",
                        Json.COMPACT.toJson(files)));
            case READ_FILE:
                return manager.readFile(id, request.data())
                        .thenApply(read -> {
                            if (!read.success()) {
                                return new ResponsePayload(ProjectAction.READ_FILE, false, read.message(), "");
                            }
                            long now = System.currentTimeMillis();
                            FileLocks.Lock lock = FileLocks.holder(id, read.path(), caller.key(), now);
                            return new ResponsePayload(ProjectAction.READ_FILE, true,
                                    lock == null ? "" : read.path() + ": " + lock.describe(now), Json.COMPACT.toJson(read));
                        });
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
                long now = System.currentTimeMillis();
                FileLocks.Lock lock = FileLocks.claim(id, file.path, caller.key(), caller.name(), now);
                if (lock != null) {
                    return CompletableFuture.completedFuture(new ResponsePayload(ProjectAction.WRITE_FILE, false,
                            "Not saved: " + file.path + " - " + lock.describe(now)
                                    + ". Your text stays in the editor; save again when they are done.", file.path));
                }
                return manager.writeFile(id, file.path, file.content == null ? "" : file.content)
                        .thenApply(result -> {
                            if (result.success() && caller.server() != null && Config.get(Config.HOT_RELOAD_ON_SAVE)
                                    && Config.get(Config.ENABLE_SCRIPTING) && isReloadTrigger(file.path)) {
                                net.xmilon.craftpy.lifecycle.ProjectController.get().scheduleReload(id, caller.server());
                            }
                            // the path goes back so the client clears the unsaved mark of this file, not of the active one
                            return result.success()
                                    ? new ResponsePayload(ProjectAction.WRITE_FILE, true, result.message(), file.path)
                                    : fail(ProjectAction.WRITE_FILE, result.message());
                        });
            }
            case VALIDATE:
                if (!Config.get(Config.ENABLE_SCRIPTING)) {
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
                long max = (long) Math.min(Config.get(Config.MAX_ASSET_SIZE_KB), Config.get(Config.MAX_PACKET_SIZE_KB)) * 1024;
                String refused = UploadRules.check(upload.path, upload.chunk, upload.total, max);
                if (refused != null) {
                    return CompletableFuture.completedFuture(uploadReply(false, refused, String.valueOf(upload.path)));
                }
                long now = System.currentTimeMillis();
                PENDING_UPLOADS.values().removeIf(old -> now - old.created > PENDING_UPLOAD_MS);
                String key = caller.key() + "|" + id + "|" + upload.path;
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
                            if (result.success() && caller.server() != null
                                    && net.xmilon.craftpy.registry.SlotRegistry.isProjectActive(id)) {
                                net.xmilon.craftpy.lifecycle.ProjectController.refreshPacks(caller.server());
                            }
                            return uploadReply(result.success(), result.message(), upload.path);
                        });
            }
            case EVAL: {
                String typed = consoleCommand(request.data());
                if (typed != null) {
                    return minecraftCommand(caller, typed);
                }
                if (!Config.get(Config.ENABLE_SCRIPTING)) {
                    return CompletableFuture.completedFuture(fail(action, "Scripting is disabled in the config"));
                }
                if (!PyRuntime.available()) {
                    return CompletableFuture.completedFuture(fail(action,
                            "No Python: this CraftPy build has none for this computer (see the log). Install CPython 3.13+ or set pythonPath in config."));
                }
                String code = request.data();
                String session = "default";
                try {
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(request.data()).getAsJsonObject();
                    if (obj.has("code")) code = obj.get("code").getAsString();
                    if (obj.has("session")) session = obj.get("session").getAsString();
                } catch (Exception ignored) {}
                
                return PyRuntime.get().evaluate(id, code, session, caller.server() == null ? null : caller.name())
                        .thenApply(ProjectRequestHandler::evalResponse);
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
                        if (result.installed() != null && caller.server() == null) {
                            message += " - installed; it loads when the game starts";
                        } else if (result.installed() != null) {
                            message += result.replaced() ? " - replaced; press RELOAD to apply" : " - installed; enable it and press RELOAD";
                        }
                        return new ResponsePayload(ProjectAction.BUILD, true, message, result.moduleId());
                    } catch (java.io.IOException e) {
                        return fail(ProjectAction.BUILD, "Build failed: " + e.getMessage());
                    }
                }, CraftPyExecutors.io());
            }
            case MODULES_LIST:
                return CraftPyExecutors.supplyAsync(() -> {
                    var body = Json.COMPACT.toJsonTree(net.xmilon.craftpy.project.ModuleLoader.listing()).getAsJsonObject();
                    body.addProperty("requestId", request.data());
                    return new ResponsePayload(ProjectAction.MODULES_LIST, true, "", body.toString());
                }, CraftPyExecutors.io()).exceptionally(error -> failRequest(request, "Module scan failed: " + error.getMessage()));
            case MODULE_README: {
                final String requestedPath = request.data() == null ? "" : request.data();
                return CraftPyExecutors.supplyAsync(() -> {
                    com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
                    obj.addProperty("path", requestedPath);
                    // only the README of a module CraftPy found, never of another file on the computer
                    var known = net.xmilon.craftpy.project.ModuleLoader.knownModuleFile(requestedPath);
                    obj.addProperty("readme", known == null ? "" : net.xmilon.craftpy.project.ModuleLoader.readmeFor(known.toString()));
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
                final long scope = net.xmilon.craftpy.project.ModuleLoader.stateRevision();
                return CraftPyExecutors.runAsync(
                        () -> net.xmilon.craftpy.project.ModuleLoader.setEnabled(moduleId, enabled, scope),
                        CraftPyExecutors.forProject("@module-state"))
                        .thenApply(v -> ResponsePayload.success(action,
                                (enabled ? "Will enable " : "Will disable ") + moduleId + " on RELOAD", ""));
            }
            case MODULE_SET_ALL: {
                final boolean enabled = Boolean.parseBoolean(request.data());
                final long scope = net.xmilon.craftpy.project.ModuleLoader.stateRevision();
                return CraftPyExecutors.runAsync(
                        () -> net.xmilon.craftpy.project.ModuleLoader.setAllEnabled(enabled, scope),
                        CraftPyExecutors.forProject("@module-state"))
                        .thenApply(v -> ResponsePayload.success(action,
                                (enabled ? "Will enable" : "Will disable") + " all modules on RELOAD", ""));
            }
            case MODULE_RELOAD: {
                return net.xmilon.craftpy.project.ModuleLoader.applyChanges()
                        .handle((result, error) -> {
                            if (caller.server() != null) ServerApps.broadcast(caller.server());
                            var body = result == null ? new com.google.gson.JsonObject() : Json.COMPACT.toJsonTree(result).getAsJsonObject();
                            body.addProperty("requestId", request.data());
                            return new ResponsePayload(action, error == null && result.success(),
                                    error == null ? result.summary() : "Reload failed: " + error.getMessage(), body.toString());
                        });
            }
            case MODULE_DELETE: {
                ModulePath modulePath = Json.COMPACT.fromJson(request.data(), ModulePath.class);
                final String path = modulePath == null ? null : modulePath.path;
                if (path == null || path.isBlank()) {
                    return CompletableFuture.completedFuture(fail(action, "Malformed delete request"));
                }
                return CraftPyExecutors.supplyAsync(() -> {
                    try {
                        // a path from the client: only a module CraftPy found under pyforge/ can be deleted
                        var known = net.xmilon.craftpy.project.ModuleLoader.knownModuleFile(path);
                        boolean ok = known != null && net.xmilon.craftpy.project.ModuleLoader.delete(known);
                        return ok ? ResponsePayload.success(action, "Deleted " + path, "")
                                : fail(action, "Not a module CraftPy knows: " + path);
                    } catch (java.io.IOException e) {
                        return fail(action, "Delete failed: " + e.getMessage());
                    }
                }, CraftPyExecutors.io());
            }
            case MODULE_APPROVE: {
                com.google.gson.JsonObject obj;
                try {
                    obj = com.google.gson.JsonParser.parseString(request.data()).getAsJsonObject();
                } catch (RuntimeException e) {
                    return CompletableFuture.completedFuture(fail(action, "Malformed approval"));
                }
                String moduleId = obj.has("id") ? obj.get("id").getAsString() : "";
                if (!ProjectManager.isValidId(moduleId)) {
                    return CompletableFuture.completedFuture(fail(action, "Invalid module id"));
                }
                String owner = "module:" + moduleId;
                boolean approve = obj.has("approve") && obj.get("approve").getAsBoolean();
                var installedModule = net.xmilon.craftpy.project.ModuleLoader.findFileById(moduleId);
                String expectedHash = obj.has("sha256") ? obj.get("sha256").getAsString() : "";
                if (approve && (installedModule == null || expectedHash.isBlank() || !expectedHash.equals(net.xmilon.craftpy.project.ModuleLoader.sha256(installedModule))))
                    return CompletableFuture.completedFuture(fail(action, "Module changed since it was displayed; refresh and review its permissions again"));
                if (approve) {
                    try { net.xmilon.craftpy.security.PermissionStore.declare(owner,
                            net.xmilon.craftpy.project.ModuleLoader.readInfo(installedModule).permissions, expectedHash); }
                    catch (java.io.IOException changed) { return CompletableFuture.completedFuture(fail(action, "Module could not be read; refresh before approving")); }
                }
                var asked = net.xmilon.craftpy.security.PermissionStore.declared(owner);
                if (approve) {
                    net.xmilon.craftpy.security.PermissionStore.approve(owner, asked); // exactly what it declares, no more
                } else {
                    net.xmilon.craftpy.security.PermissionStore.revoke(owner);
                }
                return CompletableFuture.completedFuture(ResponsePayload.success(action, approve
                        ? "Approved for " + moduleId + ": " + net.xmilon.craftpy.security.PermissionLabels.warning(asked)
                        : "Revoked what " + moduleId + " was allowed", ""));
            }
            case MODULE_UNPACK: {
                ModulePath modulePath = Json.COMPACT.fromJson(request.data(), ModulePath.class);
                final String path = modulePath == null ? null : modulePath.path;
                return CraftPyExecutors.supplyAsync(() -> {
                    var known = net.xmilon.craftpy.project.ModuleLoader.knownModuleFile(path);
                    if (known == null) {
                        return fail(action, "Not a module CraftPy knows: " + path);
                    }
                    try {
                        var result = net.xmilon.craftpy.project.ModuleUnpacker.unpack(known, manager.projectsDir(),
                                net.xmilon.craftpy.project.PyForgePaths.system().resolve("unpacking"));
                        if (result.moduleId() != null) {
                            // the project and the module must not both run
                            net.xmilon.craftpy.project.ModuleLoader.setEnabled(result.moduleId(), false);
                        }
                        // code of other people: it gets nothing it declares until someone trusts it
                        net.xmilon.craftpy.security.PermissionStore.distrust(result.projectId());
                        net.xmilon.craftpy.project.DecompileLog.record(result.source().module, result.source().sha256,
                                result.projectId());
                        String skipped = result.report().skipped().isEmpty() ? ""
                                : "; left out " + result.report().skipped().size() + " file(s): "
                                + String.join(", ", result.report().skipped().stream().limit(8).toList());
                        net.xmilon.craftpy.network.CraftPyNetwork.broadcastProjectList(caller.server());
                        return ResponsePayload.success(action, "Opened " + known.getFileName() + " as project '"
                                + result.projectId() + "' (" + result.report().written().size() + " files"
                                + (result.moduleId() == null ? "" : ", module " + result.moduleId() + " disabled") + skipped
                                + "). Nothing ran: review the code, then press Run. What it declares under \"permissions\""
                                + " stays off until /craftpy project trust " + result.projectId() + ".", result.projectId());
                    } catch (java.io.IOException | RuntimeException e) {
                        return fail(action, "Could not open the module as a project: " + e.getMessage());
                    }
                }, CraftPyExecutors.io());
            }
            default:
                return CompletableFuture.completedFuture(fail(action, "Unsupported action: " + action));
        }
    }

    /** A console line that starts with {@code /} is a Minecraft command, not Python; null otherwise. */
    static String consoleCommand(String data) {
        String code;
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(data).getAsJsonObject();
            code = obj.has("code") ? obj.get("code").getAsString() : "";
        } catch (RuntimeException notJson) {
            code = data == null ? "" : data;
        }
        String text = code.strip();
        return text.startsWith("/") && !text.contains("\n") && text.length() > 1 ? text : null;
    }

    /**
     * {@code /give @s diamond}, {@code /welcome:gift Alex 3}: run as the player who typed it (with their permission level),
     * and what the command answers is shown in the console.
     */
    private static CompletableFuture<ResponsePayload> minecraftCommand(Caller caller, String command) {
        MinecraftServer server = caller.server();
        if (server == null) {
            return CompletableFuture.completedFuture(fail(ProjectAction.EVAL, NEEDS_GAME));
        }
        return server.submit(() -> {
            ServerPlayer player;
            try {
                player = server.getPlayerList().getPlayer(java.util.UUID.fromString(caller.key()));
            } catch (IllegalArgumentException notAPlayer) {
                player = null;
            }
            if (player == null) {
                return fail(ProjectAction.EVAL, "Commands run as a player in the game");
            }
            List<String> lines = CommandOutput.run(server, player.createCommandSourceStack(), command);
            com.google.gson.JsonObject out = new com.google.gson.JsonObject();
            com.google.gson.JsonArray output = new com.google.gson.JsonArray();
            output.add(command);
            lines.forEach(output::add);
            if (lines.isEmpty()) {
                output.add("(the command answered nothing)");
            }
            out.add("output", output);
            out.add("value", com.google.gson.JsonNull.INSTANCE);
            out.add("error", com.google.gson.JsonNull.INSTANCE);
            return new ResponsePayload(ProjectAction.EVAL, true, "", Json.COMPACT.toJson(out));
        });
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
                String side = object.has("side") ? object.get("side").getAsString() : "both";
                return manager.createProject(name, template, side);
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

    /** Actions that act on the running game (worlds, loaded modules). */
    private static final java.util.Set<ProjectAction> NEEDS_SERVER = java.util.EnumSet.of(ProjectAction.LOAD,
            ProjectAction.UNLOAD, ProjectAction.MODULE_SET_ENABLED, ProjectAction.MODULE_SET_ALL, ProjectAction.MODULE_RELOAD);

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
                    "No Python: this CraftPy build has none for this computer (see the log). Install CPython 3.13+ or set pythonPath in config."));
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
                return PyRuntime.get().validateProject(projectId, manager.sideOf(projectId).id(), pyFiles, contents)
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
        return player.hasPermissions(Config.get(Config.PERMISSION_LEVEL));
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

    private static ResponsePayload failRequest(RequestPayload request, String message) {
        if (request.action() != ProjectAction.MODULE_RELOAD && request.action() != ProjectAction.MODULES_LIST)
            return fail(request.action(), message);
        var body = new com.google.gson.JsonObject(); body.addProperty("requestId", request.data());
        return new ResponsePayload(request.action(), false, message, body.toString());
    }

}
