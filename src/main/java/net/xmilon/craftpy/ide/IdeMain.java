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
