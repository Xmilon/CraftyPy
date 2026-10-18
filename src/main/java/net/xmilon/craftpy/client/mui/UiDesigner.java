package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import icyllis.modernui.R;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * Visual editor for the in-game windows stored as {@code ui/<id>.json}: a widget tree on the left, the
 * live window in the middle (drawn by the same code that draws it in game) and the properties of the
 * selected widget on the right. It edits the JSON text of the open editor tab; nothing is written to disk until
 * the user saves that tab. UI thread only.
 */
final class UiDesigner {
    private static final Set<String> CONTAINERS = Set.of("vbox", "hbox", "grid", "tabs", "scroll", "canvas");
    private static final List<String> TYPES = List.of("label", "button", "text_input", "text_area", "checkbox",
            "slider", "dropdown", "list", "progress", "image", "item_icon", "canvas", "vbox", "hbox", "grid", "tabs",
            "scroll");
    private static final Set<String> NUMBERS = Set.of("size", "weight", "spacing", "padding", "columns", "min", "max",
            "selected", "width", "height", "x", "y");
    /** Positions and sizes snap to this many dp. */
    static final int GRID = 4;
    /** The smallest width or height a handle makes. */
    static final int MIN_SIZE = 16;
    private static final Set<String> LISTS = Set.of("options", "items");

    private final Context context;
    private final JsonObject window;
    /** Receives the window's JSON text: after every change when {@link #live}, else on Apply. */
    private final Consumer<String> onApply;
    private final boolean live;
    private JsonObject selected;

    private LinearLayout treeColumn;
    private LinearLayout previewHolder;
    private LinearLayout props;

    private UiDesigner(Context context, JsonObject window, Consumer<String> onApply, boolean live) {
        this.context = context;
        this.window = window;
        this.onApply = onApply;
        this.live = live;
        this.selected = window.getAsJsonObject("root");
    }

    /**
     * Opens the designer as a dialog on {@code text} (the JSON of a window; blank or broken text starts a new window).
     * {@code onApply} receives the new JSON text when Apply is pressed.
     */
    static void open(View anchor, String windowId, String text, Consumer<String> onApply) {
        String problem = problem(text);
        if (problem != null) { // Apply would replace the broken text with an empty window
            var popup = Overlays.dialog(anchor, "UI designer - " + windowId, 520, -1);
            var error = label(anchor.getContext(), problem, R.attr.textAppearanceBodyMedium);
            error.setTextColor(IdeColors.ERROR);
            popup.body.addView(error);
            popup.show();
            return;
        }
        var designer = new UiDesigner(anchor.getContext(), parse(windowId, text), onApply, false);
        var popup = Overlays.dialog(anchor, "UI designer - " + windowId, 1000, -1);
        popup.body.addView(designer.build(false), new LinearLayout.LayoutParams(MATCH_PARENT, popup.body.dp(520)));
        var buttons = row(anchor.getContext());
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(button(anchor.getContext(), "Apply to editor", R.attr.buttonTonalStyle, v -> {
            onApply.accept(designer.json());
            popup.dismiss();
        }));
        popup.body.addView(buttons);
        popup.show();
    }

    /**
     * The designer as a view for a narrow tool window: every change goes to {@code onChange} at once (the editor tab
     * holds the text, so Ctrl+Z and Ctrl+S work as usual).
     */
    static View docked(Context context, String windowId, String text, Consumer<String> onChange) {
        return docked(context, windowId, text, onChange, List.of(), path -> { });
    }

    /**
     * As above, keeping the selection across a rebuild: {@code selection} is the path of child indexes from the root to
     * the widget to select (edits typed in the JSON rebuild the designer), {@code onSelect} hears every new selection.
     * Text that is not a window (broken JSON) is shown as an error and never overwritten.
     */
    static View docked(Context context, String windowId, String text, Consumer<String> onChange, List<Integer> selection,
                       Consumer<List<Integer>> onSelect) {
        String problem = problem(text);
        if (problem != null) {
            var error = label(context, problem + "\nFix it in the editor; the designer comes back when it reads again."
                    + " Nothing here writes to the file meanwhile.", R.attr.textAppearanceBodyMedium);
            error.setTextColor(IdeColors.ERROR);
            error.setPadding(error.dp(8), error.dp(8), error.dp(8), error.dp(8));
            return error;
        }
        var designer = new UiDesigner(context, parse(windowId, text), onChange, true);
        designer.onSelect = onSelect;
        JsonObject node = nodeAt(designer.window.getAsJsonObject("root"), selection);
        if (node != null) {
            designer.selected = node;
        }
        return designer.build(true);
    }

    /** Why this text cannot be designed (not JSON, not a window), or null; blank text is a new window. */
    @Nullable
    static String problem(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return "This file is not a window: it must be a JSON object.";
            }
            JsonObject object = parsed.getAsJsonObject();
            if (object.has("root") && !object.get("root").isJsonObject()) {
                return "\"root\" must be an object (the outer box of the window).";
            }
            return null;
        } catch (RuntimeException e) {
            return "This file is not valid JSON: " + e.getMessage();
        }
    }

    private Consumer<List<Integer>> onSelect = path -> { };

    /** The node at {@code path} (child indexes from the root), or null when the path no longer exists. */
    @Nullable
    static JsonObject nodeAt(JsonObject root, List<Integer> path) {
        JsonObject node = root;
        for (int index : path) {
            if (!node.has("children") || !node.get("children").isJsonArray()) {
                return null;
            }
            JsonArray children = node.getAsJsonArray("children");
            if (index < 0 || index >= children.size() || !children.get(index).isJsonObject()) {
                return null;
            }
            node = children.get(index).getAsJsonObject();
        }
        return node;
    }

    /** The path of child indexes from the root to {@code node}; empty for the root or a node not in the tree. */
    static List<Integer> pathOf(JsonObject root, JsonObject node) {
        List<Integer> path = new ArrayList<>();
        return find(root, node, path) ? path : List.of();
    }

    private static boolean find(JsonObject at, JsonObject node, List<Integer> path) {
        if (at == node) {
            return true;
        }
        if (at.has("children") && at.get("children").isJsonArray()) {
            JsonArray children = at.getAsJsonArray("children");
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).isJsonObject()) {
                    path.add(i);
                    if (find(children.get(i).getAsJsonObject(), node, path)) {
                        return true;
                    }
                    path.remove(path.size() - 1);
                }
            }
        }
        return false;
    }

    String json() {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(window) + "\n";
    }

    /** After every edit: the live designer writes the JSON back right away. */
    private void changed() {
        if (live) {
            onApply.accept(json());
        }
    }

    static JsonObject parse(String windowId, String text) {
        try {
            JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();
            if (parsed.has("root") && parsed.get("root").isJsonObject()) {
                parsed.addProperty("id", WindowFragment.str(parsed, "id", windowId));
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // blank or invalid: start fresh
        }
        JsonObject fresh = new JsonObject();
        fresh.addProperty("id", windowId);
        fresh.addProperty("title", windowId);
        JsonArray size = new JsonArray();
        size.add(320);
        size.add(220);
        fresh.add("size", size);
        JsonObject root = newNode("canvas");
        root.addProperty("height", 160);
        fresh.add("root", root);
        return fresh;
    }

    /** Palette, tree, properties and the live window; {@code narrow} stacks them for a side panel. */
    private View build(boolean narrow) {
        treeColumn = new LinearLayout(context);
        treeColumn.setOrientation(LinearLayout.VERTICAL);
        previewHolder = new LinearLayout(context);
        previewHolder.setOrientation(LinearLayout.VERTICAL);
        previewHolder.setGravity(Gravity.CENTER_HORIZONTAL);
        props = new LinearLayout(context);
        props.setOrientation(LinearLayout.VERTICAL);

        var palette = new LinearLayout(context);
        palette.setOrientation(LinearLayout.VERTICAL);
        var tools = row(context);
        tools.addView(button(context, "+ Add", R.attr.buttonTonalStyle, v -> {
            var menu = new icyllis.modernui.widget.PopupMenu(context, v);
            for (int i = 0; i < TYPES.size(); i++) {
                menu.getMenu().add(0, i, i, TYPES.get(i).replace('_', ' '));
            }
            menu.setOnMenuItemClickListener(item -> {
                add(TYPES.get(item.getItemId()));
                return true;
            });
            menu.show();
        }));
        tools.addView(button(context, "Delete", R.attr.borderlessButtonStyle, v -> delete()));
        tools.addView(button(context, "Up", R.attr.borderlessButtonStyle, v -> move(-1)));
        tools.addView(button(context, "Down", R.attr.borderlessButtonStyle, v -> move(1)));
        tools.addView(button(context, "Copy Python", R.attr.borderlessButtonStyle,
                v -> net.xmilon.craftpy.client.IdeHost.get().setClipboard(pythonStub())));
        palette.addView(tools);
        var hint = label(context, "Drag widgets in the window to move them, drag the corner squares to resize.",
                R.attr.textAppearanceBodySmall);
        hint.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        palette.addView(hint);

        var treeScroll = new ScrollView(context);
        treeScroll.addView(treeColumn);
        var propsScroll = new ScrollView(context);
        propsScroll.addView(props);
        var sideways = new icyllis.modernui.widget.HorizontalScrollView(context);
        sideways.addView(previewHolder, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        var previewScroll = new ScrollView(context);
        previewScroll.setBackground(new icyllis.modernui.graphics.drawable.ColorDrawable(IdeColors.EDITOR));
        previewScroll.addView(sideways, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        LinearLayout root = new LinearLayout(context);
        if (narrow) {
            root.setOrientation(LinearLayout.VERTICAL);
            root.addView(palette);
            root.addView(previewScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 5));
            // tree and properties side by side under the window
            var lower = new LinearLayout(context);
            lower.setOrientation(LinearLayout.HORIZONTAL);
            lower.addView(treeScroll, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
            lower.addView(propsScroll, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
            root.addView(lower, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 4));
        } else {
            root.setOrientation(LinearLayout.HORIZONTAL);
            var left = new LinearLayout(context);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(palette);
            left.addView(treeScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
            root.addView(left, new LinearLayout.LayoutParams(root.dp(300), MATCH_PARENT));
            var lpMid = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1);
            lpMid.leftMargin = lpMid.rightMargin = root.dp(8);
            root.addView(previewScroll, lpMid);
            root.addView(propsScroll, new LinearLayout.LayoutParams(root.dp(280), MATCH_PARENT));
        }
        refreshAll();
        return root;
    }

    /** {@code @action} functions for every action name the window uses (close is built in). */
    String pythonStub() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (Entry entry : entries()) {
            for (String key : new String[]{"on_click", "on_change", "on_select", "on_submit"}) {
                String name = WindowFragment.str(entry.node, key, "").trim();
                if (name.matches("[A-Za-z_][A-Za-z0-9_]*") && !name.equals("close")) {
                    names.add(name);
                }
            }
        }
        String id = WindowFragment.str(window, "id", "window");
        StringBuilder out = new StringBuilder("from craftpy import *\n\n# ui.open(player, \"").append(id)
                .append("\") shows this window. ctx.values holds every input by its id.\n");
        for (String name : names) {
            out.append("\n\n@action(\"").append(name).append("\")\ndef ").append(name)
                    .append("(ctx):\n    ctx.player.say(\"").append(name).append(": \", ctx.values)\n");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Tree
    // ------------------------------------------------------------------

    private record Entry(JsonObject node, @Nullable JsonObject parent, int depth) {
    }

    private void collect(JsonObject node, @Nullable JsonObject parent, int depth, List<Entry> out) {
        out.add(new Entry(node, parent, depth));
        if (node.has("children") && node.get("children").isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray("children")) {
                if (child.isJsonObject()) {
                    collect(child.getAsJsonObject(), node, depth + 1, out);
                }
            }
        }
    }

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        collect(window.getAsJsonObject("root"), null, 0, out);
        return out;
    }

    private void refreshAll() {
        refreshTree();
        refreshProps();
        refreshPreview();
    }

    private void select(JsonObject node) {
        selected = node;
        onSelect.accept(pathOf(window.getAsJsonObject("root"), node));
    }

    private void refreshTree() {
        treeColumn.removeAllViews();
        List<Entry> all = entries();
        List<View> rows = new ArrayList<>();
        for (Entry entry : all) {
            String type = WindowFragment.str(entry.node, "type", "?");
            String text = WindowFragment.str(entry.node, "text", WindowFragment.str(entry.node, "label",
                    WindowFragment.str(entry.node, "id", "")));
            var rowView = label(context, "  ".repeat(entry.depth) + (CONTAINERS.contains(type) ? "▾ " : "") + type
                    + (text.isEmpty() ? "" : "  · " + text), R.attr.textAppearanceBodyMedium);
            rowView.setPadding(rowView.dp(8), rowView.dp(5), rowView.dp(8), rowView.dp(5));
            styleChip(rowView, entry.node == selected);
            rowView.setOnClickListener(v -> {
                select(entry.node);
                refreshTree();
                refreshProps();
            });
            if (entry.parent != null) { // the root does not move
                rowView.setTooltipText("Drag onto another widget: above, below, or into a box");
                rowView.setOnTouchListener(new TreeDrag(entry.node, all, rows));
            }
            rows.add(rowView);
            treeColumn.addView(rowView, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
    }

    // ------------------------------------------------------------------
    // Moving a widget by dragging it in the tree
    // ------------------------------------------------------------------

    /** Where a dragged widget goes, relative to the row it is dropped on. */
    enum Drop { BEFORE, AFTER, INTO }

    /**
     * Moves {@code node} next to or into {@code target}. False (and nothing changed) when the move is not possible: the
     * root, a node onto itself or into its own subtree, before/after the root, into a widget that is not a box.
     */
    static boolean moveNode(JsonObject root, JsonObject node, JsonObject target, Drop where) {
        if (node == root || node == target || (find(node, target, new ArrayList<>()))) {
            return false; // the target is inside the dragged node
        }
        JsonObject from = parentOf(root, node);
        if (from == null) {
            return false;
        }
        JsonObject into;
        if (where == Drop.INTO) {
            if (!CONTAINERS.contains(WindowFragment.str(target, "type", ""))) {
                return false;
            }
            into = target;
        } else {
            into = parentOf(root, target);
            if (into == null) {
                return false; // before or after the root
            }
        }
        JsonArray source = from.getAsJsonArray("children");
        source.remove(indexOf(source, node));
        if (!into.has("children") || !into.get("children").isJsonArray()) {
            into.add("children", new JsonArray());
        }
        JsonArray children = into.getAsJsonArray("children");
        List<JsonElement> list = new ArrayList<>();
        children.forEach(list::add);
        int at = where == Drop.INTO ? list.size() : indexOf(children, target) + (where == Drop.AFTER ? 1 : 0);
        list.add(at, node);
        while (children.size() > 0) {
            children.remove(0);
        }
        list.forEach(children::add);
        return true;
    }

    @Nullable
    private static JsonObject parentOf(JsonObject at, JsonObject node) {
        if (at.has("children") && at.get("children").isJsonArray()) {
            for (JsonElement child : at.getAsJsonArray("children")) {
                if (child == node) {
                    return at;
                }
                if (child.isJsonObject()) {
                    JsonObject found = parentOf(child.getAsJsonObject(), node);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    /** The drop zone of a point at {@code fraction} (0 top, 1 bottom) of a row showing {@code target}. */
    static Drop zone(JsonObject target, float fraction) {
        boolean box = CONTAINERS.contains(WindowFragment.str(target, "type", ""));
        if (box) {
            return fraction < 0.3f ? Drop.BEFORE : fraction > 0.7f ? Drop.AFTER : Drop.INTO;
        }
        return fraction < 0.5f ? Drop.BEFORE : Drop.AFTER;
    }

    /** Drags a tree row: past a few pixels it shows where the widget would go; releasing moves it (one undo step). */
    private final class TreeDrag implements View.OnTouchListener {
        private final JsonObject node;
        private final List<Entry> all;
        private final List<View> rows;
        private float downY;
        private boolean dragging;
        private int targetRow = -1;
        private Drop targetZone;

        TreeDrag(JsonObject node, List<Entry> all, List<View> rows) {
            this.node = node;
            this.all = all;
            this.rows = rows;
        }

        @Override
        public boolean onTouch(View v, icyllis.modernui.view.MotionEvent event) {
            int[] at = new int[2];
            v.getLocationInWindow(at);
            float y = at[1] + event.getY();
            switch (event.getAction()) {
                case icyllis.modernui.view.MotionEvent.ACTION_DOWN -> {
                    downY = y;
                    dragging = false;
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true); // a drag is not a scroll of the tree
                    }
                    return false;
                }
                case icyllis.modernui.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging && Math.abs(y - downY) < v.dp(6)) {
                        return false;
                    }
                    if (!dragging) {
                        dragging = true;
                        v.setPressed(false); // the row never sees the release as a click
                    }
                    aim(y);
                    return true;
                }
                case icyllis.modernui.view.MotionEvent.ACTION_UP, icyllis.modernui.view.MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        return false;
                    }
                    dragging = false;
                    int row = targetRow;
                    Drop zone = targetZone;
                    clearMarks();
                    if (event.getAction() == icyllis.modernui.view.MotionEvent.ACTION_UP && row >= 0
                            && moveNode(window.getAsJsonObject("root"), node, all.get(row).node, zone)) {
                        select(node);
                        refreshAll();
                        changed();
                    }
                    return true;
                }
                default -> {
                    return dragging;
                }
            }
        }

        /** Marks the row under the pointer: a line above or below it, or the whole row for "into this box". */
        private void aim(float y) {
            clearMarks();
            for (int i = 0; i < rows.size(); i++) {
                View row = rows.get(i);
                int[] at = new int[2];
                row.getLocationInWindow(at);
                if (y >= at[1] && y < at[1] + row.getHeight()) {
                    Drop zone = zone(all.get(i).node, (y - at[1]) / Math.max(1, row.getHeight()));
                    JsonObject copy = window.getAsJsonObject("root").deepCopy();
                    List<Integer> nodePath = pathOf(window.getAsJsonObject("root"), node);
                    List<Integer> targetPath = pathOf(window.getAsJsonObject("root"), all.get(i).node);
                    JsonObject copiedNode = nodeAt(copy, nodePath);
                    JsonObject copiedTarget = nodeAt(copy, targetPath);
                    // a dry run on a copy: only possible drops are marked
                    if (copiedNode != null && copiedTarget != null && moveNode(copy, copiedNode, copiedTarget, zone)) {
                        targetRow = i;
                        targetZone = zone;
                        row.setBackground(mark(row, zone));
                    }
                    return;
                }
            }
        }

        private void clearMarks() {
            if (targetRow >= 0 && targetRow < rows.size()) {
                styleChip(rows.get(targetRow), all.get(targetRow).node == selected);
            }
            targetRow = -1;
            targetZone = null;
        }
    }

    /** The drop mark: an accent line at the top or bottom of the row, or the row filled for "into". */
    private static icyllis.modernui.graphics.drawable.Drawable mark(View row, Drop zone) {
        return new icyllis.modernui.graphics.drawable.Drawable() {
            private final icyllis.modernui.graphics.Paint paint = new icyllis.modernui.graphics.Paint();

            @Override
            public void draw(@icyllis.modernui.annotation.NonNull icyllis.modernui.graphics.Canvas canvas) {
                var b = getBounds();
                if (zone == Drop.INTO) {
                    paint.setColor(IdeColors.SELECTED);
                    canvas.drawRect(b.left, b.top, b.right, b.bottom, paint);
                    paint.setColor(IdeColors.ACCENT);
                    paint.setStyle(icyllis.modernui.graphics.Paint.STROKE);
                    paint.setStrokeWidth(row.dp(1));
                    canvas.drawRect(b.left, b.top, b.right, b.bottom, paint);
                    paint.setStyle(icyllis.modernui.graphics.Paint.FILL);
                } else {
                    paint.setColor(IdeColors.ACCENT);
                    float y = zone == Drop.BEFORE ? b.top : b.bottom - row.dp(2);
                    canvas.drawRect(b.left, y, b.right, y + row.dp(2), paint);
                }
            }
        };
    }

    private void refreshPreview() {
        previewHolder.removeAllViews();
        int[] size = windowSize();
        var frame = card(context);
        int pad = frame.dp(10);
        frame.setPadding(pad, pad, pad, pad);
        frame.addView(label(context, WindowFragment.str(window, "title", ""), R.attr.textAppearanceTitleMedium));
        java.util.Map<View, JsonObject> shown = new java.util.HashMap<>();
        try {
            JsonObject root = window.getAsJsonObject("root");
            var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            WindowFragment.sized(frame, root, lp);
            frame.addView(WindowFragment.previewView(context, root, shown), lp);
        } catch (RuntimeException e) {
            frame.addView(label(context, "Preview failed: " + e.getMessage(), R.attr.textAppearanceBodySmall));
        }
        // the window as big as in game, with room around it for the handles; the overlay takes every click and drag
        var stage = new icyllis.modernui.widget.FrameLayout(context);
        int margin = stage.dp(8);
        var frameLp = new icyllis.modernui.widget.FrameLayout.LayoutParams(frame.dp(size[0]), frame.dp(size[1]));
        frameLp.leftMargin = frameLp.topMargin = frameLp.rightMargin = frameLp.bottomMargin = margin;
        stage.addView(frame, frameLp);
        stage.addView(new PreviewOverlay(context, shown, frame),
                new icyllis.modernui.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        previewHolder.addView(stage, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
    }

    private int[] windowSize() {
        int[] size = {320, 220};
        if (window.has("size") && window.get("size").isJsonArray() && window.getAsJsonArray("size").size() >= 2) {
            size[0] = Math.max(120, Math.min(900, window.getAsJsonArray("size").get(0).getAsInt()));
            size[1] = Math.max(80, Math.min(600, window.getAsJsonArray("size").get(1).getAsInt()));
        }
        return size;
    }

    /**
     * Drawn over the preview window, like the design surface of a window builder: a click selects the widget under the
     * pointer, a drag moves it (to any spot in a canvas, into another place in a box), the corner squares resize it and
     * the square at the window's bottom-right corner resizes the window. The real widgets under it never react. Each
     * gesture is one change (one undo step in the editor tab).
     */
    private final class PreviewOverlay extends View {
        private enum Mode { NONE, PRESS, MOVE, RESIZE, WINDOW }

        private final java.util.Map<View, JsonObject> shown;
        private final View frame;
        private final icyllis.modernui.graphics.Paint paint = new icyllis.modernui.graphics.Paint();
        private final float density;
        private Mode mode = Mode.NONE;
        private float downX;
        private float downY;
        private int corner; // 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right
        private JsonObject node;
        @Nullable
        private JsonObject parent;
        private View nodeView;
        private int startX;
        private int startY;
        private int startW;
        private int startH;
        @Nullable
        private JsonObject dropTarget;
        private Drop dropZone;
        private boolean changedSomething;

        PreviewOverlay(Context context, java.util.Map<View, JsonObject> shown, View frame) {
            super(context);
            this.shown = shown;
            this.frame = frame;
            this.density = context.getResources().getDisplayMetrics().density;
            setClickable(true);
            setOnTouchListener((v, event) -> handle(event));
        }

        /** {@code view}'s rectangle in this overlay's coordinates: left, top, right, bottom. */
        private float[] rect(View view) {
            int[] mine = new int[2];
            int[] its = new int[2];
            getLocationInWindow(mine);
            view.getLocationInWindow(its);
            float left = its[0] - mine[0];
            float top = its[1] - mine[1];
            return new float[] {left, top, left + view.getWidth(), top + view.getHeight()};
        }

        @Nullable
        private View viewOf(JsonObject which) {
            for (var entry : shown.entrySet()) {
                if (entry.getValue() == which && entry.getKey().isShown()) {
                    return entry.getKey();
                }
            }
            return null;
        }

        /** The deepest (smallest) widget under the point, skipping {@code skip} and what is inside it. */
        @Nullable
        private JsonObject hit(float x, float y, @Nullable JsonObject skip) {
            JsonObject best = null;
            float bestArea = Float.MAX_VALUE;
            for (var entry : shown.entrySet()) {
                if (!entry.getKey().isShown()) {
                    continue;
                }
                if (skip != null && (entry.getValue() == skip || find(skip, entry.getValue(), new ArrayList<>()))) {
                    continue;
                }
                float[] r = rect(entry.getKey());
                float area = (r[2] - r[0]) * (r[3] - r[1]);
                if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3] && area <= bestArea) {
                    best = entry.getValue();
                    bestArea = area;
                }
            }
            return best;
        }

        private boolean inCanvas() {
            return parent != null && "canvas".equals(WindowFragment.str(parent, "type", ""));
        }

        private boolean handle(icyllis.modernui.view.MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            switch (event.getAction()) {
                case icyllis.modernui.view.MotionEvent.ACTION_DOWN -> {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true); // a drag here is not a scroll
                    }
                    downX = x;
                    downY = y;
                    changedSomething = false;
                    float[] w = rect(frame);
                    if (near(x, y, w[2], w[3])) {
                        mode = Mode.WINDOW;
                        int[] size = windowSize();
                        startW = size[0];
                        startH = size[1];
                        return true;
                    }
                    View current = viewOf(selected);
                    if (current != null) {
                        float[] r = rect(current);
                        float[][] corners = {{r[0], r[1]}, {r[2], r[1]}, {r[0], r[3]}, {r[2], r[3]}};
                        for (int i = 0; i < 4; i++) {
                            if (near(x, y, corners[i][0], corners[i][1]) && selected != window.getAsJsonObject("root")) {
                                begin(selected, current);
                                corner = i;
                                mode = Mode.RESIZE;
                                return true;
                            }
                        }
                    }
                    JsonObject under = hit(x, y, null);
                    if (under == null) {
                        mode = Mode.NONE;
                        return true;
                    }
                    View view = viewOf(under);
                    if (under != selected) {
                        select(under);
                        refreshTree();
                        refreshProps();
                    }
                    begin(under, view);
                    mode = Mode.PRESS;
                    invalidate();
                    return true;
                }
                case icyllis.modernui.view.MotionEvent.ACTION_MOVE -> {
                    float dx = (x - downX) / density;
                    float dy = (y - downY) / density;
                    switch (mode) {
                        case PRESS -> {
                            if (Math.abs(x - downX) + Math.abs(y - downY) > dp(4) && parent != null) {
                                mode = Mode.MOVE;
                            }
                        }
                        case MOVE -> drag(x, y, dx, dy);
                        case RESIZE -> resize(dx, dy);
                        case WINDOW -> {
                            JsonArray size = new JsonArray();
                            size.add(Math.max(120, Math.min(900, resized(startW, dx))));
                            size.add(Math.max(80, Math.min(600, resized(startH, dy))));
                            window.add("size", size);
                            var lp = frame.getLayoutParams();
                            lp.width = Math.round(size.get(0).getAsInt() * density);
                            lp.height = Math.round(size.get(1).getAsInt() * density);
                            frame.setLayoutParams(lp);
                            changedSomething = true;
                        }
                        default -> {
                        }
                    }
                    invalidate();
                    return true;
                }
                case icyllis.modernui.view.MotionEvent.ACTION_UP, icyllis.modernui.view.MotionEvent.ACTION_CANCEL -> {
                    boolean up = event.getAction() == icyllis.modernui.view.MotionEvent.ACTION_UP;
                    if (up && mode == Mode.MOVE && !inCanvas() && dropTarget != null
                            && moveNode(window.getAsJsonObject("root"), node, dropTarget, dropZone)) {
                        select(node);
                        changedSomething = true;
                    }
                    mode = Mode.NONE;
                    dropTarget = null;
                    if (changedSomething) {
                        changed();
                        post(UiDesigner.this::refreshAll); // rebuilds this overlay too, after the gesture
                    }
                    invalidate();
                    return true;
                }
                default -> {
                    return true;
                }
            }
        }

        private boolean near(float x, float y, float px, float py) {
            return Math.abs(x - px) <= dp(7) && Math.abs(y - py) <= dp(7);
        }

        private void begin(JsonObject which, @Nullable View view) {
            node = which;
            nodeView = view;
            Entry entry = entryOf(which);
            parent = entry == null ? null : entry.parent;
            startX = intOf(which, "x", 0);
            startY = intOf(which, "y", 0);
            startW = intOf(which, "width", view == null ? 40 : Math.round(view.getWidth() / density));
            startH = intOf(which, "height", view == null ? 20 : Math.round(view.getHeight() / density));
        }

        /** Moves the widget: freely in a canvas, else marks where in the boxes it would go. */
        private void drag(float x, float y, float dx, float dy) {
            if (inCanvas()) {
                View box = viewOf(parent);
                int maxX = box == null ? Integer.MAX_VALUE : Math.round(box.getWidth() / density) - startW;
                int maxY = box == null ? Integer.MAX_VALUE : Math.round(box.getHeight() / density) - startH;
                int nx = moved(startX, dx, maxX);
                int ny = moved(startY, dy, Math.max(maxY, startY)); // a canvas without a height grows downwards
                node.addProperty("x", nx);
                node.addProperty("y", ny);
                if (nodeView != null && nodeView.getLayoutParams() instanceof icyllis.modernui.widget.FrameLayout.LayoutParams lp) {
                    lp.leftMargin = Math.round(nx * density);
                    lp.topMargin = Math.round(ny * density);
                    nodeView.setLayoutParams(lp);
                }
                changedSomething = true;
                return;
            }
            dropTarget = null;
            JsonObject target = hit(x, y, node);
            View targetView = target == null ? null : viewOf(target);
            if (target == null || targetView == null) {
                return;
            }
            Entry targetEntry = entryOf(target);
            boolean across = targetEntry != null && targetEntry.parent != null
                    && "hbox".equals(WindowFragment.str(targetEntry.parent, "type", ""));
            float[] r = rect(targetView);
            float fraction = across ? (x - r[0]) / Math.max(1, r[2] - r[0]) : (y - r[1]) / Math.max(1, r[3] - r[1]);
            Drop zone = zone(target, fraction);
            JsonObject root = window.getAsJsonObject("root");
            JsonObject copy = root.deepCopy();
            JsonObject copiedNode = nodeAt(copy, pathOf(root, node));
            JsonObject copiedTarget = nodeAt(copy, pathOf(root, target));
            if (copiedNode != null && copiedTarget != null && moveNode(copy, copiedNode, copiedTarget, zone)) {
                dropTarget = target;
                dropZone = zone;
            }
        }

        private void resize(float dx, float dy) {
            boolean left = corner == 0 || corner == 2;
            boolean top = corner == 0 || corner == 1;
            int width = resized(startW, left ? -dx : dx);
            int height = resized(startH, top ? -dy : dy);
            node.addProperty("width", width);
            node.addProperty("height", height);
            if (inCanvas()) {
                if (left) {
                    node.addProperty("x", Math.max(0, startX + startW - width));
                }
                if (top) {
                    node.addProperty("y", Math.max(0, startY + startH - height));
                }
            }
            if (nodeView != null) {
                var lp = nodeView.getLayoutParams();
                lp.width = Math.round(width * density);
                lp.height = Math.round(height * density);
                if (lp instanceof icyllis.modernui.widget.FrameLayout.LayoutParams frameLp) {
                    frameLp.leftMargin = Math.round(intOf(node, "x", 0) * density);
                    frameLp.topMargin = Math.round(intOf(node, "y", 0) * density);
                }
                if (lp instanceof LinearLayout.LayoutParams linearLp && linearLp.weight > 0) {
                    linearLp.weight = 0; // a size set by hand wins over the share of the box
                    node.remove("weight");
                }
                nodeView.setLayoutParams(lp);
            }
            changedSomething = true;
        }

        @Override
        protected void onDraw(@icyllis.modernui.annotation.NonNull icyllis.modernui.graphics.Canvas canvas) {
            float handle = dp(3);
            // the window's resize corner
            float[] w = rect(frame);
            paint.setStyle(icyllis.modernui.graphics.Paint.FILL);
            paint.setColor(IdeColors.TEXT_SECONDARY);
            canvas.drawRect(w[2] - handle, w[3] - handle, w[2] + handle, w[3] + handle, paint);
            View current = viewOf(selected);
            if (current != null) {
                float[] r = rect(current);
                paint.setStyle(icyllis.modernui.graphics.Paint.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(IdeColors.ACCENT);
                canvas.drawRect(r[0], r[1], r[2], r[3], paint);
                if (selected != window.getAsJsonObject("root")) {
                    paint.setStyle(icyllis.modernui.graphics.Paint.FILL);
                    for (float[] c : new float[][] {{r[0], r[1]}, {r[2], r[1]}, {r[0], r[3]}, {r[2], r[3]}}) {
                        canvas.drawRect(c[0] - handle, c[1] - handle, c[0] + handle, c[1] + handle, paint);
                    }
                }
            }
            if (mode == Mode.MOVE && dropTarget != null) {
                View target = viewOf(dropTarget);
                if (target != null) {
                    float[] r = rect(target);
                    Entry entry = entryOf(dropTarget);
                    boolean across = entry != null && entry.parent != null
                            && "hbox".equals(WindowFragment.str(entry.parent, "type", ""));
                    paint.setStyle(icyllis.modernui.graphics.Paint.FILL);
                    paint.setColor(IdeColors.INFO);
                    float t = dp(2);
                    switch (dropZone) {
                        case INTO -> {
                            paint.setStyle(icyllis.modernui.graphics.Paint.STROKE);
                            paint.setStrokeWidth(t);
                            canvas.drawRect(r[0], r[1], r[2], r[3], paint);
                        }
                        case BEFORE -> canvas.drawRect(r[0], r[1], across ? r[0] + t : r[2], across ? r[3] : r[1] + t, paint);
                        case AFTER -> canvas.drawRect(across ? r[2] - t : r[0], across ? r[1] : r[3] - t, r[2], r[3], paint);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Structure edits
    // ------------------------------------------------------------------

    private void add(String type) {
        Entry target = entryOf(selected);
        JsonObject container = selected;
        if (!CONTAINERS.contains(WindowFragment.str(selected, "type", ""))) {
            container = target != null && target.parent != null ? target.parent : window.getAsJsonObject("root");
        }
        if (!container.has("children") || !container.get("children").isJsonArray()) {
            container.add("children", new JsonArray());
        }
        JsonObject node = newNode(type);
        if ("canvas".equals(WindowFragment.str(container, "type", ""))) {
            place(container, node);
        }
        container.getAsJsonArray("children").add(node);
        select(node);
        refreshAll();
        changed();
    }

    /** A new widget in a canvas: at the left, below the lowest one, with a size it is usable at. */
    static void place(JsonObject canvas, JsonObject node) {
        int bottom = 0;
        if (canvas.has("children") && canvas.get("children").isJsonArray()) {
            for (JsonElement child : canvas.getAsJsonArray("children")) {
                if (child.isJsonObject()) {
                    JsonObject c = child.getAsJsonObject();
                    bottom = Math.max(bottom, intOf(c, "y", 0) + intOf(c, "height", 20));
                }
            }
        }
        node.addProperty("x", 8);
        node.addProperty("y", snap(bottom + 8));
        switch (WindowFragment.str(node, "type", "")) {
            case "button", "checkbox", "dropdown" -> {
                node.addProperty("width", 96);
                node.addProperty("height", 24);
            }
            case "text_input", "slider", "progress" -> {
                node.addProperty("width", 140);
                node.addProperty("height", 24);
            }
            case "text_area", "list", "vbox", "hbox", "grid", "tabs", "scroll", "canvas" -> {
                node.addProperty("width", 160);
                node.addProperty("height", 80);
            }
            default -> {
            }
        }
    }

    static int intOf(JsonObject node, String key, int fallback) {
        try {
            return node.has(key) ? (int) Math.round(node.get(key).getAsDouble()) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** {@code value} on the {@link #GRID}. */
    static int snap(float value) {
        return Math.round(value / GRID) * GRID;
    }

    /** A position dragged by {@code delta} dp: snapped, never left of or above 0, never past {@code max}. */
    static int moved(int start, float delta, int max) {
        return Math.max(0, Math.min(Math.max(0, max), snap(start + delta)));
    }

    /** A size dragged by {@code delta} dp: snapped and at least {@link #MIN_SIZE}. */
    static int resized(int start, float delta) {
        return Math.max(MIN_SIZE, snap(start + delta));
    }

    private void delete() {
        Entry entry = entryOf(selected);
        if (entry == null || entry.parent == null) {
            return;
        }
        JsonArray siblings = entry.parent.getAsJsonArray("children");
        int index = indexOf(siblings, selected);
        if (index >= 0) {
            siblings.remove(index);
        }
        select(entry.parent);
        refreshAll();
        changed();
    }

    private void move(int delta) {
        Entry entry = entryOf(selected);
        if (entry == null || entry.parent == null) {
            return;
        }
        JsonArray siblings = entry.parent.getAsJsonArray("children");
        int index = indexOf(siblings, selected);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= siblings.size()) {
            return;
        }
        List<JsonElement> all = new ArrayList<>();
        siblings.forEach(all::add);
        all.add(target, all.remove(index));
        while (siblings.size() > 0) {
            siblings.remove(0);
        }
        all.forEach(siblings::add);
        select(selected); // its path changed
        refreshAll();
        changed();
    }

    @Nullable
    private Entry entryOf(JsonObject node) {
        for (Entry entry : entries()) {
            if (entry.node == node) {
                return entry;
            }
        }
        return null;
    }

    private static int indexOf(JsonArray array, JsonObject node) {
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i) == node) {
                return i;
            }
        }
        return -1;
    }

    static JsonObject newNode(String type) {
        JsonObject node = new JsonObject();
        node.addProperty("type", type);
        switch (type) {
            case "label" -> node.addProperty("text", "Label");
            case "button" -> {
                node.addProperty("text", "Button");
                node.addProperty("on_click", "");
            }
            case "text_input", "text_area" -> {
                node.addProperty("id", "field" + (int) (Math.random() * 900 + 100));
                node.addProperty("hint", "Type here");
            }
            case "checkbox" -> {
                node.addProperty("id", "option" + (int) (Math.random() * 900 + 100));
                node.addProperty("label", "Option");
            }
            case "slider" -> {
                node.addProperty("id", "amount" + (int) (Math.random() * 900 + 100));
                node.addProperty("min", 0);
                node.addProperty("max", 100);
                node.addProperty("value", 50);
            }
            case "dropdown" -> {
                node.addProperty("id", "choice" + (int) (Math.random() * 900 + 100));
                JsonArray options = new JsonArray();
                options.add("One");
                options.add("Two");
                node.add("options", options);
            }
            case "list" -> {
                JsonArray items = new JsonArray();
                items.add("First");
                items.add("Second");
                node.add("items", items);
            }
            case "progress" -> node.addProperty("value", 0.5);
            case "image" -> node.addProperty("source", "texture.png");
            case "item_icon" -> node.addProperty("item", "minecraft:diamond");
            case "grid" -> node.addProperty("columns", 2);
            default -> {
            }
        }
        if (CONTAINERS.contains(type)) {
            node.add("children", new JsonArray());
        }
        return node;
    }

    // ------------------------------------------------------------------
    // Properties
    // ------------------------------------------------------------------

    private static List<String> propsOf(String type) {
        List<String> list = new ArrayList<>();
        switch (type) {
            case "label" -> list.addAll(List.of("text", "size", "color", "style"));
            case "button" -> list.addAll(List.of("text", "on_click", "style"));
            case "text_input" -> list.addAll(List.of("id", "value", "hint", "on_submit"));
            case "text_area" -> list.addAll(List.of("id", "value", "hint"));
            case "checkbox" -> list.addAll(List.of("id", "label", "checked", "on_change"));
            case "slider" -> list.addAll(List.of("id", "min", "max", "value", "on_change"));
            case "dropdown" -> list.addAll(List.of("id", "options", "selected", "on_change"));
            case "list" -> list.addAll(List.of("items", "on_select"));
            case "progress" -> list.addAll(List.of("value", "max"));
            case "image" -> list.add("source");
            case "item_icon" -> list.add("item");
            case "vbox", "hbox" -> list.addAll(List.of("spacing", "padding"));
            case "grid" -> list.addAll(List.of("columns", "spacing"));
            default -> {
            }
        }
        if (type.equals("canvas")) {
            list.add("padding");
        }
        list.add("width");
        list.add("height");
        list.add("weight");
        list.add("title");
        return list;
    }

    private void refreshProps() {
        props.removeAllViews();
        boolean isRoot = selected == window.getAsJsonObject("root");
        if (isRoot) {
            props.addView(label(context, "Window", R.attr.textAppearanceTitleSmall));
            for (String key : new String[]{"id", "title", "width", "height"}) {
                props.addView(field(context, key, windowValue(key), value -> setWindow(key, value)));
            }
            props.addView(spacer(context));
        }
        String type = WindowFragment.str(selected, "type", "?");
        props.addView(label(context, "Widget: " + type, R.attr.textAppearanceTitleSmall));
        List<String> keys = new ArrayList<>(propsOf(type));
        Entry entry = entryOf(selected);
        if (entry != null && entry.parent != null && "canvas".equals(WindowFragment.str(entry.parent, "type", ""))) {
            keys.addAll(0, List.of("x", "y")); // placed freely in its canvas
        }
        for (String key : keys) {
            props.addView(field(context, key, nodeValue(selected, key), value -> setProp(key, value)));
        }
    }

    private View spacer(Context context) {
        var view = new View(context);
        view.setMinimumHeight(view.dp(12));
        return view;
    }

    private View field(Context context, String key, String value, Consumer<String> onChange) {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        var name = label(context, key, R.attr.textAppearanceLabelMedium);
        name.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        column.addView(name);
        var input = CraftPyFragment.field(context);
        input.setSingleLine();
        input.setText(value);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onChange.accept(s.toString());
            }
        });
        column.addView(input, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.topMargin = column.dp(6);
        column.setLayoutParams(lp);
        return column;
    }

    private String windowValue(String key) {
        return switch (key) {
            case "width", "height" -> {
                JsonArray size = window.has("size") && window.get("size").isJsonArray() ? window.getAsJsonArray("size") : null;
                int index = key.equals("width") ? 0 : 1;
                yield size != null && size.size() > index ? size.get(index).getAsString() : "";
            }
            default -> WindowFragment.str(window, key, "");
        };
    }

    private void setWindow(String key, String value) {
        if (key.equals("width") || key.equals("height")) {
            JsonArray size = new JsonArray();
            int w = parseInt(windowValue("width"), 320);
            int h = parseInt(windowValue("height"), 220);
            size.add(key.equals("width") ? parseInt(value, w) : w);
            size.add(key.equals("height") ? parseInt(value, h) : h);
            window.add("size", size);
        } else {
            window.addProperty(key, value);
        }
        refreshPreview();
        changed();
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String nodeValue(JsonObject node, String key) {
        if (!node.has(key)) {
            return "";
        }
        JsonElement value = node.get(key);
        if (value.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            value.getAsJsonArray().forEach(e -> parts.add(e.getAsString()));
            return String.join(", ", parts);
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    /** Empty removes the key; numbers, booleans and comma lists are stored as such, everything else as text. */
    private void setProp(String key, String value) {
        String text = value.trim();
        if (text.isEmpty() && !key.equals("on_click")) {
            selected.remove(key);
        } else if (LISTS.contains(key)) {
            JsonArray array = new JsonArray();
            for (String part : value.split(",")) {
                if (!part.trim().isEmpty()) {
                    array.add(part.trim());
                }
            }
            selected.add(key, array);
        } else if (key.equals("checked")) {
            selected.add(key, new JsonPrimitive(text.equalsIgnoreCase("true")));
        } else if (NUMBERS.contains(key) || (key.equals("value") && isNumberWidget())) {
            try {
                double number = Double.parseDouble(text);
                selected.add(key, new JsonPrimitive(number % 1 == 0 ? (Number) Integer.valueOf((int) number)
                        : (Number) Double.valueOf(number)));
            } catch (NumberFormatException e) {
                return; // keep the old value until the text is a number
            }
        } else {
            selected.addProperty(key, value);
        }
        refreshPreview();
        if (key.equals("text") || key.equals("label") || key.equals("id")) {
            refreshTree();
        }
        changed();
    }

    private boolean isNumberWidget() {
        String type = WindowFragment.str(selected, "type", "");
        return type.equals("slider") || type.equals("progress");
    }

    private static icyllis.modernui.graphics.drawable.ShapeDrawable background(Context context) {
        var bg = new icyllis.modernui.graphics.drawable.ShapeDrawable();
        bg.setCornerRadius(context.getResources().getDisplayMetrics().density * 12);
        bg.setColor(color(context, R.attr.colorSurfaceContainerHigh));
        return bg;
    }

    static boolean isContainer(String type) {
        return CONTAINERS.contains(type);
    }
}
