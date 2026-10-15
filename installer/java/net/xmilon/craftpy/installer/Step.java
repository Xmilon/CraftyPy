package net.xmilon.craftpy.installer;

import java.nio.file.Path;
import java.util.List;

/** One thing the installer does. {@link #text()} is what the user is shown before anything runs. */
public sealed interface Step {

    String text();

    /** Download {@code url} to {@code target}; the bytes must hash to {@code sha256}. */
    record Download(String what, String url, Path target, String sha256) implements Step {
        public String text() {
            return "Download " + what + " -> " + target;
        }
    }

    /** Run a program; a non-zero exit stops the install. */
    record Run(String what, List<String> command) implements Step {
        public String text() {
            return what + ": " + String.join(" ", command);
        }
    }

    /** Copy the CraftPy jar bundled in the installer to {@code target}. */
    record CopyMod(Path target) implements Step {
        public String text() {
            return "Copy CraftPy -> " + target;
        }
    }

    /** Something that cannot be automated: shown, never run. */
    record Manual(String text) implements Step {
    }
}
