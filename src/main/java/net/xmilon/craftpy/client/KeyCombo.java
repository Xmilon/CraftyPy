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
        if (key == 256) return null; // Escape remains the screen/back key.
        String base = NAMES.get(key);
        if (base == null && key >= 0 && key <= 512) base = "keycode_" + key;
        if (base == null) {
            return null;
        }
        return ((modifiers & 2) != 0 ? "ctrl+" : "") + ((modifiers & 1) != 0 ? "shift+" : "")
                + ((modifiers & 4) != 0 ? "alt+" : "") + base;
    }

    public static int knownKeys() {
        return NAMES.size();
    }

    /** Canonical keyboard assignment; an empty string means unbound. */
    public static String normalize(String combo) {
        if (combo == null || combo.isBlank()) return "";
        int modifiers = 0;
        String base = null;
        for (String part : combo.toLowerCase(java.util.Locale.ROOT).replace(" ", "").split("\\+", -1)) {
            int bit = switch (part) { case "ctrl", "control" -> 2; case "shift" -> 1; case "alt" -> 4; default -> 0; };
            if (bit != 0) {
                if ((modifiers & bit) != 0) throw new IllegalArgumentException("Repeated modifier: " + part);
                modifiers |= bit;
            } else if (base == null && (NAMES.containsValue(part) || part.matches("mouse[1-8]|keycode_[0-9]{1,3}"))) base = part;
            else throw new IllegalArgumentException("Unknown or extra key: " + part);
        }
        if (base == null) throw new IllegalArgumentException("Choose a key after the modifiers");
        return ((modifiers & 2) != 0 ? "ctrl+" : "") + ((modifiers & 1) != 0 ? "shift+" : "")
                + ((modifiers & 4) != 0 ? "alt+" : "") + base;
    }

    public static int code(String combo) {
        String base = combo.substring(combo.lastIndexOf('+') + 1);
        if (base.startsWith("keycode_")) return Integer.parseInt(base.substring(8));
        return NAMES.entrySet().stream().filter(e -> e.getValue().equals(base)).mapToInt(Map.Entry::getKey).findFirst().orElse(-1);
    }

    public static int modifiers(String combo) {
        return (combo.contains("ctrl+") ? 2 : 0) | (combo.contains("shift+") ? 1 : 0) | (combo.contains("alt+") ? 4 : 0);
    }

    public static String mouse(int button, int modifiers) {
        if (button < 0 || button > 7) return null;
        return normalize(((modifiers & 2) != 0 ? "ctrl+" : "") + ((modifiers & 1) != 0 ? "shift+" : "")
                + ((modifiers & 4) != 0 ? "alt+" : "") + "mouse" + (button + 1));
    }
}
