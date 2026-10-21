package net.xmilon.craftpy.keys;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.xmilon.craftpy.client.KeyCombo;
import net.xmilon.craftpy.util.Json;

/** Preferences outlive their module. Server identity is part of a remote action's key. */
public final class BindingStore {
    public record Id(String scope, String owner, String action) { }
    public record Entry(Id id, KeyAction action, String assigned, boolean active) { }
    private final Path file;
    private final Map<Id, Entry> entries = new LinkedHashMap<>();

    public BindingStore(Path file) throws IOException {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            var rows = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            for (var raw : rows) {
                JsonObject o = raw.getAsJsonObject();
                KeyAction action = KeyAction.parse(single(o.getAsJsonObject("description"))).getFirst();
                Id id = new Id(o.get("scope").getAsString(), o.get("owner").getAsString(), action.id());
                entries.put(id, new Entry(id, action, KeyCombo.normalize(o.get("assigned").getAsString()), false));
            }
        } catch (RuntimeException bad) { throw new IOException("Invalid module key preferences: " + file, bad); }
    }

    private static com.google.gson.JsonArray single(JsonObject object) {
        var array = new com.google.gson.JsonArray(); array.add(object); return array;
    }

    public synchronized void replace(String scope, String owner, List<KeyAction> actions) throws IOException {
        deactivate(scope, owner);
        for (KeyAction action : actions) {
            Id id = new Id(scope, owner, action.id());
            Entry old = entries.get(id);
            entries.put(id, new Entry(id, action, old == null ? action.combo() : old.assigned(), true));
        }
        save();
    }

    public synchronized void deactivate(String scope, String owner) {
        entries.replaceAll((id, entry) -> id.scope().equals(scope) && (owner == null || id.owner().equals(owner))
                ? new Entry(id, entry.action(), entry.assigned(), false) : entry);
    }

    public synchronized void assign(Id id, String combo) throws IOException {
        Entry old = entries.get(id);
        if (old == null) throw new IllegalArgumentException("Unknown key action");
        String normalized = KeyCombo.normalize(combo);
        entries.put(id, new Entry(id, old.action(), normalized, old.active()));
        try { save(); } catch (IOException error) { entries.put(id, old); throw error; }
    }

    /** Drops one saved preference (and with it the remap the player made). Active actions come back with their defaults. */
    public synchronized void forget(Id id) throws IOException {
        if (entries.remove(id) != null) save();
    }

    /** Drops every preference whose module is not running now: old modules, other servers, deleted projects. */
    public synchronized int forgetInactive() throws IOException {
        int before = entries.size();
        entries.values().removeIf(entry -> !entry.active());
        if (entries.size() != before) save();
        return before - entries.size();
    }

    public synchronized List<Entry> entries() { return List.copyOf(entries.values()); }

    public synchronized List<Entry> matching(String combo) {
        if (combo == null || combo.isEmpty()) return List.of();
        return entries.values().stream().filter(e -> e.active() && combo.equals(e.assigned())).toList();
    }

    private void save() throws IOException {
        var rows = new com.google.gson.JsonArray();
        for (Entry e : entries.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("scope", e.id().scope()); o.addProperty("owner", e.id().owner());
            o.add("description", e.action().json()); o.addProperty("assigned", e.assigned()); rows.add(o);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, Json.GSON.toJson(rows));
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}
