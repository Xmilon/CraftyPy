package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import net.xmilon.craftpy.client.PixelBuffer;

/**
 * Pixel editing surface: draws the {@link PixelBuffer} and handles the drawing tools. Left button draws, right button
 * drags the picture, the wheel zooms. Pure drawing logic lives in {@link PixelBuffer}; this class only turns mouse
 * input into those calls.
 * <p>
 * Drawing cost is proportional to what is visible: only the pixels inside the view are looked at, and a row of equal
 * pixels is one rectangle, so a 512x512 texture stays smooth.
 */
final class PixelCanvas extends View {
    enum Tool { PENCIL, ERASER, FILL, PICKER, LINE, RECT, BOX, ELLIPSE, DISC, LIGHTEN, DARKEN, SELECT }

    private static final int EMPTY = 0xFF2E2E2E;
    private static final int EMPTY_ALT = 0xFF3A3A3A;
    private static final int MAX_SIZE = 1024;

    private final Paint paint = new Paint();
    private PixelBuffer buffer;
    private Tool tool = Tool.PENCIL;
    private int color = 0xFFFFFFFF;
    private int brushSize = 1;
    private int tolerance;
    private boolean mirrorX;
    private boolean mirrorY;
    private boolean grid = true;
    private boolean tiled;
    private int zoom; // 0 = fit
    private float panX;
    private float panY;

    private boolean panning;
    private float lastMouseX;
    private float lastMouseY;
    private boolean drawing;
    private int startX;
    private int startY;
    private int hoverX = -1;
    private int hoverY = -1;
    private List<int[]> preview = List.of();

    private int[] selection; // x0, y0, x1, y1 (normalised), or null
    private PixelBuffer.Region pasting;

    private IntConsumer onPick = c -> { };
    private Runnable onChange = () -> { };
    private Runnable onHover = () -> { };
    private Runnable onSelect = () -> { };

    PixelCanvas(Context context, PixelBuffer buffer) {
        super(context);
        this.buffer = buffer;
    }

    // ------------------------------------------------------------------ settings

    void setTool(Tool tool) {
        this.tool = tool;
        preview = List.of();
        invalidate();
    }

    Tool tool() {
        return tool;
    }

    void setColor(int argb) {
        color = argb;
    }

    void setBrushSize(int size) {
        brushSize = Math.max(1, Math.min(32, size));
    }

    void setTolerance(int value) {
        tolerance = Math.max(0, Math.min(255, value));
    }

    void setMirror(boolean horizontal, boolean vertical) {
        mirrorX = horizontal;
        mirrorY = vertical;
        invalidate();
    }

    void setGrid(boolean grid) {
        this.grid = grid;
        invalidate();
    }

    boolean grid() {
        return grid;
    }

    void setTiled(boolean tiled) {
        this.tiled = tiled;
        invalidate();
    }

    void setBuffer(PixelBuffer next) {
        buffer = next;
        selection = null;
        pasting = null;
        preview = List.of();
        fit();
    }

    void setOnPick(IntConsumer onPick) {
        this.onPick = onPick;
    }

    /** Called after every edit (stroke end, undo, flip...), for the undo buttons and the dirty flag. */
    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    void setOnHover(Runnable onHover) {
        this.onHover = onHover;
    }

    void setOnSelect(Runnable onSelect) {
        this.onSelect = onSelect;
    }

    int hoverX() {
        return hoverX;
    }

    int hoverY() {
        return hoverY;
    }

    // ------------------------------------------------------------------ selection and paste

    /** The selected box {x0, y0, x1, y1}, or null. */
    int[] selection() {
        return selection == null ? null : selection.clone();
    }

    void clearSelection() {
        if (selection != null) {
            selection = null;
            onSelect.run();
            invalidate();
        }
    }

    void selectAll() {
        selection = new int[]{0, 0, buffer.width() - 1, buffer.height() - 1};
        onSelect.run();
        invalidate();
    }

    /** The pasted picture follows the mouse; a click puts it down. */
    void startPaste(PixelBuffer.Region region) {
        pasting = region;
        invalidate();
    }

    boolean isPasting() {
        return pasting != null;
    }

    void cancelPaste() {
        if (pasting != null) {
            pasting = null;
            invalidate();
        }
    }

    // ------------------------------------------------------------------ view

    void zoomBy(int step) {
        int now = scale();
        zoom = Math.max(1, Math.min(64, now + step));
        invalidate();
    }

    void fit() {
        zoom = 0;
        panX = 0;
        panY = 0;
        invalidate();
    }

    int scale() {
        if (zoom > 0) {
            return zoom;
        }
        int fitted = Math.min(getWidth() / buffer.width(), getHeight() / buffer.height());
        return Math.max(1, fitted);
    }

    private float originX() {
        return (getWidth() - buffer.width() * scale()) / 2f + panX;
    }

    private float originY() {
        return (getHeight() - buffer.height() * scale()) / 2f + panY;
    }

    private int pixelAt(float mouse, float origin) {
        return (int) Math.floor((mouse - origin) / scale());
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int s = scale();
        float ox = originX();
        float oy = originY();
        int w = buffer.width();
        int h = buffer.height();
        int fromX = Math.max(0, (int) Math.floor(-ox / s));
        int toX = Math.min(w - 1, (int) Math.floor((getWidth() - ox) / s));
        int fromY = Math.max(0, (int) Math.floor(-oy / s));
        int toY = Math.min(h - 1, (int) Math.floor((getHeight() - oy) / s));
        if (tiled) {
            for (int tileY = -1; tileY <= 1; tileY++) {
                for (int tileX = -1; tileX <= 1; tileX++) {
                    if (tileX != 0 || tileY != 0) {
                        drawPixels(canvas, ox + tileX * w * s, oy + tileY * h * s, s, 0, w - 1, 0, h - 1, true);
                    }
                }
            }
        }
        if (fromX <= toX && fromY <= toY) {
            drawPixels(canvas, ox, oy, s, fromX, toX, fromY, toY, false);
        }
        if (!preview.isEmpty()) {
            paint.setColor(tool == Tool.ERASER ? 0x88FFFFFF : color);
            for (int[] p : preview) {
                if (buffer.inside(p[0], p[1])) {
                    canvas.drawRect(ox + p[0] * s, oy + p[1] * s, ox + (p[0] + 1) * s, oy + (p[1] + 1) * s, paint);
                }
            }
        }
        if (pasting != null && buffer.inside(hoverX, hoverY)) {
            for (int y = 0; y < pasting.height(); y++) {
                for (int x = 0; x < pasting.width(); x++) {
                    int argb = pasting.pixels()[y * pasting.width() + x];
                    if ((argb >>> 24) != 0 && buffer.inside(hoverX + x, hoverY + y)) {
                        paint.setColor(argb);
                        canvas.drawRect(ox + (hoverX + x) * s, oy + (hoverY + y) * s,
                                ox + (hoverX + x + 1) * s, oy + (hoverY + y + 1) * s, paint);
                    }
                }
            }
        }
        if (grid && s >= 6 && fromX <= toX && fromY <= toY) {
            paint.setColor(0x44000000);
            for (int x = fromX; x <= toX + 1; x++) {
                canvas.drawRect(ox + x * s, oy + fromY * s, ox + x * s + 1, oy + (toY + 1) * s, paint);
            }
            for (int y = fromY; y <= toY + 1; y++) {
                canvas.drawRect(ox + fromX * s, oy + y * s, ox + (toX + 1) * s, oy + y * s + 1, paint);
            }
        }
        if (selection != null) {
            outline(canvas, ox + selection[0] * s, oy + selection[1] * s,
                    ox + (selection[2] + 1) * s, oy + (selection[3] + 1) * s, 0xFFFFFFFF);
        }
        outline(canvas, ox, oy, ox + w * s, oy + h * s, 0x66FFFFFF);
        if (buffer.inside(hoverX, hoverY) && pasting == null) {
            outline(canvas, ox + hoverX * s, oy + hoverY * s, ox + (hoverX + 1) * s, oy + (hoverY + 1) * s, 0xAAFFFFFF);
        }
    }

    /** One rectangle per run of equal pixels; transparent runs get a flat dark background with a hint of checker. */
    private void drawPixels(Canvas canvas, float ox, float oy, int s, int fromX, int toX, int fromY, int toY, boolean faded) {
        for (int y = fromY; y <= toY; y++) {
            int x = fromX;
            while (x <= toX) {
                int argb = buffer.get(x, y);
                int end = x;
                while (end + 1 <= toX && buffer.get(end + 1, y) == argb) {
                    end++;
                }
                float left = ox + x * s;
                float right = ox + (end + 1) * s;
                float top = oy + y * s;
                int alpha = argb >>> 24;
                if (alpha != 255) {
                    paint.setColor(EMPTY);
                    canvas.drawRect(left, top, right, top + s, paint);
                    if (s >= 8) {
                        paint.setColor(EMPTY_ALT);
                        for (int cx = x; cx <= end; cx++) {
                            if (((cx + y) & 1) == 1) {
                                canvas.drawRect(ox + cx * s, top, ox + (cx + 1) * s, top + s, paint);
                            }
                        }
                    }
                }
                if (alpha != 0) {
                    paint.setColor(faded ? (argb & 0x00FFFFFF) | ((alpha / 2) << 24) : argb);
                    canvas.drawRect(left, top, right, top + s, paint);
                }
                x = end + 1;
            }
        }
    }

    private void outline(Canvas canvas, float left, float top, float right, float bottom, int argb) {
        paint.setColor(argb);
        canvas.drawRect(left, top, right, top + 1, paint);
        canvas.drawRect(left, bottom - 1, right, bottom, paint);
        canvas.drawRect(left, top, left + 1, bottom, paint);
        canvas.drawRect(right - 1, top, right, bottom, paint);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        float mx = event.getX();
        float my = event.getY();
        int px = pixelAt(mx, originX());
        int py = pixelAt(my, originY());
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN -> {
                lastMouseX = mx;
                lastMouseY = my;
                panning = (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
                if (!panning) {
                    begin(px, py);
                }
            }
            case MotionEvent.ACTION_MOVE -> {
                if (panning) {
                    panX += mx - lastMouseX;
                    panY += my - lastMouseY;
                    lastMouseX = mx;
                    lastMouseY = my;
                    invalidate();
                } else {
                    drag(px, py);
                }
                setHover(px, py);
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!panning) {
                    end(px, py, event.getAction() == MotionEvent.ACTION_CANCEL);
                }
                panning = false;
            }
            default -> {
            }
        }
        return true;
    }

    private void begin(int x, int y) {
        startX = x;
        startY = y;
        if (pasting != null) {
            buffer.paste(x, y, pasting);
            pasting = null;
            onChange.run();
            invalidate();
            return;
        }
        drawing = true;
        switch (tool) {
            case PENCIL, ERASER, LIGHTEN, DARKEN -> {
                buffer.checkpoint();
                stamp(x, y);
            }
            case FILL -> {
                buffer.fill(x, y, color, tolerance);
                onChange.run();
            }
            case PICKER -> {
                if (buffer.inside(x, y)) {
                    onPick.accept(buffer.get(x, y));
                }
            }
            case SELECT -> {
                selection = null;
                onSelect.run();
                preview = List.of();
            }
            default -> preview = shape(x, y);
        }
        invalidate();
    }

    private void drag(int x, int y) {
        if (!drawing) {
            return;
        }
        switch (tool) {
            case PENCIL, ERASER, LIGHTEN, DARKEN -> {
                for (int[] p : PixelBuffer.line(startX, startY, x, y)) {
                    stamp(p[0], p[1]);
                }
                startX = x;
                startY = y;
            }
            case LINE, RECT, BOX, ELLIPSE, DISC -> preview = shape(x, y);
            case SELECT -> preview = PixelBuffer.rect(startX, startY, x, y, false);
            case PICKER -> {
                if (buffer.inside(x, y)) {
                    onPick.accept(buffer.get(x, y));
                }
            }
            default -> {
            }
        }
        invalidate();
    }

    private void end(int x, int y, boolean cancelled) {
        if (!drawing) {
            return;
        }
        drawing = false;
        switch (tool) {
            case PENCIL, ERASER, LIGHTEN, DARKEN -> onChange.run();
            case LINE, RECT, BOX, ELLIPSE, DISC -> {
                if (!cancelled) {
                    buffer.checkpoint();
                    for (int[] p : shape(x, y)) {
                        place(p[0], p[1], filledShape() ? 1 : brushSize);
                    }
                    onChange.run();
                }
                preview = List.of();
            }
            case SELECT -> {
                preview = List.of();
                if (!cancelled) {
                    int x0 = Math.max(0, Math.min(startX, x));
                    int y0 = Math.max(0, Math.min(startY, y));
                    int x1 = Math.min(buffer.width() - 1, Math.max(startX, x));
                    int y1 = Math.min(buffer.height() - 1, Math.max(startY, y));
                    selection = x0 <= x1 && y0 <= y1 ? new int[]{x0, y0, x1, y1} : null;
                    onSelect.run();
                }
            }
            default -> {
            }
        }
        invalidate();
    }

    private boolean filledShape() {
        return tool == Tool.BOX || tool == Tool.DISC;
    }

    /** One pencil / eraser / shade dab at (x, y), with the brush size and the mirrors. */
    private void stamp(int x, int y) {
        place(x, y, brushSize);
    }

    private void place(int x, int y, int size) {
        for (int[] cell : PixelBuffer.brush(x, y, size)) {
            for (int[] at : mirrored(cell[0], cell[1])) {
                apply(at[0], at[1]);
            }
        }
    }

    private void apply(int x, int y) {
        if (!buffer.inside(x, y)) {
            return;
        }
        switch (tool) {
            case ERASER -> buffer.set(x, y, 0);
            case LIGHTEN -> buffer.shade(x, y, 0.15f);
            case DARKEN -> buffer.shade(x, y, -0.15f);
            default -> buffer.set(x, y, color);
        }
    }

    private List<int[]> mirrored(int x, int y) {
        List<int[]> out = new ArrayList<>(4);
        out.add(new int[]{x, y});
        int mx = buffer.width() - 1 - x;
        int my = buffer.height() - 1 - y;
        if (mirrorX) {
            out.add(new int[]{mx, y});
        }
        if (mirrorY) {
            out.add(new int[]{x, my});
        }
        if (mirrorX && mirrorY) {
            out.add(new int[]{mx, my});
        }
        return out;
    }

    private List<int[]> shape(int x, int y) {
        List<int[]> base = switch (tool) {
            case LINE -> PixelBuffer.line(startX, startY, x, y);
            case ELLIPSE, DISC -> PixelBuffer.ellipse(startX, startY, x, y, tool == Tool.DISC);
            default -> PixelBuffer.rect(startX, startY, x, y, tool == Tool.BOX);
        };
        List<int[]> out = new ArrayList<>();
        int size = filledShape() ? 1 : brushSize;
        for (int[] p : base) {
            for (int[] cell : PixelBuffer.brush(p[0], p[1], size)) {
                out.addAll(mirrored(cell[0], cell[1]));
            }
        }
        return out;
    }

    @Override
    public boolean onHoverEvent(@NonNull MotionEvent event) {
        setHover(pixelAt(event.getX(), originX()), pixelAt(event.getY(), originY()));
        return super.onHoverEvent(event);
    }

    private void setHover(int x, int y) {
        if (x != hoverX || y != hoverY) {
            hoverX = x;
            hoverY = y;
            onHover.run();
            invalidate();
        }
    }

    @Override
    public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
            float delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (delta != 0) {
                zoomBy(delta > 0 ? 1 : -1);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    /** Repaint after the buffer changed from outside (undo, flip...). */
    void refresh() {
        invalidate();
    }

    static int maxSize() {
        return MAX_SIZE;
    }
}
