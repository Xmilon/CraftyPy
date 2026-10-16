package net.xmilon.craftpy.installer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The pinned versions, download URLs and hashes the installer works from ({@code requirements.properties}). */
public record Requirements(String minecraft, int javaMin, String neoforgeVersion, String neoforgeUrl, String neoforgeSha256,
                           String modernUiFile, String modernUiUrl, String modernUiSha256) {

    public static Requirements load() {
        try (InputStream in = Requirements.class.getResourceAsStream("/requirements.properties")) {
            if (in == null) {
                throw new IllegalStateException("requirements.properties is missing from the installer");
            }
            Properties p = new Properties();
            p.load(in);
            return from(p);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read requirements.properties", e);
        }
    }

    static Requirements from(Properties p) {
        return new Requirements(get(p, "minecraft"), Integer.parseInt(get(p, "java.min")), get(p, "neoforge.version"),
                get(p, "neoforge.url"), get(p, "neoforge.sha256"), get(p, "modernui.file"), get(p, "modernui.url"),
                get(p, "modernui.sha256"));
    }

    private static String get(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("requirements.properties has no '" + key + "'");
        }
        return value.trim();
    }
}
