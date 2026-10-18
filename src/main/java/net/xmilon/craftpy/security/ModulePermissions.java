package net.xmilon.craftpy.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a project or module asks for, written in its project.json / module.json as
 * {@code "permissions": {"net": ["api.github.com", "wikipedia.org"], "files": true}}. Asking is not having: the player
 * or server owner approves it (see {@link PermissionStore}), and the access config still decides which sites exist at all.
 */
public final class ModulePermissions {
    /** Sites it wants to reach; an entry covers its subdomains. */
    public List<String> net = new ArrayList<>();
    /** Wants {@code files.*} (its own data folder). */
    public boolean files = false;
    /** Folders of {@code pyforge/shared/} it wants to read ({@code "music"}). */
    public List<String> shared = new ArrayList<>();
    /** Wants {@code server.command} (runs at permission level 2, like a command block). */
    public boolean commands = false;
    /** Wants the Java bridge: full trust, it can do anything the game can. */
    public boolean java = false;

    public ModulePermissions() {
    }

    public ModulePermissions(List<String> net, boolean files) {
        this.net = new ArrayList<>(net);
        this.files = files;
    }

    /** Cleaned copy: valid lowercase domains only, no duplicates, at most 20; null reads as empty. */
    public static ModulePermissions clean(ModulePermissions raw) {
        ModulePermissions out = new ModulePermissions();
        if (raw == null) {
            return out;
        }
        Set<String> domains = new LinkedHashSet<>();
        if (raw.net != null) {
            for (String site : raw.net) {
                String domain = NetPolicy.normalizeDomain(site);
                if (domain != null && domains.size() < 20) {
                    domains.add(domain);
                }
            }
        }
        out.net = new ArrayList<>(domains);
        out.files = raw.files;
        Set<String> folders = new LinkedHashSet<>();
        if (raw.shared != null) {
            for (String folder : raw.shared) {
                if (folder != null && folder.matches("[a-z0-9_-]{1,32}") && folders.size() < 10) {
                    folders.add(folder);
                }
            }
        }
        out.shared = new ArrayList<>(folders);
        out.commands = raw.commands;
        out.java = raw.java;
        return out;
    }

    public boolean isEmpty() {
        return (net == null || net.isEmpty()) && !files && (shared == null || shared.isEmpty()) && !commands && !java;
    }

    /** The entry of this list that covers {@code host}, or null. */
    public String covering(String host) {
        if (net != null) {
            for (String entry : net) {
                if (NetPolicy.covers(entry, host)) {
                    return entry;
                }
            }
        }
        return null;
    }
}
