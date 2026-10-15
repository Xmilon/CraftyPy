package net.xmilon.craftpy.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads mod jars without loading any class: which mods a folder holds (from {@code META-INF/neoforge.mods.toml}) and
 * which classes a jar contains. Used when no game is running (the standalone IDE) and for the class lists of a dump.
 */
public final class ModJars {
    private static final Pattern MOD_BLOCK = Pattern.compile("(?m)^\\s*\\[\\[mods]]");
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"");
    private static final int MAX_CLASSES = 30_000;

    /** A jar and one mod in it. */
    public record Jar(Path path, String modId, String version) {
    }

    private ModJars() {
    }

    /** Every mod found in the {@code .jar} files of a folder (one jar can hold several). Unreadable jars are skipped. */
    public static List<Jar> scan(Path modsDir) {
        List<Jar> out = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return out;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jar : files) {
                out.addAll(modsIn(jar));
            }
        } catch (IOException e) {
            // an unreadable folder just has no mods
        }
        return out;
    }

    public static List<Jar> modsIn(Path jar) {
        List<Jar> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) {
                return out;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                out.addAll(parse(jar, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        } catch (IOException | RuntimeException e) {
            // not a mod jar
        }
        return out;
    }

    /** The mods declared by the text of a {@code neoforge.mods.toml}. A version that is a build placeholder becomes empty. */
    public static List<Jar> parse(Path jar, String toml) {
        List<Jar> out = new ArrayList<>();
        Matcher blocks = MOD_BLOCK.matcher(toml);
        List<Integer> starts = new ArrayList<>();
        while (blocks.find()) {
            starts.add(blocks.start());
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : toml.length();
            String block = toml.substring(starts.get(i), end);
            // a dependency table ([[dependencies.x]]) starts the next block; cut the text there
            int dependencies = block.indexOf("[[dependencies");
            if (dependencies > 0) {
                block = block.substring(0, dependencies);
            }
            Matcher id = MOD_ID.matcher(block);
            if (!id.find()) {
                continue;
            }
            Matcher version = VERSION.matcher(block);
            String text = version.find() ? version.group(1) : "";
            out.add(new Jar(jar, id.group(1), text.contains("${") ? "" : text));
        }
        return out;
    }

    /** Dotted names of the classes in a jar (no nested or mixin classes), capped; never loads them. */
    public static List<String> classNames(Path jar) {
        List<String> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements() && out.size() < MAX_CLASSES) {
                String name = entries.nextElement().getName();
                if (name.endsWith(".class") && !name.contains("$") && !name.startsWith("META-INF/")
                        && !name.contains("/mixin/") && !name.endsWith("module-info.class")) {
                    out.add(name.substring(0, name.length() - 6).replace('/', '.'));
                }
            }
        } catch (IOException | RuntimeException e) {
            // fewer classes is fine
        }
        return out;
    }

    /** The classes that look like NeoForge events: in an {@code event} package or named {@code ...Event}. */
    public static List<String> eventNames(List<String> classes) {
        List<String> out = new ArrayList<>();
        for (String name : classes) {
            if (name.endsWith("Event") || name.contains(".event.")) {
                out.add(name);
            }
        }
        return out;
    }
}
