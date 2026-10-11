package net.xmilon.craftpy.project;

import java.nio.file.Path;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Resolves the on-disk layout inside the game instance:
 *
 * <pre>
 * &lt;instance&gt;/pyforge/
 *   runtime/   bundled/downloaded Python
 *   logs/      per-project + global logs
 *   projects/  &lt;project_id&gt;/...
 *   exports/   .pyforge zip exports
 *   imports/   drop zips here
 * </pre>
 */
public final class PyForgePaths {
    private PyForgePaths() {
    }

    public static Path root() {
        return FMLPaths.GAMEDIR.get().resolve("pyforge");
    }

    public static Path runtime() {
        return root().resolve("runtime");
    }

    public static Path logs() {
        return root().resolve("logs");
    }

    public static Path projects() {
        return root().resolve("projects");
    }

    public static Path exports() {
        return root().resolve("exports");
    }

    public static Path imports() {
        return root().resolve("imports");
    }

    public static void ensureLayout() {
        for (Path path : new Path[] { root(), runtime(), logs(), projects(), exports(), imports() }) {
            try {
                java.nio.file.Files.createDirectories(path);
            } catch (java.io.IOException e) {
                net.xmilon.craftpy.CraftPy.LOGGER.error("Failed to create {}", path, e);
            }
        }
    }
}
