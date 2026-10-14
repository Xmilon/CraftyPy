package net.xmilon.craftpy.client.mui;

import java.util.function.IntConsumer;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;

/** A thin draggable bar between two panels. Reports drag distance in pixels; {@code onDone} on release. */
public class Splitter extends View {
    private final boolean vertical;
    private final IntConsumer onDrag;
    private final Runnable onDone;
    private final Paint paint = new Paint();
    private float last;
    private boolean dragging;
    private boolean hovered;

    /** @param vertical true for a vertical bar (drag left/right), false for a horizontal one (drag up/down) */
    public Splitter(Context context, boolean vertical, IntConsumer onDrag, Runnable onDone) {
        super(context);
        this.vertical = vertical;
        this.onDrag = onDrag;
        this.onDone = onDone;
        setClickable(true);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int color = CraftPyFragment.color(getContext(), dragging || hovered ? R.attr.colorPrimary : R.attr.colorOutlineVariant);
        paint.setColor(color);
        float thickness = dragging || hovered ? dp(2) : dp(1);
        if (vertical) {
            float x = (getWidth() - thickness) / 2f;
            canvas.drawRect(x, 0, x + thickness, getHeight(), paint);
        } else {
            float y = (getHeight() - thickness) / 2f;
            canvas.drawRect(0, y, getWidth(), y + thickness, paint);
        }
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        float position = vertical ? event.getRawX() : event.getRawY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN -> {
                dragging = true;
                last = position;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                int delta = Math.round(position - last);
                if (delta != 0) {
                    last = position;
                    onDrag.accept(delta);
                }
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false;
                invalidate();
                onDone.run();
                return true;
            }
            default -> {
                return super.onTouchEvent(event);
            }
        }
    }

    @Override
    public boolean onHoverEvent(@NonNull MotionEvent event) {
        boolean inside = event.getAction() != MotionEvent.ACTION_HOVER_EXIT;
        if (inside != hovered) {
            hovered = inside;
            invalidate();
        }
        return super.onHoverEvent(event);
    }
}
