package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.network.RequestPayload;
import net.xmilon.craftpy.network.ResponsePayload;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.project.ProjectSummary;
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
    private boolean debugMode;
    private java.util.function.BiConsumer<String, Integer> definitionCallback;

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

    public void select(String id) {
        this.selectedId = id;
        this.activeProjectId = id;
        this.files = new ArrayList<>();
        this.activeFile = null;
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

    /**
     * Fetches a project asset file from the server for previewing. The callback
     * runs on the client thread. One request is in flight at a time - previews
     * are opened one screen at a time anyway.
     */
    public void requestAsset(String projectId, String path, java.util.function.Consumer<AssetBytes> callback) {
        this.assetCallback = callback;
        request(ProjectAction.READ_ASSET, projectId, path);
    }

    private java.util.function.Consumer<AssetBytes> assetCallback;

    public record EntryOp(String path, String type) {
    }

    public record RenameOp(String from, String to) {
    }

    /** Uploads binary asset bytes to the active project as base64, in chunks. */
    public void uploadAsset(String projectId, String path, byte[] bytes) {
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

    private static final int UPLOAD_CHUNK = 16000;

    private record AssetUpload(String path, int chunk, int total, String data) {
    }

    public void saveActiveFile() {
        if (activeProjectId == null || activeFile == null) {
            append("Nothing to save");
            return;
        }
        String content = openFiles.get(activeFile);
        if (content == null) {
            return;
        }
        FileWrite write = new FileWrite(activeFile, content);
        request(ProjectAction.WRITE_FILE, activeProjectId, Json.COMPACT.toJson(write));
    }

    public void validateActiveFile() {
        if (activeFile == null) {
            append("No file open to validate");
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

    // ---- requests ----

    public void refresh() {
        request(ProjectAction.LIST, null, "");
    }

    public void request(ProjectAction action, String projectId, String data) {
        PacketDistributor.sendToServer(RequestPayload.of(action, projectId, data));
    }

    public void onResponse(ResponsePayload payload) {
        switch (payload.action()) {
            case LIST -> {
                List<ProjectSummary> parsed = Json.COMPACT.fromJson(payload.json(),
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
                List<String> parsed = Json.COMPACT.fromJson(payload.json(),
                        new TypeToken<List<String>>() {
                        }.getType());
                files.clear();
                if (parsed != null) {
                    files.addAll(parsed);
                    autoOpen(files);
                }
            }
            case READ_FILE -> {
                ProjectManager.FileRead read = Json.COMPACT.fromJson(payload.json(), ProjectManager.FileRead.class);
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
                List<ValidationProblem> parsed = Json.COMPACT.fromJson(payload.json(),
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
            case SET_ACTIVE -> {
                List<String> parsed = Json.COMPACT.fromJson(payload.json(),
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
                if (payload.success() && activeFile != null) {
                    dirty.remove(activeFile);
                }
                if (payload.message() != null && !payload.message().isBlank()) {
                    append(payload.message());
                }
            }
            case EVAL -> {
                JsonObject result = Json.COMPACT.fromJson(payload.json(), JsonObject.class);
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
                ProjectManager.FindResult result = Json.COMPACT.fromJson(payload.json(), ProjectManager.FindResult.class);
                if (definitionCallback != null && result != null) {
                    definitionCallback.accept(result.file(), result.line());
                }
                return;
            }
            case READ_ASSET -> {
                java.util.function.Consumer<AssetBytes> callback = this.assetCallback;
                this.assetCallback = null;
                if (callback == null) {
                    return;
                }
                if (!payload.success()) {
                    callback.accept(new AssetBytes("", new byte[0],
                            payload.message().isEmpty() ? "Failed to read asset" : payload.message()));
                    return;
                }
                try {
                    com.google.gson.JsonObject object = Json.COMPACT.fromJson(payload.json(), JsonObject.class);
                    String path = object.get("path").getAsString();
                    byte[] data = java.util.Base64.getDecoder().decode(object.get("data").getAsString());
                    callback.accept(new AssetBytes(path, data, ""));
                } catch (Exception e) {
                    callback.accept(new AssetBytes("", new byte[0], "Bad asset payload: " + e.getMessage()));
                }
                return;
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
        Minecraft.getInstance().execute(() -> {
            for (Runnable current : listeners) {
                current.run();
            }
        });
    }

    public record FileWrite(String path, String content) {
    }
}