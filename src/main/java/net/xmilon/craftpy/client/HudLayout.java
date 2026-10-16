package net.xmilon.craftpy.client;

import java.util.Locale;
import java.util.Map;

/** Where a HUD element goes on the screen and what color it is. Pure maths and names: no game classes. */
public final class HudLayout {
    private static final Map<String, Integer> COLORS = Map.ofEntries(
            Map.entry("black", 0x000000), Map.entry("dark_blue", 0x0000AA), Map.entry("dark_green", 0x00AA00),
            Map.entry("dark_aqua", 0x00AAAA), Map.entry("dark_red", 0xAA0000), Map.entry("dark_purple", 0xAA00AA),
            Map.entry("gold", 0xFFAA00), Map.entry("gray", 0xAAAAAA), Map.entry("dark_gray", 0x555555),
            Map.entry("blue", 0x5555FF), Map.entry("green", 0x55FF55), Map.entry("aqua", 0x55FFFF),
            Map.entry("red", 0xFF5555), Map.entry("light_purple", 0xFF55FF), Map.entry("yellow", 0xFFFF55),
            Map.entry("white", 0xFFFFFF));

    private HudLayout() {
    }

    public static boolean isAnchor(String anchor) {
        return anchor != null && (anchor.equals("center") || anchor.matches("(top|center|bottom)_(left|center|right)"));
    }

    /**
     * Top-left corner of an element of {@code w} x {@code h} (already scaled) next to {@code anchor}, moved by {@code x}
     * and {@code y}. Anchors on the right and bottom put the element's own right / bottom edge on the screen edge.
     */
    public static int[] position(String anchor, double x, double y, int w, int h, int screenW, int screenH) {
        String vertical = anchor.equals("center") ? "center" : anchor.substring(0, anchor.indexOf('_'));
        String horizontal = anchor.equals("center") ? "center" : anchor.substring(anchor.indexOf('_') + 1);
        int left = switch (horizontal) {
            case "left" -> 0;
            case "right" -> screenW - w;
            default -> (screenW - w) / 2;
        };
        int top = switch (vertical) {
            case "top" -> 0;
            case "bottom" -> screenH - h;
            default -> (screenH - h) / 2;
        };
        return new int[]{(int) Math.round(left + x), (int) Math.round(top + y)};
    }

    /** {@code gold} or {@code #ff8800} as 0xRRGGBB; {@code fallback} when it is neither. */
    public static int color(String name, int fallback) {
        if (name == null) {
            return fallback;
        }
        if (name.matches("#[0-9a-fA-F]{6}")) {
            return Integer.parseInt(name.substring(1), 16);
        }
        return COLORS.getOrDefault(name.toLowerCase(Locale.ROOT), fallback);
    }
}
