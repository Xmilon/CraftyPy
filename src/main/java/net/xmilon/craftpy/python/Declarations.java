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
    private final String error;
    private final int errorLine;

    private Declarations(String error, int errorLine) {
        this.error = error;
        this.errorLine = errorLine;
    }

    public static Declarations parse(JsonObject result, String fileName) {
        if (result == null) {
            return new Declarations("Worker returned no result", -1);
        }
        if (result.has("error")) {
            JsonElement raw = result.get("error");
            String message = raw.isJsonPrimitive() ? raw.getAsString() : "execution failed";
            int line = result.get("line") == null ? -1 : result.get("line").getAsInt();
            return new Declarations(message, line);
        }
        JsonElement rawDeclarations = result.get("declarations");
        if (rawDeclarations == null || !rawDeclarations.isJsonObject()) {
            String message = rawDeclarations == null ? "no declarations returned" : "bad declarations payload";
            return new Declarations(message, -1);
        }
        Declarations declarations = new Declarations(null, -1);
        JsonObject raw = rawDeclarations.getAsJsonObject();

        addContents(declarations.contents, raw.getAsJsonArray("blocks"), "block");
        addContents(declarations.contents, raw.getAsJsonArray("items"), "item");
        addContents(declarations.contents, raw.getAsJsonArray("effects"), "effect");

        JsonArray handlers = raw.getAsJsonArray("handlers");
        for (JsonElement element : handlers) {
            JsonObject entry = element.getAsJsonObject();
            Integer every = entry.get("every") == null ? null : entry.get("every").getAsInt();
            declarations.handlers.add(new Handler(entry.get("event").getAsString(), every, entry));
        }

        addDatapack(declarations.datapack, raw.getAsJsonArray("recipes"), "recipe");
        addDatapack(declarations.datapack, raw.getAsJsonArray("loot_tables"), "loot_table");
        addDatapack(declarations.datapack, raw.getAsJsonArray("tags"), "tag");

        if (raw.has("lang")) {
            declarations.datapack.add(new DatapackEntry("lang", fileName, raw.getAsJsonObject("lang")));
        }

        JsonArray log = result.getAsJsonArray("log");
        for (JsonElement element : log) {
            if (element.isJsonPrimitive()) {
                declarations.log.add(element.getAsString());
            }
        }
        return declarations;
    }

    private static void addContents(List<Content> target, JsonArray array, String kind) {
        for (JsonElement element : array) {
            JsonObject entry = element.getAsJsonObject();
            String id = entry.get("id").getAsString();
            JsonElement definition = entry.get("definition");
            target.add(new Content(kind, id, definition == null ? new JsonObject() : definition.getAsJsonObject()));
        }
    }

    private static void addDatapack(List<DatapackEntry> target, JsonArray array, String kind) {
        for (JsonElement element : array) {
            JsonObject entry = element.getAsJsonObject();
            String id = entry.get("id") == null ? "" : entry.get("id").getAsString();
            target.add(new DatapackEntry(kind, id, entry));
        }
    }

    public List<Content> contents() {
        return contents;
    }

    public List<Handler> handlers() {
        return handlers;
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