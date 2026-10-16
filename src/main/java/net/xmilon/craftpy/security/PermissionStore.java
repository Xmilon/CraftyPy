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
    private static Map<String, ModulePermissions> approved;

    private PermissionStore() {
    }

    private static Path file() {
        return PyForgePaths.system().resolve("permissions.json");
    }

    // -- what an owner asks for -----------------------------------------------------------------------------------------

    public static synchronized void declare(String owner, ModulePermissions asked) {
        DECLARED.put(owner, ModulePermissions.clean(asked));
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

    public static synchronized ModulePermissions approved(String owner) {
        ModulePermissions own = OWN.get(owner);
        if (own != null) {
            return own;
        }
        return approvals().getOrDefault(owner, new ModulePermissions());
    }

    /** Approves exactly {@code grant}; what it asks beyond the declaration has no effect. */
    public static synchronized void approve(String owner, ModulePermissions grant) {
        approvals().put(owner, ModulePermissions.clean(grant));
        save();
    }

    public static synchronized void revoke(String owner) {
        approvals().remove(owner);
        save();
    }

    /** True when everything the owner declares is approved. */
    public static synchronized boolean fullyApproved(String owner) {
        ModulePermissions asked = declared(owner);
        ModulePermissions ok = approved(owner);
        return (!asked.files || ok.files) && ok.net.containsAll(asked.net);
    }

    /** The declared entry that covers {@code host} and is approved, or null: the owner may use this host. */
    public static synchronized String netEntry(String owner, String host) {
        String entry = declared(owner).covering(host);
        return entry != null && approved(owner).net.contains(entry) ? entry : null;
    }

    public static synchronized boolean filesAllowed(String owner) {
        return declared(owner).files && approved(owner).files;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), Json.GSON.toJson(approved), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CraftPyLog.LOGGER.warn("permissions.json could not be saved: {}", e.toString());
        }
    }

    /** For tests: forget everything held in memory so the next call reads the file again. */
    public static synchronized void forget() {
        DECLARED.clear();
        OWN.clear();
        approved = null;
    }
}
