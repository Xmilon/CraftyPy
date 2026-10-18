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
    private static final Set<String> CONTAINERS = Set.of("vbox", "hbox", "grid", "tabs", "scroll");
    private static final List<String> TYPES = List.of("label", "button", "text_input", "text_area", "checkbox",
            "slider", "dropdown", "list", "progress", "image", "item_icon", "vbox", "hbox", "grid", "tabs", "scroll");
    private static final Set<String> NUMBERS = Set.of("size", "weight", "spacing", "padding", "columns", "min", "max",
            "selected");
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
        JsonObject root = newNode("vbox");
        root.addProperty("padding", 4);
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
        var hint = label(context, "Click a widget to add it to the selected box. Buttons call @action(\"name\") in Python:"
                + " type the name in on_click.", R.attr.textAppearanceBodySmall);
        hint.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        palette.addView(hint);
        LinearLayout line = null;
        for (int i = 0; i < TYPES.size(); i++) {
            if (i % 4 == 0) {
                line = row(context);
                palette.addView(line);
            }
            String type = TYPES.get(i);
            var add = button(context, type.replace('_', ' '), R.attr.borderlessButtonStyle, v -> add(type));
            line.addView(add, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        }
        var dragHint = label(context, "Move a widget: drag it in the tree, above or below another, or into a box.",
                R.attr.textAppearanceBodySmall);
        dragHint.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        palette.addView(dragHint);
        var treeButtons = row(context);
        treeButtons.addView(button(context, "Delete", R.attr.borderlessButtonStyle, v -> delete()));
        treeButtons.addView(button(context, "Up", R.attr.borderlessButtonStyle, v -> move(-1)));
        treeButtons.addView(button(context, "Down", R.attr.borderlessButtonStyle, v -> move(1)));
        treeButtons.addView(button(context, "Copy Python", R.attr.borderlessButtonStyle, v -> {
            net.xmilon.craftpy.client.IdeHost.get().setClipboard(pythonStub());
        }));
        palette.addView(treeButtons);

        var treeScroll = new ScrollView(context);
        treeScroll.addView(treeColumn);
        var propsScroll = new ScrollView(context);
        propsScroll.addView(props);
        var previewScroll = new ScrollView(context);
        previewScroll.addView(previewHolder, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        LinearLayout root = new LinearLayout(context);
        if (narrow) {
            root.setOrientation(LinearLayout.VERTICAL);
            root.addView(palette);
            root.addView(previewScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 4));
            root.addView(treeScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 2));
            root.addView(propsScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 3));
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
        int[] size = {320, 220};
        if (window.has("size") && window.get("size").isJsonArray() && window.getAsJsonArray("size").size() >= 2) {
            size[0] = window.getAsJsonArray("size").get(0).getAsInt();
            size[1] = window.getAsJsonArray("size").get(1).getAsInt();
        }
        var frame = card(context);
        int pad = frame.dp(10);
        frame.setPadding(pad, pad, pad, pad);
        frame.addView(label(context, WindowFragment.str(window, "title", ""), R.attr.textAppearanceTitleMedium));
        try {
            frame.addView(WindowFragment.previewView(context, window.getAsJsonObject("root")));
        } catch (RuntimeException e) {
            frame.addView(label(context, "Preview failed: " + e.getMessage(), R.attr.textAppearanceBodySmall));
        }
        previewHolder.addView(frame, new LinearLayout.LayoutParams(frame.dp(Math.max(120, size[0])), WRAP_CONTENT));
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
        container.getAsJsonArray("children").add(node);
        select(node);
        refreshAll();
        changed();
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
        for (String key : propsOf(type)) {
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
