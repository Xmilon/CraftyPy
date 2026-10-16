package net.xmilon.craftpy.client.mui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Rect;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewParent;
import net.minecraft.client.gui.GuiGraphics;
import net.xmilon.craftpy.client.IdeHost;

/**
 * A Modern UI view whose inside is drawn by Minecraft: models, items, mobs and JEI recipe layouts look exactly as in
 * game. The view only reserves the space; {@link McCanvasRenderer} paints it with a {@link GuiGraphics} right after
 * the screen has drawn the Modern UI frame. The UI thread keeps a snapshot of where the view is ({@link #snap}); the
 * render thread only reads it. Outside the game (standalone IDE) the view shows {@link #fallback} instead.
 */
final class McCanvas extends icyllis.modernui.widget.FrameLayout {
    /** Draws the inside. Coordinates are GUI units relative to the view's top left; the mouse is -1 when outside. */
    interface Painter {
        void paint(GuiGraphics g, int width, int height, int mouseX, int mouseY, float partialTick);

        /** A drag of the left button, in GUI units. */
        default void drag(double dx, double dy) {
        }

        /** The wheel: positive is up. */
        default void scroll(double amount) {
        }

        /** A click (press and release without a drag), GUI units, button 0 = left. Render thread. */
        default void click(int x, int y, int button) {
        }
    }

    /** Where the view is, in window pixels; written on the UI thread every frame, read on the render thread. */
    record Snap(boolean visible, int x, int y, int width, int height, int clipLeft, int clipTop, int clipRight, int clipBottom) {
        static final Snap HIDDEN = new Snap(false, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    static final List<McCanvas> LIVE = new CopyOnWriteArrayList<>();

    private volatile Painter painter;
    volatile Snap snap = Snap.HIDDEN;
    private final icyllis.modernui.widget.TextView fallback;
    private final int[] location = new int[2];
    private final Rect clip = new Rect();
    private final Runnable sync = this::sync;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private boolean dragged;

    McCanvas(Context context, Painter painter) {
        super(context);
        this.painter = painter;
        fallback = CraftPyFragment.label(context, "Drawn by Minecraft: open the IDE in game to see it.",
                icyllis.modernui.R.attr.textAppearanceBodySmall);
        fallback.setTextColor(IdeColors.TEXT_SECONDARY);
        fallback.setGravity(icyllis.modernui.view.Gravity.CENTER);
        fallback.setVisibility(live() ? GONE : VISIBLE);
        addView(fallback, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    Painter painter() {
        return painter;
    }

    void setPainter(Painter painter) {
        this.painter = painter;
    }

    void setFallback(String text) {
        fallback.setText(text);
    }

    static boolean live() {
        return IdeHost.get().inGame();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (live()) {
            LIVE.add(this);
            postOnAnimation(sync);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        LIVE.remove(this);
        removeCallbacks(sync);
        snap = Snap.HIDDEN;
        super.onDetachedFromWindow();
    }

    /** Once per frame while attached: where the view is and whether anything covers it. */
    private void sync() {
        if (!isAttachedToWindow()) {
            return;
        }
        if (isShown() && getWidth() > 0 && getHeight() > 0 && !covered() && getGlobalVisibleRect(clip)) {
            getLocationInWindow(location);
            snap = new Snap(true, location[0], location[1], getWidth(), getHeight(), clip.left, clip.top, clip.right, clip.bottom);
        } else {
            snap = Snap.HIDDEN;
        }
        postOnAnimation(sync);
    }

    /** True while a dialog, menu or popup lies over the layer this view is in (Minecraft would paint over it). */
    private boolean covered() {
        View child = this;
        ViewParent parent = getParent();
        while (parent instanceof View parentView) {
            if (parent instanceof Overlays.Host host) {
                return host.indexOfChild(child) < host.getChildCount() - 1;
            }
            child = parentView;
            parent = parentView.getParent();
        }
        return false;
    }

    static double guiScale() {
        return net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        Painter current = painter;
        if (current == null || !live()) {
            return false;
        }
        double scale = guiScale();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                dragged = false;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY) > dp(3)) {
                    dragged = true;
                }
                if (dragged) {
                    current.drag((event.getX() - lastX) / scale, (event.getY() - lastY) / scale);
                }
                lastX = event.getX();
                lastY = event.getY();
            }
            case MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    int x = (int) (event.getX() / scale);
                    int y = (int) (event.getY() / scale);
                    // painters keep their layout on the render thread: hit-test there, not here
                    net.minecraft.client.Minecraft.getInstance().execute(() -> current.click(x, y, 0));
                }
            }
            default -> {
            }
        }
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
        Painter current = painter;
        if (current != null && live() && event.getAction() == MotionEvent.ACTION_SCROLL) {
            float delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (delta != 0) {
                current.scroll(delta);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }
}
