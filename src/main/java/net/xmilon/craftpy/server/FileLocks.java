package net.xmilon.craftpy.server;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared sessions: several players edit the projects of one server. The player who saved a file last holds it for
 * {@link #HOLD_MS}; a save by someone else meanwhile is refused (their text stays in their editor, nothing is lost) and
 * opening the file says who is editing it. Leaving the server releases a player's files.
 */
public final class FileLocks {
    public static final long HOLD_MS = 5 * 60_000L;

    public record Lock(String key, String name, long at) {
        String describe(long now) {
            long minutes = Math.max(0, (now - at) / 60_000L);
            return name + " is editing this file (saved " + (minutes == 0 ? "just now" : minutes + " min ago") + ")";
        }
    }

    private static final Map<String, Lock> LOCKS = new HashMap<>();

    private FileLocks() {
    }

    /** Null when {@code key} may write the file (it holds it from now on), otherwise the other player's lock. */
    public static synchronized Lock claim(String project, String path, String key, String name, long now) {
        Lock held = holder(project, path, key, now);
        if (held != null) {
            return held;
        }
        LOCKS.put(project + "/" + path, new Lock(key, name, now));
        return null;
    }

    /** The lock of someone other than {@code key} that is still running, or null. */
    public static synchronized Lock holder(String project, String path, String key, long now) {
        Lock held = LOCKS.get(project + "/" + path);
        return held != null && !held.key().equals(key) && now - held.at() < HOLD_MS ? held : null;
    }

    public static synchronized void release(String key) {
        LOCKS.values().removeIf(lock -> lock.key().equals(key));
    }

    static synchronized void clear() {
        LOCKS.clear();
    }
}
