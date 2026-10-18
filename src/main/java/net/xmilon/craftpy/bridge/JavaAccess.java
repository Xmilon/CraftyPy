package net.xmilon.craftpy.bridge;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What Python may touch through the Java bridge. Three modes (config {@code javaBridge.mode}):
 * <ul>
 *   <li>{@code OFF} - nothing.</li>
 *   <li>{@code RESTRICTED} (default) - Minecraft, NeoForge, Mojang libraries, CraftPy's game objects and the packages listed in
 *       {@code javaBridge.extraPackages} (put the package of a mod you want to script there).</li>
 *   <li>{@code FULL} - every class that is not on the hard deny list.</li>
 * </ul>
 * The hard deny list (processes, threads, reflection, files, network, class loading, the mod loader) applies in
 * every mode: a script can drive the game, it cannot leave the game.
 */
public final class JavaAccess {
    public enum Mode { OFF, RESTRICTED, FULL }

    private static final List<String> DENY = List.of(
            "java.lang.Runtime", "java.lang.Process", "java.lang.System", "java.lang.Thread", "java.lang.Class",
            "java.lang.Module", "java.lang.ClassLoader", "java.lang.reflect.", "java.lang.invoke.",
            "java.lang.foreign.", "java.lang.management.", "java.io.", "java.nio.", "java.net.", "java.security.",
            "java.sql.", "java.util.concurrent.", "java.util.logging.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.objectweb.", "org.spongepowered.", "cpw.", "net.neoforged.fml.", "net.neoforged.neoforgespi.",
            "net.neoforged.bus.", "net.neoforged.jarjar.", "net.minecraft.client.");

    private static final List<String> RESTRICTED_ALLOW = List.of(
            "net.minecraft.", "net.neoforged.", "com.mojang.", "it.unimi.dsi.fastutil.");

    /**
     * The only parts of CraftPy itself a script may touch, in every mode: the game objects it makes. Everything else
     * (permissions, file store, paths, config, the worker, the bridge) would let a script approve or widen itself.
     */
    private static final List<String> CRAFTPY_EXPOSED = List.of(
            "net.xmilon.craftpy.block.", "net.xmilon.craftpy.entity.", "net.xmilon.craftpy.registry.",
            "net.xmilon.craftpy.content.");

    /** Plain JDK value types and collections: needed as arguments and results, harmless on their own. */
    private static final Set<String> JDK_VALUES = Set.of(
            "java.lang.Object", "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
            "java.lang.Float", "java.lang.Short", "java.lang.Byte", "java.lang.Boolean", "java.lang.Character",
            "java.lang.Number", "java.lang.Math", "java.lang.Enum", "java.lang.Comparable", "java.lang.Iterable",
            "java.lang.CharSequence", "java.lang.Runnable", "java.util.List", "java.util.ArrayList",
            "java.util.LinkedList", "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap",
            "java.util.TreeMap", "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet",
            "java.util.TreeSet", "java.util.Collection", "java.util.Collections", "java.util.Arrays",
            "java.util.Optional", "java.util.OptionalInt", "java.util.OptionalDouble", "java.util.UUID",
            "java.util.Iterator", "java.util.Random", "java.util.Map$Entry", "java.util.stream.Stream",
            "java.util.stream.Collectors");

    /** Method names never callable, on any class. */
    private static final Set<String> DENIED_METHODS = Set.of(
            "getClass", "wait", "notify", "notifyAll", "forName", "getClassLoader", "setAccessible");

    private static volatile Mode mode = Mode.RESTRICTED;
    private static volatile List<String> extraPackages = List.of();

    private JavaAccess() {
    }

    public static void configure(Mode newMode, List<String> extra) {
        mode = newMode;
        extraPackages = List.copyOf(extra);
    }

    public static Mode mode() {
        return mode;
    }

    /** Reads the config when NeoForge has loaded it; unit tests and early calls keep the defaults. */
    public static void refreshFromConfig() {
        try {
            configure(net.xmilon.craftpy.Config.JAVA_BRIDGE_MODE.get(), // throws while no config is loaded: keep what is set
                    List.copyOf(net.xmilon.craftpy.Config.JAVA_BRIDGE_PACKAGES.get()));
        } catch (Throwable ignored) {
            // config not loaded yet: keep what is set
        }
    }

    public static boolean allowedMethod(String name) {
        return !DENIED_METHODS.contains(name);
    }

    public static boolean allowed(Class<?> type) {
        while (type.isArray()) {
            type = type.getComponentType();
        }
        if (type.isPrimitive()) {
            return true;
        }
        if (mode == Mode.OFF) {
            return false;
        }
        String name = type.getName();
        if (name.startsWith("net.xmilon.craftpy.")) {
            return CRAFTPY_EXPOSED.stream().anyMatch(name::startsWith);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : DENY) {
            if (lower.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        if (JDK_VALUES.contains(name) || name.startsWith("java.util.function.")) {
            return true;
        }
        if (name.startsWith("java.") || name.startsWith("kotlin.") || name.startsWith("scala.")) {
            return false;
        }
        if (mode == Mode.FULL) {
            return true;
        }
        for (String prefix : RESTRICTED_ALLOW) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        for (String prefix : extraPackages) {
            if (!prefix.isBlank() && name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
