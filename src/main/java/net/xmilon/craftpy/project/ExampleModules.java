package net.xmilon.craftpy.project;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Build entry point. The same native packer produces IDE builds and shipped example modules. */
public final class ExampleModules {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), generated = Path.of(args[1]).resolve("craftpy_modules");
        Path exports = root.resolve("examples/modules"); Files.createDirectories(exports); Files.createDirectories(generated);
        List<Path> sources = new ArrayList<>();
        sources.add(root.resolve("src/main/resources/craftpy_default/welcome"));
        try (var dirs = Files.list(root.resolve("examples"))) { sources.addAll(dirs.filter(p -> Files.isRegularFile(p.resolve("project.json"))).sorted().toList()); }
        List<String> manifest = new ArrayList<>();
        for (Path source : sources) {
            var result = ModuleBuilder.build(source, exports.resolve(source.getFileName() + ".cpm"));
            byte[] bytes = Files.readAllBytes(result.export());
            Files.write(generated.resolve(result.moduleId() + ".cpm"), bytes);
            manifest.add(result.moduleId() + " " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        Collections.sort(manifest); Files.writeString(generated.resolve("index.txt"), String.join("\n", manifest) + "\n");
    }
}
