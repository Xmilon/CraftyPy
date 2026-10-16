package net.xmilon.craftpy.dynamics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.project.ModuleInfo;
import net.xmilon.craftpy.project.ModuleLoader;
import net.xmilon.craftpy.project.ModuleSide;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.project.PyForgePaths;

/**
 * The resource pack of the client-side modules ({@code "side": "client"}): their {@code assets/} unpacked from the
 * {@code .cpm} files enabled on this computer. Built by the player's own CraftPy, so it works on any server, even one
 * without CraftPy. {@link DynamicPackManager} offers it to the game; it is rebuilt when the modules change.
 */
public final class ClientModulePack {
    public static final String PACK_ID = "craftpy:client_modules";
    private static final String MARKER = ".modules";

    private ClientModulePack() {
    }

    public static Path root() {
        return PyForgePaths.build().resolve("clientpack").resolve("craftpy-client-modules");
    }

    /** Rebuilds the pack when the enabled client modules changed; true when it did (a resource reload then shows it). */
    public static synchronized boolean refresh() {
        Path root = root();
        try {
            List<ModuleInfo> modules = new ArrayList<>();
            for (ModuleInfo info : ModuleLoader.list()) {
                if (info.enabled && info.primary && info.error == null && ModuleSide.of(info.side) == ModuleSide.CLIENT) {
                    modules.add(info);
                }
            }
            modules.sort(Comparator.comparing(m -> m.id));
            StringBuilder fingerprint = new StringBuilder();
            for (ModuleInfo m : modules) {
                fingerprint.append(m.id).append('|').append(m.path).append('|').append(m.modified).append('|')
                        .append(m.sizeBytes).append('\n');
            }
            Path marker = root.resolve(MARKER);
            if (modules.isEmpty() ? !Files.exists(root)
                    : Files.isRegularFile(marker) && Files.readString(marker).contentEquals(fingerprint)) {
                return false;
            }
            ProjectManager.deleteRecursively(root);
            if (!modules.isEmpty()) {
                build(root, modules);
                Files.writeString(marker, fingerprint);
                CraftPyLog.LOGGER.info("Client modules pack rebuilt: {}", modules.stream().map(m -> m.id).toList());
            }
            return true;
        } catch (IOException | RuntimeException e) {
            CraftPyLog.LOGGER.error("Could not build the client modules pack", e);
            return false;
        }
    }

    /** Writes pack.mcmeta and the assets of {@code modules} (later ids win on the same file). */
    static void build(Path root, List<ModuleInfo> modules) throws IOException {
        try {
            PackBuilder.writeMcmeta(root, PackBuilder.RESOURCE_FORMAT, "CraftPy client modules");
        } catch (Exception e) {
            throw new IOException(e);
        }
        Path top = root.toAbsolutePath().normalize();
        for (ModuleInfo module : modules) {
            try (ZipFile zip = new ZipFile(module.path)) {
                for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                    Path out = target(top, module.id, entry.getName());
                    if (entry.isDirectory() || out == null) {
                        continue;
                    }
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /**
     * Where a zip entry of a module goes: {@code assets/textures/x.png} into the module's namespace,
     * {@code assets/minecraft/...} into that namespace (the same rule as projects); null for anything else or a path that
     * would leave the pack.
     */
    static Path target(Path root, String moduleId, String entryName) {
        String name = entryName.replace('\\', '/');
        if (!name.startsWith("assets/")) {
            return null;
        }
        String rest = name.substring("assets/".length());
        int slash = rest.indexOf('/');
        boolean folder = slash >= 0;
        String first = folder ? rest.substring(0, slash) : rest;
        String namespace = PackBuilder.assetNamespace(moduleId, first, folder);
        if (namespace == null || rest.isEmpty()) {
            return null;
        }
        String inside = !folder || PackBuilder.isResourceFolder(first) ? rest : rest.substring(slash + 1);
        if (inside.isEmpty()) {
            return null;
        }
        Path out = root.resolve("assets").resolve(namespace).resolve(inside).normalize();
        return out.startsWith(root.resolve("assets")) ? out : null;
    }
}
