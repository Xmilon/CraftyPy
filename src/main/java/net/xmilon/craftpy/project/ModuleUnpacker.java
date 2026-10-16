package net.xmilon.craftpy.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.xmilon.craftpy.util.Json;

/**
 * The module debuilder: a {@code .cpm} back into a project that opens in the IDE. The module is untrusted input, so
 * this never overwrites anything (the project gets a free id), unpacks through {@link ModuleArchive} (paths, sizes),
 * keeps only the file types a project is made of, writes project.json itself from cleaned values, and runs nothing:
 * the code only runs when the user presses Run, in the same sandbox as always.
 */
public final class ModuleUnpacker {
    /** What a CraftPy project is made of. Programs, scripts and function files of a module stay out. */
    static final Set<String> TYPES = Set.of("py", "json", "mcmeta", "png", "ogg", "md", "txt", "fsh", "vsh", "glsl");
    private static final int MAX_META = 64 * 1024;

    public record Result(String projectId, String moduleId, ModuleArchive.Report report) {
    }

    private ModuleUnpacker() {
    }

    public static Result unpack(Path cpm, Path projectsDir, Path workDir) throws IOException {
        try (ZipFile zip = new ZipFile(cpm.toFile())) {
            ModuleBuilder.ModuleMetadata meta = readMeta(zip);
            String moduleId = ProjectManager.isValidId(meta.id) ? meta.id : null;
            String id = freeId(projectsDir, moduleId != null ? moduleId : fallbackId(cpm));
            Files.createDirectories(workDir);
            Path temp = Files.createTempDirectory(workDir, "unpack-");
            try {
                ModuleArchive.Report extracted = ModuleArchive.extract(zip, temp,
                        name -> !name.equals("module.json") && TYPES.contains(extension(name)));
                // module.json becomes project.json (written below), so it is not "left out"
                ModuleArchive.Report report = new ModuleArchive.Report(extracted.written(), extracted.skipped().stream()
                        .filter(line -> !line.startsWith("module.json ")).toList(), extracted.bytes());
                ProjectMetadata project = new ProjectMetadata(id, clean(meta.name, id, 64));
                project.version = clean(meta.version, "1.0.0", 32);
                project.author = clean(meta.author, "", 64);
                project.description = clean(meta.description, "", 500);
                if (meta.apiVersion != null && meta.apiVersion.matches("\\d{1,3}\\.\\d{1,3}")) {
                    project.apiVersion = meta.apiVersion;
                }
                Json.write(temp.resolve("project.json"), project);
                Files.move(temp, projectsDir.resolve(id)); // fails (and nothing is lost) if the id was taken meanwhile
                return new Result(id, moduleId, report);
            } catch (IOException | RuntimeException e) {
                ProjectManager.deleteRecursively(temp);
                throw e;
            }
        }
    }

    private static ModuleBuilder.ModuleMetadata readMeta(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("module.json");
        if (entry == null) {
            throw new IOException("Not a CraftPy module: module.json is missing");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(MAX_META + 1);
            if (bytes.length > MAX_META) {
                throw new IOException("module.json is too large");
            }
            ModuleBuilder.ModuleMetadata meta = Json.COMPACT.fromJson(new String(bytes, StandardCharsets.UTF_8),
                    ModuleBuilder.ModuleMetadata.class);
            return meta == null ? new ModuleBuilder.ModuleMetadata() : meta;
        } catch (RuntimeException broken) {
            throw new IOException("module.json cannot be read: " + broken.getMessage());
        }
    }

    /** {@code id}, else {@code id_2}, {@code id_3}...: an existing project is never touched. */
    static String freeId(Path projectsDir, String id) throws IOException {
        String base = id.length() > 60 ? id.substring(0, 60) : id;
        for (int n = 1; n < 1000; n++) {
            String candidate = n == 1 ? base : base + "_" + n;
            if (ProjectManager.isValidId(candidate) && !Files.exists(projectsDir.resolve(candidate))) {
                return candidate;
            }
        }
        throw new IOException("No free project id for " + id);
    }

    static String fallbackId(Path cpm) {
        String name = cpm.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("\\.cpm$", "")
                .replaceAll("[^a-z0-9_]", "_").replaceAll("^[^a-z]+", "");
        return ProjectManager.isValidId(name) ? name : "module";
    }

    /** Control characters out, at most {@code max} characters. */
    static String clean(String value, String fallback, int max) {
        if (value == null) {
            return fallback;
        }
        String text = value.replaceAll("\\p{Cntrl}", " ").strip();
        if (text.isEmpty()) {
            return fallback;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String extension(String name) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        return dot > slash ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
