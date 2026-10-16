package net.xmilon.craftpy.project;

import java.util.Locale;

/**
 * Where a module does its work ({@code "side"} in module.json).
 * <ul>
 * <li>{@code both}: code and data run on the server, its assets go to the players (the default, what every module was).</li>
 * <li>{@code client}: resources (textures, models, sounds, translations) applied by each player's own CraftPy as a
 * resource pack on any server, even one without CraftPy, and Python that runs on the player's computer
 * (HUD, keys, windows; see {@code ClientPython}). Nothing of it runs on the server.</li>
 * <li>{@code server}: only behaviour (code, recipes, loot, tags, commands); players need nothing installed.</li>
 * </ul>
 */
public enum ModuleSide {
    BOTH, CLIENT, SERVER;

    /** Unknown or missing values are {@code both}, so modules built before sides keep working as they did. */
    public static ModuleSide of(String raw) {
        if (raw == null) {
            return BOTH;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "client" -> CLIENT;
            case "server" -> SERVER;
            default -> BOTH;
        };
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Its code and data run on the server. */
    public boolean runsOnServer() {
        return this != CLIENT;
    }

    /** It has resources for the players. */
    public boolean hasAssets() {
        return this != SERVER;
    }

    /** The folders of a project that go into a module of this side. */
    public boolean packs(String folder) {
        return switch (this) {
            case BOTH -> true;
            case CLIENT -> folder.equals("assets") || folder.equals("code") || folder.equals("ui");
            case SERVER -> !folder.equals("assets");
        };
    }
}
