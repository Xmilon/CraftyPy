package net.xmilon.craftpy.project;

import java.nio.file.*;
import java.util.*;

/**
 * Dev and test entry point: packs every project under examples/ (and the welcome project) into .cpm files in one
 * output folder. Nothing is shipped in the jar or committed; the examples stay plain projects.
 */
public final class ExampleModules {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), out = Path.of(args[1]);
        Files.createDirectories(out);
        List<Path> sources = new ArrayList<>();
        // the Welcome tour is a template: made into a project here so it can be packed like the examples
        Path welcome = Files.createTempDirectory("craftpy-welcome").resolve("welcome");
        ProjectTemplates.materialize("welcome", "welcome", "Welcome", "both", welcome);
        sources.add(welcome);
        try (var dirs = Files.list(root.resolve("examples"))) { sources.addAll(dirs.filter(p -> Files.isRegularFile(p.resolve("project.json"))).sorted().toList()); }
        for (Path source : sources) ModuleBuilder.build(source, out.resolve(source.getFileName() + ".cpm"));
    }
}
