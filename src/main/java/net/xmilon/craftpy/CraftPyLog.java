package net.xmilon.craftpy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod id and the logger, kept apart from {@link CraftPy} (the NeoForge mod class) so code that also runs in the standalone
 * IDE can log without loading the game.
 */
public final class CraftPyLog {
    public static final String MODID = "craftpy";
    public static final Logger LOGGER = LoggerFactory.getLogger("net.xmilon.craftpy.CraftPy");

    private static String version;

    private CraftPyLog() {
    }

    /** The mod version ("1.1.1"), from craftpy_version.properties written by the build; "dev" when it is missing. */
    public static synchronized String version() {
        if (version == null) {
            var props = new java.util.Properties();
            try (var in = CraftPyLog.class.getResourceAsStream("/craftpy_version.properties")) {
                if (in != null) {
                    props.load(in);
                }
            } catch (java.io.IOException ignored) {
                // shown as "dev"
            }
            version = props.getProperty("version", "dev");
        }
        return version;
    }
}
