package net.xmilon.craftpy.client;

import java.io.InputStream;
import java.nio.file.Path;

import net.xmilon.craftpy.network.ProjectAction;

/**
 * What the editor needs from the program it runs in: the game ({@code McHost}) or the standalone IDE. UI and store code goes
 * through this instead of {@code Minecraft}, so it also runs without the game.
 */
public interface IdeHost {

    /** Runs {@code task} on the thread that owns the client store (the game's client thread). */
    void execute(Runnable task);

    /** Sends one request to the project service: over the network in the game, straight to it in the standalone IDE. */
    void send(ProjectAction action, String projectId, String data);

    String clipboard();

    void setClipboard(String text);

    /** Folder of the editor's own settings file. */
    Path configDir();

    /** A bundled document under {@code assets/craftpy/docs/}, or null when it is not there. Caller closes the stream. */
    InputStream doc(String file) throws java.io.IOException;

    /** Closes the screen or window the editor is shown in. */
    void closeScreen();

    /** True inside the running game: sounds play, the 3D preview screens open, worlds can run projects. */
    default boolean inGame() {
        return false;
    }

    /** Used until a host is installed (unit tests): runs inline, keeps the clipboard in memory. */
    IdeHost PLAIN = new IdeHost() {
        private String clipboard = "";

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void send(ProjectAction action, String projectId, String data) {
            // no service behind the plain host
        }

        @Override
        public String clipboard() {
            return clipboard;
        }

        @Override
        public void setClipboard(String text) {
            clipboard = text == null ? "" : text;
        }

        @Override
        public Path configDir() {
            return Path.of(System.getProperty("craftpy.home", "."), "config");
        }

        @Override
        public InputStream doc(String file) {
            return IdeHost.class.getResourceAsStream("/assets/craftpy/docs/" + file);
        }

        @Override
        public void closeScreen() {
        }
    };

    final class Holder {
        private static volatile IdeHost host = PLAIN;

        private Holder() {
        }
    }

    static IdeHost get() {
        return Holder.host;
    }

    static void install(IdeHost host) {
        Holder.host = host;
    }
}
