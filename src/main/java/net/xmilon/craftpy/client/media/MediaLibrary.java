package net.xmilon.craftpy.client.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.xmilon.craftpy.project.PyForgePaths;

/**
 * The audio files of a {@code pyforge/shared/<folder>} the player filled by hand. A module names files relative to the
 * folder; nothing outside it (no {@code ..}, no absolute paths, no links out) can be reached.
 */
public final class MediaLibrary {
    public static final int MAX_FILES = 2000;

    private MediaLibrary() {
    }

    /** Folder names are plain words: {@code music}, {@code sounds_2}. */
    public static boolean validFolder(String folder) {
        return folder != null && folder.matches("[a-z0-9_-]{1,32}");
    }

    public static Path folder(String folder) {
        return PyForgePaths.shared().resolve(folder);
    }

    /** Every playable file of the folder and its subfolders, relative with {@code /}, sorted; at most {@link #MAX_FILES}. */
    public static List<String> list(Path base) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            return out;
        }
        Path real = base.toRealPath();
        try (Stream<Path> files = Files.walk(real, 8)) {
            files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> MediaDecoder.supported(p.getFileName().toString()))
                    .map(p -> real.relativize(p).toString().replace('\\', '/'))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .limit(MAX_FILES)
                    .forEach(out::add);
        }
        return out;
    }

    /** The file {@code name} inside {@code base}, or an exception that says why not. */
    public static Path resolve(Path base, String name) throws IOException {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains(":") || name.contains("\\")) {
            throw new IOException("Not a file name of the folder: " + name);
        }
        Path real = base.toRealPath();
        Path file = real.resolve(name).normalize();
        if (!file.startsWith(real) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("There is no file '" + name + "' in " + base.getFileName());
        }
        if (!MediaDecoder.supported(name)) {
            throw new IOException("'" + name + "' is not an audio file the player can open (OGG, WAV, AIFF, MP3, FLAC)");
        }
        return file;
    }
}
