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
        StringBuilder text = new StringBuilder("⚠ ");
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
        if (!asked.net.isEmpty()) {
            text.append("This module is trying to access ").append(asked.net.size() == 1 ? "this site: " : "these sites: ")
                    .append(String.join(", ", asked.net));
            if (!dangerous.isEmpty()) {
                text.append(" (DANGER ZONE: ").append(String.join(", ", dangerous)).append(")");
            }
            if (!blocked.isEmpty()) {
                text.append(" (the server does not allow: ").append(String.join(", ", blocked)).append(")");
            }
            text.append(asked.files ? "; it also wants a data folder on this computer" : "");
        } else {
            text.append("This module wants a data folder on this computer (files it can read and write)");
        }
        return text.append(" — continue at your own risk!").toString();
    }

    public static String warning(ModulePermissions asked) {
        return warning(asked, AccessConfig.strings(AccessConfig.ALLOWED_DOMAINS), AccessConfig.strings(AccessConfig.DANGER_DOMAINS));
    }

    /** The warning as a quote block for the top of a README (Markdown). */
    public static String readmeBlock(ModulePermissions asked) {
        String text = warning(asked);
        return text.isEmpty() ? "" : "> **" + text + "**\n\n";
    }
}
