package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the cursor has been (24): jumps to bookmarks, problems, search results and symbols push the
 * place you left, so Back/Forward and the "recent locations" list can return to it. Lines are 0-based.
 */
public final class JumpHistory {
    public record Location(String path, int line) {
    }

    public static final int MAX = 40;

    private final List<Location> stack = new ArrayList<>();
    /** Index of the current position; == size() means "at the live cursor, nothing to go forward to". */
    private int index;

    /** Remembers {@code from} before jumping away. Jumps within 2 lines of the last entry are ignored. */
    public void push(Location from) {
        if (from == null || from.path() == null) {
            return;
        }
        while (stack.size() > index) {
            stack.remove(stack.size() - 1); // a new jump discards the "forward" part
        }
        if (!stack.isEmpty()) {
            Location last = stack.get(stack.size() - 1);
            if (last.path().equals(from.path()) && Math.abs(last.line() - from.line()) <= 2) {
                index = stack.size();
                return;
            }
        }
        stack.add(from);
        while (stack.size() > MAX) {
            stack.remove(0);
        }
        index = stack.size();
    }

    public boolean canBack() {
        return index > 0;
    }

    public boolean canForward() {
        return index < stack.size() - 1;
    }

    /** Back: returns the previous place; {@code current} is saved so Forward can return to it. */
    public Location back(Location current) {
        if (!canBack()) {
            return null;
        }
        if (index == stack.size() && current != null && current.path() != null) {
            stack.add(current);
        }
        index = Math.min(index, stack.size() - 1) - 1;
        return stack.get(index);
    }

    public Location forward() {
        if (!canForward()) {
            return null;
        }
        index++;
        return stack.get(index);
    }

    /** Newest first. */
    public List<Location> recent() {
        List<Location> out = new ArrayList<>(stack);
        java.util.Collections.reverse(out);
        return out;
    }

    public void clear() {
        stack.clear();
        index = 0;
    }
}
