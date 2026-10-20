package net.xmilon.craftpy.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.util.Json;

/**
 * Who asked for what ({@link #declare}, set when a project or module is loaded) and what was approved
 * ({@link #approve}, kept in {@code pyforge/system/permissions.json}). An owner is a project id or {@code module:<id>}.
 * Nothing is allowed unless it is both declared and approved, so changing a module's declaration (a new version asking
 * for more) never inherits the old approval for the new part.
 */
public final class PermissionStore {
    private static final Map<String, ModulePermissions> DECLARED = new HashMap<>();
    private static final Map<String, ModulePermissions> OWN = new HashMap<>();
    /** SHA-256 of the module file that is installed now, by owner. */
    private static final Map<String, String> CURRENT_HASH = new HashMap<>();
    private static Map<String, ModulePermissions> approved;
    /** SHA-256 of the module file each approval was given to ({@code pyforge/system/permission_hashes.json}). */
    private static Map<String, String> approvedHashes;

    private PermissionStore() {
    }

    private static Path file() {
        return PyForgePaths.system().resolve("permissions.json");
    }

    private static Path hashFile() {
        return PyForgePaths.system().resolve("permission_hashes.json");
    }

    // -- what an owner asks for -----------------------------------------------------------------------------------------

    public static synchronized void declare(String owner, ModulePermissions asked) {
        DECLARED.put(owner, ModulePermissions.clean(asked));
    }

    /**
     * As {@link #declare(String, ModulePermissions)}, for the module file with this SHA-256. An approval holds only for
     * the file it was given to: a module changed since (a new version, or someone edited the .cpm) asks again.
     */
    public static synchronized void declare(String owner, ModulePermissions asked, String sha256) {
        declare(owner, asked);
        if (sha256 != null) {
            CURRENT_HASH.put(owner, sha256);
        }
        if (adminApproves) {
            // a dedicated server: whoever put the module in pyforge/modules/ and enabled it approved it; players are
            // told what it can do when they join (ServerModules)
            OWN.put(owner, ModulePermissions.clean(asked));
        }
    }

    /** True on a dedicated server: installing a module is its approval. Tests set it. */
    static boolean adminApproves = net.neoforged.fml.loading.FMLEnvironment.dist != null
            && net.neoforged.fml.loading.FMLEnvironment.dist.isDedicatedServer();

    /** True when the owner was approved, but for another version of its module file than the one installed now. */
    public static synchronized boolean changedSinceApproval(String owner) {
        String current = CURRENT_HASH.get(owner);
        return current != null && approvals().containsKey(owner) && !current.equals(hashes().get(owner));
    }

    /**
     * A project of this computer: the person who writes its project.json is the person who runs it, so what it declares
     * is approved. Modules (code of other people) always go through {@link #approve}.
     */
    public static synchronized void declareOwn(String projectId, ModulePermissions asked) {
        ModulePermissions clean = ModulePermissions.clean(asked);
        DECLARED.put(projectId, clean);
        OWN.put(projectId, clean);
    }

    /**
     * What a project asks for. A project made on this computer (New project) is trusted and gets what it declares; one
     * that came from elsewhere (a module opened as a project, a folder copied in) is code of other people and needs
     * {@link #trust} or {@link #approve} first, like a module.
     */
    public static synchronized void declareProject(String projectId, ModulePermissions asked) {
        if (isTrusted(projectId)) {
            declareOwn(projectId, asked);
        } else {
            OWN.remove(projectId);
            declare(projectId, asked);
        }
    }

    // -- projects of this computer ----------------------------------------------------------------------------------------

    private static java.util.Set<String> trusted;

    private static Path trustFile() {
        return PyForgePaths.system().resolve("trusted_projects.json");
    }

    private static java.util.Set<String> trustedProjects() {
        if (trusted == null) {
            trusted = new java.util.TreeSet<>();
            try {
                if (Files.isRegularFile(trustFile())) {
                    String[] ids = Json.COMPACT.fromJson(Files.readString(trustFile(), StandardCharsets.UTF_8), String[].class);
                    if (ids != null) {
                        trusted.addAll(java.util.Arrays.asList(ids));
                    }
                } else if (Files.isDirectory(PyForgePaths.projects())) {
                    // first start with this list: the projects already here were trusted before, they stay trusted
                    try (var dirs = Files.list(PyForgePaths.projects())) {
                        dirs.filter(Files::isDirectory).forEach(dir -> trusted.add(dir.getFileName().toString()));
                    }
                    saveTrusted();
                }
            } catch (IOException | RuntimeException broken) {
                CraftPyLog.LOGGER.warn("trusted_projects.json could not be read; projects ask for approval: {}", broken.toString());
            }
        }
        return trusted;
    }

    public static synchronized boolean isTrusted(String projectId) {
        return trustedProjects().contains(projectId);
    }

    /** Marks a project as made on this computer (New project, or an op trusting it): it gets what it declares. */
    public static synchronized void trust(String projectId) {
        trustedProjects().add(projectId);
        saveTrusted();
    }

    /** A project made from code of other people: it needs approval before it gets anything. */
    public static synchronized void distrust(String projectId) {
        trustedProjects().remove(projectId);
        OWN.remove(projectId);
        saveTrusted();
    }

    private static void saveTrusted() {
        try {
            Files.createDirectories(trustFile().getParent());
            Files.writeString(trustFile(), Json.GSON.toJson(trusted), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("trusted_projects.json could not be saved: {}", e.toString());
        }
    }

    public static synchronized ModulePermissions declared(String owner) {
        return DECLARED.getOrDefault(owner, new ModulePermissions());
    }

    // -- what was approved ------------------------------------------------------------------------------------------------

    private static Map<String, ModulePermissions> approvals() {
        if (approved == null) {
            approved = new TreeMap<>();
            try {
                if (Files.isRegularFile(file())) {
                    var all = Json.COMPACT.fromJson(Files.readString(file(), StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
                    if (all != null) {
                        for (var entry : all.entrySet()) {
                            approved.put(entry.getKey(), ModulePermissions.clean(
                                    Json.COMPACT.fromJson(entry.getValue(), ModulePermissions.class)));
                        }
                    }
                }
            } catch (IOException | RuntimeException broken) {
                CraftPyLog.LOGGER.warn("permissions.json could not be read; nothing is approved: {}", broken.toString());
            }
        }
        return approved;
    }

    private static Map<String, String> hashes() {
        if (approvedHashes == null) {
            approvedHashes = new TreeMap<>();
            try {
                if (Files.isRegularFile(hashFile())) {
                    var all = Json.COMPACT.fromJson(Files.readString(hashFile(), StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
                    if (all != null) {
                        all.entrySet().forEach(e -> approvedHashes.put(e.getKey(), e.getValue().getAsString()));
                    }
                }
            } catch (IOException | RuntimeException broken) {
                CraftPyLog.LOGGER.warn("permission_hashes.json could not be read; modules ask again: {}", broken.toString());
            }
        }
        return approvedHashes;
    }

    public static synchronized ModulePermissions approved(String owner) {
        ModulePermissions own = OWN.get(owner);
        if (own != null) {
            return own;
        }
        if (changedSinceApproval(owner)) {
            return new ModulePermissions(); // given to another version of the module: nothing until approved again
        }
        return approvals().getOrDefault(owner, new ModulePermissions());
    }

    /**
     * Approves exactly {@code grant}; what it asks beyond the declaration has no effect. The approval is tied to the
     * module file installed now.
     */
    public static synchronized void approve(String owner, ModulePermissions grant) {
        approvals().put(owner, ModulePermissions.clean(grant));
        String current = CURRENT_HASH.get(owner);
        if (current != null) {
            hashes().put(owner, current);
        } else {
            hashes().remove(owner);
        }
        save();
    }

    public static synchronized void revoke(String owner) {
        approvals().remove(owner);
        hashes().remove(owner);
        save();
    }

    /** True when everything the owner declares is approved. */
    public static synchronized boolean fullyApproved(String owner) {
        ModulePermissions asked = declared(owner);
        ModulePermissions ok = approved(owner);
        return (!asked.files || ok.files) && ok.net.containsAll(asked.net) && ok.shared.containsAll(asked.shared)
                && (!asked.commands || ok.commands) && (!asked.java || ok.java);
    }

    /** True when the owner declared {@code commands} and that was approved. */
    public static synchronized boolean commandsAllowed(String owner) {
        return declared(owner).commands && approved(owner).commands;
    }

    /** True when the owner declared the Java bridge and that was approved. */
    public static synchronized boolean javaAllowed(String owner) {
        return declared(owner).java && approved(owner).java;
    }

    /** The declared entry that covers {@code host} and is approved, or null: the owner may use this host. */
    public static synchronized String netEntry(String owner, String host) {
        String entry = declared(owner).covering(host);
        return entry != null && approved(owner).net.contains(entry) ? entry : null;
    }

    /** True when the owner declared {@code pyforge/shared/<folder>} and that was approved. */
    public static synchronized boolean sharedAllowed(String owner, String folder) {
        return declared(owner).shared.contains(folder) && approved(owner).shared.contains(folder);
    }

    public static synchronized boolean filesAllowed(String owner) {
        return declared(owner).files && approved(owner).files;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), Json.GSON.toJson(approved), StandardCharsets.UTF_8);
            Files.writeString(hashFile(), Json.GSON.toJson(hashes()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("permissions.json could not be saved: {}", e.toString());
        }
    }

    /** For tests: forget everything held in memory so the next call reads the file again. */
    public static synchronized void forget() {
        DECLARED.clear();
        OWN.clear();
        CURRENT_HASH.clear();
        approved = null;
        approvedHashes = null;
        trusted = null;
    }
}
