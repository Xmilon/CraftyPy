package net.xmilon.craftpy.client;

import com.google.gson.JsonObject;
import java.nio.file.*;
import net.xmilon.craftpy.util.Json;

/** Acknowledging remote behavior is separate from granting a local module any permission. */
public final class ModuleAcknowledgments {
    private final Path file;
    private JsonObject cached;
    public ModuleAcknowledgments(Path file) { this.file = file; }
    private JsonObject read() {
        if (cached != null) return cached;
        try { cached = Files.exists(file) ? com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject(); }
        catch (java.io.IOException | RuntimeException invalid) { cached = new JsonObject(); }
        return cached;
    }
    public boolean remembered(String server, String id, String hash) {
        if (!valid(server, id, hash)) return false;
        var all = read(); var entry = all.get(server);
        return entry instanceof JsonObject modules && modules.has(id) && modules.get(id).isJsonPrimitive() && hash.equals(modules.get(id).getAsString());
    }
    public void set(String server, String id, String hash, boolean remember) {
        if (!valid(server, id, hash)) return;
        var all = read(); JsonObject modules = all.get(server) instanceof JsonObject object ? object : new JsonObject();
        if (remember) modules.addProperty(id, hash); else modules.remove(id);
        all.add(server, modules);
        try { Json.write(file, all); } catch (java.io.IOException error) { net.xmilon.craftpy.CraftPyLog.LOGGER.warn("Could not save module acknowledgment", error); }
    }
    private static boolean valid(String server, String id, String hash) { return server != null && !server.isBlank() && server.length() <= 512 && id != null && id.matches("[a-z][a-z0-9_]{0,63}") && hash != null && hash.matches("[0-9a-f]{64}"); }
}
