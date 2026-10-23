package net.xmilon.craftpy.menu;

/**
 * The look of a Minecraft-style window, drawn with plain rectangles: the grey container panel, slot boxes, bars, the progress
 * arrow and the flame. The game screen and the UI maker's preview draw through the same {@link Surface}, so what you design is
 * what the player sees. No textures are needed (nothing of Mojang's is copied).
 */
public final class LegacyShapes {
    /** Whatever can fill a rectangle with a color (ARGB): the game's GuiGraphics, or a Modern UI canvas. */
    public interface Surface {
        void fill(int x1, int y1, int x2, int y2, int argb);
    }

    public static final int PANEL = 0xFFC6C6C6, WHITE = 0xFFFFFFFF, SHADOW = 0xFF555555, BLACK = 0xFF000000, SLOT = 0xFF8B8B8B,
            SLOT_EDGE = 0xFF373737, WELL = 0xFF1A1A1A, TEXT = 0xFF404040;

    private LegacyShapes() {
    }

    public static void panel(Surface s, int x, int y, int w, int h) {
        s.fill(x, y, x + w, y + h, BLACK);
        s.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
        s.fill(x + 1, y + 1, x + w - 2, y + 3, WHITE);
        s.fill(x + 1, y + 1, x + 3, y + h - 2, WHITE);
        s.fill(x + 2, y + h - 3, x + w - 1, y + h - 1, SHADOW);
        s.fill(x + w - 3, y + 2, x + w - 1, y + h - 1, SHADOW);
    }

    /** The 18 x 18 box around a slot whose item is drawn at (itemX, itemY). */
    public static void slotBox(Surface s, int itemX, int itemY) {
        int x = itemX - 1, y = itemY - 1;
        s.fill(x, y, x + 18, y + 18, SLOT_EDGE);
        s.fill(x + 1, y + 1, x + 18, y + 18, WHITE);
        s.fill(x + 1, y + 1, x + 17, y + 17, SLOT);
    }

    /** The slots of the player inventory: three rows and the hotbar below them. */
    public static void playerSlots(Surface s, int x, int y) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                slotBox(s, x + column * 18, y + row * 18);
            }
        }
        for (int column = 0; column < 9; column++) {
            slotBox(s, x + column * 18, y + 58);
        }
    }

    /** A dark well with a frame; returns nothing, the caller draws the contents inside (x, y, w, h). */
    public static void well(Surface s, int x, int y, int w, int h) {
        s.fill(x - 1, y - 1, x + w + 1, y + h + 1, SLOT_EDGE);
        s.fill(x, y, x + w, y + h, WELL);
    }

    /** The energy bar: red-orange filled from the bottom by {@code fraction}. */
    public static void energy(Surface s, int x, int y, int w, int h, double fraction) {
        well(s, x, y, w, h);
        int filled = (int) Math.round(h * Math.max(0, Math.min(1, fraction)));
        for (int row = 0; row < filled; row++) {
            int shade = 0xE0 - 0x40 * row / Math.max(1, h);
            s.fill(x, y + h - 1 - row, x + w, y + h - row, 0xFF000000 | shade << 16 | (shade / 5) << 8);
        }
    }

    /** A plain bar in {@code color}: fills left to right when wider than tall, else bottom to top. */
    public static void bar(Surface s, int x, int y, int w, int h, double fraction, int rgb) {
        well(s, x, y, w, h);
        double f = Math.max(0, Math.min(1, fraction));
        int color = 0xFF000000 | rgb;
        if (w >= h) {
            s.fill(x, y, x + (int) Math.round(w * f), y + h, color);
        } else {
            int filled = (int) Math.round(h * f);
            s.fill(x, y + h - filled, x + w, y + h, color);
        }
    }

    /** The progress arrow, pointing right; the part up to {@code fraction} is green. */
    public static void arrow(Surface s, int x, int y, int w, int h, double fraction) {
        int head = Math.max(3, Math.min(h / 2 + 1, w / 2));
        int shaftTop = y + h / 2 - Math.max(1, h / 6), shaftBottom = y + h / 2 + Math.max(1, h / 6) + 1;
        int done = (int) Math.round(w * Math.max(0, Math.min(1, fraction)));
        for (int column = 0; column < w; column++) {
            int top, bottom;
            if (column < w - head) {
                top = shaftTop;
                bottom = shaftBottom;
            } else {
                int into = column - (w - head);
                int half = (h / 2) * (head - into) / head;
                top = y + h / 2 - half;
                bottom = y + h / 2 + half + 1;
            }
            s.fill(x + column, top, x + column + 1, bottom, column < done ? 0xFF3C9A3C : SLOT);
        }
    }

    /** The flame: a tapering column that burns from the bottom up by {@code fraction}. */
    public static void flame(Surface s, int x, int y, int w, int h, double fraction) {
        int burning = (int) Math.round(h * Math.max(0, Math.min(1, fraction)));
        for (int row = 0; row < h; row++) {
            int widthHere = Math.max(2, w - (h - 1 - row) * w / (2 * h) - Math.abs(h / 2 - row) / 3);
            int left = x + (w - widthHere) / 2;
            boolean lit = row >= h - burning;
            s.fill(left, y + row, left + widthHere, y + row + 1, lit ? (row > h * 2 / 3 ? 0xFFFFC030 : 0xFFE06010) : SLOT_EDGE);
        }
    }
}
