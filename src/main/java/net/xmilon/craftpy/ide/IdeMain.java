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
 * {@code -Dcraftpy.ide.smoke=true} starts everything except the window and exits (used by the build to check the classpath).
 */
public final class IdeMain {
    private IdeMain() {
    }

    static Path home() {
        return Path.of(System.getProperty("craftpy.home", "")).toAbsolutePath();
    }

    public static void main(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            System.setProperty("craftpy.home", args[0]);
        } else if (System.getProperty("craftpy.home") == null) {
            System.setProperty("craftpy.home", Path.of("").toAbsolutePath().toString());
        }
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
            app.run(new CraftPyFragment(CraftPyFragment.PAGE_HOME),
                    window -> WindowChrome.apply(window, "CraftPy IDE - " + home()));
        } catch (Exception e) {
            CraftPyLog.LOGGER.error("The IDE window failed", e);
        }
        ProjectManager.shutdown();
        System.exit(0);
    }
}
