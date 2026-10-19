package net.xmilon.craftpy.project;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.util.Json;

/** Seeds packaged examples once; a deleted or user-replaced module is never restored or overwritten. */
public final class BundledModules {
    private BundledModules() { }
    public static synchronized void install() {
        Path receipt = PyForgePaths.system().resolve("bundled_modules.json");
        try (var index = BundledModules.class.getResourceAsStream("/craftpy_modules/index.txt")) {
            if (index == null) return;
            Set<String> handled = new TreeSet<>();
            if (Files.exists(receipt)) handled.addAll(Arrays.asList(Json.COMPACT.fromJson(Files.readString(receipt), String[].class)));
            Set<String> installed = new HashSet<>();
            for (Path path : ModuleLoader.scanAll()) {
                try { installed.add(ModuleLoader.readInfo(path).id); } catch (java.io.IOException ignored) { }
            }
            for (String line : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] entry = line.split(" "); String id = entry[0];
                if (entry.length != 2 || !ProjectManager.isValidId(id) || !entry[1].matches("[0-9a-f]{64}")) throw new java.io.IOException("Invalid bundled module index");
                if (handled.contains(id)) continue;
                Path target = PyForgePaths.modules().resolve(id + ".cpm");
                if (!installed.contains(id) && !Files.exists(target)) {
                    try (var stream = BundledModules.class.getResourceAsStream("/craftpy_modules/" + id + ".cpm")) {
                        if (stream == null) throw new java.io.IOException("Missing bundled module " + id);
                        byte[] bytes = stream.readAllBytes();
                        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                        if (!hash.equals(entry[1])) throw new java.io.IOException("Bundled module hash mismatch: " + id);
                        Files.createDirectories(target.getParent());
                        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                        ModuleLoader.setEnabled(id, false);
                    }
                }
                handled.add(id); Json.write(receipt, handled);
            }
        } catch (Exception error) { CraftPyLog.LOGGER.error("Bundled modules could not be installed", error); }
    }
}
