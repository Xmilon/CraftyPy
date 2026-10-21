package net.xmilon.craftpy.client.media;

/**
 * Which part of a picture goes where in the box a widget has: the whole picture or a cropped part of it, stretched,
 * fitted inside the box (letterboxed) or filling it with the overflow cut off.
 */
public final class MediaLayout {
    private MediaLayout() {
    }

    public enum Fit {
        CONTAIN, COVER, STRETCH;

        public static Fit of(String name) {
            if (name != null) {
                for (Fit fit : values()) {
                    if (fit.name().equalsIgnoreCase(name.strip())) {
                        return fit;
                    }
                }
            }
            return CONTAIN;
        }
    }

    /** Source and destination rectangles as left, top, right, bottom. */
    public record Placement(float[] src, float[] dst) {
    }

    /**
     * @param crop {@code [x, y, width, height]} in source pixels, or null for the whole picture; a rectangle that does
     *             not lie in the picture is ignored
     */
    public static Placement place(int imageWidth, int imageHeight, float boxWidth, float boxHeight, Fit fit, float[] crop) {
        float sx = 0, sy = 0, sw = imageWidth, sh = imageHeight;
        if (crop != null && crop.length == 4 && crop[2] >= 1 && crop[3] >= 1 && crop[0] >= 0 && crop[1] >= 0
                && crop[0] + crop[2] <= imageWidth && crop[1] + crop[3] <= imageHeight) {
            sx = crop[0];
            sy = crop[1];
            sw = crop[2];
            sh = crop[3];
        }
        if (boxWidth <= 0 || boxHeight <= 0 || sw <= 0 || sh <= 0) {
            return new Placement(new float[] {sx, sy, sx + sw, sy + sh}, new float[] {0, 0, 0, 0});
        }
        switch (fit) {
            case STRETCH:
                return new Placement(new float[] {sx, sy, sx + sw, sy + sh}, new float[] {0, 0, boxWidth, boxHeight});
            case COVER: {
                float scale = Math.max(boxWidth / sw, boxHeight / sh);
                float visibleW = boxWidth / scale, visibleH = boxHeight / scale;
                float left = sx + (sw - visibleW) / 2, top = sy + (sh - visibleH) / 2;
                return new Placement(new float[] {left, top, left + visibleW, top + visibleH},
                        new float[] {0, 0, boxWidth, boxHeight});
            }
            default: {
                float scale = Math.min(boxWidth / sw, boxHeight / sh);
                float w = sw * scale, h = sh * scale;
                float left = (boxWidth - w) / 2, top = (boxHeight - h) / 2;
                return new Placement(new float[] {sx, sy, sx + sw, sy + sh}, new float[] {left, top, left + w, top + h});
            }
        }
    }
}
