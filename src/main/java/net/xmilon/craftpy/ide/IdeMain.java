package net.xmilon.craftpy.ide;

import java.nio.file.Path;

import icyllis.modernui.ModernUI;
import icyllis.modernui.R;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.client.IdeHost;
import net.xmilon.craftpy.client.mui.CraftPyFragment;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.python.PyRuntime;

/**
 * The CraftPy IDE without Minecraft: the same editor in its own window, working on the {@code pyforge/} folder of a game
 * directory. Python (validate, completion, the console) works; running a project in a world needs the game.
 *
 * <p>{@code java net.xmilon.craftpy.ide.IdeMain [game folder]} (default: {@code -Dcraftpy.home}, then the current folder).
 * {@code -Dcraftpy.ide.smoke=true} starts everything except the window and exits (used by the build to check the classpath);
 * {@code -Dcraftpy.ide.page=editor|modules|docs} opens another page first, {@code -Dcraftpy.ide.exitAfter=<seconds>} closes
 * the window by itself (unattended checks of the real UI).
 */
public final class IdeMain {
    private IdeMain() {
    }

    /**
     * The game folder the IDE works on: the first argument, {@code -Dcraftpy.home}, the {@code CRAFTPY_HOME} environment
     * variable, {@code home=} in {@code craftpy-ide.properties} next to the IDE's jar (written by {@code gradlew ideDist}),
     * the Minecraft folder of this computer when CraftPy has been used there, and the current folder last.
     */
    static Path chooseHome(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0]).toAbsolutePath();
        }
        for (String value : new String[]{System.getProperty("craftpy.home"), System.getenv("CRAFTPY_HOME"), propertiesHome()}) {
            if (value != null && !value.isBlank()) {
                return Path.of(value.trim()).toAbsolutePath();
            }
        }
        Path minecraft = minecraftFolder();
        if (minecraft != null && java.nio.file.Files.isDirectory(minecraft.resolve("pyforge"))) {
            return minecraft;
        }
        return Path.of("").toAbsolutePath();
    }

    private static String propertiesHome() {
        try {
            var source = IdeMain.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            Path jar = Path.of(source.getLocation().toURI());
            Path dir = java.nio.file.Files.isDirectory(jar) ? jar : jar.getParent();
            for (Path candidate : new Path[]{dir, dir.getParent()}) { // the jar's folder, or the one above lib/
                Path file = candidate == null ? null : candidate.resolve("craftpy-ide.properties");
                if (file != null && java.nio.file.Files.isRegularFile(file)) {
                    var properties = new java.util.Properties();
                    try (var in = java.nio.file.Files.newBufferedReader(file)) {
                        properties.load(in);
                    }
                    return properties.getProperty("home");
                }
            }
        } catch (Exception ignored) {
            // no properties: the next choice applies
        }
        return null;
    }

    private static Path minecraftFolder() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String user = System.getProperty("user.home", "");
        if (os.startsWith("windows")) {
            String appData = System.getenv("APPDATA");
            return appData == null ? null : Path.of(appData, ".minecraft");
        }
        if (os.contains("mac")) {
            return Path.of(user, "Library", "Application Support", "minecraft");
        }
        return Path.of(user, ".minecraft");
    }

    static Path home() {
        return Path.of(System.getProperty("craftpy.home", "")).toAbsolutePath();
    }

    public static void main(String[] args) {
        System.setProperty("craftpy.home", chooseHome(args).toString());
        PyForgePaths.ensureLayout();
        ProjectManager.init();
        IdeHost.install(new LocalHost());
        Runtime.getRuntime().addShutdownHook(new Thread(PyRuntime::shutdown, "craftpy-ide-shutdown"));
        CraftPyLog.LOGGER.info("CraftPy IDE ready: {}", PyForgePaths.root());
        System.out.println("CraftPy IDE ready: " + PyForgePaths.root());
        if (Boolean.getBoolean("craftpy.ide.smoke")) {
            System.exit(0);
        }
        try (ModernUI app = new ModernUI()) {
            // Modern UI starts light on its own; in the game its config sets this pair (its default), so match it
            app.setTheme(R.style.Theme_Material3_Dark);
            app.getTheme().applyStyle(R.style.ThemeOverlay_Material3_Dark_Rust, true);
            int page = switch (System.getProperty("craftpy.ide.page", "home")) {
                case "editor" -> CraftPyFragment.PAGE_EDITOR;
                case "modules" -> CraftPyFragment.PAGE_MODULES;
                case "docs" -> CraftPyFragment.PAGE_DOCS;
                default -> CraftPyFragment.PAGE_HOME;
            };
            long exitAfter = Long.getLong("craftpy.ide.exitAfter", 0L); // seconds; for unattended UI checks
            app.run(new CraftPyFragment(page), window -> {
                WindowChrome.apply(window, "CraftPy IDE - " + home());
                if (exitAfter > 0) {
                    Thread closer = new Thread(() -> {
                        try {
                            Thread.sleep(exitAfter * 1000);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                        System.out.println("CraftPy IDE closing after " + exitAfter + " s");
                        LocalHost.closeWindow();
                    }, "craftpy-ide-exit");
                    closer.setDaemon(true);
                    closer.start();
                }
            });
        } catch (Exception e) {
            CraftPyLog.LOGGER.error("The IDE window failed", e);
        }
        System.out.println("CraftPy IDE window closed");
        ProjectManager.shutdown();
        System.exit(0);
    }
}
