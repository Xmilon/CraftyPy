package net.xmilon.craftpy.client.mui;

import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.view.View;

/**
 * A small line icon drawn with the canvas (no image files, sharp at every scale), in the style of IntelliJ's New UI
 * icons: 16 units square, 1.25 unit strokes, round ends. Tinted with the secondary text color unless the glyph has its own.
 */
public class IdeIcon extends View {
    public enum Glyph {
        HOME, MODULES, CODE, BOOK, FOLDER, STRUCTURE, PLAY, STOP, RELOAD, CHECK, HAMMER, SAVE, SEARCH, GEAR, TERMINAL,
        WARNING, BOOKMARK, PULSE, IMAGE, MENU, CLOSE, MINUS, SPLIT, CHEVRON_DOWN, PLUS, PYTHON, BRUSH, SOUND, EYE, CATALOG, BUG, GRAPH, LAYOUT, BACK
    }

    private final Paint paint = new Paint();
    private Glyph glyph;
    private int tint;

    public IdeIcon(Context context, Glyph glyph) {
        super(context);
        this.glyph = glyph;
        this.tint = ownColor(glyph);
        paint.setAntiAlias(true);
        paint.setStrokeCap(Paint.CAP_ROUND);
        paint.setStrokeJoin(Paint.JOIN_ROUND);
    }

    public void setGlyph(Glyph glyph) {
        this.glyph = glyph;
        this.tint = ownColor(glyph);
        invalidate();
    }

    public Glyph glyph() {
        return glyph;
    }

    public void setTint(int color) {
        this.tint = color;
        invalidate();
    }

    /** Run is green and stop red everywhere, like in IntelliJ; everything else follows the text color. */
    static int ownColor(Glyph glyph) {
        return switch (glyph) {
            case PLAY, BUG -> IdeColors.SUCCESS;
            case STOP -> IdeColors.ERROR;
            case WARNING -> IdeColors.WARNING;
            default -> IdeColors.TEXT_SECONDARY;
        };
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = dp(16);
        setMeasuredDimension(resolveSize(size + getPaddingLeft() + getPaddingRight(), widthMeasureSpec),
                resolveSize(size + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        float size = Math.min(w, Math.min(h, dp(16)));
        if (size <= 0) {
            return;
        }
        float u = size / 16f; // one unit of the 16 unit grid
        canvas.save();
        canvas.translate(getPaddingLeft() + (w - size) / 2f, getPaddingTop() + (h - size) / 2f);
        paint.setColor(isEnabled() ? tint : IdeColors.TEXT_DISABLED);
        paint.setStrokeWidth(1.25f * u);
        paint.setStyle(Paint.STROKE);
        draw(canvas, u);
        canvas.restore();
    }

    private void line(Canvas c, float u, float x0, float y0, float x1, float y1) {
        c.drawLine(x0 * u, y0 * u, x1 * u, y1 * u, paint);
    }

    private void rect(Canvas c, float u, float l, float t, float r, float b, float radius) {
        c.drawRoundRect(l * u, t * u, r * u, b * u, radius * u, paint);
    }

    private void draw(Canvas c, float u) {
        switch (glyph) {
            case HOME -> {
                line(c, u, 2, 8, 8, 2.5f);
                line(c, u, 8, 2.5f, 14, 8);
                rect(c, u, 4, 7.5f, 12, 14, 1);
                line(c, u, 8, 14, 8, 10.5f);
            }
            case MODULES -> {
                rect(c, u, 2.5f, 2.5f, 7.5f, 7.5f, 1);
                rect(c, u, 8.5f, 2.5f, 13.5f, 7.5f, 1);
                rect(c, u, 2.5f, 8.5f, 7.5f, 13.5f, 1);
                line(c, u, 11, 9, 11, 13);
                line(c, u, 9, 11, 13, 11);
            }
            case CODE -> {
                line(c, u, 5.5f, 4, 2, 8);
                line(c, u, 2, 8, 5.5f, 12);
                line(c, u, 10.5f, 4, 14, 8);
                line(c, u, 14, 8, 10.5f, 12);
                line(c, u, 9, 3, 7, 13);
            }
            case BOOK -> {
                rect(c, u, 3, 2.5f, 13, 13.5f, 1.5f);
                line(c, u, 6, 2.5f, 6, 13.5f);
                line(c, u, 8.5f, 6, 11, 6);
                line(c, u, 8.5f, 8.5f, 11, 8.5f);
            }
            case FOLDER -> {
                line(c, u, 2, 4, 6, 4);
                line(c, u, 6, 4, 7.5f, 5.5f);
                rect(c, u, 2, 5.5f, 14, 13, 1);
            }
            case STRUCTURE -> {
                rect(c, u, 2, 2.5f, 6, 5.5f, 0.5f);
                line(c, u, 4, 5.5f, 4, 12);
                line(c, u, 4, 8.5f, 8, 8.5f);
                line(c, u, 4, 12, 8, 12);
                line(c, u, 9.5f, 8.5f, 14, 8.5f);
                line(c, u, 9.5f, 12, 14, 12);
            }
            case PLAY -> {
                // a filled, slightly rounded triangle: outline plus dense strokes (the canvas has no paths)
                paint.setStrokeWidth(1.6f * u);
                line(c, u, 4.5f, 2.8f, 12.6f, 8);
                line(c, u, 12.6f, 8, 4.5f, 13.2f);
                line(c, u, 4.5f, 13.2f, 4.5f, 2.8f);
                paint.setStrokeWidth(0.9f * u);
                for (float y = 3.4f; y <= 12.6f; y += 0.45f) {
                    float reach = (y <= 8 ? y - 2.8f : 13.2f - y) / 5.2f * 8.1f;
                    line(c, u, 4.5f, y, 4.5f + reach, y);
                }
            }
            case STOP -> {
                paint.setStyle(Paint.FILL);
                rect(c, u, 3.5f, 3.5f, 12.5f, 12.5f, 2f);
                paint.setStyle(Paint.STROKE);
            }
            case BUG -> { // a ladybug: round body, head, legs; the line down the back is the debugger's mark
                c.drawCircle(8 * u, 9.3f * u, 4.1f * u, paint);
                c.drawCircle(8 * u, 4.2f * u, 1.7f * u, paint);
                line(c, u, 8, 5.9f, 8, 13.4f);
                line(c, u, 3.9f, 7.6f, 1.8f, 6.4f);
                line(c, u, 3.9f, 10, 1.6f, 10);
                line(c, u, 4.3f, 12.2f, 2.3f, 13.8f);
                line(c, u, 12.1f, 7.6f, 14.2f, 6.4f);
                line(c, u, 12.1f, 10, 14.4f, 10);
                line(c, u, 11.7f, 12.2f, 13.7f, 13.8f);
            }
            case GRAPH -> { // three nodes and their links: the mod map
                c.drawCircle(4 * u, 4.5f * u, 2 * u, paint);
                c.drawCircle(12 * u, 5 * u, 2 * u, paint);
                c.drawCircle(8 * u, 12 * u, 2 * u, paint);
                line(c, u, 6, 4.6f, 10, 4.9f);
                line(c, u, 5, 6.3f, 7, 10.2f);
                line(c, u, 11, 6.8f, 9, 10.2f);
            }
            case LAYOUT -> { // a window with a title bar and two panes: the UI maker
                rect(c, u, 2, 2.5f, 14, 13.5f, 1.5f);
                line(c, u, 2, 5.5f, 14, 5.5f);
                line(c, u, 6.5f, 5.5f, 6.5f, 13.5f);
                rect(c, u, 8.5f, 7.5f, 12, 9.5f, 0.5f);
            }
            case RELOAD -> {
                c.drawArc(8 * u, 8 * u, 5 * u, 30, 300, paint);
                line(c, u, 12.5f, 2.5f, 12.8f, 5.6f);
                line(c, u, 12.8f, 5.6f, 9.8f, 5.6f);
            }
            case CHECK -> {
                line(c, u, 3, 8.5f, 6.5f, 12);
                line(c, u, 6.5f, 12, 13, 4.5f);
            }
            case HAMMER -> {
                rect(c, u, 3, 2.5f, 11, 6, 1);
                line(c, u, 7, 6, 7, 14);
                line(c, u, 11, 4.2f, 13.5f, 5.5f);
            }
            case SAVE -> {
                rect(c, u, 2.5f, 2.5f, 13.5f, 13.5f, 1.5f);
                rect(c, u, 5, 2.5f, 11, 6, 0);
                rect(c, u, 4.5f, 9, 11.5f, 13.5f, 0.5f);
            }
            case SEARCH -> {
                c.drawCircle(7 * u, 7 * u, 4.2f * u, paint);
                line(c, u, 10.2f, 10.2f, 13.5f, 13.5f);
            }
            case GEAR -> {
                c.drawCircle(8 * u, 8 * u, 2 * u, paint);
                c.drawCircle(8 * u, 8 * u, 4.6f * u, paint);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    line(c, u, 8 + (float) Math.cos(a) * 4.6f, 8 + (float) Math.sin(a) * 4.6f,
                            8 + (float) Math.cos(a) * 6.3f, 8 + (float) Math.sin(a) * 6.3f);
                }
            }
            case TERMINAL -> {
                rect(c, u, 2, 3, 14, 13, 1.5f);
                line(c, u, 4.5f, 6, 7, 8);
                line(c, u, 7, 8, 4.5f, 10);
                line(c, u, 8.5f, 10.5f, 11.5f, 10.5f);
            }
            case WARNING -> {
                line(c, u, 8, 2.5f, 14, 13);
                line(c, u, 14, 13, 2, 13);
                line(c, u, 2, 13, 8, 2.5f);
                line(c, u, 8, 6.5f, 8, 9.2f);
                line(c, u, 8, 11, 8, 11.1f);
            }
            case BOOKMARK -> {
                line(c, u, 4, 2.5f, 12, 2.5f);
                line(c, u, 4, 2.5f, 4, 13.5f);
                line(c, u, 12, 2.5f, 12, 13.5f);
                line(c, u, 4, 13.5f, 8, 10.5f);
                line(c, u, 8, 10.5f, 12, 13.5f);
            }
            case PULSE -> {
                line(c, u, 1.5f, 8.5f, 4.5f, 8.5f);
                line(c, u, 4.5f, 8.5f, 6.5f, 3.5f);
                line(c, u, 6.5f, 3.5f, 9.5f, 12.5f);
                line(c, u, 9.5f, 12.5f, 11.5f, 8.5f);
                line(c, u, 11.5f, 8.5f, 14.5f, 8.5f);
            }
            case IMAGE -> {
                rect(c, u, 2, 3, 14, 13, 1.5f);
                c.drawCircle(10.5f * u, 6 * u, 1.2f * u, paint);
                line(c, u, 2.5f, 12, 6.5f, 7.5f);
                line(c, u, 6.5f, 7.5f, 10, 11);
                line(c, u, 9, 10, 11, 8.5f);
                line(c, u, 11, 8.5f, 13.5f, 11);
            }
            case MENU -> {
                line(c, u, 2.5f, 4, 13.5f, 4);
                line(c, u, 2.5f, 8, 13.5f, 8);
                line(c, u, 2.5f, 12, 13.5f, 12);
            }
            case CLOSE -> {
                line(c, u, 4, 4, 12, 12);
                line(c, u, 12, 4, 4, 12);
            }
            case MINUS -> line(c, u, 3.5f, 8, 12.5f, 8);
            case BACK -> {
                line(c, u, 13, 8, 3.5f, 8);
                line(c, u, 7.5f, 3.5f, 3.5f, 8);
                line(c, u, 3.5f, 8, 7.5f, 12.5f);
            }
            case SPLIT -> {
                rect(c, u, 2, 3, 14, 13, 1.5f);
                line(c, u, 8, 3, 8, 13);
            }
            case CHEVRON_DOWN -> {
                line(c, u, 4.5f, 6.5f, 8, 10);
                line(c, u, 8, 10, 11.5f, 6.5f);
            }
            case PLUS -> {
                line(c, u, 8, 3, 8, 13);
                line(c, u, 3, 8, 13, 8);
            }
            case CATALOG -> { // stacked layers: everything the mods bring
                rect(c, u, 2.5f, 2.5f, 13.5f, 5.5f, 1);
                rect(c, u, 2.5f, 6.5f, 13.5f, 9.5f, 1);
                rect(c, u, 2.5f, 10.5f, 13.5f, 13.5f, 1);
            }
            case BRUSH -> {
                line(c, u, 13.5f, 2.5f, 7, 9);
                rect(c, u, 3, 9, 7.5f, 13.5f, 2);
            }
            case SOUND -> {
                line(c, u, 2.5f, 6, 5, 6);
                line(c, u, 5, 6, 8.5f, 3);
                line(c, u, 8.5f, 3, 8.5f, 13);
                line(c, u, 8.5f, 13, 5, 10);
                line(c, u, 5, 10, 2.5f, 10);
                line(c, u, 2.5f, 10, 2.5f, 6);
                c.drawArc(9 * u, 8 * u, 3 * u, -50, 100, paint);
                c.drawArc(9 * u, 8 * u, 5.2f * u, -50, 100, paint);
            }
            case EYE -> {
                c.drawArc(8 * u, 13 * u, 7.5f * u, 210, 120, paint);
                c.drawArc(8 * u, 3 * u, 7.5f * u, 30, 120, paint);
                c.drawCircle(8 * u, 8 * u, 2 * u, paint);
            }
            case PYTHON -> {
                rect(c, u, 3, 2.5f, 10, 8, 2);
                rect(c, u, 6, 8, 13, 13.5f, 2);
                c.drawCircle(5.5f * u, 4.5f * u, 0.4f * u, paint);
                c.drawCircle(10.5f * u, 11.5f * u, 0.4f * u, paint);
            }
        }
    }
}
