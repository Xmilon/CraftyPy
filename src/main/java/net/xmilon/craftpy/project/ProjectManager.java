package net.xmilon.craftpy.project;

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

import net.xmilon.craftpy.CraftPy;
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
        instance = manager;
        return manager;
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
                CraftPy.LOGGER.error("Failed to list projects", e);
            }
            summaries.sort((a, b) -> a.id.compareToIgnoreCase(b.id));
            return summaries;
        });
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
                Json.writeString(dir.resolve("code").resolve("main.py"), templateMainPy(id));
                return OpResult.ok(id, "Created project " + id);
            } catch (IOException e) {
                CraftPy.LOGGER.error("Failed to create project {}", id, e);
                return OpResult.fail("Failed to create project: " + e.getMessage());
            }
        });
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
        });
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
        });
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
        });
    }

    public void setLastError(String id, String error) {
        Project project = projects.get(id);
        if (project != null) {
            project.setLastError(error);
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
        try {
            ProjectMetadata metadata = loadMetadata(id, dir);
            Project project = projects.computeIfAbsent(id, key -> new Project(key, dir, metadata));
            if (!loaded && metadata.requiresRestart) {
                // Restart-only projects keep their behavior until the game restarts.
                project.setLoaded(false);
                return OpResult.ok(id, "Marked " + id + " for unload at restart");
            }
            project.metadata(metadata);
            project.setLoaded(loaded);
            project.setLastError(null);
            return OpResult.ok(id, (loaded ? "Loaded " : "Unloaded ") + id);
        } catch (IOException e) {
            return OpResult.fail("Failed to " + (loaded ? "load " : "unload ") + id + ": " + e.getMessage());
        }
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
                stream.filter(Files::isRegularFile)
                        .filter(path -> !path.startsWith(dir.resolve("build")))
                        .forEach(path -> files.add(dir.relativize(path).toString().replace('\\', '/')));
            } catch (IOException e) {
                CraftPy.LOGGER.error("Failed to list files of {}", id, e);
            }
            files.sort(String::compareTo);
            return files;
        });
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
                if (Files.size(file) > (long) Config.MAX_PACKET_SIZE_KB.get() * 1024) {
                    return new FileRead(id, path, false, "File exceeds max packet size", "");
                }
                return new FileRead(id, path, true, "", Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return new FileRead(id, path, false, "Failed to read file: " + e.getMessage(), "");
            }
        });
    }

    /** Writes a file inside the project. Creates parent directories. */
    public CompletableFuture<OpResult> writeFile(String id, String path, String content) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            Path file = resolveSafe(dir, path);
            if (file == null) {
                return OpResult.fail("Illegal path: " + path);
            }
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, content, StandardCharsets.UTF_8);
                return OpResult.ok(id, "Saved " + path);
            } catch (IOException e) {
                return OpResult.fail("Failed to save " + path + ": " + e.getMessage());
            }
        });
    }

    /** Writes a binary file inside the project (asset upload). Creates parent directories. */
    public CompletableFuture<OpResult> writeBinary(String id, String path, byte[] content) {
        return CraftPyExecutors.supplyAsync(() -> {
            Path dir = projectsDir.resolve(id);
            Path file = resolveSafe(dir, path);
            if (file == null) {
                return OpResult.fail("Illegal path: " + path);
            }
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, content);
                return OpResult.ok(id, "Wrote " + path + " (" + content.length + " bytes)");
            } catch (IOException e) {
                return OpResult.fail("Failed to write " + path + ": " + e.getMessage());
            }
        });
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
        });
    }

    /** Maximum binary preview size; must fit a single base64 response payload. */
    public static final int BINARY_READ_LIMIT = 24 * 1024;

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
                    return OpResult.ok(id, "Created folder " + path);
                }
                Files.createDirectories(target.getParent());
                String content = path.toLowerCase(Locale.ROOT).endsWith(".py")
                        ? "# " + target.getFileName() + "\n" : "";
                Files.writeString(target, content, StandardCharsets.UTF_8);
                return OpResult.ok(id, "Created " + path);
            } catch (IOException e) {
                return OpResult.fail("Failed to create " + path + ": " + e.getMessage());
            }
        });
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
                if (Files.isDirectory(target)) {
                    deleteRecursively(target);
                } else if (Files.exists(target)) {
                    Files.delete(target);
                } else {
                    return OpResult.fail("Not found: " + path);
                }
                return OpResult.ok(id, "Deleted " + path);
            } catch (IOException e) {
                return OpResult.fail("Failed to delete " + path + ": " + e.getMessage());
            }
        });
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
                return OpResult.ok(id, "Renamed " + from + " -> " + to);
            } catch (IOException e) {
                return OpResult.fail("Failed to rename: " + e.getMessage());
            }
        });
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
                CraftPy.LOGGER.warn("findDefinition failed for {}: {}", id, e.getMessage());
            }
            return FindResult.missing();
        });
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
        if (normalized.contains("..")) {
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
                "assets/animations", "assets/other", "data", "build" }) {
            Files.createDirectories(dir.resolve(sub));
        }
    }

    private static String templateMainPy(String id) {
        return "# " + id + " - CraftPy project\n"
                + "# Define content with the CraftPy Python API, e.g.:\n"
                + "#\n"
                + "# from craftpy import *\n"
                + "#\n"
                + "# @block(\"ruby_block\")\n"
                + "# class RubyBlock:\n"
                + "#     name = \"Ruby Block\"\n"
                + "#     texture = tex(\"minecraft:block/dirt\")\n"
                + "#     hardness = 2.0\n"
                + "\n"
                + "def main(ctx):\n"
                + "    ctx.log(\"Hello from " + id + "\")\n";
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
