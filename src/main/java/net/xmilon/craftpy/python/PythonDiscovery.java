package net.xmilon.craftpy.python;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.Config;
import net.xmilon.craftpy.project.PyForgePaths;

/**
 * Locates a usable CPython 3 executable. Priority:
 * <ol>
 * <li>Config {@code pythonPath} (explicit user choice)</li>
 * <li>A bundled runtime under {@code pyforge/runtime/}</li>
 * <li>A system Python on PATH ({@code py}, {@code python3}, {@code python})</li>
 * </ol>
 * The executable is probed with {@code --version} and must report Python 3.13+.
 */
public final class PythonDiscovery {
    private static Path cached;
    private static String cachedVersion;
    private static Boolean downloaded;

    private PythonDiscovery() {
    }

    public static synchronized Path pythonExecutable() {
        if (cached != null) {
            return cached;
        }
        List<Path> candidates = new ArrayList<>();

        String configured = Config.PYTHON_PATH.get();
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured.trim()));
        }
        runtimeBundled().ifPresent(candidates::add);
        for (String name : new String[] { "py", "python3", "python" }) {
            for (Path dir : pathDirectories()) {
                candidates.add(dir.resolve(name));
            }
        }

        for (Path candidate : candidates) {
            String version = probe(candidate);
            if (version != null) {
                cached = candidate;
                cachedVersion = version;
                return candidate;
            }
        }
        return null;
    }

    public static synchronized String version() {
        return cachedVersion;
    }

    private static Optional<Path> runtimeBundled() {
        Path runtime = PyForgePaths.runtime();
        for (String name : new String[] { "python", "python3" }) {
            String exe = System.getProperty("os.name").toLowerCase().contains("win")
                    ? name + ".exe" : name;
            Path candidate = runtime.resolve(exe);
            if (candidate.toFile().isFile()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static List<Path> pathDirectories() {
        List<Path> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path == null) {
            return dirs;
        }
        for (String segment : path.split(java.io.File.pathSeparator)) {
            if (!segment.isBlank()) {
                dirs.add(Path.of(segment));
            }
        }
        return dirs;
    }

    /** Runs {@code --version} and returns the reported version on stdout. */
    public static String probe(Path executable) {
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "--version");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                process.waitFor();
                if (line == null) {
                    return null;
                }
                String version = line.replaceFirst("(?i)python?\\s*", "").trim();
                if (isSupported(version)) {
                    return version;
                }
                CraftPy.LOGGER.warn("Found Python at {} but version {} is not supported (need 3.13+)",
                        executable, line);
                return null;
            }
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static boolean isSupported(String version) {
        if (version == null) {
            return false;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)").matcher(version);
        if (!matcher.find()) {
            return false;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        return major > 3 || (major == 3 && minor >= 13);
    }
}