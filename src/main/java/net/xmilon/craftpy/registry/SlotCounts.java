package net.xmilon.craftpy.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * How many generic block and item slots are registered at startup. The registries freeze when the game starts, so
 * this has to be known before the config system has loaded: it is read from {@code config/craftpy-slots.properties}
 * (written with the defaults on first start). Every slot costs one registered block and two items plus a few small
 * generated files, so 256 is plenty for most projects; 4096 is the hard maximum. Changing it needs a game restart.
 */
public final class SlotCounts {
    public static final int DEFAULT = 256;
    public static final int MAX = 4096;

    private static final int BLOCKS;
    private static final int ITEMS;
    private static final int KINETICS;

    static {
        Properties properties = new Properties();
        try {
            Path file = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("craftpy-slots.properties");
            if (Files.isRegularFile(file)) {
                try (var in = Files.newInputStream(file)) {
                    properties.load(in);
                }
            } else {
                Files.writeString(file, "# Generic slots registered at startup (1 to " + MAX + "). Restart the game after changing.\n"
                        + "# Content beyond this many blocks / items cannot be declared at the same time.\n"
                        + "blocks=" + DEFAULT + "\nitems=" + DEFAULT + "\nkinetics=32\n");
            }
        } catch (Throwable ignored) {
            // no config folder (unit tests) or unreadable: the defaults apply
        }
        BLOCKS = parse(properties.getProperty("blocks"));
        ITEMS = parse(properties.getProperty("items"));
        KINETICS = parse(properties.getProperty("kinetics"), 32);
    }

    private SlotCounts() {
    }

    static int parse(String text) {
        return parse(text, DEFAULT);
    }
    private static int parse(String text, int fallback) {
        try {
            return Math.max(1, Math.min(MAX, Integer.parseInt(text.trim())));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static int blocks() {
        return BLOCKS;
    }

    public static int items() {
        return ITEMS;
    }
    public static int kinetics() { return KINETICS; }
}
