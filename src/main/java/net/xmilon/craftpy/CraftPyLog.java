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

    private CraftPyLog() {
    }
}
