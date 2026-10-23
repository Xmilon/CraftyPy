package net.xmilon.craftpy.client.mui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.text.StaticLayout;
import icyllis.modernui.text.TextPaint;
import icyllis.modernui.text.TextUtils;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import net.xmilon.craftpy.client.graph.Graph;
import net.xmilon.craftpy.util.CraftPyExecutors;

/**
 * Draws a {@link Graph} of cards on a canvas you move around, like the JSON Visualizer of VS Code: drag to pan, the wheel zooms
 * around the pointer, a click on a card's title folds or unfolds what is under it, a click on a row selects it. Only the cards
 * and lines on screen are drawn and texts are measured once, so big graphs stay smooth; a big graph is laid out on a worker
 * thread. Texts use the editor font (fixed width), which is what lets the layout size cards without the UI. UI thread only.
 */
final class GraphView extends View {
    /** A click on a row (or the title: row -1) of a card. */
    interface OnSelect {
        void selected(Graph.Node node, int row);
    }

    private static final int BIG = 400;
    private static final float MIN_SCALE = 0.2f, MAX_SCALE = 2f;

    private final Paint paint = new Paint();
    private final TextPaint text = TextPaint.obtain();
    private final Map<String, StaticLayout> layouts = new HashMap<>();
    private final Graph.Metrics metrics;
    private final float charWidth;
    private Graph graph;
    private boolean laidOut;
    private IntUnaryOperator accentColor = accent -> accent == 1 ? IdeColors.WARNING : IdeColors.INFO;
    private OnSelect onSelect = (node, row) -> { };
    private float tx, ty, scale = 1;
    private boolean fitPending = true;
    private int selectedNode = -1, selectedRow = -2;
    private List<Integer> matches = List.of();
    private int match = -1;
    private float downX, downY, lastX, lastY;
    private boolean dragging;
    private int layoutRun;

    GraphView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        text.setTypeface(EditorFont.get());
        text.setTextSize(dp(12));
        float measured = text.measureTextRun("MMMMMMMMMM", 0, 10, false, null) / 10f;
        charWidth = measured > 0 ? measured : dp(7);
        metrics = new Graph.Metrics(charWidth, dp(18), dp(24), dp(8), dp(56), dp(14), 44);
    }

    void setAccentColor(IntUnaryOperator colors) {
        this.accentColor = colors;
        invalidate();
    }

    void setOnSelect(OnSelect listener) {
        this.onSelect = listener;
    }

    @Nullable
    Graph graph() {
        return graph;
    }

    boolean laidOut() {
        return laidOut;
    }

    /** Shows a graph; a big one is laid out on a worker thread first (the old one stays until it is ready). */
    void setGraph(Graph next, boolean fit) {
        int run = ++layoutRun;
        if (next.nodes.size() < BIG) {
            next.layout(metrics);
            apply(next, fit);
            return;
        }
        CraftPyExecutors.supplyAsync(() -> {
            next.layout(metrics);
            return next;
        }).thenAccept(done -> post(() -> {
            if (run == layoutRun) apply(done, fit);
        }));
    }

    private void apply(Graph next, boolean fit) {
        graph = next;
        laidOut = true;
        selectedNode = -1;
        selectedRow = -2;
        matches = List.of();
        match = -1;
        fitPending = fit || fitPending;
        if (getWidth() > 0 && fitPending) fit();
        invalidate();
    }

    /** Lays out again after folding or a search opened cards, keeping {@code anchor} where it is on screen. */
    private void relayout(int anchor) {
        if (graph == null) return;
        double ax = anchor >= 0 ? graph.nodes.get(anchor).x : 0, ay = anchor >= 0 ? graph.nodes.get(anchor).y : 0;
        graph.layout(metrics);
        if (anchor >= 0) {
            tx -= (float) ((graph.nodes.get(anchor).x - ax) * scale);
            ty -= (float) ((graph.nodes.get(anchor).y - ay) * scale);
        }
        invalidate();
    }

    /** Zooms and moves so every shown card fits (never bigger than 1:1). */
    void fit() {
        fitPending = false;
        if (graph == null || getWidth() <= 0) {
            fitPending = true;
            return;
        }
        double[] b = graph.bounds();
        double w = Math.max(1, b[2] - b[0]), h = Math.max(1, b[3] - b[1]);
        int pad = dp(24);
        scale = (float) Math.max(MIN_SCALE, Math.min(1, Math.min((getWidth() - 2 * pad) / w, (getHeight() - 2 * pad) / h)));
        tx = (float) ((getWidth() - w * scale) / 2 - b[0] * scale);
        ty = (float) ((getHeight() - h * scale) / 2 - b[1] * scale);
        invalidate();
    }

    void zoom(float factor, float aroundX, float aroundY) {
        float next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
        tx = aroundX - (aroundX - tx) * (next / scale);
        ty = aroundY - (aroundY - ty) * (next / scale);
        scale = next;
        invalidate();
    }

    void foldBelow(int depth) {
        if (graph == null) return;
        graph.foldBelow(depth);
        graph.layout(metrics);
        fit();
    }

    /** Marks the cards that contain {@code query} and moves to the first; returns how many there are. */
    int search(String query) {
        if (graph == null) return 0;
        matches = graph.search(query);
        match = matches.isEmpty() ? -1 : 0;
        relayout(-1);
        if (match >= 0) center(matches.get(0));
        invalidate();
        return matches.size();
    }

    /** Moves to the next (or previous) found card; returns its number counting from 1, or 0. */
    int next(int direction) {
        if (matches.isEmpty()) return 0;
        match = Math.floorMod(match + direction, matches.size());
        center(matches.get(match));
        return match + 1;
    }

    /** Marks a card as selected; {@code center} also moves it to the middle (a pick from elsewhere, not a click here). */
    void select(int node, boolean center) {
        if (node == selectedNode && !center) return;
        selectedNode = node;
        selectedRow = -1;
        if (node >= 0 && center) center(node);
        invalidate();
    }

    private void center(int node) {
        Graph.Node at = graph.nodes.get(node);
        if (!at.shown) return;
        tx = (float) (getWidth() / 2f - (at.x + at.w / 2) * scale);
        ty = (float) (getHeight() / 2f - (at.y + at.h / 2) * scale);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (fitPending && graph != null && laidOut) fit();
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        paint.setStroke(false);
        paint.setColor(IdeColors.EDITOR);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        if (graph == null || !laidOut) {
            label(canvas, graph == null ? "Nothing to show" : "Laying out ...", dp(16), dp(16), IdeColors.TEXT_SECONDARY, getWidth());
            return;
        }
        // what of the graph is on screen, in graph units
        double left = -tx / scale, top = -ty / scale, right = left + getWidth() / scale, bottom = top + getHeight() / scale;
        canvas.save();
        canvas.translate(tx, ty);
        canvas.scale(scale, scale);
        paint.setStroke(true);
        paint.setStrokeWidth(Math.max(1f, dp(1.5f)));
        for (Graph.Edge edge : graph.edges) {
            Graph.Node from = graph.nodes.get(edge.from()), to = graph.nodes.get(edge.to());
            if (!from.shown || !to.shown) continue;
            float x0, y0, x1, y1;
            if (graph.tree) {
                x0 = (float) (from.x + from.w);
                y0 = (float) (from.y + metrics.headerHeight() + (edge.row() + 0.5) * metrics.rowHeight());
                x1 = (float) to.x;
                y1 = (float) (to.y + metrics.headerHeight() / 2);
            } else {
                // what a card needs is to its left: from its left side to the needed card's right side
                x0 = (float) from.x;
                y0 = (float) (from.y + from.h / 2);
                x1 = (float) (to.x + to.w);
                y1 = (float) (to.y + to.h / 2);
            }
            if (Math.max(x0, x1) < left || Math.min(x0, x1) > right || Math.max(y0, y1) < top || Math.min(y0, y1) > bottom) continue;
            paint.setColor(edgeColor(edge.kind()));
            float mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
            canvas.drawBezier(x0, y0, mx, y0, mx, my, paint);
            canvas.drawBezier(mx, my, mx, y1, x1, y1, paint);
        }
        paint.setStroke(false);
        for (Graph.Node node : graph.nodes) {
            if (!node.shown || node.x > right || node.x + node.w < left || node.y > bottom || node.y + node.h < top) continue;
            card(canvas, node);
        }
        canvas.restore();
        if (graph.notShown > 0) {
            label(canvas, graph.notShown + " more objects not shown (the graph keeps the first " + graph.nodes.size() + ")",
                    dp(8), getHeight() - dp(22), IdeColors.WARNING, getWidth());
        }
    }

    private void card(Canvas canvas, Graph.Node node) {
        float x = (float) node.x, y = (float) node.y, w = (float) node.w, h = (float) node.h;
        float radius = dp(6);
        int accent = accentColor.applyAsInt(node.accent);
        boolean found = match >= 0 && matches.contains(node.id);
        boolean current = match >= 0 && matches.get(match) == node.id;
        paint.setColor(found ? IdeColors.ACCENT : node.id == selectedNode ? IdeColors.FOCUS : IdeColors.BORDER);
        float border = found || node.id == selectedNode ? dp(2) : dp(1);
        canvas.drawRoundRect(x - border, y - border, x + w + border, y + h + border, radius + border, paint);
        paint.setColor(IdeColors.RAISED);
        canvas.drawRoundRect(x, y, x + w, y + h, radius, paint);
        paint.setColor(current ? IdeColors.ACCENT_SOFT : (accent & 0x00FFFFFF) | 0x30000000);
        canvas.drawRoundRect(x, y, x + w, y + (float) metrics.headerHeight(), radius, paint);
        paint.setColor(accent);
        canvas.drawRect(x, y + (float) metrics.headerHeight() - dp(2), x + w, y + (float) metrics.headerHeight(), paint);
        float inner = w - 2 * (float) metrics.padding();
        String title = node.children.isEmpty() || !graph.tree ? node.title : (node.folded ? "▸ " : "▾ ") + node.title;
        label(canvas, title, x + (float) metrics.padding(), y + (float) (metrics.headerHeight() - metrics.rowHeight()) / 2 + dp(1),
                IdeColors.TEXT, (int) inner);
        float rowY = y + (float) metrics.headerHeight() + dp(1);
        for (int r = 0; r < node.rows.size(); r++) {
            Graph.Row row = node.rows.get(r);
            if (node.id == selectedNode && r == selectedRow) {
                paint.setColor(IdeColors.SELECTED);
                canvas.drawRect(x + 1, rowY, x + w - 1, rowY + (float) metrics.rowHeight(), paint);
            }
            float rx = x + (float) metrics.padding();
            if (!row.key.isEmpty()) {
                String key = row.key + ": ";
                label(canvas, key, rx, rowY, IdeColors.TEXT_SECONDARY, (int) inner);
                rx += Math.min(inner, charWidth * key.length());
            }
            label(canvas, row.value, rx, rowY, valueColor(row.kind), (int) Math.max(charWidth, x + w - metrics.padding() - rx));
            rowY += (float) metrics.rowHeight();
        }
    }

    private static int valueColor(int kind) {
        return switch (kind) {
            case Graph.STRING -> IdeColors.SUCCESS;
            case Graph.NUMBER -> IdeColors.INFO;
            case Graph.BOOL -> IdeColors.WARNING;
            case Graph.NULL, Graph.MORE -> IdeColors.TEXT_DISABLED;
            case Graph.CHILD -> IdeColors.TEXT_SECONDARY;
            default -> IdeColors.TEXT;
        };
    }

    private static int edgeColor(String kind) {
        int color = switch (kind) {
            case "required" -> IdeColors.TEXT_SECONDARY;
            case "optional" -> IdeColors.INFO;
            case "incompatible", "discouraged" -> IdeColors.ERROR;
            case "imports" -> IdeColors.SUCCESS;
            case "uses" -> IdeColors.WARNING;
            default -> IdeColors.TEXT_DISABLED;
        };
        return (color & 0x00FFFFFF) | 0xB0000000;
    }

    /** One line of text, cut with "..." at {@code width} pixels; layouts are kept, so each text is measured once. */
    private void label(Canvas canvas, String value, float x, float y, int argb, int width) {
        String key = width + "|" + value;
        StaticLayout layout = layouts.get(key);
        if (layout == null) {
            if (layouts.size() > 4000) layouts.clear();
            layout = StaticLayout.builder(value, 0, value.length(), text, Math.max(1, width)).setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
            layouts.put(key, layout);
        }
        text.setColor(argb);
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        float x = event.getX(), y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN -> {
                requestFocus();
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = lastX = x;
                downY = lastY = y;
                dragging = false;
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!dragging && Math.abs(x - downX) + Math.abs(y - downY) > dp(4)) dragging = true;
                if (dragging) {
                    tx += x - lastX;
                    ty += y - lastY;
                    lastX = x;
                    lastY = y;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                getParent().requestDisallowInterceptTouchEvent(false);
                if (!dragging) click(x, y);
                dragging = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                getParent().requestDisallowInterceptTouchEvent(false);
                dragging = false;
                return true;
            }
            default -> {
                return super.onTouchEvent(event);
            }
        }
    }

    private void click(float x, float y) {
        if (graph == null || !laidOut) return;
        double gx = (x - tx) / scale, gy = (y - ty) / scale;
        int node = graph.nodeAt(gx, gy);
        if (node < 0) return;
        int row = graph.rowAt(node, gy, metrics);
        Graph.Node at = graph.nodes.get(node);
        if (row == -1 && graph.tree && !at.children.isEmpty()) {
            at.folded = !at.folded;
            relayout(node);
        } else if (row >= 0 && at.rows.get(row).child >= 0 && graph.nodes.get(at.rows.get(row).child).shown) {
            // a row that holds an object or array leads to it
            selectedNode = at.rows.get(row).child;
            selectedRow = -1;
            center(selectedNode);
            onSelect.selected(graph.nodes.get(selectedNode), -1);
            return;
        }
        selectedNode = node;
        selectedRow = row;
        invalidate();
        onSelect.selected(at, row);
    }

    @Override
    public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
            float wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (wheel != 0) {
                zoom((float) Math.pow(1.15, wheel), event.getX(), event.getY());
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEY_EQUAL, KeyEvent.KEY_KP_ADD -> zoom(1.2f, getWidth() / 2f, getHeight() / 2f);
            case KeyEvent.KEY_MINUS, KeyEvent.KEY_KP_SUBTRACT -> zoom(1 / 1.2f, getWidth() / 2f, getHeight() / 2f);
            case KeyEvent.KEY_0 -> fit();
            case KeyEvent.KEY_LEFT -> pan(dp(60), 0);
            case KeyEvent.KEY_RIGHT -> pan(-dp(60), 0);
            case KeyEvent.KEY_UP -> pan(0, dp(60));
            case KeyEvent.KEY_DOWN -> pan(0, -dp(60));
            default -> {
                return super.onKeyDown(keyCode, event);
            }
        }
        return true;
    }

    private void pan(float dx, float dy) {
        tx += dx;
        ty += dy;
        invalidate();
    }
}
