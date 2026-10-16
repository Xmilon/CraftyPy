package net.xmilon.craftpy.client;

import net.xmilon.craftpy.CraftPyLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.network.ResponsePayload;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.project.ProjectSummary;
import net.xmilon.craftpy.project.ModuleInfo;
import net.xmilon.craftpy.python.ValidationProblem;
import net.xmilon.craftpy.util.Json;

/**
 * Client-side cache of server project data. All updates arrive through network
 * responses and are applied on the client thread.
 */
public final class ClientProjectStore {
    private static final ClientProjectStore INSTANCE = new ClientProjectStore();
    private static final int MAX_LOG = 200;

    private final List<ProjectSummary> projects = new ArrayList<>();
    private final Map<String, List<String>> consoleLogs = new java.util.HashMap<>();
    private final Map<String, String> openFiles = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private String selectedId;
    private String activeProjectId;
    private String pendingSelectId;
    private List<String> files = new ArrayList<>();
    private String activeFile;
    private List<ValidationProblem> validationProblems = new ArrayList<>();
    private final List<String> activeProjects = new ArrayList<>();
    private final List<String> dirty = new ArrayList<>();
    /** What was sent by the last save of each file: edits typed while the save travels keep the file unsaved. */
    private final Map<String, String> saving = new java.util.HashMap<>();
    private final List<ModuleInfo> modules = new ArrayList<>();
    private final java.util.Map<String, java.util.function.Consumer<String>> readmeCallbacks = new java.util.HashMap<>();
    private final java.util.Set<String> virtualFiles = new java.util.HashSet<>();
    private boolean debugMode;
    private java.util.function.BiConsumer<String, Integer> definitionCallback;
    private String pendingIdeProject;
    /** File to open once the selected project's file list arrives (instead of main.py). */
    private String pendingOpenFile;

    private ClientProjectStore() {
    }

    public static ClientProjectStore getInstance() {
        return INSTANCE;
    }

    public void setListener(Runnable listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void clearListener(Runnable listener) {
        listeners.remove(listener);
    }

    public List<String> log() {
        return log("default");
    }

    public List<String> log(String session) {
        return Collections.unmodifiableList(consoleLogs.computeIfAbsent(session, k -> new ArrayList<>()));
    }

    public void clearLog() {
        clearLog("default");
    }

    public void clearLog(String session) {
        consoleLogs.computeIfAbsent(session, k -> new ArrayList<>()).clear();
        notifyListener();
    }

    public List<ProjectSummary> projects() {
        return Collections.unmodifiableList(projects);
    }

    public ProjectSummary find(String id) {
        if (id == null) {
            return null;
        }
        for (ProjectSummary summary : projects) {
            if (id.equals(summary.id)) {
                return summary;
            }
        }
        return null;
    }

    public String selectedId() {
        return selectedId;
    }

    /** Selects a project and opens {@code file} once its files are listed. */
    public void select(String id, String file) {
        if (id != null && id.equals(activeProjectId) && files.contains(file)) {
            openFile(file);
            return;
        }
        select(id);
        this.pendingOpenFile = file;
    }

    public void select(String id) {
        this.pendingOpenFile = null;
        if (id == null || !id.equals(activeProjectId)) {
            // Tabs belong to one project: a Save after switching would write them into the new one.
            openFiles.clear();
            dirty.clear();
            virtualFiles.clear();
            validationProblems.clear();
            this.activeFile = null;
        }
        this.selectedId = id;
        this.activeProjectId = id;
        this.files = new ArrayList<>();
        if (id == null) {
            notifyListener();
            return;
        }
        request(ProjectAction.LIST_FILES, id, "");
    }

    // ---- files ----

    public List<String> files() {
        return Collections.unmodifiableList(files);
    }

    public String activeProjectId() {
        return activeProjectId;
    }

    public String activeFile() {
        return activeFile;
    }

    public void openFile(String path) {
        if (openFiles.containsKey(path)) {
            this.activeFile = path;
            notifyListener();
            return;
        }
        request(ProjectAction.READ_FILE, activeProjectId, path);
    }

    /**
     * Drops every open file tab after the project's files changed on disk (rename everywhere) and reopens
     * {@code reopen} once the file list is back. Callers make sure nothing unsaved is open.
     */
    public void reloadAfterDiskChange(String reopen) {
        openFiles.keySet().removeIf(path -> !virtualFiles.contains(path));
        dirty.clear();
        activeFile = null;
        if (activeProjectId == null) {
            notifyListener();
            return;
        }
        pendingOpenFile = reopen;
        request(ProjectAction.LIST_FILES, activeProjectId, "");
    }

    /** Opens a read-only, project-independent buffer (e.g. the built-in API doc). */
    public void openVirtualFile(String path, String content) {
        if (path == null || content == null) {
            return;
        }
        openFiles.put(path, content);
        virtualFiles.add(path);
        dirty.remove(path);
        this.activeFile = path;
        notifyListener();
    }

    /** True when the tab is a built-in reference (never written to or validated on the server). */
    public boolean isVirtual(String path) {
        return path != null && virtualFiles.contains(path);
    }

    /** Snapshot of all open file contents, used for asset usage scanning. */
    public Map<String, String> openFileContents() {
        return Collections.unmodifiableMap(openFiles);
    }

    /** Paths of the open editor tabs, in the order they were opened. */
    public List<String> openTabs() {
        return new ArrayList<>(openFiles.keySet());
    }

    /** Closes an editor tab; when the active file is closed the newest tab becomes active. */
    public void closeFile(String path) {
        if (path == null || !openFiles.containsKey(path)) {
            return;
        }
        openFiles.remove(path);
        dirty.remove(path);
        virtualFiles.remove(path);
        if (path.equals(activeFile)) {
            activeFile = openFiles.isEmpty() ? null : new ArrayList<>(openFiles.keySet()).get(openFiles.size() - 1);
        }
        notifyListener();
    }

    public String fileContent(String path) {
        return openFiles.get(path);
    }

    public boolean hasUnsaved(String path) {
        return dirty.contains(path);
    }

    /** True when any open tab has edits that were not saved. */
    public boolean hasUnsavedChanges() {
        return !dirty.isEmpty();
    }

    /** True when the file has local edits not yet written to the server. */
    public boolean isDirty(String path) {
        return dirty.contains(path);
    }

    public boolean debugMode() {
        return debugMode;
    }

    public void setDebugMode(boolean value) {
        this.debugMode = value;
    }

    public void setDefinitionCallback(java.util.function.BiConsumer<String, Integer> callback) {
        this.definitionCallback = callback;
    }

    public String dirtyContent(String path) {
        return openFiles.get(path);
    }

    /** Stores locally-edited content (no network round trip until save). */
    public void markDirty(String path, String content) {
        openFiles.put(path, content);
        if (!dirty.contains(path)) {
            dirty.add(path);
        }
    }

    // ---- console / explorer actions ----

    public void evaluate(String code) {
        evaluate(code, "default");
    }

    public void evaluate(String code, String session) {
        if (activeProjectId == null) {
            append("Open a project before using the console");
            notifyListener();
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("code", code == null ? "" : code);
        params.addProperty("session", session);
        request(ProjectAction.EVAL, activeProjectId, Json.COMPACT.toJson(params));
    }

    public void interrupt() {
        request(ProjectAction.INTERRUPT, activeProjectId, "");
    }

    public void createEntry(String path, String type) {
        if (activeProjectId == null) {
            append("Open a project first");
            return;
        }
        request(ProjectAction.CREATE_ENTRY, activeProjectId, Json.COMPACT.toJson(new EntryOp(path, type)));
    }

    public void deleteEntry(String path) {
        if (activeProjectId == null) {
            return;
        }
        request(ProjectAction.DELETE_ENTRY, activeProjectId, Json.COMPACT.toJson(new EntryOp(path, null)));
    }

    public void renameEntry(String from, String to) {
        if (activeProjectId == null) {
            return;
        }
        request(ProjectAction.RENAME_ENTRY, activeProjectId, Json.COMPACT.toJson(new RenameOp(from, to)));
    }

    public void findDefinition(String symbol) {
        if (activeProjectId != null && symbol != null && !symbol.isBlank()) {
            request(ProjectAction.FIND_DEFINITION, activeProjectId, symbol);
        }
    }

    /** Result of {@link #requestAsset}; {@code data} is empty when {@code error} is set. */
    public record AssetBytes(String path, byte[] data, String error) {
    }

    /** Project the server asked the IDE to open (from {@code /craftpy ide project <id>}). */
    public String pendingIdeProject() {
        return pendingIdeProject;
    }

    public void setPendingIdeProject(String id) {
        this.pendingIdeProject = id;
    }

    /**
     * Fetches a project asset file from the server for previewing. The callback
     * runs on the client thread. Several requests may be in flight (the dock opens a texture and a sound in a row):
     * the callbacks wait in order and a reply goes to the one that asked for its path.
     */
    public void requestAsset(String projectId, String path, java.util.function.Consumer<AssetBytes> callback) {
        assetWaiters.add(new AssetWaiter(path, callback));
        request(ProjectAction.READ_ASSET, projectId, path);
    }

    private record AssetWaiter(String path, java.util.function.Consumer<AssetBytes> callback) {
    }

    private final java.util.ArrayDeque<AssetWaiter> assetWaiters = new java.util.ArrayDeque<>();

    /** The waiter for a reply: the one that asked for this path, else the oldest. */
    private AssetWaiter takeWaiter(String path) {
        for (AssetWaiter waiter : assetWaiters) {
            if (waiter.path().equals(path)) {
                assetWaiters.remove(waiter);
                return waiter;
            }
        }
        return assetWaiters.poll();
    }
    private final java.util.Map<String, java.util.ArrayDeque<java.util.function.Consumer<String>>> uploadAcks = new java.util.HashMap<>();

    public record EntryOp(String path, String type) {
    }

    public record RenameOp(String from, String to) {
    }

    /** Uploads binary asset bytes to the active project as base64, in chunks. */
    public void uploadAsset(String projectId, String path, byte[] bytes) {
        uploadAsset(projectId, path, bytes, null);
    }

    /**
     * As above; {@code done} runs on the client thread once the server answered: null when the file was written,
     * otherwise the reason it was refused. Use it before telling the player that something is saved.
     */
    public void uploadAsset(String projectId, String path, byte[] bytes, java.util.function.Consumer<String> done) {
        if (done != null) {
            uploadAcks.computeIfAbsent(path, k -> new java.util.ArrayDeque<>()).add(done);
        }
        // A single custom-payload string is capped at 32767 chars, so large
        // assets (sounds especially) must be split across several packets. Base64
        // is a single continuous string, so concatenating the chunks on the
        // server reconstructs the original bytes exactly.
        String encoded = java.util.Base64.getEncoder().encodeToString(bytes);
        int total = Math.max(1, (encoded.length() + UPLOAD_CHUNK - 1) / UPLOAD_CHUNK);
        append("Uploading " + path + " (" + bytes.length + " bytes, " + total + " chunk" + (total == 1 ? "" : "s")
                + ") ...");
        for (int i = 0; i < total; i++) {
            int start = i * UPLOAD_CHUNK;
            int end = Math.min(encoded.length(), start + UPLOAD_CHUNK);
            AssetUpload upload = new AssetUpload(path, i, total, encoded.substring(start, end));
            request(ProjectAction.UPLOAD_ASSET, projectId, Json.COMPACT.toJson(upload));
        }
    }

    private static final int UPLOAD_CHUNK = net.xmilon.craftpy.server.UploadRules.CHUNK_CHARS;

    private record AssetUpload(String path, int chunk, int total, String data) {
    }

    public void saveActiveFile() {
        saveFile(activeFile);
    }

    /** Writes one open file to the project (the split editor saves a file that is not the active one). */
    public void saveFile(String path) {
        if (activeProjectId == null || path == null) {
            append("Nothing to save");
            return;
        }
        if (virtualFiles.contains(path)) {
            append("'" + path + "' is a built-in reference - it cannot be saved.");
            return;
        }
        String content = openFiles.get(path);
        if (content == null) {
            return;
        }
        saving.put(path, content);
        FileWrite write = new FileWrite(path, content);
        request(ProjectAction.WRITE_FILE, activeProjectId, Json.COMPACT.toJson(write));
    }

    public void validateActiveFile() {
        if (activeFile == null) {
            append("No file open to validate");
            return;
        }
        if (virtualFiles.contains(activeFile)) {
            append("'" + activeFile + "' is a built-in reference - nothing to validate.");
            return;
        }
        String content = openFiles.get(activeFile);
        if (content == null) {
            return;
        }
        FileWrite write = new FileWrite(activeFile, content);
        request(ProjectAction.WRITE_FILE, activeProjectId, Json.COMPACT.toJson(write));
        net.xmilon.craftpy.client.CodeProblems.setForFile(activeFile, content);
        request(ProjectAction.VALIDATE, activeProjectId, activeFile);
        append("Validating " + activeFile + " on the Python worker ...");
    }

    public List<ValidationProblem> validationProblems() {
        return Collections.unmodifiableList(validationProblems);
    }

    public List<String> activeProjects() {
        return Collections.unmodifiableList(activeProjects);
    }

    // ---- modules ----

    public List<ModuleInfo> modules() {
        return Collections.unmodifiableList(modules);
    }

    public ModuleInfo findModule(String path) {
        if (path == null) {
            return null;
        }
        for (ModuleInfo module : modules) {
            if (path.equals(module.path)) {
                return module;
            }
        }
        return null;
    }

    /**
     * Runs a server-side editor tool ({@code symbols}, {@code usage}, {@code rename}, {@code explain},
     * {@code decls}, {@code profile}, {@code trace}). The callback gets the reply object on the client thread;
     * on failure it carries an {@code error} string. One request per op is in flight at a time.
     */
    public void tool(String op, JsonObject args, java.util.function.Consumer<JsonObject> callback) {
        boolean global = op.equals("trace") || op.equals("profile");
        if (activeProjectId == null && !global) {
            append("Open a project first");
            notifyListener();
            return;
        }
        args.addProperty("op", op);
        toolCallbacks.put(op, callback);
        request(ProjectAction.TOOL, activeProjectId == null ? "" : activeProjectId, Json.COMPACT.toJson(args));
    }

    private final Map<String, java.util.function.Consumer<JsonObject>> toolCallbacks = new java.util.HashMap<>();

    /** Asks the server to re-scan every .cpm under pyforge/. */
    public void refreshModules() {
        request(ProjectAction.MODULES_LIST, null, "");
    }

    public void setModuleEnabled(String id, boolean enabled) {
        if (id == null || id.isBlank()) {
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("id", id);
        params.addProperty("enabled", enabled);
        request(ProjectAction.MODULE_SET_ENABLED, null, Json.COMPACT.toJson(params));
    }

    public void setAllModulesEnabled(boolean enabled) {
        request(ProjectAction.MODULE_SET_ALL, null, Boolean.toString(enabled));
    }

    public void reloadModule(String id) {
        if (id != null && !id.isBlank()) {
            request(ProjectAction.MODULE_RELOAD, null, id);
        }
    }

    public void deleteModule(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("path", path);
        request(ProjectAction.MODULE_DELETE, null, Json.COMPACT.toJson(params));
    }

    /** Fetches a module's bundled README (markdown); the callback runs on the client thread. */
    public void requestModuleReadme(String path, java.util.function.Consumer<String> callback) {
        if (path == null || path.isBlank()) {
            if (callback != null) {
                callback.accept("");
            }
            return;
        }
        if (callback != null) {
            readmeCallbacks.put(path, callback);
        }
        request(ProjectAction.MODULE_README, null, path);
    }

    // ---- requests ----

    /** Asks for the file list of the open project again (after a change the server made outside the usual requests). */
    public void listFiles() {
        if (activeProjectId != null) {
            request(ProjectAction.LIST_FILES, activeProjectId, "");
        }
    }

    public void refresh() {
        request(ProjectAction.LIST, null, "");
    }

    /** Packs the current project into a shareable .cpm module (empty data = current version). */
    public void buildProject(String version) {
        String id = activeProjectId();
        if (id == null) {
            append("No project loaded - pick one via File > Projects...");
            return;
        }
        request(ProjectAction.BUILD, id, version == null ? "" : version);
    }

    public void request(ProjectAction action, String projectId, String data) {
        IdeHost.get().send(action, projectId, data);
    }

    /** Parses a response payload defensively; a malformed body logs and yields null instead of crashing the client. */
    private <T> T tryParse(ProjectAction action, String json, Class<T> type) {
        try {
            return Json.COMPACT.fromJson(json, type);
        } catch (Exception e) {
            CraftPyLog.LOGGER.warn("CraftPy: bad {} response ({}): {} -> {}", action, type.getSimpleName(), e.getMessage(),
                    truncate(json));
            return null;
        }
    }

    private <T> T tryParse(ProjectAction action, String json, java.lang.reflect.Type type) {
        try {
            return Json.COMPACT.fromJson(json, type);
        } catch (Exception e) {
            CraftPyLog.LOGGER.warn("CraftPy: bad {} response: {} -> {}", action, e.getMessage(), truncate(json));
            return null;
        }
    }

    private static String truncate(String json) {
        if (json == null) {
            return "null";
        }
        return json.length() <= 120 ? json : json.substring(0, 120) + "...";
    }

    public void onResponse(ResponsePayload payload) {
        switch (payload.action()) {
            case LIST -> {
                List<ProjectSummary> parsed = tryParse(payload.action(), payload.json(),
                        new TypeToken<List<ProjectSummary>>() {
                        }.getType());
                projects.clear();
                if (parsed != null) {
                    projects.addAll(parsed);
                    // A just-created project: select it so the create flow ends visibly.
                    if (pendingSelectId != null) {
                        for (ProjectSummary summary : parsed) {
                            if (pendingSelectId.equals(summary.id)) {
                                pendingSelectId = null;
                                select(summary.id);
                                break;
                            }
                        }
                    }
                }
            }
            case CREATE -> {
                if (payload.success()) {
                    String created = payload.json();
                    if (created != null && !created.isBlank()) {
                        pendingSelectId = created;
                    }
                    if (payload.message() != null && !payload.message().isBlank()) {
                        append(payload.message());
                    }
                    refresh();
                } else {
                    if (payload.message() != null && !payload.message().isBlank()) {
                        append(payload.message());
                    }
                    notifyListener();
                    return;
                }
            }
            case LIST_FILES -> {
                List<String> parsed = tryParse(payload.action(), payload.json(),
                        new TypeToken<List<String>>() {
                        }.getType());
                files.clear();
                if (parsed != null) {
                    files.addAll(parsed);
                    autoOpen(files);
                }
            }
            case READ_FILE -> {
                ProjectManager.FileRead read = tryParse(payload.action(), payload.json(), ProjectManager.FileRead.class);
                if (read != null && read.success()) {
                    openFiles.put(read.path(), read.content());
                    dirty.remove(read.path());
                    this.activeFile = read.path();
                    CodeProblems.setForFile(read.path(), read.content());
                } else if (read != null) {
                    append(read.message());
                }
            }
            case VALIDATE -> {
                List<ValidationProblem> parsed = tryParse(payload.action(), payload.json(),
                        new com.google.gson.reflect.TypeToken<List<ValidationProblem>>() {
                        }.getType());
                validationProblems.clear();
                if (parsed != null) {
                    validationProblems.addAll(parsed);
                }
                if (validationProblems.isEmpty()) {
                    append("No Python problems found.");
                } else {
                    append(validationProblems.size() + " Python problem(s) reported.");
                }
            }
            case WINDOW -> net.xmilon.craftpy.client.mui.WindowFragment.apply(payload.message(), payload.json());
            case SET_ACTIVE -> {
                List<String> parsed = tryParse(payload.action(), payload.json(),
                        new com.google.gson.reflect.TypeToken<List<String>>() {
                        }.getType());
                activeProjects.clear();
                if (parsed != null) {
                    activeProjects.addAll(parsed);
                }
                net.xmilon.craftpy.client.ClientPackSync.reload();
            }
            case CONSOLE -> {
                if (payload.message() != null && !payload.message().isBlank()) {
                    append(payload.message());
                }
                return;
            }
            case WRITE_FILE -> {
                String path = payload.json() == null || payload.json().isBlank() ? activeFile : payload.json();
                if (payload.success() && path != null) {
                    String sent = saving.remove(path);
                    if (sent == null || sent.equals(openFiles.get(path))) {
                        dirty.remove(path);
                    }
                }
                if (payload.message() != null && !payload.message().isBlank()) {
                    append(payload.message());
                }
            }
            case EVAL -> {
                JsonObject result = tryParse(payload.action(), payload.json(), JsonObject.class);
                if (result != null) {
                    String session = result.has("session") ? result.get("session").getAsString() : "default";
                    if (result.has("output") && result.get("output").isJsonArray()) {
                        for (com.google.gson.JsonElement element : result.getAsJsonArray("output")) {
                            append(session, element.getAsString());
                        }
                    }
                    if (result.has("error") && !result.get("error").isJsonNull()) {
                        append(session, "ERROR: " + result.getAsJsonObject("error").get("message").getAsString());
                    } else if (result.has("value") && !result.get("value").isJsonNull()) {
                        append(session, "=> " + result.get("value").getAsString());
                    }
                }
                return;
            }
            case CREATE_ENTRY, DELETE_ENTRY, RENAME_ENTRY -> {
                if (payload.message() != null && !payload.message().isBlank()) {
                    append(payload.message());
                }
                if (payload.success() && activeProjectId != null) {
                    request(ProjectAction.LIST_FILES, activeProjectId, "");
                }
            }
            case FIND_DEFINITION -> {
                ProjectManager.FindResult result = tryParse(payload.action(), payload.json(), ProjectManager.FindResult.class);
                if (definitionCallback != null && result != null) {
                    definitionCallback.accept(result.file(), result.line());
                }
                return;
            }
            case READ_ASSET -> {
                if (!payload.success()) {
                    AssetWaiter waiter = assetWaiters.poll();
                    if (waiter != null) {
                        waiter.callback().accept(new AssetBytes("", new byte[0],
                                payload.message().isEmpty() ? "Failed to read asset" : payload.message()));
                    }
                    return;
                }
                try {
                    com.google.gson.JsonObject object = Json.COMPACT.fromJson(payload.json(), JsonObject.class);
                    String path = object.get("path").getAsString();
                    AssetWaiter waiter = takeWaiter(path);
                    if (waiter != null) {
                        byte[] data = java.util.Base64.getDecoder().decode(object.get("data").getAsString());
                        waiter.callback().accept(new AssetBytes(path, data, ""));
                    }
                } catch (Exception e) {
                    AssetWaiter waiter = assetWaiters.poll();
                    if (waiter != null) {
                        waiter.callback().accept(new AssetBytes("", new byte[0], "Bad asset payload: " + e.getMessage()));
                    }
                }
                return;
            }
            case UPLOAD_ASSET -> {
                var acks = uploadAcks.get(payload.json());
                var done = acks == null ? null : acks.poll();
                if (acks != null && acks.isEmpty()) {
                    uploadAcks.remove(payload.json());
                }
                if (done != null) {
                    done.accept(payload.success() ? null : payload.message());
                }
                if (payload.message() != null && !payload.message().isBlank()) {
                    append(payload.message());
                }
                if (!payload.success()) {
                    notifyListener();
                    return;
                }
                refresh();
            }
            case TOOL -> {
                JsonObject body = tryParse(payload.action(), payload.json(), JsonObject.class);
                String op = body != null && body.has("op") ? body.get("op").getAsString() : "";
                if (body == null) {
                    body = new JsonObject();
                }
                if (!payload.success()) {
                    body.addProperty("error", payload.message().isEmpty() ? "Tool failed" : payload.message());
                }
                // Editor assists fire on every keystroke: several replies are in flight, so their callback stays.
                boolean assist = op.equals("complete") || op.equals("hover") || op.equals("signature") || op.equals("catalog");
                java.util.function.Consumer<JsonObject> callback = assist ? toolCallbacks.get(op) : toolCallbacks.remove(op);
                if (callback != null) {
                    callback.accept(body);
                } else if (!payload.success() && !assist) {
                    append(payload.message());
                    notifyListener();
                }
                return;
            }
            case OPEN_IDE -> {
                this.pendingIdeProject = payload.json() == null || payload.json().isBlank() ? null : payload.json();
                notifyListener();
                return;
            }
            case BUILD -> {
                if (payload.message() != null && !payload.message().isBlank()) {
                    append(payload.message());
                }
                notifyListener();
                return;
            }
            case MODULES_LIST -> {
                List<ModuleInfo> parsed = tryParse(payload.action(), payload.json(),
                        new TypeToken<List<ModuleInfo>>() {
                        }.getType());
                modules.clear();
                if (parsed != null) {
                    modules.addAll(parsed);
                }
            }
            case MODULE_README -> {
                JsonObject obj = tryParse(payload.action(), payload.json(), JsonObject.class);
                if (obj != null) {
                    String path = obj.has("path") && !obj.get("path").isJsonNull() ? obj.get("path").getAsString() : null;
                    String readme = obj.has("readme") && !obj.get("readme").isJsonNull() ? obj.get("readme").getAsString() : "";
                    java.util.function.Consumer<String> callback = path == null ? null : readmeCallbacks.remove(path);
                    if (callback != null) {
                        callback.accept(readme);
                    }
                }
                return;
            }
            case MODULE_SET_ENABLED, MODULE_SET_ALL, MODULE_RELOAD, MODULE_DELETE -> {
                if (payload.message() != null && !payload.message().isBlank()) {
                    append(payload.message());
                }
                refreshModules();
            }
            default -> {
                if (payload.message() != null && !payload.message().isBlank()) {
                    append(payload.message());
                }
                if (!payload.success()) {
                    notifyListener();
                    return;
                }
                refresh();
            }
        }
        notifyListener();
    }

    private void autoOpen(List<String> projectFiles) {
        String wanted = pendingOpenFile;
        pendingOpenFile = null;
        if (wanted != null && projectFiles.contains(wanted)) {
            openFile(wanted);
            return;
        }
        if (activeFile != null) {
            return; // a refresh (new file, rename...) must not jump away from what is open
        }
        // Prefer main.py, otherwise the first .py file.
        for (String preferred : new String[] { "code/main.py", "code/Main.py" }) {
            if (projectFiles.contains(preferred)) {
                openFile(preferred);
                return;
            }
        }
        for (String file : projectFiles) {
            if (file.startsWith("code/") && file.endsWith(".py")) {
                openFile(file);
                return;
            }
        }
    }

    public void append(String message) {
        append("default", message);
    }

    public void append(String session, String message) {
        List<String> log = consoleLogs.computeIfAbsent(session, k -> new ArrayList<>());
        log.add(message);
        while (log.size() > MAX_LOG) {
            log.remove(0);
        }
    }

    private void notifyListener() {
        if (listeners.isEmpty()) {
            return;
        }
        IdeHost.get().execute(() -> {
            for (Runnable current : listeners) {
                current.run();
            }
        });
    }

    public record FileWrite(String path, String content) {
    }
}