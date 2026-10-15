package net.xmilon.craftpy.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * The catalog files on disk: one {@code <namespace>.json} per namespace and an {@code index.json} with the version and
 * jar hash each was made from. Files of mods that were removed stay; they are just not part of any view until the mod
 * is back (and if it comes back unchanged, nothing has to be dumped again).
 */
public final class CatalogStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** What the index remembers about a namespace file. */
    public static final class IndexEntry {
        public String mod = "";
        public String version = "";
        public String jarHash = "";
    }

    private static final class Index {
        Map<String, IndexEntry> namespaces = new TreeMap<>();
        /** "path|size|mtime" to SHA-256, so a jar is read once, not on every start. */
        Map<String, String> hashes = new HashMap<>();
    }

    private final Path dir;

    public CatalogStore(Path dir) {
        this.dir = dir;
    }

    public Path directory() {
        return dir;
    }

    public synchronized void save(Catalog catalog) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(file(catalog.namespace), GSON.toJson(catalog), StandardCharsets.UTF_8);
        Index index = readIndex();
        IndexEntry entry = new IndexEntry();
        entry.mod = catalog.mod;
        entry.version = catalog.version;
        entry.jarHash = catalog.jarHash;
        index.namespaces.put(catalog.namespace, entry);
        writeIndex(index);
    }

    /** The saved catalog of a namespace, or null (missing or unreadable). */
    public synchronized Catalog load(String namespace) {
        Path file = file(namespace);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Catalog.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** True when the saved catalog of a namespace was made from this version and jar. */
    public synchronized boolean isCurrent(String namespace, String version, String jarHash) {
        IndexEntry entry = readIndex().namespaces.get(namespace);
        return entry != null && entry.version.equals(version) && entry.jarHash.equals(jarHash) && Files.isRegularFile(file(namespace));
    }

    /** The mods that have a saved catalog, installed or not. */
    public synchronized Set<String> knownMods() {
        Set<String> mods = new java.util.TreeSet<>();
        for (IndexEntry entry : readIndex().namespaces.values()) {
            mods.add(entry.mod);
        }
        return mods;
    }

    /** Every saved catalog whose mod is installed; the others stay on disk but are hidden. */
    public synchronized List<Catalog> visible(Set<String> installedMods) {
        List<Catalog> out = new ArrayList<>();
        for (var entry : readIndex().namespaces.entrySet()) {
            if (!installedMods.contains(entry.getValue().mod)) {
                continue;
            }
            Catalog catalog = load(entry.getKey());
            if (catalog != null) {
                out.add(catalog);
            }
        }
        return out;
    }

    /** SHA-256 of a file, remembered by path, size and modification time. */
    public synchronized String hashOf(Path jar) throws IOException {
        Index index = readIndex();
        String prefix = jar.toAbsolutePath() + "|";
        String key = prefix + Files.size(jar) + "|" + Files.getLastModifiedTime(jar).toMillis();
        String known = index.hashes.get(key);
        if (known != null) {
            return known;
        }
        String hash = sha256(jar);
        index.hashes.keySet().removeIf(k -> k.startsWith(prefix)); // older states of the same file
        index.hashes.put(key, hash);
        writeIndex(index);
        return hash;
    }

    public static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path file(String namespace) {
        return dir.resolve(namespace.replaceAll("[^a-z0-9_.-]", "_") + ".json");
    }

    private Index readIndex() {
        Path file = dir.resolve("index.json");
        if (Files.isRegularFile(file)) {
            try {
                Index index = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Index.class);
                if (index != null) {
                    return index;
                }
            } catch (IOException | RuntimeException e) {
                // a broken index is rebuilt by the next dump
            }
        }
        return new Index();
    }

    private void writeIndex(Index index) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.json"), GSON.toJson(index), StandardCharsets.UTF_8);
    }
}
