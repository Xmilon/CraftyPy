package net.xmilon.craftpy.lifecycle;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Event tracer (44): while switched on it remembers the last events the game fired, whether any Python
 * handler wanted them, and how long the handlers took. This is what answers "why does my handler never
 * run?" - the event either never appears here, or appears with {@code handled = false}.
 */
public final class EventTrace {
    public static final int CAPACITY = 300;

    /** {@code handlers}/{@code ms} are -1 until the worker answered. */
    public record Entry(long seq, long timeMs, String event, String summary, boolean handled, int handlers, double ms) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static volatile boolean enabled;
    private static long sequence;

    private EventTrace() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static synchronized void setEnabled(boolean value) {
        enabled = value;
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }

    /** Records an event; returns its sequence number, or -1 while tracing is off. */
    public static synchronized long begin(String event, JsonObject context, boolean handled) {
        if (!enabled) {
            return -1;
        }
        long seq = ++sequence;
        ENTRIES.add(new Entry(seq, System.currentTimeMillis(), event, summary(context), handled, handled ? -1 : 0,
                handled ? -1 : 0));
        while (ENTRIES.size() > CAPACITY) {
            ENTRIES.remove(0);
        }
        return seq;
    }

    public static synchronized void finish(long seq, int handlers, double ms) {
        for (int i = ENTRIES.size() - 1; i >= 0 && seq >= 0; i--) {
            Entry entry = ENTRIES.get(i);
            if (entry.seq() == seq) {
                ENTRIES.set(i, new Entry(seq, entry.timeMs(), entry.event(), entry.summary(), entry.handled(),
                        handlers, ms));
                return;
            }
        }
    }

    public static synchronized List<Entry> since(long afterSeq) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.seq() > afterSeq || entry.handlers() < 0) { // unfinished rows are re-sent until done
                out.add(entry);
            }
        }
        return out;
    }

    /** "player=Dev block=ruby_rush:ruby_ore" - the identifying bits of an event context. */
    static String summary(JsonObject context) {
        if (context == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        add(out, "player", name(context.get("player"), "name"));
        add(out, "block", name(context.get("block"), "id"));
        add(out, "item", name(context.get("item"), "id"));
        add(out, "entity", name(context.get("entity"), "type"));
        add(out, "killer", name(context.get("killer"), "name"));
        if (context.has("message") && context.get("message").isJsonPrimitive()) {
            add(out, "message", context.get("message").getAsString());
        }
        return out.toString();
    }

    private static String name(JsonElement element, String key) {
        if (element != null && element.isJsonObject() && element.getAsJsonObject().has(key)
                && element.getAsJsonObject().get(key).isJsonPrimitive()) {
            return element.getAsJsonObject().get(key).getAsString();
        }
        return null;
    }

    private static void add(StringBuilder out, String key, String value) {
        if (value != null && !value.isEmpty()) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(key).append('=').append(value.length() > 40 ? value.substring(0, 40) + "..." : value);
        }
    }
}
