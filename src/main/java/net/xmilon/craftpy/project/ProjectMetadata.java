package net.xmilon.craftpy.project;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contents of a project's project.json. Field names intentionally mirror the
 * on-disk format so users can edit the file by hand.
 */
public class ProjectMetadata {
    public String id = "";
    public String name = "";
    public String version = "1.0.0";
    public String author = "";
    public String description = "";
    public String mcVersion = "1.21.1";
    /** The Python API version the project was written for; new projects get the current one. */
    public String apiVersion = net.xmilon.craftpy.python.PyApi.version();
    public boolean requiresRestart = false;
    /** {@code both} (default) or {@code client}: a client project runs on the player's computer, not in the world. */
    public String side = "both";
    /** Sites and the data folder the project asks for: {"net": ["wikipedia.org"], "files": true}. */
    public net.xmilon.craftpy.security.ModulePermissions permissions = new net.xmilon.craftpy.security.ModulePermissions();
    /** Installed modules this project uses ({@code from modules.<id> import ...}); a built module lists them too. */
    public java.util.List<String> dependencies = new java.util.ArrayList<>();
    /** Picture of the project in the Hub: an item id or a PNG under assets/ ({@link ModuleApp#icon}). */
    public String icon = "";
    /** Entries this project adds to the Hub; each opens one of its windows. */
    public java.util.List<ModuleApp> apps = new java.util.ArrayList<>();
    public java.util.List<String> requiredMods = new java.util.ArrayList<>();
    public java.util.List<String> requiredCapabilities = new java.util.ArrayList<>();
    public Map<String, Integer> slotUsage = new LinkedHashMap<>();
    /** Set when the project was decompiled from a module: Build module then replaces that module. Null otherwise. */
    public DecompiledFrom decompiledFrom;

    /** The module a decompiled project came from, and the SHA-256 of its .cpm at that moment. */
    public static class DecompiledFrom {
        public String module = "";
        public String sha256 = "";

        public DecompiledFrom() {
        }

        public DecompiledFrom(String module, String sha256) {
            this.module = module;
            this.sha256 = sha256;
        }
    }

    public ProjectMetadata() {
    }

    public ProjectMetadata(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
