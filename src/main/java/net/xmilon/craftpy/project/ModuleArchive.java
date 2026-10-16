package net.xmilon.craftpy.project;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks a {@code .cpm} safely. A module can come from anyone, so its zip is untrusted input:
 * <ul>
 * <li>entry names that leave the target ({@code ../}, absolute, drive letters, {@code :} streams, NUL) are refused -
 * otherwise a module could drop a jar into {@code mods/} and run Java code outside the Python sandbox;</li>
 * <li>Windows device names ({@code CON}, {@code NUL}, {@code COM1}...) and names ending in a dot or space are refused;</li>
 * <li>only the folders a module has are unpacked ({@code code/ assets/ data/ ui/}, {@code module.json}, README);</li>
 * <li>the real bytes are counted (not the sizes the zip claims), so a zip bomb stops at the limits;</li>
 * <li>a second entry with the same name is ignored.</li>
 * </ul>
 */
public final class ModuleArchive {
    public static final long MAX_TOTAL = 256L * 1024 * 1024;
    public static final long MAX_FILE = 64L * 1024 * 1024;
    public static final int MAX_ENTRIES = 10_000;

    private static final Set<String> TOP = Set.of("code", "assets", "data", "ui");
    private static final Set<String> TOP_FILES = Set.of("module.json", "README.md", "readme.md", "README.MD", "Readme.md");
    private static final Pattern DEVICE = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

    /** What was unpacked and what was left out (with the reason), for the user to see. */
    public record Report(List<String> written, List<String> skipped, long bytes) {
    }

    private ModuleArchive() {
    }

    /**
     * The entry name as a clean relative path ({@code code/main.py}), or null when it must not be written: outside the
     * target, a device name, or not a part of a module.
     */
    public static String safeName(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0) {
            return null;
        }
        String name = raw.replace('\\', '/');
        if (name.startsWith("/") || name.contains(":")) {
            return null; // absolute, C:, and NTFS streams (file.txt:hidden)
        }
        List<String> parts = new ArrayList<>();
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..") || DEVICE.matcher(part).matches() || part.endsWith(".") || part.endsWith(" ")) {
                return null;
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            return null;
        }
        boolean allowed = parts.size() == 1 ? TOP_FILES.contains(parts.get(0)) : TOP.contains(parts.get(0));
        return allowed ? String.join("/", parts) : null;
    }

    /**
     * Writes the entries {@code keep} accepts (given the clean name) into {@code target}. Throws when a limit is passed;
     * the caller unpacks into a fresh folder and deletes it then.
     */
    public static Report extract(ZipFile zip, Path target, Predicate<String> keep) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        List<String> written = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long total = 0;
        int count = 0;
        for (ZipEntry entry : Collections.list(zip.entries())) {
            if (++count > MAX_ENTRIES) {
                throw new IOException("Module has more than " + MAX_ENTRIES + " files");
            }
            if (entry.isDirectory()) {
                continue;
            }
            String name = safeName(entry.getName());
            if (name == null) {
                skipped.add(entry.getName() + " (not allowed in a module)");
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                skipped.add(entry.getName() + " (appears twice)");
                continue;
            }
            if (!keep.test(name)) {
                skipped.add(name + " (file type not unpacked)");
                continue;
            }
            Path out = root.resolve(name).normalize();
            if (!out.startsWith(root) || out.equals(root)) {
                skipped.add(entry.getName() + " (outside the folder)");
                continue;
            }
            Files.createDirectories(out.getParent());
            long size = 0;
            try (InputStream in = zip.getInputStream(entry); OutputStream file = Files.newOutputStream(out)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    size += read;
                    total += read;
                    if (size > MAX_FILE) {
                        throw new IOException(name + " is larger than " + MAX_FILE / (1024 * 1024) + " MB");
                    }
                    if (total > MAX_TOTAL) {
                        throw new IOException("Module unpacks to more than " + MAX_TOTAL / (1024 * 1024) + " MB");
                    }
                    file.write(buffer, 0, read);
                }
            }
            written.add(name);
        }
        return new Report(written, skipped, total);
    }
}
