package net.xmilon.craftpy.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.xmilon.craftpy.security.FileStore;

/**
 * Backups of what modules and projects keep for themselves, as one JSON file in the world's {@code craftpy_backups/} folder:
 * their {@code storage} values, their {@code files.*} data folders and the saved state of loaded CraftPy machines (slots,
 * values, energy, liquid). A restore puts back what the backup holds for each owner it names: that owner's storage values
 * are replaced by the backup's, its files are written again (other files stay), and a machine is restored only where the same
 * machine block still stands. Pure file and JSON work; the command gives it the live data.
 */
public final class Backups {
    public static final String FOLDER = "craftpy_backups";
    public static final int FORMAT = 1;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,119}\\.json");
    private static final Pattern OWNER_FOLDER = Pattern.compile("[a-z0-9_][a-z0-9_.-]{0,63}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
    /** Files bigger than this are not put into a backup (the data folders have a size cap of their own). */
    static final long MAX_FILE = 4L * 1024 * 1024;

    private Backups() {
    }

    /** A machine of the world as the backup keeps it: where, which block, the project it is of, and its saved state as SNBT. */
    public record Machine(String dimension, int x, int y, int z, String block, String owner, String state) {
    }

    /** What a restore did; {@code skipped} says what it could not put back and why. */
    public record Restored(int values, int files, int machines, List<String> skipped) {
    }

    /** Where the machines of a backup go back: true when the same block stands there and took the state. */
    public interface MachineSink {
        boolean restore(Machine machine);
    }

    /** {@code 2026-10-23_19-20-05.json}, or with a project {@code 2026-10-23_19-20-05_bank.json}. */
    public static String fileName(Instant when, String project) {
        String clean = project == null ? "" : "_" + project.replaceAll("[^a-z0-9_]", "_");
        return STAMP.format(when) + clean + ".json";
    }

    /** The owner of a storage key ({@code bank/world/accounts} is bank's, {@code module:shop/world/x} is the module's). */
    static String ownerOf(String key) {
        int slash = key.indexOf('/');
        return slash < 0 ? key : key.substring(0, slash);
    }

    /** True when the owner is {@code project} itself or the module of that id; every owner when {@code project} is null. */
    static boolean wanted(String owner, String project) {
        return project == null || owner.equals(project) || owner.equals("module:" + project);
    }

    /** The folder name of an owner's {@code files.*} data, like {@link FileStore} names it. */
    static String folderOf(String owner) {
        return owner.replace(':', '-');
    }

    /** The backup of everything, or of one project (and its module) when {@code project} is not null. */
    public static JsonObject build(Map<String, JsonElement> storage, Path dataRoot, List<Machine> machines, String project,
                                   Instant when, String world) throws IOException {
        JsonObject out = new JsonObject();
        out.addProperty("craftpy_backup", FORMAT);
        out.addProperty("created", when.toString());
        out.addProperty("world", world);
        if (project != null) out.addProperty("project", project);
        JsonObject values = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : new java.util.TreeMap<>(storage).entrySet()) {
            String owner = ownerOf(entry.getKey());
            if (!wanted(owner, project)) continue;
            if (!values.has(owner)) values.add(owner, new JsonObject());
            values.getAsJsonObject(owner).add(entry.getKey().substring(Math.min(entry.getKey().length(), owner.length() + 1)),
                    entry.getValue().deepCopy());
        }
        out.add("storage", values);
        JsonObject files = new JsonObject();
        if (Files.isDirectory(dataRoot)) {
            try (Stream<Path> folders = Files.list(dataRoot)) {
                for (Path folder : folders.sorted().toList()) {
                    String name = folder.getFileName().toString();
                    if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || !OWNER_FOLDER.matcher(name).matches()) continue;
                    String owner = name.startsWith("module-") ? "module:" + name.substring("module-".length()) : name;
                    if (!wanted(owner, project)) continue;
                    JsonObject inFolder = filesOf(folder);
                    if (!inFolder.isEmpty()) files.add(owner, inFolder);
                }
            }
        }
        out.add("files", files);
        JsonArray kept = new JsonArray();
        for (Machine machine : machines) {
            JsonObject entry = new JsonObject();
            entry.addProperty("dimension", machine.dimension());
            entry.addProperty("x", machine.x());
            entry.addProperty("y", machine.y());
            entry.addProperty("z", machine.z());
            entry.addProperty("block", machine.block());
            entry.addProperty("owner", machine.owner());
            entry.addProperty("state", machine.state());
            kept.add(entry);
        }
        out.add("machines", kept);
        return out;
    }

    /** Every regular file of a data folder (no links), as UTF-8 text or, when it is not text, base64. */
    private static JsonObject filesOf(Path folder) throws IOException {
        JsonObject out = new JsonObject();
        Path real = folder.toRealPath();
        try (Stream<Path> walk = Files.walk(real, 4)) {
            for (Path file : walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                String relative = real.relativize(file).toString().replace('\\', '/');
                if (Files.size(file) > MAX_FILE || relative.contains(".tmp")) continue;
                byte[] bytes = Files.readAllBytes(file);
                JsonObject entry = new JsonObject();
                String text = utf8(bytes);
                if (text != null) {
                    entry.addProperty("text", text);
                } else {
                    entry.addProperty("base64", Base64.getEncoder().encodeToString(bytes));
                }
                out.add(relative, entry);
            }
        }
        return out;
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notText) {
            return null;
        }
    }

    /**
     * Puts back what the backup holds, for every owner in it or only {@code project}. Storage values of a restored owner are
     * replaced as a whole (so it is as it was at the backup); files are written again; machines go back where the same block
     * stands. Nothing outside the data folders is ever written: names are checked like {@code files.*} checks them.
     */
    public static Restored restore(JsonObject backup, Map<String, JsonElement> storage, Path dataRoot, MachineSink machines,
                                   String project) throws IOException {
        if (!backup.has("craftpy_backup") || backup.get("craftpy_backup").getAsInt() > FORMAT) {
            throw new IOException("This is not a CraftPy backup, or one of a newer CraftPy");
        }
        List<String> skipped = new ArrayList<>();
        int values = 0, files = 0, restoredMachines = 0;
        JsonObject saved = backup.has("storage") ? backup.getAsJsonObject("storage") : new JsonObject();
        for (Map.Entry<String, JsonElement> owner : saved.entrySet()) {
            if (!wanted(owner.getKey(), project)) continue;
            String prefix = owner.getKey() + "/";
            storage.keySet().removeIf(key -> key.startsWith(prefix));
            for (Map.Entry<String, JsonElement> value : owner.getValue().getAsJsonObject().entrySet()) {
                storage.put(prefix + value.getKey(), value.getValue().deepCopy());
                values++;
            }
        }
        JsonObject savedFiles = backup.has("files") ? backup.getAsJsonObject("files") : new JsonObject();
        for (Map.Entry<String, JsonElement> owner : savedFiles.entrySet()) {
            if (!wanted(owner.getKey(), project)) continue;
            String folderName = folderOf(owner.getKey());
            if (!OWNER_FOLDER.matcher(folderName).matches()) {
                skipped.add("files of '" + owner.getKey() + "': not an owner name");
                continue;
            }
            Path folder = dataRoot.resolve(folderName);
            for (Map.Entry<String, JsonElement> file : owner.getValue().getAsJsonObject().entrySet()) {
                String name;
                try {
                    name = FileStore.cleanName(file.getKey());
                } catch (IllegalArgumentException bad) {
                    skipped.add(owner.getKey() + "/" + file.getKey() + ": " + bad.getMessage());
                    continue;
                }
                Path target = folder.resolve(name).normalize();
                if (!target.startsWith(folder.normalize())) {
                    skipped.add(owner.getKey() + "/" + file.getKey() + ": leads out of its folder");
                    continue;
                }
                JsonObject entry = file.getValue().getAsJsonObject();
                byte[] bytes = entry.has("text") ? entry.get("text").getAsString().getBytes(StandardCharsets.UTF_8)
                        : Base64.getDecoder().decode(entry.get("base64").getAsString());
                Files.createDirectories(target.getParent());
                if (Files.isSymbolicLink(target)) {
                    skipped.add(owner.getKey() + "/" + file.getKey() + ": a link is there");
                    continue;
                }
                net.neoforged.neoforge.common.IOUtilities.atomicWrite(target, out -> out.write(bytes));
                files++;
            }
        }
        JsonArray savedMachines = backup.has("machines") ? backup.getAsJsonArray("machines") : new JsonArray();
        for (JsonElement element : savedMachines) {
            JsonObject entry = element.getAsJsonObject();
            Machine machine = new Machine(entry.get("dimension").getAsString(), entry.get("x").getAsInt(), entry.get("y").getAsInt(),
                    entry.get("z").getAsInt(), entry.get("block").getAsString(),
                    entry.has("owner") ? entry.get("owner").getAsString() : "", entry.get("state").getAsString());
            if (!wanted(machine.owner(), project)) continue;
            if (machines.restore(machine)) {
                restoredMachines++;
            } else {
                skipped.add("machine at " + machine.x() + " " + machine.y() + " " + machine.z() + " in " + machine.dimension()
                        + ": not loaded, or no longer " + machine.block());
            }
        }
        return new Restored(values, files, restoredMachines, skipped);
    }

    /** A backup file of the folder by its name; refuses names that are not a plain {@code .json} file name. */
    public static Path resolve(Path folder, String name) throws IOException {
        String clean = name.endsWith(".json") ? name : name + ".json";
        if (!NAME.matcher(clean).matches() || clean.contains("..")) {
            throw new IOException("'" + name + "' is not the name of a backup (see /craftpy backup list)");
        }
        Path file = folder.resolve(clean).normalize();
        if (!file.getParent().equals(folder.normalize())) {
            throw new IOException("A backup is a file of " + FOLDER + "/");
        }
        return file;
    }

    /** The backups of a world folder, newest first. */
    public static List<String> list(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> NAME.matcher(n).matches())
                    .sorted(java.util.Comparator.reverseOrder()).toList();
        }
    }
}
