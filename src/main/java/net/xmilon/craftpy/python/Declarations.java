package net.xmilon.craftpy.python;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Result of running a project's main.py on the worker. Holds the declarative
 * payload: content declarations ({@code @block}, {@code @item}, ...), datapack
 * contributions (recipes, loot tables, tags) and captured context log lines.
 */
public final class Declarations {
    public record Content(String kind, String id, JsonObject definition) {
    }

    public record Handler(String event, Integer every, JsonObject definition) {
    }

    public record CommandDef(String name, JsonObject params) {
    }

    public record DatapackEntry(String kind, String id, JsonElement payload) {
    }

    private final List<Content> contents = new ArrayList<>();
    private final List<Handler> handlers = new ArrayList<>();
    private final List<CommandDef> commands = new ArrayList<>();
    private final List<DatapackEntry> datapack = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final boolean requiresRestart;
    private final String error;
    private final int errorLine;

    private Declarations(String error, int errorLine) {
        this.error = error;
        this.errorLine = errorLine;
        this.requiresRestart = false;
    }

    private Declarations(String error, int errorLine, boolean requiresRestart) {
        this.error = error;
        this.errorLine = errorLine;
        this.requiresRestart = requiresRestart;
    }

    public static Declarations parse(JsonObject result, String fileName) {
        if (result == null) {
            return new Declarations("Worker returned no result", -1);
        }
        if (result.has("error")) {
            JsonElement raw = result.get("error");
            String message = result.has("message") && result.get("message").isJsonPrimitive()
                    ? result.get("message").getAsString()
                    : raw.isJsonPrimitive() ? raw.getAsString() : "execution failed";
            // An error inside an imported file names that file (the line number belongs to it).
            if (result.has("file") && result.get("file").isJsonPrimitive()
                    && !result.get("file").getAsString().equals(fileName)) {
                message = result.get("file").getAsString() + ": " + message;
            }
            int line = result.get("line") == null ? -1 : result.get("line").getAsInt();
            return new Declarations(message, line);
        }
        JsonElement rawDeclarations = result.get("declarations");
        if (rawDeclarations == null || !rawDeclarations.isJsonObject()) {
            String message = rawDeclarations == null ? "no declarations returned" : "bad declarations payload";
            return new Declarations(message, -1);
        }
        JsonObject raw = rawDeclarations.getAsJsonObject();
        boolean requiresRestart = false;
        if (raw.has("meta") && raw.get("meta").isJsonObject()) {
            JsonObject meta = raw.getAsJsonObject("meta");
            if (meta.has("requiresRestart") && meta.get("requiresRestart").isJsonPrimitive()) {
                requiresRestart = meta.get("requiresRestart").getAsBoolean();
            }
            if (!requiresRestart && meta.has("core") && meta.get("core").isJsonPrimitive()) {
                requiresRestart = meta.get("core").getAsBoolean();
            }
        }
        Declarations declarations = new Declarations(null, -1, requiresRestart);

        addContents(declarations.contents, raw.getAsJsonArray("blocks"), "block");
        addContents(declarations.contents, raw.getAsJsonArray("items"), "item");
        addContents(declarations.contents, raw.getAsJsonArray("entities"), "entity");
        addContents(declarations.contents, raw.getAsJsonArray("effects"), "effect");

        JsonArray handlers = raw.getAsJsonArray("handlers");
        if (handlers != null) {
            for (JsonElement element : handlers) {
                JsonObject entry = element.getAsJsonObject();
                // @on("event") sends "every": null - JsonNull must read as "no interval", not crash the module.
                JsonElement everyValue = entry.get("every");
                Integer every = everyValue == null || everyValue.isJsonNull() ? null : everyValue.getAsInt();
                declarations.handlers.add(new Handler(entry.get("event").getAsString(), every, entry));
            }
        }

        JsonArray commands = raw.getAsJsonArray("commands");
        if (commands != null) {
            for (JsonElement element : commands) {
                JsonObject entry = element.getAsJsonObject();
                String name = entry.get("name") == null || entry.get("name").isJsonNull() ? "" : entry.get("name").getAsString();
                declarations.commands.add(new CommandDef(name, entry));
            }
        }

        addDatapack(declarations.datapack, raw.getAsJsonArray("recipes"), "recipe");
        addDatapack(declarations.datapack, raw.getAsJsonArray("loot_tables"), "loot_table");
        addDatapack(declarations.datapack, raw.getAsJsonArray("tags"), "tag");
        addDatapack(declarations.datapack, raw.getAsJsonArray("advancements"), "advancement");
        addDatapack(declarations.datapack, raw.getAsJsonArray("sounds"), "sound");
        addDatapack(declarations.datapack, raw.getAsJsonArray("particles"), "particle");
        addDatapack(declarations.datapack, raw.getAsJsonArray("enchantments"), "enchantment");
        addDatapack(declarations.datapack, raw.getAsJsonArray("raw"), "raw");

        if (raw.has("lang")) {
            declarations.datapack.add(new DatapackEntry("lang", fileName, raw.getAsJsonObject("lang")));
        }

        JsonArray log = result.has("log") && result.get("log").isJsonArray() ? result.getAsJsonArray("log") : new JsonArray();
        for (JsonElement element : log) {
            if (element.isJsonPrimitive()) {
                declarations.log.add(element.getAsString());
            }
        }
        return declarations;
    }

    private static void addContents(List<Content> target, JsonArray array, String kind) {
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            JsonObject entry = element.getAsJsonObject();
            String id = entry.get("id").getAsString();
            JsonElement definition = entry.get("definition");
            target.add(new Content(kind, id, definition == null || !definition.isJsonObject()
                    ? new JsonObject() : definition.getAsJsonObject()));
        }
    }

    private static void addDatapack(List<DatapackEntry> target, JsonArray array, String kind) {
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            JsonObject entry = element.getAsJsonObject();
            String id = entry.get("id") == null || entry.get("id").isJsonNull() ? "" : entry.get("id").getAsString();
            target.add(new DatapackEntry(kind, id, entry));
        }
    }

    public List<Content> contents() {
        return contents;
    }

    public List<Handler> handlers() {
        return handlers;
    }

    public List<CommandDef> commands() {
        return commands;
    }

    public List<DatapackEntry> datapack() {
        return datapack;
    }

    public List<String> log() {
        return log;
    }

    public String error() {
        return error;
    }

    public boolean requiresRestart() {
        return requiresRestart;
    }

    public int errorLine() {
        return errorLine;
    }

    public boolean failed() {
        return error != null;
    }

    public static Declarations error(String message) {
        return new Declarations(message, -1);
    }
}