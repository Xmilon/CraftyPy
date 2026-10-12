package net.xmilon.craftpy.project;

import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads .cpm (CraftPy Module) files into the game.
 * Modules are scanned from pyforge/modules/ and pyforge/imports/.
 */
public final class ModuleLoader {
    private static final Map<String, ModuleBuilder.ModuleMetadata> LOADED_MODULES = new HashMap<>();
    private static final Map<String, Path> MODULE_FILES = new HashMap<>();

    public static void discoverAndLoad() {
        Path modulesDir = PyForgePaths.modules();
        Path importsDir = PyForgePaths.imports();
        
        List<Path> files = new ArrayList<>();
        scan(modulesDir, files);
        scan(importsDir, files);
        
        for (Path file : files) {
            try {
                loadModule(file);
            } catch (Exception e) {
                CraftPy.LOGGER.error("Failed to load module {}", file, e);
            }
        }
    }

    /** Re-loads a single module from its original .cpm file (drop + re-import). */
    public static boolean reload(String id) throws IOException {
        Path file = MODULE_FILES.get(id);
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        LOADED_MODULES.remove(id);
        MODULE_FILES.remove(id);
        // Re-extract the code/assets into the cache dir so the worker runs the fresh copy.
        Path cacheDir = PyForgePaths.root().resolve("cache").resolve(id);
        if (Files.isDirectory(cacheDir)) {
            net.xmilon.craftpy.project.ProjectManager.deleteRecursively(cacheDir);
        }
        loadModule(file);
        return true;
    }

    private static void scan(Path dir, List<Path> out) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".cpm")).forEach(out::add);
        } catch (IOException e) {
            CraftPy.LOGGER.error("Error scanning modules in {}", dir, e);
        }
    }

    public static void loadModule(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry metaEntry = zip.getEntry("module.json");
            if (metaEntry == null) {
                throw new IOException("Missing module.json in " + path);
            }
            
            ModuleBuilder.ModuleMetadata meta;
            try (InputStream is = zip.getInputStream(metaEntry)) {
                meta = Json.COMPACT.fromJson(new String(is.readAllBytes()), ModuleBuilder.ModuleMetadata.class);
            }
            
            if (LOADED_MODULES.containsKey(meta.id)) {
                CraftPy.LOGGER.info("Module {} already loaded, skipping.", meta.id);
                return;
            }
            
            // Extract code/assets/data to a persistent cache for the current session
            Path cacheDir = PyForgePaths.root().resolve("cache").resolve(meta.id);
            extractZip(zip, cacheDir);
            
            LOADED_MODULES.put(meta.id, meta);
            MODULE_FILES.put(meta.id, path);
            CraftPy.LOGGER.info("Loaded module: {} ({})", meta.name, meta.id);
            
            // Wire into SlotRegistry as a persistent module
            net.xmilon.craftpy.python.PyRuntime.get().loadModule(meta.id, cacheDir);
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

    public static Collection<ModuleBuilder.ModuleMetadata> loadedModules() {
        return Collections.unmodifiableCollection(LOADED_MODULES.values());
    }
}
