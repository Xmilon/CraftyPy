package net.xmilon.craftpy.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The pixels of a texture being edited (ARGB ints) with the drawing operations and an undo history.
 * No Minecraft or UI classes, so it is unit-testable. Every mutating operation except {@link #set} snapshots first;
 * a stroke calls {@link #checkpoint()} once and then {@link #set} per pixel.
 */
public final class PixelBuffer {
    private static final int MAX_HISTORY = 60;

    private int width;
    private int height;
    private int[] pixels;
    private final ArrayDeque<int[]> undo = new ArrayDeque<>();
    private final ArrayDeque<int[]> redo = new ArrayDeque<>();
    private int version;

    public PixelBuffer(int width, int height) {
        this(width, height, new int[width * height]);
    }

    public PixelBuffer(int width, int height, int[] argb) {
        if (width < 1 || height < 1 || argb.length != width * height) {
            throw new IllegalArgumentException("Bad texture size " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.pixels = argb.clone();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Bumps on every change; views use it to know when to repaint. */
    public int version() {
        return version;
    }

    public boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    public int get(int x, int y) {
        return pixels[y * width + x];
    }

    public int[] copy() {
        return pixels.clone();
    }

    /** Sets one pixel; out of range is ignored. Call {@link #checkpoint()} before a stroke. */
    public void set(int x, int y, int argb) {
        if (inside(x, y) && pixels[y * width + x] != argb) {
            pixels[y * width + x] = argb;
            version++;
        }
    }

    public void checkpoint() {
        undo.push(pixels.clone());
        if (undo.size() > MAX_HISTORY) {
            undo.removeLast();
        }
        redo.clear();
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void undo() {
        if (!undo.isEmpty()) {
            redo.push(pixels.clone());
            restore(undo.pop());
        }
    }

    public void redo() {
        if (!redo.isEmpty()) {
            undo.push(pixels.clone());
            restore(redo.pop());
        }
    }

    private void restore(int[] snapshot) {
        // A rotation of a non-square image changes the size; snapshots remember it through their length only,
        // which is why rotate() refuses non-square textures.
        pixels = snapshot;
        version++;
    }

    public void clear(int argb) {
        checkpoint();
        java.util.Arrays.fill(pixels, argb);
        version++;
    }

    /** Replaces every pixel connected to (x, y) with the same colour (4-neighbourhood). */
    public void fill(int x, int y, int argb) {
        fill(x, y, argb, 0);
    }

    /**
     * Flood fill; pixels whose every channel differs from the clicked one by at most {@code tolerance} (0 to 255)
     * count as the same colour. Uses an explicit int stack, so large images never overflow it or flood the GC.
     */
    public void fill(int x, int y, int argb, int tolerance) {
        if (!inside(x, y)) {
            return;
        }
        int target = pixels[y * width + x];
        int tol = Math.max(0, Math.min(255, tolerance));
        if (target == argb && tol == 0) {
            return;
        }
        checkpoint();
        boolean[] seen = new boolean[pixels.length];
        int[] stack = new int[Math.max(16, pixels.length / 4)];
        int top = 0;
        stack[top++] = y * width + x;
        while (top > 0) {
            int at = stack[--top];
            if (seen[at]) {
                continue;
            }
            seen[at] = true;
            if (!near(pixels[at], target, tol)) {
                continue;
            }
            pixels[at] = argb;
            int px = at % width;
            int py = at / width;
            if (top + 4 >= stack.length) {
                stack = java.util.Arrays.copyOf(stack, stack.length * 2);
            }
            if (px + 1 < width) {
                stack[top++] = at + 1;
            }
            if (px > 0) {
                stack[top++] = at - 1;
            }
            if (py + 1 < height) {
                stack[top++] = at + width;
            }
            if (py > 0) {
                stack[top++] = at - width;
            }
        }
        version++;
    }

    private static boolean near(int a, int b, int tolerance) {
        if (a == b) {
            return true;
        }
        for (int shift = 0; shift < 32; shift += 8) {
            if (Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF)) > tolerance) {
                return false;
            }
        }
        return true;
    }

    /** Bresenham line, both ends included. */
    public static List<int[]> line(int x0, int y0, int x1, int y1) {
        List<int[]> points = new ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        while (true) {
            points.add(new int[]{x, y});
            if (x == x1 && y == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
        return points;
    }

    /** Rectangle between two corners, outline or filled; points may repeat and may lie outside the texture. */
    public static List<int[]> rect(int x0, int y0, int x1, int y1, boolean filled) {
        List<int[]> points = new ArrayList<>();
        int left = Math.min(x0, x1);
        int right = Math.max(x0, x1);
        int top = Math.min(y0, y1);
        int bottom = Math.max(y0, y1);
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                if (filled || x == left || x == right || y == top || y == bottom) {
                    points.add(new int[]{x, y});
                }
            }
        }
        return points;
    }

    public void flipHorizontal() {
        checkpoint();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width / 2; x++) {
                swap(y * width + x, y * width + (width - 1 - x));
            }
        }
        version++;
    }

    public void flipVertical() {
        checkpoint();
        for (int y = 0; y < height / 2; y++) {
            for (int x = 0; x < width; x++) {
                swap(y * width + x, (height - 1 - y) * width + x);
            }
        }
        version++;
    }

    /** Clockwise quarter turn; false (and nothing changes) for a non-square texture. */
    public boolean rotate() {
        if (width != height) {
            return false;
        }
        checkpoint();
        int[] turned = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                turned[x * width + (width - 1 - y)] = pixels[y * width + x];
            }
        }
        pixels = turned;
        version++;
        return true;
    }

    /** Moves the whole picture by (dx, dy) with wrap-around, handy for seamless tiles. */
    public void shift(int dx, int dy) {
        checkpoint();
        int[] moved = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                moved[Math.floorMod(y + dy, height) * width + Math.floorMod(x + dx, width)] = pixels[y * width + x];
            }
        }
        pixels = moved;
        version++;
    }

    private void swap(int a, int b) {
        int tmp = pixels[a];
        pixels[a] = pixels[b];
        pixels[b] = tmp;
    }

    // -- colour helpers ----------------------------------------------------------------------------

    /** Points of an ellipse inside the box spanned by two corners (outline, or filled). */
    public static List<int[]> ellipse(int x0, int y0, int x1, int y1, boolean filled) {
        int left = Math.min(x0, x1);
        int right = Math.max(x0, x1);
        int top = Math.min(y0, y1);
        int bottom = Math.max(y0, y1);
        List<int[]> out = new ArrayList<>();
        double cx = (left + right) / 2.0;
        double cy = (top + bottom) / 2.0;
        double rx = (right - left + 1) / 2.0;
        double ry = (bottom - top + 1) / 2.0;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                double dx = (x - cx) / rx;
                double dy = (y - cy) / ry;
                double inside = dx * dx + dy * dy;
                if (filled ? inside <= 1.0 : inside <= 1.0 && !(inner(x, y, cx, cy, rx, ry))) {
                    out.add(new int[]{x, y});
                }
            }
        }
        return out;
    }

    /** True when all four neighbours of (x, y) are inside the ellipse, i.e. the pixel is not on its outline. */
    private static boolean inner(int x, int y, double cx, double cy, double rx, double ry) {
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : around) {
            double dx = (x + d[0] - cx) / rx;
            double dy = (y + d[1] - cy) / ry;
            if (dx * dx + dy * dy > 1.0) {
                return false;
            }
        }
        return true;
    }

    /** The pixels of a square brush of {@code size} (1 to 32) centred on (x, y). */
    public static List<int[]> brush(int x, int y, int size) {
        int n = Math.max(1, Math.min(32, size));
        int start = -(n - 1) / 2;
        List<int[]> out = new ArrayList<>(n * n);
        for (int dy = 0; dy < n; dy++) {
            for (int dx = 0; dx < n; dx++) {
                out.add(new int[]{x + start + dx, y + start + dy});
            }
        }
        return out;
    }

    /** Lightens ({@code amount > 0}) or darkens ({@code amount < 0}) one pixel by that fraction; alpha is kept. */
    public void shade(int x, int y, float amount) {
        if (!inside(x, y)) {
            return;
        }
        int argb = pixels[y * width + x];
        if ((argb >>> 24) == 0) {
            return;
        }
        int out = argb & 0xFF000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int channel = (argb >>> shift) & 0xFF;
            float value = amount >= 0 ? channel + (255 - channel) * amount : channel * (1 + amount);
            out |= Math.max(0, Math.min(255, Math.round(value))) << shift;
        }
        set(x, y, out);
    }

    /** Replaces every pixel of one colour with another (a global recolour). Returns how many changed. */
    public int replaceColor(int from, int to) {
        if (from == to) {
            return 0;
        }
        checkpoint();
        int changed = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] == from) {
                pixels[i] = to;
                changed++;
            }
        }
        version++;
        return changed;
    }

    /** A rectangle of pixels, as picked up by select / copy / cut. */
    public record Region(int width, int height, int[] pixels) {
    }

    /** Copies the box between two corners (clamped to the image); null when nothing is inside. */
    public Region region(int x0, int y0, int x1, int y1) {
        int left = Math.max(0, Math.min(x0, x1));
        int right = Math.min(width - 1, Math.max(x0, x1));
        int top = Math.max(0, Math.min(y0, y1));
        int bottom = Math.min(height - 1, Math.max(y0, y1));
        if (left > right || top > bottom) {
            return null;
        }
        int w = right - left + 1;
        int h = bottom - top + 1;
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            System.arraycopy(pixels, (top + y) * width + left, out, y * w, w);
        }
        return new Region(w, h, out);
    }

    /** Clears the box between two corners to transparent (one undo step). */
    public void clearRegion(int x0, int y0, int x1, int y1) {
        checkpoint();
        for (int y = Math.max(0, Math.min(y0, y1)); y <= Math.min(height - 1, Math.max(y0, y1)); y++) {
            for (int x = Math.max(0, Math.min(x0, x1)); x <= Math.min(width - 1, Math.max(x0, x1)); x++) {
                pixels[y * width + x] = 0;
            }
        }
        version++;
    }

    /** Draws a region with its top left corner at (x, y); transparent pixels of the region leave the image alone. */
    public void paste(int x, int y, Region region) {
        checkpoint();
        for (int ry = 0; ry < region.height(); ry++) {
            for (int rx = 0; rx < region.width(); rx++) {
                int argb = region.pixels()[ry * region.width() + rx];
                if ((argb >>> 24) != 0 && inside(x + rx, y + ry)) {
                    pixels[(y + ry) * width + x + rx] = argb;
                }
            }
        }
        version++;
    }

    /**
     * A new buffer of another size holding this picture, anchored top left ({@code 0}), centred ({@code 1}) or bottom
     * right ({@code 2}) on each axis; new area is transparent. The undo history does not carry over.
     */
    public PixelBuffer resized(int newWidth, int newHeight, int anchorX, int anchorY) {
        if (newWidth < 1 || newHeight < 1 || newWidth > 1024 || newHeight > 1024) {
            throw new IllegalArgumentException("Textures are 1 to 1024 pixels wide and high");
        }
        int[] out = new int[newWidth * newHeight];
        int offsetX = anchorX == 0 ? 0 : anchorX == 1 ? (newWidth - width) / 2 : newWidth - width;
        int offsetY = anchorY == 0 ? 0 : anchorY == 1 ? (newHeight - height) / 2 : newHeight - height;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int nx = x + offsetX;
                int ny = y + offsetY;
                if (nx >= 0 && ny >= 0 && nx < newWidth && ny < newHeight) {
                    out[ny * newWidth + nx] = pixels[y * width + x];
                }
            }
        }
        return new PixelBuffer(newWidth, newHeight, out);
    }

    /** The distinct colours used, most frequent first, at most {@code limit} (an extracted palette). */
    public List<Integer> palette(int limit) {
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (int argb : pixels) {
            if ((argb >>> 24) != 0) {
                counts.merge(argb, 1, Integer::sum);
            }
        }
        List<Integer> colors = new ArrayList<>(counts.keySet());
        colors.sort((a, b) -> counts.get(b) - counts.get(a));
        return colors.subList(0, Math.min(limit, colors.size()));
    }


    /** NativeImage stores ABGR; this editor uses ARGB. The swap is its own inverse. */
    public static int swapRedBlue(int color) {
        return (color & 0xFF00FF00) | ((color & 0xFF) << 16) | ((color >>> 16) & 0xFF);
    }

    /** Parses "#RRGGBB", "RRGGBB" or "#AARRGGBB"; null when it is not a colour. */
    public static Integer parseHex(String text) {
        String s = text == null ? "" : text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (!(s.length() == 6 || s.length() == 8) || !s.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return null;
        }
        long value = Long.parseLong(s, 16);
        return s.length() == 6 ? (int) (0xFF000000L | value) : (int) value;
    }

    public static String hex(int argb) {
        return String.format("#%08X", argb);
    }
}
