package net.xmilon.craftpy.client.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * Design tokens for the UI toolkit.
 */
public final class Theme {
    public static final String BG = "bg";
    public static final String PANEL = "panel";
    public static final String PANEL_ALT = "panel_alt";
    public static final String ACCENT = "accent";
    public static final String TEXT = "text";
    public static final String MUTED = "muted";
    public static final String BORDER = "border";
    public static final String SELECTED = "selected";
    public static final String ERROR = "error";
    public static final String SUCCESS = "success";
    public static final String WARNING = "warning";

    private static Theme current = defaultDark();

    private final Map<String, Integer> colors = new HashMap<>();
    private final Map<String, Integer> dimensions = new HashMap<>();

    public static Theme current() {
        return current;
    }

    public static void setCurrent(Theme theme) {
        current = theme;
    }

    public int color(String token) {
        return colors.getOrDefault(token, 0xFFFF00FF);
    }

    public int dim(String token) {
        return dimensions.getOrDefault(token, 0);
    }

    public static Theme defaultDark() {
        Theme t = new Theme();
        t.colors.put(BG, 0xFF10141A);
        t.colors.put(PANEL, 0xFF1B222C);
        t.colors.put(PANEL_ALT, 0xFF232C38);
        t.colors.put(ACCENT, 0xFF4FC3F7);
        t.colors.put(TEXT, 0xFFD7E1EA);
        t.colors.put(MUTED, 0xFF7D8B9A);
        t.colors.put(BORDER, 0xFF2E3A48);
        t.colors.put(SELECTED, 0xFF2C5B7A);
        t.colors.put(ERROR, 0xFFFF6B6B);
        t.colors.put(SUCCESS, 0xFF81C784);
        t.colors.put(WARNING, 0xFFFFB86C);

        t.dimensions.put("padding", 4);
        t.dimensions.put("spacing", 4);
        t.dimensions.put("radius", 0);
        return t;
    }
}
