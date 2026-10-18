package net.xmilon.craftpy.security;

import java.util.ArrayList;
import java.util.List;

/**
 * The warning every module with permissions carries, on the Modules page, in its README and when it is switched on:
 * which sites it is trying to reach, which of them are in the danger zone, and that going on is the player's own risk.
 */
public final class PermissionLabels {
    private PermissionLabels() {
    }

    /** One line; empty when the module asks for nothing. */
    public static String warning(ModulePermissions asked, List<String> general, List<String> danger) {
        if (asked == null || asked.isEmpty()) {
            return "";
        }
        List<String> dangerous = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        for (String site : asked.net) {
            switch (NetPolicy.zone(site, general, danger)) {
                case DANGER -> dangerous.add(site);
                case NONE -> blocked.add(site);
                default -> {
                }
            }
        }
        List<String> parts = new ArrayList<>();
        if (asked.java) {
            parts.add("use the Java bridge (FULL TRUST: it can do anything the game can, files and other modules included)");
        }
        if (asked.commands) {
            parts.add("run server commands (like a command block)");
        }
        if (!asked.net.isEmpty()) {
            StringBuilder sites = new StringBuilder("access ").append(asked.net.size() == 1 ? "this site: " : "these sites: ")
                    .append(String.join(", ", asked.net));
            if (!dangerous.isEmpty()) {
                sites.append(" (DANGER ZONE: ").append(String.join(", ", dangerous)).append(")");
            }
            if (!blocked.isEmpty()) {
                sites.append(" (the server does not allow: ").append(String.join(", ", blocked)).append(")");
            }
            parts.add(sites.toString());
        }
        if (asked.files) {
            parts.add("keep a data folder on this computer (files it can read and write)");
        }
        if (!asked.shared.isEmpty()) {
            parts.add("read pyforge/shared/" + String.join(", pyforge/shared/", asked.shared));
        }
        return "⚠ This module wants to " + String.join("; ", parts) + " — continue at your own risk!";
    }

    public static String warning(ModulePermissions asked) {
        return warning(asked, AccessConfig.strings(AccessConfig.ALLOWED_DOMAINS), AccessConfig.strings(AccessConfig.DANGER_DOMAINS));
    }

    /** Short names of what the owner asks for ("web: a.com", "files", "commands", "Java: FULL TRUST"), for chips and lists. */
    public static List<String> chips(ModulePermissions asked) {
        List<String> out = new ArrayList<>();
        if (asked == null) {
            return out;
        }
        if (asked.java) {
            out.add("Java: FULL TRUST");
        }
        if (asked.commands) {
            out.add("commands");
        }
        if (!asked.net.isEmpty()) {
            out.add("web: " + String.join(", ", asked.net));
        }
        if (asked.files) {
            out.add("files");
        }
        for (String folder : asked.shared) {
            out.add("shared/" + folder);
        }
        return out;
    }

    public static String shortList(ModulePermissions asked) {
        return String.join("; ", chips(asked));
    }

    /** The warning as a quote block for the top of a README (Markdown). */
    public static String readmeBlock(ModulePermissions asked) {
        String text = warning(asked);
        return text.isEmpty() ? "" : "> **" + text + "**\n\n";
    }
}
