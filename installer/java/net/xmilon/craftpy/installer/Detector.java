package net.xmilon.craftpy.installer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/** Looks at this PC (Java, the Minecraft folder) and returns an {@link Env}. */
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
        return new Env(os, minecraftDir, Runtime.version().feature(), neoforge, mods);
    }
}
