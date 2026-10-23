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
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;

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
            "slider", "dropdown", "list", "progress", "image", "item_icon", "video", "audio", "canvas", "vbox", "hbox", "grid", "tabs",
            "scroll");
    private static final Set<String> NUMBERS = Set.of("size", "weight", "spacing", "padding", "columns", "min", "max",
            "selected", "width", "height", "x", "y", "speed", "volume", "position", "opacity", "z");
    private static final Set<String> BOOLEANS = Set.of("checked", "visible", "enabled", "autoplay", "loop", "muted", "controls");
    private static final List<String> FITS = List.of("contain", "cover", "stretch");
    /** Positions and sizes snap to this many dp. */
    static final int GRID = 4;
    /** The smallest width or height a handle makes. */
    static final int MIN_SIZE = 16;
    static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8;
    private static final int[] HANDLE_EDGES = {LEFT | TOP, RIGHT | TOP, LEFT | BOTTOM, RIGHT | BOTTOM,
            TOP, RIGHT, BOTTOM, LEFT};
    private static final Set<String> LISTS = Set.of("options", "items");

    private final Context context;
    private final JsonObject window;
    /** Every design edit goes through the editor's existing document and undo history. */
    private final Consumer<String> onApply;
    record EditorActions(Runnable undo, Runnable redo, Runnable save) { }
    private final EditorActions actions;
    private JsonObject selected;

    private LinearLayout treeColumn;
    private LinearLayout previewHolder;
    private LinearLayout props;
    private PreviewOverlay preview;
    private View previewViewport;
    private View workspace;
    private LinearLayout toolbox;
    private TextView selectionInfo;
    private TextView bindingStatus;
    private final List<String> bindingProblems = new ArrayList<>();
    private final java.util.Map<String, View> toolButtons = new java.util.HashMap<>();
    private String placementType;
    private boolean showGrid = true;
    private float zoom = 1;
    private View stage;
    private View zoomHolder;

    private UiDesigner(Context context, JsonObject window, Consumer<String> onApply, EditorActions actions) {
        this.context = context;
        this.window = window;
        this.onApply = onApply;
        this.actions = actions;
        this.selected = window.getAsJsonObject("root");
    }

    static UiDesigner create(Context context, String windowId, String text, Consumer<String> onChange,
                             List<Integer> selection, Consumer<List<Integer>> onSelect, EditorActions actions) {
        if (problem(text) != null) {
            throw new IllegalArgumentException(problem(text));
        }
        var designer = new UiDesigner(context, parse(windowId, text), onChange, actions);
        designer.onSelect = onSelect;
        JsonObject node = nodeAt(designer.window.getAsJsonObject("root"), selection);
        if (node != null) {
            designer.selected = node;
        }
        designer.workspace = designer.build();
        return designer;
    }

    View view() {
        return workspace;
    }

    static boolean isWindowFile(@Nullable String path) {
        return path != null && path.startsWith("ui/") && path.endsWith(".json");
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
            if (!object.has("root")) {
                return "This file has no root widget. Use Source to edit it.";
            }
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
        onApply.accept(json());
        updateSelectionInfo();
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

    /** The designer owns the main editor area; the game still renders the very same widget tree. */
    private View build() {
        treeColumn = new LinearLayout(context);
        treeColumn.setOrientation(LinearLayout.VERTICAL);
        previewHolder = new LinearLayout(context);
        previewHolder.setOrientation(LinearLayout.VERTICAL);
        previewHolder.setGravity(Gravity.CENTER_HORIZONTAL);
        previewHolder.setPadding(previewHolder.dp(24), previewHolder.dp(24), previewHolder.dp(24), previewHolder.dp(24));
        props = new LinearLayout(context);
        props.setOrientation(LinearLayout.VERTICAL);
        props.setPadding(props.dp(10), 0, props.dp(10), props.dp(10));
        var root = new LinearLayout(context) {
            @Override
            public boolean dispatchKeyEvent(@icyllis.modernui.annotation.NonNull KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed()) {
                    switch (event.getKeyCode()) {
                        case KeyEvent.KEY_S -> actions.save.run();
                        case KeyEvent.KEY_Z -> {
                            if (event.isShiftPressed()) actions.redo.run(); else actions.undo.run();
                        }
                        case KeyEvent.KEY_Y -> actions.redo.run();
                        default -> { return super.dispatchKeyEvent(event); }
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        var tools = row(context);
        tools.setPadding(tools.dp(6), tools.dp(4), tools.dp(6), tools.dp(4));
        tools.addView(button(context, "Select", R.attr.buttonTonalStyle, v -> chooseTool(null)));
        tools.addView(button(context, "Window", R.attr.borderlessButtonStyle, v -> {
            select(window.getAsJsonObject("root"));
            refreshAll();
        }));
        tools.addView(button(context, "Duplicate", R.attr.borderlessButtonStyle, v -> duplicate()));
        tools.addView(button(context, "Delete", R.attr.borderlessButtonStyle, v -> delete()));
        tools.addView(button(context, "Up", R.attr.borderlessButtonStyle, v -> move(-1)));
        tools.addView(button(context, "Down", R.attr.borderlessButtonStyle, v -> move(1)));
        tools.addView(button(context, "Grid", R.attr.borderlessButtonStyle, v -> {
            showGrid = !showGrid;
            styleChip(v, showGrid);
            preview.invalidate();
        }));
        var zoomLabel = label(context, "100%", R.attr.textAppearanceLabelMedium);
        tools.addView(button(context, "−", R.attr.borderlessButtonStyle, v -> setZoom(zoom - 0.25f, zoomLabel)));
        tools.addView(zoomLabel);
        tools.addView(button(context, "+", R.attr.borderlessButtonStyle, v -> setZoom(zoom + 0.25f, zoomLabel)));
        tools.addView(button(context, "Copy Python", R.attr.borderlessButtonStyle,
                v -> net.xmilon.craftpy.client.IdeHost.get().setClipboard(pythonStub())));
        var toolScroll = new icyllis.modernui.widget.HorizontalScrollView(context);
        toolScroll.addView(tools);
        root.addView(toolScroll);
        var left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackground(new icyllis.modernui.graphics.drawable.ColorDrawable(IdeColors.PANEL));
        left.addView(section("Toolbox"));
        var search = CraftPyFragment.field(context);
        search.setSingleLine();
        search.setHint("Search components");
        left.addView(search, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        toolbox = new LinearLayout(context);
        toolbox.setOrientation(LinearLayout.VERTICAL);
        var toolboxScroll = new ScrollView(context);
        toolboxScroll.addView(toolbox);
        left.addView(toolboxScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 3));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { refreshToolbox(s.toString()); }
        });
        refreshToolbox("");
        left.addView(section("Component hierarchy"));
        var treeScroll = new ScrollView(context);
        treeScroll.addView(treeColumn);
        left.addView(treeScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 2));
        var right = new LinearLayout(context);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackground(new icyllis.modernui.graphics.drawable.ColorDrawable(IdeColors.PANEL));
        right.addView(section("Properties & events"));
        var propsScroll = new ScrollView(context);
        propsScroll.addView(props);
        right.addView(propsScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        var sideways = new icyllis.modernui.widget.HorizontalScrollView(context);
        sideways.addView(previewHolder, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        var previewScroll = new ScrollView(context);
        previewViewport = previewScroll;
        previewScroll.setBackground(new icyllis.modernui.graphics.drawable.ColorDrawable(IdeColors.EDITOR));
        previewScroll.addView(sideways, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        var body = row(context);
        body.setBaselineAligned(false);
        body.addView(left, new LinearLayout.LayoutParams(body.dp(180), MATCH_PARENT));
        body.addView(new Splitter(context, true, dx -> resizePane(left, dx), () -> { }),
                new LinearLayout.LayoutParams(body.dp(5), MATCH_PARENT));
        body.addView(previewScroll, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        body.addView(new Splitter(context, true, dx -> resizePane(right, -dx), () -> { }),
                new LinearLayout.LayoutParams(body.dp(5), MATCH_PARENT));
        body.addView(right, new LinearLayout.LayoutParams(body.dp(220), MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        selectionInfo = label(context, "", R.attr.textAppearanceBodySmall);
        selectionInfo.setTextColor(IdeColors.TEXT_SECONDARY);
        selectionInfo.setPadding(root.dp(10), root.dp(4), root.dp(10), root.dp(4));
        root.addView(selectionInfo);
        refreshAll();
        return root;
    }

    private TextView section(String text) {
        var title = label(context, text, R.attr.textAppearanceLabelLarge);
        title.setTextColor(IdeColors.TEXT_SECONDARY);
        title.setPadding(title.dp(10), title.dp(8), title.dp(8), title.dp(6));
        return title;
    }

    private void resizePane(View pane, int dx) {
        var lp = pane.getLayoutParams();
        lp.width = Math.clamp(lp.width + dx, pane.dp(120), Math.max(pane.dp(120), workspace.getWidth() / 3));
        pane.setLayoutParams(lp);
    }

    static String displayName(String type) {
        if (type.isEmpty()) return "Widget";
        return switch (type) {
            case "vbox" -> "Vertical layout";
            case "hbox" -> "Horizontal layout";
            case "canvas" -> "Free layout";
            case "grid" -> "Grid layout";
            case "tabs" -> "Tab layout";
            case "scroll" -> "Scroll area";
            case "text_input" -> "Text field";
            case "text_area" -> "Text area";
            case "item_icon" -> "Item icon";
            case "progress" -> "Progress bar";
            default -> Character.toUpperCase(type.charAt(0)) + type.substring(1);
        };
    }

    static List<String> toolboxTypes(String query) {
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        return TYPES.stream().filter(type -> type.contains(needle)
                || displayName(type).toLowerCase(java.util.Locale.ROOT).contains(needle)).toList();
    }

    private void refreshToolbox(String query) {
        toolbox.removeAllViews();
        toolButtons.clear();
        List<String> matching = toolboxTypes(query);
        for (boolean layouts : new boolean[] {false, true}) {
            List<String> group = matching.stream().filter(type -> isContainer(type) == layouts).toList();
            if (group.isEmpty()) continue;
            toolbox.addView(section(layouts ? "Containers" : "Controls"));
            var tiles = new FlowLayout(context);
            tiles.setPadding(tiles.dp(6), tiles.dp(2), tiles.dp(6), tiles.dp(6));
            toolbox.addView(tiles, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            for (String type : group) {
                var item = label(context, displayName(type), R.attr.textAppearanceBodyMedium);
                item.setPadding(item.dp(9), item.dp(5), item.dp(9), item.dp(5));
                item.setClickable(true);
                item.setTooltipText("Drag to the design surface, or click then click the window to place");
                item.setOnClickListener(v -> chooseTool(type.equals(placementType) ? null : type));
                item.setOnTouchListener(new PaletteDrag(type));
                styleChip(item, type.equals(placementType));
                tiles.addView(item);
                toolButtons.put(type, item);
            }
        }
        if (matching.isEmpty()) toolbox.addView(section("No matching components"));
    }

    private void chooseTool(@Nullable String type) {
        placementType = type;
        toolButtons.forEach((name, view) -> styleChip(view, name.equals(type)));
        updateSelectionInfo();
    }

    private void updateSelectionInfo() {
        if (selectionInfo == null) return;
        selectionInfo.setText(placementType == null
                ? displayName(WindowFragment.str(selected, "type", "canvas")) + "  ·  "
                + nodeValue(selected, "width") + " × " + nodeValue(selected, "height")
                + "  ·  4 px snap  ·  Arrows move · Shift+arrows move faster · Delete removes"
                : "Place " + displayName(placementType) + ": click in the window, or drag from the toolbox. Esc returns to Select.");
    }

    private void setZoom(float scale, TextView label) {
        zoom = Math.clamp(scale, 0.5f, 2f);
        label.setText(Math.round(zoom * 100) + "%");
        refreshPreview();
    }

    /** Esc is routed here before the editor leaves the page. */
    boolean back() {
        if (preview != null && preview.mode != PreviewOverlay.Mode.NONE && preview.beforeGesture != null) {
            restore(window, preview.beforeGesture);
            select(nodeAt(window.getAsJsonObject("root"), preview.beforeSelection));
            preview.mode = PreviewOverlay.Mode.NONE;
            preview.getParent().requestDisallowInterceptTouchEvent(false);
            refreshAll();
            return true;
        }
        if (placementType != null) {
            chooseTool(null);
            if (preview != null) {
                preview.paletteNode = null;
                preview.dropTarget = null;
                preview.invalidate();
            }
            return true;
        }
        if (selected != window.getAsJsonObject("root")) {
            select(window.getAsJsonObject("root"));
            refreshAll();
            return true;
        }
        return false;
    }

    private boolean designKey(int key, KeyEvent event) {
        if (event.isCtrlPressed() && key == KeyEvent.KEY_D) {
            duplicate();
            return true;
        }
        if (!event.isCtrlPressed() && key == KeyEvent.KEY_DELETE) {
            delete();
            return true;
        }
        int dx = key == KeyEvent.KEY_LEFT ? -1 : key == KeyEvent.KEY_RIGHT ? 1 : 0;
        int dy = key == KeyEvent.KEY_UP ? -1 : key == KeyEvent.KEY_DOWN ? 1 : 0;
        JsonObject parent = parentOf(window.getAsJsonObject("root"), selected);
        if ((dx == 0 && dy == 0) || event.isCtrlPressed() || parent == null
                || !"canvas".equals(WindowFragment.str(parent, "type", ""))) return false;
        View box = preview.viewOf(parent);
        View widget = preview.viewOf(selected);
        if (box == null || widget == null) return false;
        float density = context.getResources().getDisplayMetrics().density;
        int step = event.isShiftPressed() ? GRID * 5 : GRID;
        selected.addProperty("x", moved(intOf(selected, "x", 0), dx * step,
                Math.round((box.getWidth() - box.getPaddingLeft() - box.getPaddingRight() - widget.getWidth()) / density)));
        selected.addProperty("y", moved(intOf(selected, "y", 0), dy * step,
                Math.round((box.getHeight() - box.getPaddingTop() - box.getPaddingBottom() - widget.getHeight()) / density)));
        changed();
        refreshAll();
        preview.requestFocus();
        return true;
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

    /** Boxes whose children are folded away in the hierarchy (by identity: the nodes are edited in place). */
    private final java.util.Set<JsonObject> collapsed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private static boolean hasChildren(JsonObject node) {
        return node.has("children") && node.get("children").isJsonArray() && !node.getAsJsonArray("children").isEmpty();
    }

    private void collect(JsonObject node, @Nullable JsonObject parent, int depth, List<Entry> out) {
        out.add(new Entry(node, parent, depth));
        if (!collapsed.contains(node) && node.has("children") && node.get("children").isJsonArray()) {
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
        boolean focused = preview != null && preview.hasFocus();
        refreshTree();
        refreshProps();
        refreshPreview();
        updateSelectionInfo();
        if (focused) preview.requestFocus();
    }

    private void select(JsonObject node) {
        selected = node;
        onSelect.accept(pathOf(window.getAsJsonObject("root"), node));
        updateSelectionInfo();
    }

    private void refreshTree() {
        treeColumn.removeAllViews();
        // the selected widget is never hidden inside a folded box
        JsonObject treeRoot = window.getAsJsonObject("root");
        for (JsonObject up = parentOf(treeRoot, selected); up != null; up = parentOf(treeRoot, up)) {
            collapsed.remove(up);
        }
        List<Entry> all = entries();
        List<View> rows = new ArrayList<>();
        for (Entry entry : all) {
            String type = WindowFragment.str(entry.node, "type", "?");
            String text = WindowFragment.str(entry.node, "text", WindowFragment.str(entry.node, "label",
                    WindowFragment.str(entry.node, "id", "")));
            var rowView = label(context, type + (text.isEmpty() ? "" : "  · " + text), R.attr.textAppearanceBodyMedium);
            rowView.setPadding(rowView.dp(8), rowView.dp(5), rowView.dp(8), rowView.dp(5));
            styleChip(rowView, entry.node == selected);
            rowView.setOnClickListener(v -> {
                chooseTool(null);
                select(entry.node);
                refreshTree();
                refreshProps();
                if (preview != null) {
                    preview.invalidate();
                }
            });
            if (entry.parent != null) { // the root does not move
                rowView.setTooltipText("Drag onto another widget: above, below, or into a box");
                rowView.setOnTouchListener(new TreeDrag(entry.node, all, rows));
            }
            rows.add(rowView);
            var line = row(context);
            var arrow = label(context, hasChildren(entry.node) ? (collapsed.contains(entry.node) ? "▸" : "▾") : "",
                    R.attr.textAppearanceBodyMedium);
            arrow.setGravity(Gravity.CENTER);
            if (hasChildren(entry.node)) {
                arrow.setOnClickListener(v -> {
                    if (!collapsed.remove(entry.node)) {
                        collapsed.add(entry.node);
                    }
                    refreshTree();
                });
            }
            var arrowLp = new LinearLayout.LayoutParams(arrow.dp(18), MATCH_PARENT);
            arrowLp.setMarginStart(arrow.dp(12) * entry.depth);
            line.addView(arrow, arrowLp);
            line.addView(rowView, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            treeColumn.addView(line, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
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
        if (from == null || !find(root, target, new ArrayList<>())) {
            return false;
        }
        JsonObject into = where == Drop.INTO ? target : parentOf(root, target);
        if (into == null || !CONTAINERS.contains(WindowFragment.str(into, "type", ""))) {
            return false;
        }
        JsonArray source = from.getAsJsonArray("children");
        source.remove(indexOf(source, node));
        return insertNode(root, node, target, where);
    }

    /** Inserts a new or detached widget; invalid targets leave the tree untouched. */
    static boolean insertNode(JsonObject root, JsonObject node, JsonObject target, Drop where) {
        if (find(root, node, new ArrayList<>()) || !find(root, target, new ArrayList<>())
                || find(node, target, new ArrayList<>())) {
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

    private final class PaletteDrag implements View.OnTouchListener {
        private final String type;
        private float downX;
        private float downY;
        private boolean dragging;

        PaletteDrag(String type) {
            this.type = type;
        }

        @Override
        public boolean onTouch(View view, icyllis.modernui.view.MotionEvent event) {
            int[] at = new int[2];
            view.getLocationInWindow(at);
            float x = at[0] + event.getX();
            float y = at[1] + event.getY();
            switch (event.getAction()) {
                case icyllis.modernui.view.MotionEvent.ACTION_DOWN -> {
                    downX = x;
                    downY = y;
                    dragging = false;
                    return false;
                }
                case icyllis.modernui.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging && Math.abs(x - downX) + Math.abs(y - downY) < view.dp(6)) {
                        return false;
                    }
                    if (!dragging && Math.abs(y - downY) > Math.abs(x - downX)) {
                        return false; // vertical gestures scroll the toolbox
                    }
                    dragging = true;
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    view.setPressed(false);
                    if (preview != null) {
                        preview.paletteDrop(type, x, y, false);
                    }
                    return true;
                }
                case icyllis.modernui.view.MotionEvent.ACTION_UP, icyllis.modernui.view.MotionEvent.ACTION_CANCEL -> {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    if (!dragging) {
                        return false;
                    }
                    dragging = false;
                    if (preview != null) {
                        if (event.getAction() == icyllis.modernui.view.MotionEvent.ACTION_UP) {
                            preview.paletteDrop(type, x, y, true);
                        }
                        preview.dropTarget = null;
                        preview.paletteNode = null;
                        preview.invalidate();
                    }
                    return true;
                }
                default -> {
                    return dragging;
                }
            }
        }
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
        MediaView.releaseAll(previewHolder); // the clips of the old preview stop and give their memory back
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
            bindingProblems.clear();
            JsonObject sample = window.has("preview_data") && window.get("preview_data").isJsonObject()
                    ? window.getAsJsonObject("preview_data") : new JsonObject();
            frame.addView(WindowFragment.previewView(context, root, shown, sample, bindingProblems), lp);
        } catch (RuntimeException e) {
            frame.addView(label(context, "Preview failed: " + e.getMessage(), R.attr.textAppearanceBodySmall));
        }
        if (bindingStatus != null) bindingStatus.setText(String.join("\n", bindingProblems));
        // the window as big as in game, with room around it for the handles; the overlay takes every click and drag
        var designStage = new icyllis.modernui.widget.FrameLayout(context);
        stage = designStage;
        var holder = new icyllis.modernui.widget.FrameLayout(context);
        zoomHolder = holder;
        stage.setPivotX(0);
        stage.setPivotY(0);
        stage.setScaleX(zoom);
        stage.setScaleY(zoom);
        int margin = stage.dp(8);
        var frameLp = new icyllis.modernui.widget.FrameLayout.LayoutParams(frame.dp(size[0]), frame.dp(size[1]));
        frameLp.leftMargin = frameLp.topMargin = frameLp.rightMargin = frameLp.bottomMargin = margin;
        designStage.addView(frame, frameLp);
        preview = new PreviewOverlay(context, shown, frame);
        designStage.addView(preview,
                new icyllis.modernui.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        holder.addView(stage, new icyllis.modernui.widget.FrameLayout.LayoutParams(stage.dp(size[0] + 16), stage.dp(size[1] + 16)));
        previewHolder.addView(holder, new LinearLayout.LayoutParams(Math.round(stage.dp(size[0] + 16) * zoom),
                Math.round(stage.dp(size[1] + 16) * zoom)));
    }

    private void resizeStage(int width, int height) {
        var lp = stage.getLayoutParams();
        lp.width = stage.dp(width + 16);
        lp.height = stage.dp(height + 16);
        stage.setLayoutParams(lp);
        var holderLp = zoomHolder.getLayoutParams();
        holderLp.width = Math.round(lp.width * zoom);
        holderLp.height = Math.round(lp.height * zoom);
        zoomHolder.setLayoutParams(holderLp);
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
        private enum Mode { NONE, PRESS, MOVE, RESIZE, WINDOW, PLACE }

        private final java.util.Map<View, JsonObject> shown;
        private final View frame;
        private final icyllis.modernui.graphics.Paint paint = new icyllis.modernui.graphics.Paint();
        private final float density;
        private Mode mode = Mode.NONE;
        private float downX;
        private float downY;
        private int edges;
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
        private JsonObject beforeGesture;
        private List<Integer> beforeSelection;
        private JsonObject paletteNode;
        private int dropX;
        private int dropY;

        PreviewOverlay(Context context, java.util.Map<View, JsonObject> shown, View frame) {
            super(context);
            this.shown = shown;
            this.frame = frame;
            this.density = context.getResources().getDisplayMetrics().density;
            setClickable(true);
            setFocusableInTouchMode(true);
            setOnTouchListener((v, event) -> handle(event));
            setOnKeyListener((v, key, event) -> event.getAction() == KeyEvent.ACTION_DOWN && designKey(key, event));
        }

        /** {@code view}'s rectangle in this overlay's coordinates: left, top, right, bottom. */
        private float[] rect(View view) {
            int[] mine = new int[2];
            int[] its = new int[2];
            getLocationInWindow(mine);
            view.getLocationInWindow(its);
            float left = (its[0] - mine[0]) / zoom;
            float top = (its[1] - mine[1]) / zoom;
            float scale = view == previewViewport ? zoom : 1;
            return new float[] {left, top, left + view.getWidth() / scale, top + view.getHeight() / scale};
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

        private boolean inside(float x, float y, float[] r) {
            return x >= r[0] && x < r[2] && y >= r[1] && y < r[3];
        }

        /** A palette drag stays on its source button; translate its window coordinates to the design surface. */
        private void paletteDrop(String type, float windowX, float windowY, boolean commit) {
            int[] at = new int[2];
            getLocationInWindow(at);
            placeAt(type, (windowX - at[0]) / zoom, (windowY - at[1]) / zoom, commit);
        }

        private void placeAt(String type, float x, float y, boolean commit) {
            if (paletteNode == null) {
                paletteNode = newControl(window.getAsJsonObject("root"), type);
                place(newNode("canvas"), paletteNode);
            }
            aimDrop(x, y, null);
            if (commit && dropTarget != null) {
                JsonObject added = paletteNode;
                if (insertNode(window.getAsJsonObject("root"), added, dropTarget, dropZone)) {
                    positionDrop(added);
                    select(added);
                    chooseTool(null);
                    changed();
                    post(UiDesigner.this::refreshAll);
                }
            }
            invalidate();
        }

        private void positionDrop(JsonObject widget) {
            JsonObject container = dropZone == Drop.INTO ? dropTarget : parentOf(window.getAsJsonObject("root"), widget);
            if (container != null && "canvas".equals(WindowFragment.str(container, "type", ""))) {
                widget.addProperty("x", dropX);
                widget.addProperty("y", dropY);
                if (paletteNode == null) {
                    widget.addProperty("width", startW);
                    widget.addProperty("height", startH);
                    widget.remove("weight");
                }
            }
        }

        private void aimDrop(float x, float y, @Nullable JsonObject skip) {
            dropTarget = null;
            if (!inside(x, y, rect(previewViewport))) {
                return;
            }
            JsonObject target = hit(x, y, skip);
            View targetView = target == null ? null : viewOf(target);
            if (targetView == null) {
                return;
            }
            JsonObject root = window.getAsJsonObject("root");
            JsonObject targetParent = parentOf(root, target);
            // In a free layout, dropping over a sibling still means placing at the pointer in its canvas.
            if (!isContainer(WindowFragment.str(target, "type", "")) && targetParent != null
                    && "canvas".equals(WindowFragment.str(targetParent, "type", ""))) {
                target = targetParent;
                targetView = viewOf(target);
            }
            if (targetView == null) {
                return;
            }
            float[] r = rect(targetView);
            targetParent = parentOf(root, target);
            boolean across = targetParent != null && "hbox".equals(WindowFragment.str(targetParent, "type", ""));
            float fraction = across ? (x - r[0]) / Math.max(1, r[2] - r[0]) : (y - r[1]) / Math.max(1, r[3] - r[1]);
            Drop zone = target == root || "canvas".equals(WindowFragment.str(target, "type", ""))
                    ? Drop.INTO : zone(target, fraction);
            JsonObject copy = root.deepCopy();
            JsonObject copiedTarget = nodeAt(copy, pathOf(root, target));
            boolean valid = skip == null ? insertNode(copy, newNode("label"), copiedTarget, zone)
                    : moveNode(copy, nodeAt(copy, pathOf(root, skip)), copiedTarget, zone);
            if (valid) {
                dropTarget = target;
                dropZone = zone;
                JsonObject container = zone == Drop.INTO ? target : targetParent;
                View box = container == null ? null : viewOf(container);
                if (box != null && "canvas".equals(WindowFragment.str(container, "type", ""))) {
                    float[] bounds = rect(box);
                    int width = paletteNode == null ? startW : intOf(paletteNode, "width", 80);
                    int height = paletteNode == null ? startH : intOf(paletteNode, "height", 24);
                    dropX = moved(0, (x - bounds[0] - box.getPaddingLeft()) / density,
                            Math.round((box.getWidth() - box.getPaddingLeft() - box.getPaddingRight()) / density) - width);
                    dropY = moved(0, (y - bounds[1] - box.getPaddingTop()) / density,
                            Math.round((box.getHeight() - box.getPaddingTop() - box.getPaddingBottom()) / density) - height);
                }
            }
        }

        private boolean handle(icyllis.modernui.view.MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            switch (event.getAction()) {
                case icyllis.modernui.view.MotionEvent.ACTION_DOWN -> {
                    requestFocus();
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true); // a drag here is not a scroll
                    }
                    downX = x;
                    downY = y;
                    changedSomething = false;
                    beforeGesture = window.deepCopy();
                    beforeSelection = pathOf(window.getAsJsonObject("root"), selected);
                    if (placementType != null) {
                        mode = Mode.PLACE;
                        placeAt(placementType, x, y, false);
                        return true;
                    }
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
                        float[][] handles = handles(r);
                        for (int i = 0; i < handles.length; i++) {
                            if (near(x, y, handles[i][0], handles[i][1]) && selected != window.getAsJsonObject("root")) {
                                begin(selected, current);
                                edges = HANDLE_EDGES[i];
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
                                drag(x, y, dx, dy);
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
                            resizeStage(size.get(0).getAsInt(), size.get(1).getAsInt());
                            changedSomething = true;
                        }
                        case PLACE -> placeAt(placementType, x, y, false);
                        default -> {
                        }
                    }
                    invalidate();
                    return true;
                }
                case icyllis.modernui.view.MotionEvent.ACTION_UP, icyllis.modernui.view.MotionEvent.ACTION_CANCEL -> {
                    boolean up = event.getAction() == icyllis.modernui.view.MotionEvent.ACTION_UP;
                    if (up && mode == Mode.PLACE) {
                        placeAt(placementType, x, y, true);
                    }
                    if (up && mode == Mode.MOVE && dropTarget != null
                            && moveNode(window.getAsJsonObject("root"), node, dropTarget, dropZone)) {
                        positionDrop(node);
                        select(node);
                        changedSomething = true;
                    }
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    mode = Mode.NONE;
                    dropTarget = null;
                    paletteNode = null;
                    if (!up && beforeGesture != null) {
                        restore(window, beforeGesture);
                        select(nodeAt(window.getAsJsonObject("root"), beforeSelection));
                    }
                    if (changedSomething || !up) {
                        if (up && !window.equals(beforeGesture)) {
                            changed();
                        }
                        post(UiDesigner.this::refreshAll); // rebuilds this overlay too, after the gesture
                    }
                    beforeGesture = null;
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
            startW = view == null ? intOf(which, "width", 40) : Math.round(view.getWidth() / density);
            startH = view == null ? intOf(which, "height", 20) : Math.round(view.getHeight() / density);
        }

        /** Moves the widget: freely in a canvas, else marks where in the boxes it would go. */
        private void drag(float x, float y, float dx, float dy) {
            aimDrop(x, y, node);
            if (dropTarget != null && (dropTarget != parent || !inCanvas())) {
                return;
            }
            if (inCanvas()) {
                dropTarget = null;
                View box = viewOf(parent);
                int maxX = box == null ? Integer.MAX_VALUE
                        : Math.round((box.getWidth() - box.getPaddingLeft() - box.getPaddingRight()) / density) - startW;
                int maxY = box == null ? Integer.MAX_VALUE
                        : Math.round((box.getHeight() - box.getPaddingTop() - box.getPaddingBottom()) / density) - startH;
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
        }

        private void resize(float dx, float dy) {
            View box = inCanvas() ? viewOf(parent) : null;
            int maxW = box == null ? Integer.MAX_VALUE
                    : Math.round((box.getWidth() - box.getPaddingLeft() - box.getPaddingRight()) / density);
            int maxH = box == null ? Integer.MAX_VALUE
                    : Math.round((box.getHeight() - box.getPaddingTop() - box.getPaddingBottom()) / density);
            int[] bounds = resizeBounds(startX, startY, startW, startH, dx, dy, edges, maxW, maxH, inCanvas());
            int width = bounds[2];
            int height = bounds[3];
            node.addProperty("width", width);
            node.addProperty("height", height);
            if (inCanvas()) {
                node.addProperty("x", bounds[0]);
                node.addProperty("y", bounds[1]);
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
            if (showGrid) {
                paint.setStyle(icyllis.modernui.graphics.Paint.FILL);
                paint.setColor(IdeColors.BORDER & 0xFFFFFF | 0x44000000);
                for (var entry : shown.entrySet()) {
                    if (!entry.getKey().isShown() || !"canvas".equals(WindowFragment.str(entry.getValue(), "type", ""))) continue;
                    float[] box = rect(entry.getKey());
                    for (float x = box[0] + entry.getKey().getPaddingLeft(); x < box[2]; x += dp(16)) {
                        for (float y = box[1] + entry.getKey().getPaddingTop(); y < box[3]; y += dp(16)) {
                            canvas.drawRect(x, y, x + dp(1), y + dp(1), paint);
                        }
                    }
                }
            }
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
                    for (float[] c : handles(r)) {
                        canvas.drawRect(c[0] - handle, c[1] - handle, c[0] + handle, c[1] + handle, paint);
                    }
                }
            }
            if ((mode == Mode.MOVE || paletteNode != null) && dropTarget != null) {
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
                            if ("canvas".equals(WindowFragment.str(dropTarget, "type", ""))) {
                                int width = paletteNode == null ? startW : intOf(paletteNode, "width", 80);
                                int height = paletteNode == null ? startH : intOf(paletteNode, "height", 24);
                                float left = r[0] + target.getPaddingLeft() + dropX * density;
                                float top = r[1] + target.getPaddingTop() + dropY * density;
                                canvas.drawRect(left, top, left + width * density, top + height * density, paint);
                            }
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

    private void duplicate() {
        JsonObject copy = duplicateNode(window.getAsJsonObject("root"), selected);
        if (copy == null) return;
        select(copy);
        refreshAll();
        changed();
    }

    @Nullable
    static JsonObject duplicateNode(JsonObject root, JsonObject node) {
        JsonObject parent = parentOf(root, node);
        if (parent == null) return null;
        JsonObject copy = node.deepCopy();
        Set<String> ids = new java.util.HashSet<>();
        collectIds(root, ids);
        renameCopiedIds(copy, ids);
        if ("canvas".equals(WindowFragment.str(parent, "type", ""))) {
            copy.addProperty("x", intOf(node, "x", 0) + GRID * 2);
            copy.addProperty("y", intOf(node, "y", 0) + GRID * 2);
        }
        return insertNode(root, copy, node, Drop.AFTER) ? copy : null;
    }

    private static void collectIds(JsonObject node, Set<String> ids) {
        String id = WindowFragment.str(node, "id", "");
        if (!id.isBlank()) ids.add(id);
        if (node.has("children") && node.get("children").isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray("children")) {
                if (child.isJsonObject()) collectIds(child.getAsJsonObject(), ids);
            }
        }
    }

    private static void renameCopiedIds(JsonObject node, Set<String> ids) {
        String id = WindowFragment.str(node, "id", "");
        if (!id.isBlank()) {
            String candidate = id + "_copy";
            for (int i = 2; ids.contains(candidate); i++) candidate = id + "_copy" + i;
            node.addProperty("id", candidate);
            ids.add(candidate);
        }
        if (node.has("children") && node.get("children").isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray("children")) {
                if (child.isJsonObject()) renameCopiedIds(child.getAsJsonObject(), ids);
            }
        }
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
            case "image", "item_icon" -> {
                node.addProperty("width", 32);
                node.addProperty("height", 32);
            }
            case "video" -> {
                node.addProperty("width", 160);
                node.addProperty("height", 126);
            }
            case "audio" -> {
                node.addProperty("width", 200);
                node.addProperty("height", 32);
            }
            default -> {
                node.addProperty("width", 80);
                node.addProperty("height", 24);
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

    private static float[][] handles(float[] r) {
        float midX = (r[0] + r[2]) / 2;
        float midY = (r[1] + r[3]) / 2;
        return new float[][] {{r[0], r[1]}, {r[2], r[1]}, {r[0], r[3]}, {r[2], r[3]},
                {midX, r[1]}, {r[2], midY}, {midX, r[3]}, {r[0], midY}};
    }

    /** Resizing from the left/top keeps the opposite edge fixed on a canvas. */
    static int[] resizeBounds(int x, int y, int width, int height, float dx, float dy, int edges,
                              int maxWidth, int maxHeight, boolean canvas) {
        if ((edges & LEFT) != 0) {
            int next = resized(width, -dx);
            if (canvas) {
                next = Math.min(x + width, next);
                x += width - next;
            }
            width = next;
        } else if ((edges & RIGHT) != 0) {
            width = resized(width, dx);
            if (canvas) {
                width = Math.min(Math.max(MIN_SIZE, maxWidth - x), width);
            }
        }
        if ((edges & TOP) != 0) {
            int next = resized(height, -dy);
            if (canvas) {
                next = Math.min(y + height, next);
                y += height - next;
            }
            height = next;
        } else if ((edges & BOTTOM) != 0) {
            height = resized(height, dy);
            if (canvas) {
                height = Math.min(Math.max(MIN_SIZE, maxHeight - y), height);
            }
        }
        return new int[] {x, y, width, height};
    }

    static void restore(JsonObject target, JsonObject snapshot) {
        for (String key : new ArrayList<>(target.keySet())) {
            target.remove(key);
        }
        snapshot.entrySet().forEach(entry -> target.add(entry.getKey(), entry.getValue().deepCopy()));
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
                node.addProperty("id", "field");
                node.addProperty("hint", "Type here");
            }
            case "checkbox" -> {
                node.addProperty("id", "option");
                node.addProperty("label", "Option");
            }
            case "slider" -> {
                node.addProperty("id", "amount");
                node.addProperty("min", 0);
                node.addProperty("max", 100);
                node.addProperty("value", 50);
            }
            case "dropdown" -> {
                node.addProperty("id", "choice");
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
            case "video" -> {
                node.addProperty("id", "video");
                node.addProperty("source", "media/clip.avi");
                node.addProperty("controls", true);
            }
            case "audio" -> {
                node.addProperty("id", "sound");
                node.addProperty("source", "media/sound.ogg");
                node.addProperty("controls", true);
            }
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

    static JsonObject newControl(JsonObject root, String type) {
        JsonObject node = newNode(type);
        String base = WindowFragment.str(node, "id", "");
        if (!base.isEmpty()) {
            Set<String> ids = new java.util.HashSet<>();
            collectIds(root, ids);
            String id = base;
            for (int i = 2; ids.contains(id); i++) id = base + i;
            node.addProperty("id", id);
        }
        return node;
    }

    // ------------------------------------------------------------------
    // Properties
    // ------------------------------------------------------------------

    private static List<String> propsOf(String type, JsonObject node) {
        List<String> list = new ArrayList<>();
        switch (type) {
            case "label" -> list.addAll(List.of("text", "size", "color", "style"));
            case "button" -> list.addAll(List.of("text", "on_click", "style"));
            case "text_input" -> list.addAll(List.of("id", "value", "hint", "on_submit"));
            case "text_area" -> list.addAll(List.of("id", "value", "hint"));
            case "checkbox" -> list.addAll(List.of("id", "label", "checked", "on_change"));
            case "slider" -> list.addAll(List.of("id", "min", "max", "value", "on_change"));
            case "dropdown" -> list.addAll(List.of("id", "options", "selected", "on_change"));
            case "list" -> list.addAll(List.of("id", "items", "selected", "on_select"));
            case "progress" -> list.addAll(List.of("value", "max"));
            case "image" -> {
                list.add("source");
                // the options of a file of the module; a texture of the game has none
                if (WindowFragment.isMediaFile(WindowFragment.str(node, "source", ""))) {
                    list.addAll(List.of("fit", "crop", "opacity", "z", "autoplay", "loop", "speed", "position", "controls", "id",
                            "on_end", "on_loop", "on_error"));
                }
            }
            case "video" -> list.addAll(List.of("id", "source", "audio", "fit", "crop", "opacity", "z", "autoplay", "loop", "speed",
                    "position", "volume", "muted", "controls", "on_end", "on_loop", "on_error"));
            case "audio" -> list.addAll(List.of("id", "source", "autoplay", "loop", "position", "volume", "muted", "controls",
                    "on_end", "on_loop", "on_error"));
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
        list.add("visible");
        list.add("enabled");
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
        props.addView(section(displayName(type)));
        List<String> keys = new ArrayList<>(propsOf(type, selected));
        Entry entry = entryOf(selected);
        if (entry != null && entry.parent != null && "canvas".equals(WindowFragment.str(entry.parent, "type", ""))) {
            keys.addAll(0, List.of("x", "y")); // placed freely in its canvas
        }
        for (String group : List.of("Identity", "Layout", "Appearance & values", "Media", "Events")) {
            List<String> fields = keys.stream().filter(key -> propertyGroup(key).equals(group)).toList();
            if (fields.isEmpty()) continue;
            props.addView(section(group));
            for (String key : fields) {
                props.addView(field(context, key, nodeValue(selected, key), value -> setProp(key, value)));
            }
        }
        props.addView(section("Data bindings"));
        props.addView(label(context, "Dictionary keys from @ui.data; empty uses the static value.", R.attr.textAppearanceBodySmall));
        for (String property : UiBindings.fields(type).keySet()) {
            String value = selected.has("bind") && selected.get("bind").isJsonObject()
                    ? WindowFragment.str(selected.getAsJsonObject("bind"), property, "") : "";
            props.addView(field(context, "bind." + property, value, key -> {
                JsonObject bindings = selected.has("bind") && selected.get("bind").isJsonObject()
                        ? selected.getAsJsonObject("bind") : new JsonObject();
                if (key.isEmpty()) bindings.remove(property); else bindings.addProperty(property, key);
                if (bindings.isEmpty()) selected.remove("bind"); else selected.add("bind", bindings);
                refreshPreview(); changed();
            }));
        }
        props.addView(section("Sample data"));
        var sample = CraftPyFragment.field(context);
        sample.setMinLines(2); sample.setMaxLines(5);
        sample.setContentDescription("Preview sample data JSON object");
        sample.setText(window.has("preview_data") ? window.get("preview_data").toString() : "{}");
        props.addView(sample);
        props.addView(button(context, "Apply sample data", R.attr.borderlessButtonStyle, view -> {
            try {
                JsonObject values = JsonParser.parseString(sample.getText().toString()).getAsJsonObject();
                window.add("preview_data", values); refreshPreview(); changed();
            } catch (RuntimeException error) { bindingStatus.setText("Sample data must be a JSON object: " + error.getMessage()); }
        }));
        bindingStatus = label(context, String.join("\n", bindingProblems), R.attr.textAppearanceBodySmall);
        bindingStatus.setTextColor(color(context, R.attr.colorError));
        props.addView(bindingStatus);
    }

    private static final Set<String> MEDIA_KEYS = Set.of("fit", "crop", "opacity", "z", "autoplay", "loop", "speed", "position",
            "volume", "muted", "controls", "audio");

    private static String hint(String key) {
        return switch (key) {
            case "source" -> "A texture of the game, or a file of the module under media/ (assets/media/): png, jpg, bmp, gif, avi, ogg, wav, mp3, flac";
            case "audio" -> "Sound file of the video, for example media/clip.ogg (a video file has no sound of its own)";
            case "fit" -> "contain: whole picture inside the box; cover: fills the box, edges cut; stretch: fills the box, distorted";
            case "crop" -> "x, y, width, height of the part to show, in picture pixels (empty: all)";
            case "opacity" -> "0 transparent to 1 solid";
            case "z" -> "Draw order inside a free layout: higher is in front";
            case "autoplay" -> "Start by itself (GIFs: on unless set off)";
            case "loop" -> "Start over at the end (GIFs: on unless set off)";
            case "speed" -> "0.25 to 4; pictures and video only, the sound plays at normal speed";
            case "position" -> "Seconds from the start";
            case "volume" -> "0 to 1";
            case "controls" -> "Show a bar with play, seek, speed and volume";
            default -> key;
        };
    }

    static String propertyGroup(String key) {
        if (key.startsWith("on_")) return "Events";
        if (Set.of("id", "text", "label", "title").contains(key)) return "Identity";
        if (MEDIA_KEYS.contains(key)) return "Media";
        if (Set.of("x", "y", "width", "height", "weight", "spacing", "padding", "columns").contains(key)) return "Layout";
        return "Appearance & values";
    }

    private View spacer(Context context) {
        var view = new View(context);
        view.setMinimumHeight(view.dp(12));
        return view;
    }

    private View field(Context context, String key, String value, Consumer<String> onChange) {
        var column = row(context);
        column.setGravity(Gravity.CENTER_VERTICAL);
        var name = label(context, key.replace("on_", "").replace('_', ' '), R.attr.textAppearanceLabelMedium);
        name.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        name.setTooltipText(key.startsWith("on_") ? "Python @action name for " + key : hint(key));
        column.addView(name, new LinearLayout.LayoutParams(name.dp(76), WRAP_CONTENT));
        if (key.equals("fit")) {
            var cycle = button(context, value.isEmpty() ? FITS.get(0) : value, R.attr.buttonTonalStyle, null);
            cycle.setContentDescription(key);
            cycle.setOnClickListener(view -> {
                String next = FITS.get((FITS.indexOf(cycle.getText().toString()) + 1) % FITS.size());
                cycle.setText(next);
                onChange.accept(next);
            });
            column.addView(cycle);
            return column;
        }
        if (BOOLEANS.contains(key)) {
            var checked = new icyllis.modernui.widget.CheckBox(context);
            checked.setChecked(Boolean.parseBoolean(value));
            checked.setContentDescription(key);
            checked.setOnCheckedChangeListener((view, enabled) -> onChange.accept(Boolean.toString(enabled)));
            column.addView(checked);
            return column;
        }
        var input = CraftPyFragment.field(context);
        input.setSingleLine();
        input.setContentDescription(key);
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
        column.addView(input, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
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
            if (key.equals("autoplay") || key.equals("loop")) {
                return Boolean.toString(WindowFragment.str(node, "source", "").toLowerCase(java.util.Locale.ROOT).endsWith(".gif"));
            }
            return key.equals("visible") || key.equals("enabled") ? "true" : BOOLEANS.contains(key) ? "false" : "";
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
        } else if (key.equals("crop")) {
            String[] parts = text.split("[ ,]+");
            if (parts.length != 4) {
                return; // keep the old crop until it has x, y, width and height
            }
            try {
                JsonArray crop = new JsonArray();
                for (String part : parts) {
                    double number = Double.parseDouble(part);
                    crop.add(number % 1 == 0 ? (Number) Integer.valueOf((int) number) : (Number) Double.valueOf(number));
                }
                selected.add(key, crop);
            } catch (NumberFormatException e) {
                return;
            }
        } else if (BOOLEANS.contains(key)) {
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
