package net.xmilon.craftpy.project;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.util.Json;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.*;
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
     * @param export    the .cpm written to {@code exports/}
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
        String moduleId = asNew ? request.newId() : meta.id;
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

        Path exportDir = PyForgePaths.exports();
        Files.createDirectories(exportDir);
        Path outputFile = exportDir.resolve(moduleId + "-" + version + ".cpm");
        Path temp = exportDir.resolve(moduleId + "-" + version + ".cpm.tmp");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(temp.toFile()))) {
            ModuleMetadata modMeta = new ModuleMetadata();
            modMeta.id = moduleId;
            modMeta.name = displayName;
            modMeta.author = meta.author;
            modMeta.description = meta.description;
            modMeta.version = version;
            modMeta.apiVersion = meta.apiVersion;
            modMeta.side = request.side().id();
            zos.putNextEntry(new ZipEntry("module.json"));
            zos.write(Json.GSON.toJson(modMeta).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // a new module is another namespace: assets/<old id>/ and "<old id>:name" ids move with it
            String from = asNew && !moduleId.equals(meta.id) ? meta.id : null;
            for (String subDir : new String[]{"code", "assets", "data", "ui"}) {
                Path dir = projectDir.resolve(subDir);
                if (Files.isDirectory(dir) && request.side().packs(subDir)) { // a client module is only its assets
                    zipDirectory(dir, subDir, zos, from, moduleId);
                }
            }
            Path readme = projectDir.resolve("README.md");
            if (Files.isRegularFile(readme)) {
                zos.putNextEntry(new ZipEntry("README.md"));
                zos.write(Files.readAllBytes(readme));
                zos.closeEntry();
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
        Files.move(temp, outputFile, StandardCopyOption.REPLACE_EXISTING);

        Path installed = null;
        boolean replaced = false;
        if (request.install()) {
            installed = existing != null ? existing : PyForgePaths.modules().resolve(moduleId + ".cpm");
            replaced = existing != null;
            Files.createDirectories(installed.getParent());
            Path staging = installed.resolveSibling(installed.getFileName() + ".tmp");
            Files.copy(outputFile, staging, StandardCopyOption.REPLACE_EXISTING);
            Files.move(staging, installed, StandardCopyOption.REPLACE_EXISTING);
        }
        CraftPyLog.LOGGER.info("Exported module {} {} to {}{}", moduleId, version, outputFile,
                installed == null ? "" : (replaced ? ", replaced " : ", installed as ") + installed);
        return new Result(outputFile, installed, moduleId, version, replaced);
    }

    private static void zipDirectory(Path folder, String parentFolder, ZipOutputStream zos, String from, String to)
            throws IOException {
        try (var stream = Files.walk(folder)) {
            stream.filter(path -> !Files.isDirectory(path)).forEach(path -> {
                String name = entryName(parentFolder, folder.relativize(path).toString().replace("\\", "/"), from, to);
                try {
                    zos.putNextEntry(new ZipEntry(name));
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
        public String[] dependencies = new String[0];
    }
}
