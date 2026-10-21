package net.xmilon.craftpy.project;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.util.Json;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds .cpm (CraftPy Module) files from projects.
 * A .cpm is a ZIP containing module.json, code/, assets/, and data/.
 */
public final class ModuleBuilder {
    private ModuleBuilder() {}

    /** What to build: a plain version string from older clients, or JSON from the build dialog. */
    public record Request(String version, String mode, String newId, String newName, boolean install, ModuleSide side) {
        public static final String UPDATE = "update";
        public static final String NEW = "new";

        /** Empty or a bare version = update the project's own module and export only (the old behaviour). */
        public static Request parse(String data) {
            String text = data == null ? "" : data.trim();
            if (!text.startsWith("{")) {
                return new Request(text.isEmpty() ? null : text, UPDATE, "", "", false, ModuleSide.BOTH);
            }
            com.google.gson.JsonObject json = Json.COMPACT.fromJson(text, com.google.gson.JsonObject.class);
            String version = text(json, "version");
            return new Request(version.isEmpty() ? null : version, NEW.equals(text(json, "mode")) ? NEW : UPDATE,
                    text(json, "newId"), text(json, "newName"), json.has("install") && json.get("install").getAsBoolean(),
                    ModuleSide.of(text(json, "side")));
        }

        private static String text(com.google.gson.JsonObject json, String key) {
            return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString().trim() : "";
        }
    }

    /**
     * @param export    the .cpm written into {@code modules/} (the same file as {@code installed})
     * @param installed the file in {@code modules/} (or where the module already lived), null when not installed
     * @param replaced  true when an installed module with this id was overwritten
     */
    public record Result(Path export, Path installed, String moduleId, String version, boolean replaced) {
    }

    /** Builds a .cpm from a project using the version from project.json. */
    public static void build(String projectId) throws IOException {
        build(projectId, (String) null);
    }

    /**
     * Builds a .cpm from a project. The version defaults to the project.json
     * version, or {@code 1.0.0} when none is set; {@code overrideVersion} (when
     * non-null) wins over both.
     */
    public static void build(String projectId, String overrideVersion) throws IOException {
        build(projectId, new Request(overrideVersion, Request.UPDATE, "", "", false, ModuleSide.BOTH));
    }

    /**
     * Builds and optionally installs a module. In "update" mode the module keeps the project's id and an installed module
     * with that id is replaced in place (the same file, so there is never a duplicate). In "new" mode the module gets
     * {@code newId} (which must not be installed yet) and its own display name; its content then lives under that
     * namespace.
     */
    public static Result build(String projectId, Request request) throws IOException {
        Path projectDir = PyForgePaths.projects().resolve(projectId);
        if (!Files.isDirectory(projectDir)) {
            throw new NoSuchFileException("Project directory not found: " + projectId);
        }
        Path projectJson = projectDir.resolve("project.json");
        if (!Files.exists(projectJson)) {
            throw new NoSuchFileException("project.json is missing in project " + projectId);
        }
        ProjectMetadata meta = Json.COMPACT.fromJson(Files.readString(projectJson), ProjectMetadata.class);
        boolean asNew = Request.NEW.equals(request.mode());
        // a decompiled project rebuilds the module it came from, also when the project got another id (foo_2)
        String updates = meta.decompiledFrom != null && ProjectManager.isValidId(meta.decompiledFrom.module)
                ? meta.decompiledFrom.module : meta.id;
        String moduleId = asNew ? request.newId() : updates;
        if (moduleId == null || !ProjectManager.isValidId(moduleId)) {
            throw new IOException("'" + moduleId + "' is not a valid module id: start with a letter, then lowercase letters, digits or _");
        }
        Path existing = ModuleLoader.findFileById(moduleId);
        if (asNew && existing != null) {
            throw new IOException("A module with the id '" + moduleId + "' is already installed - choose another id, or update that module instead");
        }
        String version = request.version() != null ? request.version()
                : meta.version == null || meta.version.isBlank() ? "1.0.0" : meta.version;
        if (!version.matches("[0-9A-Za-z][0-9A-Za-z._+-]{0,31}")) {
            throw new IOException("'" + version + "' is not a valid version (letters, digits, . _ + -)");
        }
        String displayName = asNew && !request.newName().isBlank() ? request.newName()
                : asNew ? meta.name + " (copy)" : meta.name;

        // a module lives in modules/ (where the game loads it from, and the file to share): no separate export copy
        ModuleSide side = ModuleSide.of(meta.side); // the project's side, chosen when it was made, never at build time
        Path outputFile = existing != null && !asNew ? existing : PyForgePaths.modules().resolve(moduleId + ".cpm");
        return pack(projectDir, outputFile, meta, moduleId, displayName, version, side, asNew && !moduleId.equals(meta.id));
    }

    /** Deterministic packaging from an explicit source, without inspecting installed modules or editable projects. */
    public static Result build(Path projectDir, Path outputFile) throws IOException {
        ProjectMetadata meta = Json.COMPACT.fromJson(Files.readString(projectDir.resolve("project.json")), ProjectMetadata.class);
        if (meta == null || !ProjectManager.isValidId(meta.id)) throw new IOException("Invalid example project metadata");
        if (meta.version == null || !meta.version.matches("[0-9A-Za-z][0-9A-Za-z._+-]{0,31}")) throw new IOException("Invalid example version");
        return pack(projectDir, outputFile, meta, meta.id, meta.name, meta.version, ModuleSide.of(meta.side), false);
    }

    private static Result pack(Path projectDir, Path outputFile, ProjectMetadata meta, String moduleId,
                               String displayName, String version, ModuleSide side, boolean rename) throws IOException {
        Files.createDirectories(outputFile.getParent());
        Path temp = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(temp.toFile()))) {
            ModuleMetadata modMeta = new ModuleMetadata();
            modMeta.id = moduleId;
            modMeta.name = displayName;
            modMeta.author = meta.author;
            modMeta.description = meta.description;
            modMeta.version = version;
            modMeta.apiVersion = meta.apiVersion;
            modMeta.side = side.id();
            modMeta.dependencies = meta.dependencies == null ? new String[0]
                    : meta.dependencies.stream().filter(d -> d != null && !d.isBlank()).toArray(String[]::new);
            modMeta.permissions = net.xmilon.craftpy.security.ModulePermissions.clean(meta.permissions);
            modMeta.icon = ModuleApp.icon(meta.icon);
            modMeta.apps = ModuleApp.clean(meta.apps).apps();
            modMeta.requiredMods = meta.requiredMods == null ? new String[0] : meta.requiredMods.toArray(String[]::new);
            modMeta.requiredCapabilities = meta.requiredCapabilities == null ? new String[0] : meta.requiredCapabilities.toArray(String[]::new);
            zos.putNextEntry(entry("module.json"));
            zos.write(Json.GSON.toJson(modMeta).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // a new module is another namespace: assets/<old id>/ and "<old id>:name" ids move with it
            String from = rename ? meta.id : null;
            for (String subDir : new String[]{"code", "assets", "data", "ui"}) {
                Path dir = projectDir.resolve(subDir);
                if (Files.isDirectory(dir) && side.packs(subDir)) { // a client module is only its assets
                    zipDirectory(dir, subDir, zos, from, moduleId);
                }
            }
            // every module says what is inside: the project's README, or one written from its code
            Path readme = projectDir.resolve("README.md");
            String own = Files.isRegularFile(readme) ? Files.readString(readme, java.nio.charset.StandardCharsets.UTF_8) : null;
            String text = !ModuleReadme.needsGenerating(own) ? own
                    : ModuleReadme.generate(moduleId, displayName, meta.description, meta.author, version, meta.apiVersion,
                    side, pythonFiles(projectDir));
            // the warning is forced at the top, whoever wrote the README
            text = net.xmilon.craftpy.security.PermissionLabels.readmeBlock(
                    net.xmilon.craftpy.security.ModulePermissions.clean(meta.permissions)) + text;
            zos.putNextEntry(entry("README.md"));
            zos.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
        boolean replaced = Files.exists(outputFile);
        Files.move(temp, outputFile, StandardCopyOption.REPLACE_EXISTING);
        CraftPyLog.LOGGER.info("Built module {} {} into {}{}", moduleId, version, outputFile, replaced ? " (replaced)" : "");
        return new Result(outputFile, outputFile, moduleId, version, replaced);
    }

    private static ZipEntry entry(String name) { var entry = new ZipEntry(name); entry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0)); return entry; }

    private static Map<String, String> pythonFiles(Path projectDir) throws IOException {
        Map<String, String> out = new java.util.TreeMap<>();
        Path code = projectDir.resolve("code");
        if (!Files.isDirectory(code)) {
            return out;
        }
        try (var walk = Files.walk(code)) {
            for (Path file : walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".py")).toList()) {
                out.put(projectDir.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void zipDirectory(Path folder, String parentFolder, ZipOutputStream zos, String from, String to)
            throws IOException {
        try (var stream = Files.walk(folder)) {
            stream.filter(path -> Files.isRegularFile(path) && !path.toString().contains("__pycache__") && !path.toString().endsWith(".pyc")).sorted().forEach(path -> {
                String name = entryName(parentFolder, folder.relativize(path).toString().replace("\\", "/"), from, to);
                try {
                    zos.putNextEntry(entry(name));
                    zos.write(content(name, Files.readAllBytes(path), from, to));
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /** {@code assets/<from>/...} and {@code data/<from>/...} become {@code <to>}; everything else keeps its path. */
    static String entryName(String parentFolder, String relative, String from, String to) {
        if (from != null && (parentFolder.equals("assets") || parentFolder.equals("data"))
                && (relative.startsWith(from + "/"))) {
            relative = to + relative.substring(from.length());
        }
        return parentFolder + "/" + relative;
    }

    /** Text files: quoted {@code "<from>:id"} and {@code '<from>:id'} become {@code <to>:id}; other bytes are copied. */
    static byte[] content(String name, byte[] bytes, String from, String to) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (from == null || !(lower.endsWith(".py") || lower.endsWith(".json") || lower.endsWith(".mcmeta"))) {
            return bytes;
        }
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String changed = text.replace("\"" + from + ":", "\"" + to + ":").replace("'" + from + ":", "'" + to + ":");
        return changed.equals(text) ? bytes : changed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static class ModuleMetadata {
        public String id;
        public String name;
        public String author;
        public String description;
        public String version;
        /** The Python API version the module was written for (checked when it loads). */
        public String apiVersion;
        /** {@code both} (missing = both), {@code client} or {@code server}: see {@link ModuleSide}. */
        public String side;
        /** What the module asks for (sites, data folder); approved by the player before it counts. */
        public net.xmilon.craftpy.security.ModulePermissions permissions;
        public String[] dependencies = new String[0];
        /** Hub picture and entries, already cleaned by {@link ModuleApp#clean}. */
        public String icon;
        public java.util.List<ModuleApp> apps;
        public String[] requiredMods = new String[0];
        public String[] requiredCapabilities = new String[0];
    }
}
