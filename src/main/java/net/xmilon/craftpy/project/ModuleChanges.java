package net.xmilon.craftpy.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipFile;
import net.xmilon.craftpy.security.PermissionStore;
import net.xmilon.craftpy.util.Json;

/** Applied file identities, separate from the enabled configuration saved for the next reload. */
public final class ModuleChanges {
    public record Snapshot(String runtime, String resources, String archive, String path, List<String> dependencies) {
        public Snapshot { dependencies = List.copyOf(dependencies); }
    }
    public record Plan(Set<String> restart, Set<String> resources, Set<String> removed, Set<String> kept) { }
    public record Result(int restarted, int kept, int loaded, int stopped, List<String> failed, long elapsedMillis) {
        public Result { failed = List.copyOf(failed); }
        public boolean success() { return failed.isEmpty(); }
        public String summary() {
            return restarted + " restarted, " + kept + " kept; " + loaded + " loaded, " + stopped + " stopped"
                    + (failed.isEmpty() ? "" : "; failed: " + String.join(", ", failed)) + " (" + elapsedMillis + " ms)";
        }
        public Result plus(Result other) {
            var errors = new ArrayList<>(failed); errors.addAll(other.failed);
            return new Result(restarted + other.restarted, kept + other.kept, loaded + other.loaded,
                    stopped + other.stopped, errors, elapsedMillis + other.elapsedMillis);
        }
    }
    private ModuleChanges() { }

    public static String dependencyId(String value) { return value.split("[<>=!~ ;]", 2)[0].trim(); }

    public static Snapshot snapshot(ModuleInfo info) throws IOException {
        MessageDigest runtime = digest(), resources = digest();
        String path = Path.of(info.path).toAbsolutePath().normalize().toString();
        update(runtime, path);
        update(runtime, Json.COMPACT.toJson(PermissionStore.approved("module:" + info.id)));
        try (ZipFile zip = new ZipFile(info.path)) {
            var entries = Collections.list(zip.entries());
            entries.sort(Comparator.comparing(java.util.zip.ZipEntry::getName));
            for (var entry : entries) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                MessageDigest target = name.equals("module.json") || name.startsWith("code/") || name.startsWith("ui/")
                        ? runtime : resources;
                update(target, name);
                try (var input = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[8192];
                    for (int size; (size = input.read(buffer)) != -1;) target.update(buffer, 0, size);
                }
                target.update((byte) 0);
            }
        }
        if (info.sha256 == null || info.sha256.isBlank()) throw new IOException("Cannot fingerprint " + info.id);
        return new Snapshot(hex(runtime), hex(resources), info.sha256, path, info.dependencies);
    }

    public static Snapshot projectSnapshot(Path directory) throws IOException {
        MessageDigest runtime = digest(), resources = digest();
        Path root = directory.toAbsolutePath().normalize();
        update(runtime, root.toString());
        String metadata = java.nio.file.Files.readString(root.resolve("project.json"));
        update(runtime, metadata);
        var meta = Json.COMPACT.fromJson(metadata, ProjectMetadata.class);
        update(runtime, Json.COMPACT.toJson(PermissionStore.approved(meta.id)));
        for (String folder : List.of("code", "ui", "assets", "data")) {
            Path dir = root.resolve(folder);
            if (!java.nio.file.Files.isDirectory(dir)) continue;
            try (var files = java.nio.file.Files.walk(dir)) {
                for (Path file : files.filter(java.nio.file.Files::isRegularFile).sorted().toList()) {
                    if (file.toString().contains("__pycache__") || file.toString().endsWith(".pyc")) continue;
                    MessageDigest target = folder.equals("code") || folder.equals("ui") ? runtime : resources;
                    update(target, root.relativize(file).toString());
                    try (var in = java.nio.file.Files.newInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        for (int size; (size = in.read(buffer)) != -1;) target.update(buffer, 0, size);
                    }
                    target.update((byte) 0);
                }
            }
        }
        return new Snapshot(hex(runtime), hex(resources), "", root.toString(), meta.dependencies == null ? List.of() : meta.dependencies);
    }

    public static Plan compare(Map<String, Snapshot> applied, Map<String, Snapshot> wanted) {
        Set<String> restart = new LinkedHashSet<>(), resources = new LinkedHashSet<>(), removed = new LinkedHashSet<>(applied.keySet());
        removed.removeAll(wanted.keySet());
        for (var entry : wanted.entrySet()) {
            Snapshot old = applied.get(entry.getKey()), next = entry.getValue();
            if (old == null || !old.runtime.equals(next.runtime) || !old.dependencies.equals(next.dependencies)) restart.add(entry.getKey());
            else if (!old.resources.equals(next.resources) || !old.archive.equals(next.archive)) resources.add(entry.getKey());
        }
        boolean changed;
        do {
            changed = false;
            for (var entry : wanted.entrySet()) {
                if (entry.getValue().dependencies.stream().map(ModuleChanges::dependencyId)
                        .anyMatch(id -> restart.contains(id) || removed.contains(id))) changed |= restart.add(entry.getKey());
            }
        } while (changed);
        resources.removeAll(restart);
        Set<String> kept = new LinkedHashSet<>(wanted.keySet()); kept.removeAll(restart);
        return new Plan(Set.copyOf(restart), Set.copyOf(resources), Set.copyOf(removed), Set.copyOf(kept));
    }

    public static String dependencyProblem(String id, Map<String, Snapshot> wanted) {
        return dependencyProblem(id, wanted, new LinkedHashSet<>(), new HashSet<>());
    }

    private static String dependencyProblem(String id, Map<String, Snapshot> wanted, Set<String> visiting, Set<String> done) {
        if (done.contains(id)) return "";
        if (!visiting.add(id)) return "Circular dependency: " + String.join(" -> ", visiting) + " -> " + id;
        Snapshot snapshot = wanted.get(id);
        if (snapshot == null) return "Dependency is disabled or missing: " + id;
        for (String dependency : snapshot.dependencies) {
            String problem = dependencyProblem(dependencyId(dependency), wanted, visiting, done);
            if (!problem.isEmpty()) return problem;
        }
        visiting.remove(id); done.add(id);
        return "";
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void update(MessageDigest digest, String value) { digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); }
    private static String hex(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }
}
