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
 * Modules are discovered anywhere under {@code pyforge/} (imports/, modules/,
 * the build output in exports/, or any user folder). Each module can be
 * enabled or disabled from the IDE's Modules page; that choice is persisted in
 * {@code pyforge/system/modules.json} so it survives restarts.
 */
public final class ModuleLoader {
    private static final Object LOCK = new Object();

    private static final Map<String, ModuleBuilder.ModuleMetadata> LOADED_MODULES = new HashMap<>();
    private static final Map<String, Path> MODULE_FILES = new HashMap<>();
    /** Modules disabled at runtime but still resident in the Python worker. */
    private static final Set<String> PENDING_UNLOAD = new LinkedHashSet<>();

    private static ModuleState state;
    private static boolean stateLoaded = false;

    /** User preference persisted to {@code pyforge/system/modules.json}. */
    public static class ModuleState {
        /** Ids the user turned off; everything else defaults to enabled. */
        public List<String> disabled = new ArrayList<>();
    }

    public static void discoverAndLoad() {
        synchronized (LOCK) {
            loadState();
            List<Path> files = scanAll();
            for (Path file : files) {
                try {
                    ModuleInfo info = readInfo(file);
                    if (info.error != null) {
                        CraftPyLog.LOGGER.warn("Skipping unreadable module {}: {}", file, info.error);
                        continue;
                    }
                    if (!info.enabled) {
                        continue;
                    }
                    if (LOADED_MODULES.containsKey(info.id)) {
                        CraftPyLog.LOGGER.info("Module {} already loaded from another file, skipping {}", info.id, file);
                        continue;
                    }
                    loadModule(file);
                } catch (Exception e) {
                    CraftPyLog.LOGGER.error("Failed to load module {}", file, e);
                }
            }
        }
    }

    /** Re-loads a single module from its original .cpm file (drop + re-import). */
    public static boolean reload(String id) throws IOException {
        synchronized (LOCK) {
            Path file = MODULE_FILES.get(id);
            if (file == null || !Files.isRegularFile(file)) {
                file = findFileById(id);
            }
            if (file == null || !Files.isRegularFile(file)) {
                return false;
            }
            LOADED_MODULES.remove(id);
            MODULE_FILES.remove(id);
            PENDING_UNLOAD.remove(id);
            // Re-extract the code/assets into the cache dir so the worker runs the fresh copy.
            Path cacheDir = PyForgePaths.cache().resolve(id);
            if (Files.isDirectory(cacheDir)) {
                ProjectManager.deleteRecursively(cacheDir);
            }
            loadModule(file);
            return true;
        }
    }

    /**
     * Enables or disables a module. Enabling loads it immediately when possible;
     * disabling drops the in-memory bookkeeping and, because the Python worker
     * cannot unload a module, flags it for the next restart.
     */
    public static void setEnabled(String id, boolean enabled) {
        if (id == null || id.isBlank()) {
            return;
        }
        synchronized (LOCK) {
            loadState();
            if (enabled) {
                state.disabled.remove(id);
            } else if (!state.disabled.contains(id)) {
                state.disabled.add(id);
            }
            saveState();

            if (enabled) {
                PENDING_UNLOAD.remove(id);
                if (!LOADED_MODULES.containsKey(id)) {
                    Path file = MODULE_FILES.get(id);
                    if (file == null) {
                        file = findFileById(id);
                    }
                    if (file != null) {
                        try {
                            loadModule(file);
                            CraftPyLog.LOGGER.info("Enabled module {}", id);
                        } catch (IOException e) {
                            CraftPyLog.LOGGER.error("Failed to enable module {}", id, e);
                        }
                    }
                }
            } else if (LOADED_MODULES.containsKey(id) || MODULE_FILES.containsKey(id)) {
                MODULE_FILES.remove(id);
                LOADED_MODULES.remove(id);
                PENDING_UNLOAD.add(id);
                // Its Python handlers and timers stop now; placed content stays until a restart.
                net.xmilon.craftpy.python.PyRuntime.get().unloadOwner(id, true);
                CraftPyLog.LOGGER.info("Disabled module {} (fully unloads on next restart)", id);
            }
        }
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
            Files.delete(file);
            if (id != null) {
                LOADED_MODULES.remove(id);
                MODULE_FILES.remove(id);
                PENDING_UNLOAD.add(id);
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
            return infos;
        }
    }

    /** Loads a single .cpm into the runtime (used at startup and on enable). */
    public static void loadModule(Path path) throws IOException {
        synchronized (LOCK) {
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

                if (LOADED_MODULES.containsKey(meta.id)) {
                    CraftPyLog.LOGGER.info("Module {} already loaded, skipping.", meta.id);
                    return;
                }
                if (!ModuleSide.of(meta.side).runsOnServer()) {
                    // resources only: each player's CraftPy applies it (dynamics.ClientModulePack)
                    CraftPyLog.LOGGER.info("Module {} is client-side: nothing runs on the server", meta.id);
                    return;
                }

                // Extract code/assets/data to a persistent cache for the current session
                Path cacheDir = PyForgePaths.cache().resolve(meta.id);
                extractZip(zip, cacheDir);

                LOADED_MODULES.put(meta.id, meta);
                MODULE_FILES.put(meta.id, path);
                CraftPyLog.LOGGER.info("Loaded module: {} ({})", meta.name, meta.id);

                // Wire into the Python runtime as a persistent module and apply declarations to slots
                final String moduleId = meta.id;
                CompletableFuture<JsonObject> future = net.xmilon.craftpy.python.PyRuntime.get().loadModule(moduleId, cacheDir);
                future.thenAcceptAsync(result -> {
                    if (result == null || !result.has("declarations")) {
                        CraftPyLog.LOGGER.error("Module {} returned no declarations: {}", moduleId, result);
                        return;
                    }
                    Declarations declarations = Declarations.parse(result, "module:" + moduleId);
                    boolean serverOnly = ModuleSide.of(LOADED_MODULES.get(moduleId) == null ? null
                            : LOADED_MODULES.get(moduleId).side) == ModuleSide.SERVER;
                    if (serverOnly && !declarations.failed() && declarations.contents().stream()
                            .anyMatch(c -> "block".equals(c.kind()) || "item".equals(c.kind()))) {
                        net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(ServerLifecycleHooks.getCurrentServer(),
                                "[module " + moduleId + "] is server-side but declares blocks or items: players see them"
                                        + " without textures. Build it as \"both\" to ship its assets.");
                    }
                    if (declarations.failed()) {
                        CraftPyLog.LOGGER.error("Module {} failed: {}", moduleId, declarations.error());
                        var server = ServerLifecycleHooks.getCurrentServer();
                        net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(server,
                                "[module " + moduleId + "] ERROR: " + declarations.error());
                        return;
                    }
                    // Slots, commands and pack reloads belong to the server thread (same as projects).
                    var server = ServerLifecycleHooks.getCurrentServer();
                    Runnable apply = () -> {
                        applyModuleDeclarations(moduleId, declarations);
                        CraftPyLog.LOGGER.info("Applied module {} declarations: {} block(s), {} item(s)",
                                moduleId,
                                declarations.contents().stream().filter(c -> "block".equals(c.kind())).count(),
                                declarations.contents().stream().filter(c -> "item".equals(c.kind())).count());
                    };
                    if (server != null) {
                        server.execute(apply);
                    } else {
                        apply.run();
                    }
                }, CraftPyExecutors.io()).exceptionally(ex -> {
                    CraftPyLog.LOGGER.error("Failed to apply module {} declarations", moduleId, ex);
                    return null;
                });
            }
        }
    }

    /** Applies a module's declarations to the slot registry (like a project). */
    private static void applyModuleDeclarations(String moduleId, Declarations declarations) {
        try {
            SlotRegistry.applyAll(moduleId, declarations);
            try {
                net.xmilon.craftpy.command.ProjectCommandRegistry.register(moduleId, declarations);
            } catch (Exception e) {
                CraftPyLog.LOGGER.warn("Failed to register module commands for {}", moduleId, e);
            }

            // Rebuild packs and sync to clients (like ProjectController.afterChange)
            triggerModuleSync(moduleId);
        } catch (RuntimeException e) {
            CraftPyLog.LOGGER.error("Slot allocation failed for module {}", moduleId, e);
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
                if (meta.dependencies != null) {
                    info.dependencies = new ArrayList<>(Arrays.asList(meta.dependencies));
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
        return info;
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
     * pyforge/imports/ are moved there first; exports/ and everything else is never a module source.
     */
    public static List<Path> scanAll() {
        collectImports();
        List<Path> out = new ArrayList<>();
        walk(PyForgePaths.modules(), out);
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    /** Moves dropped .cpm files from imports/ into modules/; a name that already exists there stays put. */
    private static void collectImports() {
        Path imports = PyForgePaths.imports();
        if (!Files.isDirectory(imports)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(imports, "*.cpm")) {
            Files.createDirectories(PyForgePaths.modules());
            for (Path file : stream) {
                Path target = PyForgePaths.modules().resolve(file.getFileName());
                if (Files.exists(target)) {
                    CraftPyLog.LOGGER.warn("imports/{} left in place: modules/ already has a file with that name",
                            file.getFileName());
                } else {
                    Files.move(file, target);
                    CraftPyLog.LOGGER.info("Installed module {} from imports/", file.getFileName());
                }
            }
        } catch (IOException e) {
            CraftPyLog.LOGGER.error("Error collecting dropped modules from {}", imports, e);
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

    private static void extractZip(ZipFile zip, Path target) throws IOException {
        Files.createDirectories(target);
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            Path file = target.resolve(entry.getName());
            if (entry.isDirectory()) {
                Files.createDirectories(file);
            } else {
                Files.createDirectories(file.getParent());
                try (InputStream is = zip.getInputStream(entry)) {
                    Files.copy(is, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
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
        // First pass: prefer the first enabled file for each duplicated id.
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
        return PyForgePaths.moduleStateFile();
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
    }

    private static void saveState() {
        try {
            Json.write(stateFile(), state);
        } catch (IOException e) {
            CraftPyLog.LOGGER.error("Failed to write {}", stateFile(), e);
        }
    }

    private static boolean isEnabledState(String id) {
        loadState();
        return !state.disabled.contains(id);
    }
}
