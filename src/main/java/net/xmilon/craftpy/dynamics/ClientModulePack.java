package net.xmilon.craftpy.dynamics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.client.ClientPython;
import net.xmilon.craftpy.client.ClientPython.AppliedModule;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.project.PyForgePaths;

/**
 * The resource pack of the client-side modules ({@code "side": "client"}): their {@code assets/} unpacked from the
 * successfully applied modules cached on this computer. Built by the player's own CraftPy, so it works on any server, even one
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

    /** Rebuild from applied cached assets only; scans, toggles and builds cannot apply desired bytes. */
    public static synchronized boolean refresh() {
        try {
            return refreshChecked();
        } catch (IOException | RuntimeException e) {
            CraftPyLog.LOGGER.error("Could not build the client modules pack", e);
            return false;
        }
    }

    /** Apply callers need failures instead of mistaking them for an unchanged pack. */
    public static synchronized boolean refreshChecked() throws IOException {
        Path root = root();
        List<AppliedModule> modules = new ArrayList<>(ClientPython.get().appliedModules());
        modules.sort(Comparator.comparing(AppliedModule::id));
        StringBuilder fingerprint = new StringBuilder();
        for (AppliedModule module : modules)
            fingerprint.append(module.id()).append('|').append(module.snapshot().resources()).append('\n');
        Path marker = root.resolve(MARKER);
        if (modules.isEmpty() ? !Files.exists(root)
                : Files.isRegularFile(marker) && Files.readString(marker).contentEquals(fingerprint)) return false;
        Files.createDirectories(root.getParent());
        Path staged = Files.createTempDirectory(root.getParent(), "clientpack-");
        Path backup = root.resolveSibling(root.getFileName() + ".previous");
        try {
            if (!modules.isEmpty()) {
                build(staged, modules);
                Files.writeString(staged.resolve(MARKER), fingerprint);
            }
            ProjectManager.deleteRecursively(backup);
            boolean hadPack = Files.exists(root);
            if (hadPack) Files.move(root, backup);
            try {
                if (!modules.isEmpty()) Files.move(staged, root);
            } catch (IOException e) {
                if (hadPack) Files.move(backup, root);
                throw e;
            }
            ProjectManager.deleteRecursively(backup);
            CraftPyLog.LOGGER.info("Client modules pack rebuilt: {}", modules.stream().map(AppliedModule::id).toList());
            return true;
        } finally {
            ProjectManager.deleteRecursively(staged);
        }
    }

    /** Writes cached assets, with later ids winning on the same file. */
    static void build(Path root, List<AppliedModule> modules) throws IOException {
        try {
            PackBuilder.writeMcmeta(root, PackBuilder.RESOURCE_FORMAT, "CraftPy client modules");
        } catch (Exception e) {
            throw new IOException(e);
        }
        Path top = root.toAbsolutePath().normalize();
        for (AppliedModule module : modules) {
            if (!Files.isDirectory(module.assets())) continue;
            try (var files = Files.walk(module.assets())) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String relative = module.assets().relativize(file).toString().replace('\\', '/');
                    Path out = target(top, module.id(), "assets/" + relative);
                    if (out == null) continue;
                    Files.createDirectories(out.getParent());
                    Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
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
