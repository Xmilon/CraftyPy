package net.xmilon.craftpy.installer;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * What the installer found on this PC.
 */
public record Env(String os, Path minecraftDir, int javaMajor, boolean neoforgeInstalled, Set<String> modFiles) {

    public static String osName(String property) {
        String name = property.toLowerCase(Locale.ROOT);
        return name.contains("win") ? "windows" : name.contains("mac") || name.contains("darwin") ? "macos" : "linux";
    }

    public static Path defaultMinecraftDir(String os, String home, String appData) {
        return switch (os) {
            case "windows" -> Path.of(appData == null || appData.isEmpty() ? home + "\\AppData\\Roaming" : appData, ".minecraft");
            case "macos" -> Path.of(home, "Library", "Application Support", "minecraft");
            default -> Path.of(home, ".minecraft");
        };
    }
}
