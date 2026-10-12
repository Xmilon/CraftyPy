package net.xmilon.craftpy.project;

import net.xmilon.craftpy.CraftPy;
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

    public static void build(String projectId) throws IOException {
        Path projectDir = PyForgePaths.projects().resolve(projectId);
        if (!Files.isDirectory(projectDir)) {
            throw new NoSuchFileException("Project directory not found: " + projectId);
        }

        Path exportDir = PyForgePaths.exports();
        Files.createDirectories(exportDir);
        Path outputFile = exportDir.resolve(projectId + ".cpm");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputFile.toFile()))) {
            // 1. module.json (derived from project.json)
            Path projectJson = projectDir.resolve("project.json");
            if (Files.exists(projectJson)) {
                ProjectMetadata meta = Json.COMPACT.fromJson(Files.readString(projectJson), ProjectMetadata.class);
                ModuleMetadata modMeta = new ModuleMetadata();
                modMeta.id = meta.id;
                modMeta.name = meta.name;
                modMeta.version = "1.0.0";
                
                zos.putNextEntry(new ZipEntry("module.json"));
                zos.write(Json.GSON.toJson(modMeta).getBytes());
                zos.closeEntry();
            }

            // 2. code/, assets/, data/
            for (String subDir : new String[]{"code", "assets", "data"}) {
                Path dir = projectDir.resolve(subDir);
                if (Files.isDirectory(dir)) {
                    zipDirectory(dir, subDir, zos);
                }
            }
        }
        CraftPy.LOGGER.info("Exported module: {}", outputFile);
    }

    private static void zipDirectory(Path folder, String parentFolder, ZipOutputStream zos) throws IOException {
        try (var stream = Files.walk(folder)) {
            stream.filter(path -> !Files.isDirectory(path)).forEach(path -> {
                ZipEntry zipEntry = new ZipEntry(parentFolder + "/" + folder.relativize(path).toString().replace("\\", "/"));
                try {
                    zos.putNextEntry(zipEntry);
                    Files.copy(path, zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    public static class ModuleMetadata {
        public String id;
        public String name;
        public String version;
        public String[] dependencies = new String[0];
    }
}
