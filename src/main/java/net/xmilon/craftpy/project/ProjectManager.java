package net.xmilon.craftpy.project;

import net.xmilon.craftpy.CraftPyLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.xmilon.craftpy.Config;
import net.xmilon.craftpy.util.CraftPyExecutors;
import net.xmilon.craftpy.util.Json;

/**
 * Owns every file operation for projects. All public methods run their IO on
 * the shared worker pool and never touch game state; callers apply results on
 * the owning thread.
 */
public final class ProjectManager {
    private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static volatile ProjectManager instance;

    private final Path projectsDir;
    private final Map<String, Project> projects = new ConcurrentHashMap<>();

    private ProjectManager(Path projectsDir) {
        this.projectsDir = projectsDir;
        PyForgePaths.ensureLayout();
    }

    public static ProjectManager init() {
        ProjectManager manager = new ProjectManager(PyForgePaths.projects());
        seedDefaultProject(manager.projectsDir);
        instance = manager;
        return manager;
    }

    /**
     * Installs the bundled {@code welcome} project once per instance (files listed in
     * {@code /craftpy_default/files.txt}). A marker in system/ keeps it from coming back after the user deletes it.
     */
    static void seedDefaultProject(Path projectsDir) {
        Path marker = PyForgePaths.system().resolve("default_project_installed");
        if (Files.exists(marker)) {
            return;
        }
        try {
            Path target = projectsDir.resolve("welcome");
            if (!Files.exists(target)) {
                String manifest;
                try (var in = ProjectManager.class.getResourceAsStream("/craftpy_default/files.txt")) {
                    if (in == null) {
                        return;
                    }
                    manifest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                createDirectories(target);
                for (String line : manifest.split("\\R")) {
                    String rel = line.trim();
                    if (rel.isEmpty() || rel.contains("..")) {
                        continue;
                    }
                    try (var in = ProjectManager.class.getResourceAsStream("/craftpy_default/" + rel)) {
                        if (in == null) {
                            continue;
                        }
                        Path file = projectsDir.resolve(rel);
                        Files.createDirectories(file.getParent());
                        Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                CraftPyLog.LOGGER.info("Installed the default project 'welcome'");
            }
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "The default project was installed once; delete this file to install it again.\n");
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("Could not install the default project: {}", e.toString());
        }
    }

    public static ProjectManager get() {
        return instance;
    }

    public static void shutdown() {
        instance = null;
    }

    public Path projectsDir() {
        return projectsDir;
    }

    /** The side written in the project's project.json ({@code both} when unreadable or missing). */
    public ModuleSide sideOf(String id) {
        try {
            return ModuleSide.of(loadMetadata(id, projectsDir.resolve(id)).side);
        } catch (IOException | RuntimeException unreadable) {
            return ModuleSide.BOTH;
        }
    }

    public Project getProject(String id) {
        return id == null ? null : projects.get(id);
    }

    public List<Project> loadedProjects() {
        List<Project> result = new ArrayList<>();
        for (Project project : projects.values()) {
            if (project.isLoaded()) {
                result.add(project);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Read operations
    // ------------------------------------------------------------------

    /** Scans the projects directory on a worker thread and returns summaries. */
    public CompletableFuture<List<ProjectSummary>> listProjects() {
        return CraftPyExecutors.supplyAsync(() -> {
            List<ProjectSummary> summaries = new ArrayList<>();
            if (!Files.isDirectory(projectsDir)) {
                return summaries;
            }
            try (Stream<Path> stream = Files.list(projectsDir)) {
                stream.filter(Files::isDirectory).forEach(dir -> {
                    String id = dir.getFileName().toString();
                    if (!VALID_ID.matcher(id).matches()) {
                        return;
                    }
                    ProjectSummary summary = scanProject(id, dir);
                    summaries.add(summary);
                });
            } catch (IOException e) {
                CraftPyLog.LOGGER.error("Failed to list projects", e);
            }
            summaries.sort((a, b) -> a.id.compareToIgnoreCase(b.id));
            return summaries;
        }, CraftPyExecutors.io());
    }

    private ProjectSummary scanProject(String id, Path dir) {
        Project existing = projects.get(id);
        try {
            ProjectMetadata metadata = loadMetadata(id, dir);
            ProjectSummary summary = ProjectSummary.of(metadata);
            summary.loaded = existing != null && existing.isLoaded();
            summary.error = existing == null ? null : existing.lastError();
            long[] stats = new long[2];
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    stats[0]++;
                    stats[1] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            summary.fileCount = (int) stats[0];
            summary.sizeBytes = stats[1];
            return summary;
        } catch (Exception e) {
            ProjectSummary summary = new ProjectSummary();
            summary.id = id;
            summary.name = id;
            summary.error = "Invalid project.json: " + e.getMessage();
            return summary;
        }
    }

    private ProjectMetadata loadMetadata(String id, Path dir) throws IOException {
        Path json = dir.resolve("project.json");
        ProjectMetadata metadata;
        if (Files.isRegularFile(json)) {
            metadata = Json.read(json, ProjectMetadata.class);
        } else {
            metadata = new ProjectMetadata(id, id);
        }
        if (metadata == null) {
            metadata = new ProjectMetadata(id, id);
        }
        if (metadata.id == null || metadata.id.isBlank()) {
            metadata.id = id;
        }
        if (metadata.name == null || metadata.name.isBlank()) {
            metadata.name = id;
        }
        return metadata;
    }

    // ------------------------------------------------------------------
    // Write operations
    // ------------------------------------------------------------------

    public CompletableFuture<OpResult> createProject(String rawName) {
        return createProject(rawName, ProjectTemplates.DEFAULT);
    }

    /** @param template one of {@link ProjectTemplates#ALL} (unknown ids fall back to the default). */
    public CompletableFuture<OpResult> createProject(String rawName, String template) {
        return CraftPyExecutors.supplyAsync(() -> {
            String id = sanitizeId(rawName);
            if (id.isEmpty()) {
                return OpResult.fail("Project name must contain a letter");
            }
            Path dir = projectsDir.resolve(id);
            if (Files.exists(dir)) {
                return OpResult.fail("Project '" + id + "' already exists");
            }
            try {
                createDirectories(dir);
                ProjectMetadata metadata = new ProjectMetadata(id, rawName == null || rawName.isBlank() ? id : rawName.trim());
                Json.write(dir.resolve("project.json"), metadata);
                Json.writeString(dir.resolve("code").resolve("main.py"), ProjectTemplates.source(template, id));
                Json.writeString(dir.resolve("README.md"), templateReadme(id, metadata.name));
                return OpResult.ok(id, "Created project " + id);
            } catch (IOException e) {
                CraftPyLog.LOGGER.error("Failed to create project {}", id, e);
                return OpResult.fail("Failed to create project: " + e.getMessage());
            }
        }, CraftPyExecutors.io());
    }

    public CompletableFuture<OpResult> deleteProject(String id) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            if (!Files.isDirectory(dir)) {
                return OpResult.fail("Project '" + id + "' does not exist");
            }
            Project project = projects.get(id);
            if (project != null && project.isLoaded()) {
                return OpResult.fail("Project is loaded; unload it first");
            }
            try {
                deleteRecursively(dir);
                projects.remove(id);
                return OpResult.ok(id, "Deleted project " + id);
            } catch (IOException e) {
                return OpResult.fail("Failed to delete project: " + e.getMessage());
            }
        }, CraftPyExecutors.forProject(id));
    }

    public CompletableFuture<OpResult> renameProject(String id, String newName) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            if (!Files.isDirectory(dir)) {
                return OpResult.fail("Project '" + id + "' does not exist");
            }
            try {
                ProjectMetadata metadata = loadMetadata(id, dir);
                metadata.name = newName;
                Json.write(dir.resolve("project.json"), metadata);
                Project project = projects.get(id);
                if (project != null) {
                    project.metadata(metadata);
                }
                return OpResult.ok(id, "Renamed project to " + newName);
            } catch (IOException e) {
                return OpResult.fail("Failed to rename project: " + e.getMessage());
            }
        }, CraftPyExecutors.forProject(id));
    }

    public CompletableFuture<OpResult> duplicateProject(String id) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path source = projectsDir.resolve(id);
            if (!Files.isDirectory(source)) {
                return OpResult.fail("Project '" + id + "' does not exist");
            }
            String newId = id + "_copy";
            int counter = 2;
            while (Files.exists(projectsDir.resolve(newId))) {
                newId = id + "_copy" + counter++;
            }
            Path target = projectsDir.resolve(newId);
            try {
                deleteRecursively(target);
                copyRecursively(source, target);
                ProjectMetadata metadata = loadMetadata(newId, target);
                metadata.id = newId;
                metadata.name = metadata.name + " Copy";
                Json.write(target.resolve("project.json"), metadata);
                return OpResult.ok(newId, "Duplicated to " + newId);
            } catch (IOException e) {
                return OpResult.fail("Failed to duplicate project: " + e.getMessage());
            }
        }, CraftPyExecutors.forProject(id));
    }

    public void setLastError(String id, String error) {
        Project project = projects.get(id);
        if (project != null) {
            project.setLastError(error);
        }
    }

    public void markRequiresRestart(String id, boolean flag) {
        Project project = projects.get(id);
        if (project != null) {
            project.setRequiresRestart(flag);
        }
        try {
            Path dir = projectsDir.resolve(id);
            if (Files.isDirectory(dir)) {
                ProjectMetadata meta = loadMetadata(id, dir);
                meta.requiresRestart = flag;
                Json.write(dir.resolve("project.json"), meta);
            }
        } catch (Exception ignored) {
        }
    }

    /** Marks a project as loaded. The actual content build/apply happens in later
     * milestones via the load pipeline. This method never runs on the game
     * thread itself - callers must invoke it from a worker and apply state back
     * on the server thread.
     */
    public OpResult markLoaded(String id, boolean loaded) {
        Path dir = projectsDir.resolve(id);
        if (!Files.isDirectory(dir)) {
            return OpResult.fail("Project '" + id + "' does not exist");
        }
        ProjectMetadata loadedMetadata;
        try {
            loadedMetadata = loadMetadata(id, dir);
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("Project '{}' has an unreadable project.json ({}); loading with defaults",
                    id, e.getMessage());
            loadedMetadata = new ProjectMetadata(id, id);
        }
        final ProjectMetadata metadata = loadedMetadata;
        Project project = projects.computeIfAbsent(id, key -> new Project(key, dir, metadata));
        if (!loaded && metadata.requiresRestart) {
            // Restart-only projects keep their behavior until the game restarts.
            project.setLoaded(false);
            return OpResult.ok(id, "Marked " + id + " for unload at restart");
        }
        project.metadata(metadata);
        project.setRequiresRestart(metadata.requiresRestart);
        project.setLoaded(loaded);
        project.setLastError(null);
        return OpResult.ok(id, (loaded ? "Loaded " : "Unloaded ") + id);
    }

    // ------------------------------------------------------------------
    // File operations
    // ------------------------------------------------------------------

    /** Lists all editable files (code, assets, data) of a project as relative paths. */
    public CompletableFuture<List<String>> listFiles(String id) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            List<String> files = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.filter(path -> !path.startsWith(dir.resolve("build")))
                        .forEach(path -> {
                            String relative = dir.relativize(path).toString().replace('\\', '/');
                            if (Files.isRegularFile(path)) {
                                files.add(relative);
                            } else if (!relative.isEmpty() && isEmptyDirectory(path)) {
                                files.add(relative + "/"); // empty folders show up in the explorer too
                            }
                        });
            } catch (IOException e) {
                CraftPyLog.LOGGER.error("Failed to list files of {}", id, e);
            }
            files.sort(String::compareTo);
            return files;
        }, CraftPyExecutors.forProject(id));
    }

    /**
     * Every {@code code/**.py} except {@code code/main.py} (relative path to text): the files main.py can import.
     * Capped so a runaway folder cannot flood the worker.
     */
    public CompletableFuture<Map<String, String>> readSources(String id) {
        return CraftPyExecutors.supplyAsync(() -> {
            Map<String, String> sources = new java.util.TreeMap<>();
            Path dir = projectsDir.resolve(id);
            Path code = dir.resolve("code");
            if (!Files.isDirectory(code)) {
                return sources;
            }
            long budget = 4L * 1024 * 1024;
            try (Stream<Path> stream = Files.walk(code)) {
                for (Path file : stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".py")).sorted().toList()) {
                    String relative = dir.relativize(file).toString().replace('\\', '/');
                    long size = Files.size(file);
                    if (relative.equals("code/main.py") || sources.size() >= 400 || size > budget) {
                        continue;
                    }
                    budget -= size;
                    sources.put(relative, Files.readString(file, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                CraftPyLog.LOGGER.warn("Failed to read sources of {}: {}", id, e.getMessage());
            }
            return sources;
        }, CraftPyExecutors.forProject(id));
    }

    private static boolean isEmptyDirectory(Path path) {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (Stream<Path> children = Files.list(path)) {
            return children.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /** Reads a file inside the project, verifying the path cannot escape. */
    public CompletableFuture<FileRead> readFile(String id, String path) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            Path file = resolveSafe(dir, path);
            if (file == null || !Files.isRegularFile(file)) {
                return new FileRead(id, path, false, "File not found: " + path, "");
            }
            try {
                if (Files.size(file) > (long) Config.get(Config.MAX_PACKET_SIZE_KB) * 1024) {
                    return new FileRead(id, path, false, "File exceeds max packet size", "");
                }
                return new FileRead(id, path, true, "", Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return new FileRead(id, path, false, "Failed to read file: " + e.getMessage(), "");
            }
        }, CraftPyExecutors.forProject(id));
    }

    /** Writes a file inside the project. Creates parent directories. */
    public CompletableFuture<OpResult> writeFile(String id, String path, String content) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            Path file = resolveSafe(dir, path);
            if (file == null) {
                return OpResult.fail("Illegal path: " + path);
            }
            if (!Files.isDirectory(dir)) {
                return OpResult.fail("Project not found: " + id);
            }
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, content, StandardCharsets.UTF_8);
                ChangeLog.record(id, "Saved " + path);
                return OpResult.ok(id, "Saved " + path);
            } catch (IOException e) {
                return OpResult.fail("Failed to save " + path + ": " + e.getMessage());
            }
        }, CraftPyExecutors.forProject(id));
    }

    /** Writes a binary file inside the project (asset upload). Creates parent directories. */
    public CompletableFuture<OpResult> writeBinary(String id, String path, byte[] content) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            Path file = resolveSafe(dir, path);
            if (file == null) {
                return OpResult.fail("Illegal path: " + path);
            }
            if (!Files.isDirectory(dir)) {
                return OpResult.fail("Project not found: " + id);
            }
            try {
                Files.createDirectories(file.getParent());
                writeAtomically(dir, file, content);
                ChangeLog.record(id, "Wrote " + path + " (" + content.length + " bytes)");
                return OpResult.ok(id, "Wrote " + path + " (" + content.length + " bytes)");
            } catch (IOException e) {
                return OpResult.fail("Failed to write " + path + ": " + e.getMessage());
            }
        }, CraftPyExecutors.forProject(id));
    }

    /**
     * Reads a binary file (asset) inside the project, capped at
     * {@link #BINARY_READ_LIMIT} bytes so it fits in a single response packet.
     */
    public CompletableFuture<BinaryRead> readBinary(String id, String path) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            Path file = resolveSafe(dir, path);
            if (file == null || !Files.isRegularFile(file)) {
                return new BinaryRead(path, false, "File not found: " + path, new byte[0]);
            }
            try {
                long size = Files.size(file);
                if (size > BINARY_READ_LIMIT) {
                    return new BinaryRead(path, false, "File too large to preview (" + size / 1024 + " KB, limit "
                            + BINARY_READ_LIMIT / 1024 + " KB)", new byte[0]);
                }
                return new BinaryRead(path, true, "", Files.readAllBytes(file));
            } catch (IOException e) {
                return new BinaryRead(path, false, "Failed to read " + path + ": " + e.getMessage(), new byte[0]);
            }
        }, CraftPyExecutors.forProject(id));
    }

    /** Writes through a temp file in build/ so the pack builder never reads half a file. */
    private static void writeAtomically(Path dir, Path file, byte[] content) throws IOException {
        Path temp = dir.resolve("build").resolve("upload.tmp");
        Files.createDirectories(temp.getParent());
        Files.write(temp, content);
        try {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Maximum binary preview size; must fit a single base64 response payload. */
    public static final int BINARY_READ_LIMIT = 288 * 1024; // 288 KB -> 384k base64 chars, under ResponsePayload's 400000

    public record BinaryRead(String path, boolean success, String message, byte[] data) {
    }

    /** Creates a new file or directory inside the project. */
    public CompletableFuture<OpResult> createEntry(String id, String path, String type) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            if (!Files.isDirectory(dir)) {
                return OpResult.fail("Project '" + id + "' does not exist");
            }
            Path target = resolveSafe(dir, path);
            if (target == null) {
                return OpResult.fail("Illegal path: " + path);
            }
            if (target.equals(dir.normalize())) {
                return OpResult.fail("Cannot create the project root");
            }
            try {
                if (Files.exists(target)) {
                    return OpResult.fail("Already exists: " + path);
                }
                if ("dir".equalsIgnoreCase(type)) {
                    Files.createDirectories(target);
                    ChangeLog.record(id, "Created folder " + path);
                    return OpResult.ok(id, "Created folder " + path);
                }
                Files.createDirectories(target.getParent());
                String content = path.toLowerCase(Locale.ROOT).endsWith(".py")
                        ? "# " + target.getFileName() + "\n" : "";
                Files.writeString(target, content, StandardCharsets.UTF_8);
                ChangeLog.record(id, "Created " + path);
                return OpResult.ok(id, "Created " + path);
            } catch (IOException e) {
                return OpResult.fail("Failed to create " + path + ": " + e.getMessage());
            }
        }, CraftPyExecutors.forProject(id));
    }

    /** Deletes a file or directory (recursively) inside the project. */
    public CompletableFuture<OpResult> deleteEntry(String id, String path) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            Path target = resolveSafe(dir, path);
            if (target == null) {
                return OpResult.fail("Illegal path: " + path);
            }
            if (target.equals(dir.normalize())) {
                return OpResult.fail("Cannot delete the project root");
            }
            if ("project.json".equals(dir.relativize(target).toString().replace('\\', '/'))) {
                return OpResult.fail("Cannot delete project.json");
            }
            try {
                if (!Files.exists(target)) {
                    return OpResult.fail("Not found: " + path);
                }
                moveToTrash(id, dir.relativize(target).toString().replace('\\', '/'), target);
                ChangeLog.record(id, "Deleted " + path);
                return OpResult.ok(id, "Deleted " + path);
            } catch (IOException e) {
                return OpResult.fail("Failed to delete " + path + ": " + e.getMessage());
            }
        }, CraftPyExecutors.forProject(id));
    }

    // ---- trash: deleted entries are kept so Undo (Ctrl+Z in the explorer) can bring them back

    private static final int TRASH_KEPT = 40;
    private static final java.util.concurrent.atomic.AtomicLong TRASH_COUNTER = new java.util.concurrent.atomic.AtomicLong();

    /** {@code <pyforge>/system/trash/<project>}: next to the projects folder, never inside a project. */
    private Path trashOf(String id) {
        Path base = projectsDir.toAbsolutePath().getParent();
        return (base == null ? projectsDir : base).resolve("system").resolve("trash").resolve(id);
    }

    private void moveToTrash(String id, String relative, Path target) throws IOException {
        Path stamp = trashOf(id).resolve(System.currentTimeMillis() + "-" + TRASH_COUNTER.incrementAndGet());
        Path copy = stamp.resolve(relative);
        Files.createDirectories(copy.getParent());
        try {
            Files.move(target, copy);
        } catch (IOException moveFailed) { // another drive: copy, then delete the original
            copyTree(target, copy);
            deleteRecursively(target);
        }
        pruneTrash(id);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Trash folders of a project, newest first. */
    private List<Path> trashStamps(String id) throws IOException {
        Path root = trashOf(id);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(Files::isDirectory)
                    .sorted(java.util.Comparator.comparingLong(ProjectManager::stampTime).thenComparing(Path::toString).reversed())
                    .toList();
        }
    }

    private static long stampTime(Path stamp) {
        String name = stamp.getFileName().toString();
        try {
            return Long.parseLong(name.substring(0, Math.max(0, name.indexOf('-'))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void pruneTrash(String id) {
        try {
            List<Path> stamps = trashStamps(id);
            for (Path old : stamps.subList(Math.min(TRASH_KEPT, stamps.size()), stamps.size())) {
                deleteRecursively(old);
            }
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("Could not prune the trash of {}: {}", id, e.toString());
        }
    }

    /** Brings back the most recently deleted copy of {@code path} (Undo of a delete). */
    public CompletableFuture<OpResult> restoreEntry(String id, String path) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            Path target = resolveSafe(dir, path);
            if (target == null || target.equals(dir.normalize())) {
                return OpResult.fail("Illegal path: " + path);
            }
            if (Files.exists(target)) {
                return OpResult.fail("Already exists: " + path);
            }
            String relative = dir.normalize().relativize(target).toString().replace('\\', '/');
            try {
                for (Path stamp : trashStamps(id)) {
                    Path copy = stamp.resolve(relative);
                    if (Files.exists(copy)) {
                        Files.createDirectories(target.getParent());
                        Files.move(copy, target);
                        try (Stream<Path> rest = Files.walk(stamp)) {
                            if (rest.noneMatch(Files::isRegularFile)) {
                                deleteRecursively(stamp);
                            }
                        }
                        ChangeLog.record(id, "Restored " + path);
                        return OpResult.ok(id, "Restored " + path);
                    }
                }
                return OpResult.fail("Nothing to restore for " + path);
            } catch (IOException e) {
                return OpResult.fail("Failed to restore " + path + ": " + e.getMessage());
            }
        }, CraftPyExecutors.forProject(id));
    }

    /** Renames/moves a file or directory inside the project. */
    public CompletableFuture<OpResult> renameEntry(String id, String from, String to) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            Path source = resolveSafe(dir, from);
            Path target = resolveSafe(dir, to);
            if (source == null || target == null) {
                return OpResult.fail("Illegal path");
            }
            if ("project.json".equals(dir.relativize(source).toString().replace('\\', '/'))) {
                return OpResult.fail("Cannot rename project.json");
            }
            if (!Files.exists(source)) {
                return OpResult.fail("Not found: " + from);
            }
            if (Files.exists(target)) {
                return OpResult.fail("Already exists: " + to);
            }
            try {
                Files.createDirectories(target.getParent());
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                ChangeLog.record(id, "Renamed " + from + " -> " + to);
                return OpResult.ok(id, "Renamed " + from + " -> " + to);
            } catch (IOException e) {
                return OpResult.fail("Failed to rename: " + e.getMessage());
            }
        }, CraftPyExecutors.forProject(id));
    }

    /** Every file path of a project plus the text of its text files (for project-wide analysis). */
    public record Snapshot(List<String> paths, java.util.Map<String, String> texts) {
    }

    private static final long SNAPSHOT_FILE_LIMIT = 256 * 1024;
    private static final long SNAPSHOT_TOTAL_LIMIT = 6L * 1024 * 1024;

    public CompletableFuture<Snapshot> snapshot(String id) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            List<String> paths = new ArrayList<>();
            java.util.Map<String, String> texts = new java.util.TreeMap<>();
            if (!Files.isDirectory(dir)) {
                return new Snapshot(paths, texts);
            }
            long total = 0;
            try (Stream<Path> stream = Files.walk(dir)) {
                List<Path> files = stream.filter(Files::isRegularFile)
                        .filter(path -> !path.startsWith(dir.resolve("build")))
                        .sorted().toList();
                for (Path file : files) {
                    String rel = dir.relativize(file).toString().replace('\\', '/');
                    paths.add(rel);
                    String lower = rel.toLowerCase(java.util.Locale.ROOT);
                    boolean text = lower.endsWith(".py") || lower.endsWith(".json") || lower.endsWith(".mcfunction")
                            || lower.endsWith(".md") || lower.endsWith(".txt");
                    if (text && Files.size(file) <= SNAPSHOT_FILE_LIMIT && total < SNAPSHOT_TOTAL_LIMIT) {
                        String content = Files.readString(file, StandardCharsets.UTF_8);
                        total += content.length();
                        texts.put(rel, content);
                    }
                }
            } catch (IOException e) {
                CraftPyLog.LOGGER.warn("snapshot failed for {}: {}", id, e.getMessage());
            }
            return new Snapshot(paths, texts);
        }, CraftPyExecutors.forProject(id));
    }

    /** Writes the changed texts, then moves the renamed asset files (a plan from {@code ProjectScan.renameId}). */
    public CompletableFuture<OpResult> applyRename(String id, net.xmilon.craftpy.analysis.ProjectScan.RenamePlan plan) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            try {
                for (var entry : plan.newTexts().entrySet()) {
                    Path file = resolveSafe(dir, entry.getKey());
                    if (file == null) {
                        return OpResult.fail("Illegal path: " + entry.getKey());
                    }
                    Files.createDirectories(file.getParent()); // a move can create its target file
                    Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
                }
                for (var move : plan.moves()) {
                    Path from = resolveSafe(dir, move.from());
                    Path to = resolveSafe(dir, move.to());
                    if (from == null || to == null) {
                        return OpResult.fail("Illegal path: " + move.from());
                    }
                    Files.createDirectories(to.getParent());
                    Files.move(from, to);
                }
                return OpResult.ok(id, "Renamed: " + plan.newTexts().size() + " file(s) edited, " + plan.moves().size()
                        + " moved");
            } catch (IOException e) {
                return OpResult.fail("Rename failed half way: " + e.getMessage());
            }
        }, CraftPyExecutors.forProject(id));
    }

    /** Searches the project's Python files for the definition of an identifier. */
    public CompletableFuture<FindResult> findDefinition(String id, String symbol) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            if (!Files.isDirectory(dir) || symbol == null || symbol.isBlank()) {
                return FindResult.missing();
            }
            String quoted = Pattern.quote(symbol);
            Pattern defPattern = Pattern.compile("^\\s*(?:def|class)\\s+" + quoted + "\\b");
            Pattern declPattern = Pattern.compile("@(?:block|item|entity|effect)\\(\\s*\"+" + quoted + "\"+\\s*\\)");
            Pattern assignPattern = Pattern.compile("^\\s*" + quoted + "\\s*=");
            try (Stream<Path> stream = Files.walk(dir)) {
                List<Path> pyFiles = stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".py"))
                        .sorted()
                        .toList();
                for (Path file : pyFiles) {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (defPattern.matcher(line).find() || declPattern.matcher(line).find()
                                || assignPattern.matcher(line).find()) {
                            return new FindResult(true, dir.relativize(file).toString().replace('\\', '/'), i);
                        }
                    }
                }
            } catch (IOException e) {
                CraftPyLog.LOGGER.warn("findDefinition failed for {}: {}", id, e.getMessage());
            }
            return FindResult.missing();
        }, CraftPyExecutors.forProject(id));
    }

    public record FindResult(boolean found, String file, int line) {
        public static FindResult missing() {
            return new FindResult(false, "", -1);
        }
    }

    /**
     * Resolves a relative path inside {@code base} and returns null if it would
     * escape the base directory (path traversal protection).
     */
    public static Path resolveSafe(Path base, String relative) {
        if (relative == null || relative.isBlank() || relative.startsWith("/")) {
            return null;
        }
        String normalized = relative.replace('\\', '/');
        if (normalized.contains("..") || normalized.indexOf(':') >= 0) { // ".." escapes, ":" is a drive or an NTFS stream
            return null;
        }
        Path resolved = base.resolve(normalized.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(base.normalize())) {
            return null;
        }
        return resolved;
    }

    public record FileRead(String projectId, String path, boolean success, String message, String content) {
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    public static String sanitizeId(String raw) {
        if (raw == null) {
            return "";
        }
        String id = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        id = id.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (id.isEmpty() || !Character.isLetter(id.charAt(0))) {
            id = "p_" + id;
        }
        id = id.toLowerCase(Locale.ROOT);
        if (!VALID_ID.matcher(id).matches()) {
            id = id.substring(0, Math.min(id.length(), 64));
        }
        if (!VALID_ID.matcher(id).matches()) {
            return "";
        }
        return id;
    }

    public static boolean isValidId(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }

    private static void createDirectories(Path dir) throws IOException {
        for (String sub : new String[] { "code", "assets/textures", "assets/models", "assets/sounds", "assets/lang",
                "data", "ui", "build" }) {
            Files.createDirectories(dir.resolve(sub));
        }
    }

    private static String templateReadme(String id, String name) {
        return "# " + name + "\n"
                + "\n"
                + "> CraftPy module `" + id + "`\n"
                + "\n"
                + ModuleReadme.TEMPLATE_MARK + " This Markdown file is bundled into the\n"
                + "built `.cpm` and shown as the extended description on the Modules page.\n"
                + "\n"
                + "## Features\n"
                + "\n"
                + "- ...\n"
                + "\n"
                + "## Usage\n"
                + "\n"
                + "```python\n"
                + "from craftpy import *\n"
                + "```\n";
    }

    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                        if (exc != null) {
                            throw exc;
                        }
                        Files.deleteIfExists(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
                return;
            } catch (java.nio.file.DirectoryNotEmptyException e) {
                // Windows may briefly report a just-emptied directory as non-empty.
                last = e;
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        if (!Files.exists(dir)) {
            return;
        }
        if (last != null) {
            throw last;
        }
    }

    public static void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(file).toString());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public record OpResult(boolean success, String projectId, String message) {
        public static OpResult ok(String projectId, String message) {
            return new OpResult(true, projectId, message);
        }

        public static OpResult fail(String message) {
            return new OpResult(false, null, message);
        }
    }
}
