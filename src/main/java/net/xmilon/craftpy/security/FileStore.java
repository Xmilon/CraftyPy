package net.xmilon.craftpy.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.xmilon.craftpy.project.PyForgePaths;

/**
 * {@code files.*} for Python: every project and module gets its own folder, {@code pyforge/data/<owner>/}, and can read and
 * write text files in it, nowhere else. Names are checked as text first (no {@code ..}, no absolute paths), then against the
 * real file system (no link may lead out of the folder). The folder has a size cap and a file count cap. Folders the server
 * owner lists in the config can be read, never written.
 */
public final class FileStore {
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_. -]{0,63}");
    private static final int MAX_FILES = 500;
    private static final int MAX_DEPTH = 4;

    private FileStore() {
    }

    /** The folder of an owner: {@code module:foo} becomes {@code module-foo}, which no project id can be. */
    static Path root(String owner) {
        return PyForgePaths.root().resolve("data").resolve(owner.replace(':', '-'));
    }

    /** A clean relative name ({@code notes/day1.txt}) or an IllegalArgumentException that says what is wrong. */
    public static String cleanName(String name) {
        if (name == null || name.isEmpty() || name.length() > 160) {
            throw new IllegalArgumentException("A file name is 1 to 160 characters");
        }
        String[] parts = name.replace('\\', '/').split("/", -1);
        if (parts.length > MAX_DEPTH) {
            throw new IllegalArgumentException("Folders can be nested " + (MAX_DEPTH - 1) + " deep at most");
        }
        for (String part : parts) {
            if (!SEGMENT.matcher(part).matches() || part.endsWith(".") || part.endsWith(" ")
                    || part.toLowerCase(java.util.Locale.ROOT).matches("(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?")) {
                throw new IllegalArgumentException("'" + part + "' is not a usable file or folder name (letters, digits, _ . - space;"
                        + " no '..', no leading dot)");
            }
        }
        return String.join("/", parts);
    }

    // -- the project's own folder --------------------------------------------------------------------------------------

    public static String read(String owner, String name, String sharedFolder) throws IOException {
        Path file = sharedFolder == null || sharedFolder.isEmpty() ? inside(root(owner), name, false) : inside(shared(sharedFolder), name, false);
        if (!Files.isRegularFile(file)) {
            throw new IOException("No file '" + name + "'");
        }
        long limit = (long) AccessConfig.get(AccessConfig.FILES_MAX_FILE_KB) * 1024;
        if (Files.size(file) > limit) {
            throw new IOException("'" + name + "' is larger than " + limit / 1024 + " KB");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public static void write(String owner, String name, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        long maxFile = (long) AccessConfig.get(AccessConfig.FILES_MAX_FILE_KB) * 1024;
        if (bytes.length > maxFile) {
            throw new IOException("A file can be " + maxFile / 1024 + " KB at most");
        }
        Path root = root(owner);
        Files.createDirectories(root);
        Path file = inside(root, name, true);
        long used = usage(root);
        long existing = Files.isRegularFile(file) ? Files.size(file) : 0;
        if (used - existing + bytes.length > (long) AccessConfig.get(AccessConfig.FILES_QUOTA_MB) * 1024 * 1024) {
            throw new IOException("The data folder of " + owner + " is full (" + AccessConfig.get(AccessConfig.FILES_QUOTA_MB) + " MB)");
        }
        if (!Files.exists(file) && count(root) >= MAX_FILES) {
            throw new IOException("A data folder can hold " + MAX_FILES + " files at most");
        }
        Files.createDirectories(file.getParent());
        inside(root, name, true); // again, now that the folders exist: a link made in between would show here
        Files.write(file, bytes);
    }

    public static boolean delete(String owner, String name) throws IOException {
        Path file = inside(root(owner), name, false);
        return Files.isRegularFile(file) && Files.deleteIfExists(file);
    }

    public static boolean exists(String owner, String name, String sharedFolder) throws IOException {
        Path base = sharedFolder == null || sharedFolder.isEmpty() ? root(owner) : shared(sharedFolder);
        return Files.isRegularFile(inside(base, name, false));
    }

    /** Names of the files in a folder of the owner (or of a shared folder), relative, sorted, at most 500. */
    public static List<String> list(String owner, String sharedFolder) throws IOException {
        Path base = sharedFolder == null || sharedFolder.isEmpty() ? root(owner) : shared(sharedFolder);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        Path real = base.toRealPath();
        List<String> names = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(real, MAX_DEPTH)) {
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile).sorted()::iterator) {
                if (Files.isSymbolicLink(path)) {
                    continue;
                }
                names.add(real.relativize(path).toString().replace('\\', '/'));
                if (names.size() >= MAX_FILES) {
                    break;
                }
            }
        }
        return names;
    }

    // -- folders of the server owner (read only) -----------------------------------------------------------------------

    static Path shared(String name) throws IOException {
        for (String entry : AccessConfig.strings(AccessConfig.SHARED_FOLDERS)) {
            int equals = entry.indexOf('=');
            if (equals > 0 && entry.substring(0, equals).strip().equals(name)) {
                Path folder = Path.of(entry.substring(equals + 1).strip());
                if (!Files.isDirectory(folder)) {
                    throw new IOException("The shared folder '" + name + "' does not exist on this computer");
                }
                return folder;
            }
        }
        throw new IOException("There is no shared folder '" + name + "' (craftpy-access.toml, files.sharedFolders)");
    }

    // -- containment ---------------------------------------------------------------------------------------------------

    /**
     * The file {@code name} in {@code base}, after checking the name as text and the path on disk: nothing on the way may be
     * a link, so the real path equals the plain path. {@code forWrite}: the file itself may not exist yet.
     */
    static Path inside(Path base, String name, boolean forWrite) throws IOException {
        String clean = cleanName(name);
        if (!Files.isDirectory(base)) {
            if (forWrite) {
                throw new IOException("The data folder does not exist");
            }
            return base.resolve(clean); // reading a folder that is not there: "no file"
        }
        Path root = base.toRealPath();
        Path wanted = root.resolve(clean).normalize();
        if (!wanted.startsWith(root)) {
            throw new IOException("That name leads out of the folder");
        }
        // every part that exists must be a real file or folder, not a link
        Path walk = root;
        for (String part : clean.split("/")) {
            walk = walk.resolve(part);
            if (Files.exists(walk, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(walk)) {
                throw new IOException("Links are not followed in a data folder");
            }
        }
        return wanted;
    }

    private static long usage(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            long total = 0;
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                total += Files.size(path);
            }
            return total;
        }
    }

    private static long count(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }
}
