package net.xmilon.craftpy.client;

import java.util.Map;
import java.util.TreeMap;

/**
 * The names of keys as client-side Python writes them ({@code ctrl+shift+k}, {@code f6}, {@code space}), from GLFW key
 * codes. The list must stay the same as {@code _KEY_NAMES} in worker.py.
 */
public final class KeyCombo {
    private static final Map<Integer, String> NAMES = new TreeMap<>();

    static {
        for (int key = 65; key <= 90; key++) {
            NAMES.put(key, String.valueOf((char) (key + 32))); // A-Z
        }
        for (int key = 48; key <= 57; key++) {
            NAMES.put(key, String.valueOf((char) key)); // 0-9
        }
        for (int n = 1; n <= 12; n++) {
            NAMES.put(289 + n, "f" + n);
        }
        NAMES.putAll(Map.ofEntries(
                Map.entry(32, "space"), Map.entry(257, "enter"), Map.entry(258, "tab"), Map.entry(259, "backspace"),
                Map.entry(260, "insert"), Map.entry(261, "delete"), Map.entry(262, "right"), Map.entry(263, "left"),
                Map.entry(264, "down"), Map.entry(265, "up"), Map.entry(266, "pageup"), Map.entry(267, "pagedown"),
                Map.entry(268, "home"), Map.entry(269, "end"), Map.entry(45, "minus"), Map.entry(61, "equals"),
                Map.entry(44, "comma"), Map.entry(46, "period"), Map.entry(47, "slash"), Map.entry(59, "semicolon"),
                Map.entry(96, "grave"), Map.entry(91, "bracket_left"), Map.entry(93, "bracket_right")));
    }

    private KeyCombo() {
    }

    /** @param modifiers GLFW bits: shift 1, ctrl 2, alt 4 */
    public static String name(int key, int modifiers) {
        String base = NAMES.get(key);
        if (base == null) {
            return null;
        }
        return ((modifiers & 2) != 0 ? "ctrl+" : "") + ((modifiers & 1) != 0 ? "shift+" : "")
                + ((modifiers & 4) != 0 ? "alt+" : "") + base;
    }

    public static int knownKeys() {
        return NAMES.size();
    }
}
