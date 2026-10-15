package net.xmilon.craftpy.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Resolves the on-disk layout inside the game instance. What you work with sits at the top; everything the mod
 * manages itself lives in {@code system/}:
 *
 * <pre>
 * &lt;instance&gt;/pyforge/
 *   projects/  &lt;project_id&gt;/...   your projects
 *   modules/   installed .cpm modules
 *   imports/   drop .cpm files here, they are moved into modules/
 *   exports/   built .cpm files
 *   system/    runtime/ (Python), logs/, build/ (generated packs), cache/ (unpacked modules),
 *              slots.json, modules.json - safe to delete, never edit
 * </pre>
 */
public final class PyForgePaths {
    private PyForgePaths() {
    }

    public static Path root() {
        String home = System.getProperty("craftpy.home");
        if (home != null && !home.isBlank()) { // the standalone IDE: the game folder it works on
            return Path.of(home).resolve("pyforge");
        }
        Path gameDir = null;
        try {
            gameDir = FMLPaths.GAMEDIR.get();
        } catch (Throwable ignored) {
            // no game directory (unit tests)
        }
        if (gameDir == null) {
            return java.nio.file.Paths.get("run/pyforge");
        }
        return gameDir.resolve("pyforge");
    }

    public static Path projects() {
        return root().resolve("projects");
    }

    public static Path modules() {
        return root().resolve("modules");
    }

    public static Path imports() {
        return root().resolve("imports");
    }

    public static Path exports() {
        return root().resolve("exports");
    }

    /** Everything the mod manages itself. */
    public static Path system() {
        return root().resolve("system");
    }

    public static Path runtime() {
        return system().resolve("runtime");
    }

    public static Path logs() {
        return system().resolve("logs");
    }

    /** Generated resource and data packs. */
    public static Path build() {
        return system().resolve("build");
    }

    /** Unpacked modules the Python worker runs from. */
    public static Path cache() {
        return system().resolve("cache");
    }

    public static Path slotsFile() {
        return system().resolve("slots.json");
    }

    public static Path moduleStateFile() {
        return system().resolve("modules.json");
    }

    public static void ensureLayout() {
        for (Path path : new Path[]{root(), projects(), modules(), imports(), exports(), system(), runtime(), logs()}) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                net.xmilon.craftpy.CraftPyLog.LOGGER.error("Failed to create {}", path, e);
            }
        }
        migrate();
    }

    /** Moves what older versions kept at the top level into system/ (once; nothing is overwritten). */
    private static void migrate() {
        String[][] moves = {{"slots.json", "slots.json"}, {"modules.json", "modules.json"}, {"runtime", "runtime"},
                {"logs", "logs"}, {"build", "build"}, {"cache", "cache"}};
        for (String[] move : moves) {
            Path from = root().resolve(move[0]);
            Path to = system().resolve(move[1]);
            if (!Files.exists(from) || (Files.isDirectory(to) && isEmpty(to) == false) || (Files.isRegularFile(to))) {
                continue;
            }
            try {
                if (Files.isDirectory(to)) {
                    Files.delete(to); // the freshly created empty folder makes way for the old one
                }
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                net.xmilon.craftpy.CraftPyLog.LOGGER.warn("Could not move {} into system/: {}", from, e.toString());
            }
        }
    }

    private static boolean isEmpty(Path folder) {
        try (var children = Files.list(folder)) {
            return children.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }
}
