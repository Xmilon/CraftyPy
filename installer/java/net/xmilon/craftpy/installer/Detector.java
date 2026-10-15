package net.xmilon.craftpy.installer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Looks at this PC (programs on PATH, the Minecraft folder) and returns an {@link Env}. */
public final class Detector {

    private Detector() {
    }

    public static Env detect(Path minecraftDir, Requirements req) {
        String os = Env.osName(System.getProperty("os.name"));
        Set<String> mods = new HashSet<>();
        try (Stream<Path> files = Files.isDirectory(minecraftDir.resolve("mods")) ? Files.list(minecraftDir.resolve("mods")) : Stream.empty()) {
            files.forEach(file -> mods.add(file.getFileName().toString()));
        } catch (IOException e) {
            // an unreadable mods folder is treated as empty; the copy step reports the real error
        }
        boolean neoforge = Files.isDirectory(minecraftDir.resolve("versions").resolve("neoforge-" + req.neoforgeVersion()));
        return new Env(os, minecraftDir, Runtime.version().feature(), python(req.pythonMin()), packageManager(os), neoforge, mods);
    }

    /** The first Python on PATH that is at least {@code minimum} (like "3.13"), or null. */
    static String python(String minimum) {
        String[] parts = minimum.split("\\.");
        String check = "import sys;print(int(sys.version_info[:2]>=(" + parts[0] + "," + parts[1] + ")))";
        for (String candidate : List.of("python3.13", "python3", "python")) {
            if (!onPath(candidate)) {
                continue;
            }
            String out = output(List.of(candidate, "-c", check));
            if (out != null && out.trim().equals("1")) {
                return candidate;
            }
        }
        return null;
    }

    static String packageManager(String os) {
        List<String> candidates = switch (os) {
            case "windows" -> List.of("winget");
            case "macos" -> List.of("brew");
            default -> List.of("apt", "dnf", "pacman");
        };
        return candidates.stream().filter(Detector::onPath).findFirst().orElse(null);
    }

    static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String suffix : List.of("", ".exe", ".cmd", ".bat")) {
                if (!dir.isEmpty() && Files.isRegularFile(Path.of(dir, program + suffix))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String output(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String text = new String(process.getInputStream().readAllBytes());
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0 ? text : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
