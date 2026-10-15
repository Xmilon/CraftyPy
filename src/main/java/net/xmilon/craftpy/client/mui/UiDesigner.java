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

    private final View anchor;
    private final JsonObject window;
    private final Consumer<String> onApply;
    private JsonObject selected;

    private LinearLayout treeColumn;
    private LinearLayout previewHolder;
    private LinearLayout props;
    private Overlays.Dialog popup;

    private UiDesigner(View anchor, JsonObject window, Consumer<String> onApply) {
        this.anchor = anchor;
        this.window = window;
        this.onApply = onApply;
        this.selected = window.getAsJsonObject("root");
    }

    /**
     * Opens the designer on {@code text} (the JSON of a window; blank or broken text starts a new window).
     * {@code onApply} receives the new JSON text.
     */
    static void open(View anchor, String windowId, String text, Consumer<String> onApply) {
        new UiDesigner(anchor, parse(windowId, text), onApply).show();
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

    private void show() {
        Context context = anchor.getContext();
        popup = Overlays.dialog(anchor, "UI designer - " + WindowFragment.str(window, "id", ""), 0, -1);
        var content = popup.body;
        var hint = label(context, "Add widgets on the left, edit them on the right. Buttons call a Python function by "
                + "name: write @action(\"name\") in main.py and type that name in on_click. Apply puts the JSON into the editor tab; "
                + "save it with Ctrl+S.", R.attr.textAppearanceBodySmall);
        hint.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        content.addView(hint);

        var body = new LinearLayout(context);
        body.setOrientation(LinearLayout.HORIZONTAL);
        treeColumn = new LinearLayout(context);
        treeColumn.setOrientation(LinearLayout.VERTICAL);
        previewHolder = new LinearLayout(context);
        previewHolder.setOrientation(LinearLayout.VERTICAL);
        previewHolder.setGravity(Gravity.CENTER);
        props = new LinearLayout(context);
        props.setOrientation(LinearLayout.VERTICAL);

        var left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        var treeButtons = row(context);
        treeButtons.addView(button(context, "Add", R.attr.buttonTonalStyle, this::addMenu));
        treeButtons.addView(button(context, "Delete", R.attr.borderlessButtonStyle, v -> delete()));
        treeButtons.addView(button(context, "Up", R.attr.borderlessButtonStyle, v -> move(-1)));
        treeButtons.addView(button(context, "Down", R.attr.borderlessButtonStyle, v -> move(1)));
        left.addView(treeButtons);
        var treeScroll = new ScrollView(context);
        treeScroll.addView(treeColumn);
        left.addView(treeScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        body.addView(left, new LinearLayout.LayoutParams(content.dp(260), MATCH_PARENT));

        var previewScroll = new ScrollView(context);
        previewScroll.addView(previewHolder, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        var lpMid = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1);
        lpMid.leftMargin = content.dp(8);
        lpMid.rightMargin = content.dp(8);
        body.addView(previewScroll, lpMid);

        var propsScroll = new ScrollView(context);
        propsScroll.addView(props);
        body.addView(propsScroll, new LinearLayout.LayoutParams(content.dp(280), MATCH_PARENT));

        var lpBody = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
        lpBody.topMargin = content.dp(8);
        content.addView(body, lpBody);

        var buttons = row(context);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(button(context, "Apply to editor", R.attr.buttonTonalStyle, v -> {
            onApply.accept(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(window) + "\n");
            popup.dismiss();
        }));
        content.addView(buttons);

        refreshAll();
        popup.show();
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

    private void refreshTree() {
        Context context = treeColumn.getContext();
        treeColumn.removeAllViews();
        for (Entry entry : entries()) {
            String type = WindowFragment.str(entry.node, "type", "?");
            String text = WindowFragment.str(entry.node, "text", WindowFragment.str(entry.node, "label",
                    WindowFragment.str(entry.node, "id", "")));
            var rowView = label(context, "  ".repeat(entry.depth) + type + (text.isEmpty() ? "" : "  · " + text),
                    R.attr.textAppearanceBodyMedium);
            rowView.setPadding(rowView.dp(8), rowView.dp(5), rowView.dp(8), rowView.dp(5));
            styleChip(rowView, entry.node == selected);
            rowView.setOnClickListener(v -> {
                selected = entry.node;
                refreshTree();
                refreshProps();
            });
            treeColumn.addView(rowView, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
    }

    private void refreshPreview() {
        Context context = previewHolder.getContext();
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

    private void addMenu(View from) {
        var menu = Overlays.menu(from);
        for (int i = 0; i < TYPES.size(); i++) {
            menu.getMenu().add(0, i, i, TYPES.get(i));
        }
        menu.setOnMenuItemClickListener(item -> {
            add(TYPES.get(item.getItemId()));
            return true;
        });
        menu.show();
    }

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
        selected = node;
        refreshAll();
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
        selected = entry.parent;
        refreshAll();
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
        refreshAll();
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
        Context context = props.getContext();
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
