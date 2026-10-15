package net.xmilon.craftpy.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.xmilon.craftpy.analysis.IdKinds;
import net.xmilon.craftpy.analysis.StringContext;
import net.xmilon.craftpy.catalog.CatalogView;

/**
 * Completion inside string literals: ids of the registries the installed mods fill (items, blocks, effects, attributes,
 * sounds, recipe types...), classes for the Java bridge, tags after a {@code #}. What is inside the string decides the kind
 * (see {@link IdKinds}); everything outside a string is left to the Python worker. Pure: it only reads a
 * {@link CatalogView}.
 */
public final class CompletionEngine {
    private static final int LIMIT = 120;

    private CompletionEngine() {
    }

    /**
     * The reply for a cursor at {@code line}/{@code column} of {@code code}, or null when the cursor is not inside a plain
     * string (the worker answers then). Inside a string whose kind is unknown the reply is empty, so no list of Python names
     * pops up in the middle of text.
     *
     * @param own ids the project itself declares by kind ({@code item}, {@code block}), offered first
     */
    public static JsonObject complete(String code, int line, int column, CatalogView view, Map<String, List<String>> own) {
        int offset = offsetOf(code, line, column);
        Optional<StringContext.Call> context = offset < 0 ? Optional.empty() : StringContext.at(code, offset);
        if (context.isEmpty()) {
            return null;
        }
        StringContext.Call call = context.get();
        String typed = call.typed();
        String kind = IdKinds.kindFor(call);
        List<String[]> rows = new ArrayList<>(); // id, detail
        if (kind != null && kind.equals(IdKinds.CLASS)) {
            classes(view, typed, rows);
        } else if (kind != null && kind.equals(IdKinds.EVENT)) {
            for (String event : IdKinds.EVENTS) {
                if (event.startsWith(typed)) {
                    rows.add(new String[]{event, "event"});
                }
            }
        } else if (kind != null) {
            boolean tag = typed.startsWith("#");
            String query = tag ? typed.substring(1) : typed;
            String registry = tag ? "tag:" + kind : kind;
            if (!tag) {
                for (String id : own.getOrDefault(kind, List.of())) {
                    if (matches(id, query)) {
                        rows.add(new String[]{id, kind + " - this project"});
                    }
                }
            }
            for (CatalogView.Entry entry : view.search(registry, query, LIMIT)) {
                rows.add(new String[]{tag ? "#" + entry.id() : entry.id(), (tag ? "tag " : "") + kind + " - " + entry.mod()});
            }
        }
        JsonArray items = new JsonArray();
        JsonArray names = new JsonArray();
        for (String[] row : rows) {
            if (items.size() >= LIMIT) {
                break;
            }
            JsonObject item = new JsonObject();
            item.addProperty("n", row[0]);
            item.addProperty("k", "id");
            item.addProperty("s", "");
            item.addProperty("d", row[1]);
            items.add(item);
            names.add(row[0]);
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("prefix", typed);
        reply.add("items", items);
        reply.add("suggestions", names);
        return reply;
    }

    private static void classes(CatalogView view, String typed, List<String[]> rows) {
        String query = typed.toLowerCase(Locale.ROOT);
        List<String[]> contains = new ArrayList<>();
        for (String name : view.classes()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(query)) {
                rows.add(new String[]{name, "class"});
            } else if (lower.contains(query) && contains.size() < LIMIT) {
                contains.add(new String[]{name, "class"});
            }
            if (rows.size() >= LIMIT) {
                return;
            }
        }
        rows.addAll(contains);
    }

    private static boolean matches(String id, String query) {
        String lower = id.toLowerCase(Locale.ROOT);
        String wanted = query.toLowerCase(Locale.ROOT);
        return wanted.isEmpty() || lower.startsWith(wanted) || lower.substring(lower.indexOf(':') + 1).startsWith(wanted);
    }

    /** Character offset of a 0-based line and column, or -1 when the line does not exist. */
    static int offsetOf(String code, int line, int column) {
        int start = 0;
        for (int i = 0; i < line; i++) {
            int next = code.indexOf('\n', start);
            if (next < 0) {
                return -1;
            }
            start = next + 1;
        }
        return Math.min(code.length(), start + Math.max(0, column));
    }
}
