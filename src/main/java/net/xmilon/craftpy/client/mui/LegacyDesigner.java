package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.text.StaticLayout;
import icyllis.modernui.text.TextPaint;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.HorizontalScrollView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.menu.LegacyEdit;
import net.xmilon.craftpy.menu.LegacyShapes;
import net.xmilon.craftpy.menu.ScreenLayout;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The maker of Minecraft-style windows ({@code ui/<id>.json} with {@code "legacy": true}), apart from the Modern UI window maker:
 * a plain panel at its exact size in GUI units, drawn pixel for pixel like the game draws it (whole zoom steps only), the
 * pieces of a vanilla container screen in the toolbox, the pieces in drawing order in Layers, and their few properties. The
 * window's bottom-right handle resizes it; pieces always stay inside ({@link LegacyEdit}). Each gesture is one edit of the
 * editor tab's document, so undo, redo and save are the editor's. UI thread only.
 */
final class LegacyDesigner implements WindowDesign {
    /** Sizes offered for a new window or the Size buttons: name, width, height. */
    static final List<Object[]> PRESETS = List.of(new Object[]{"Furnace", 176, 166}, new Object[]{"Chest", 176, 222},
            new Object[]{"Wide", 248, 166}, new Object[]{"Small", 176, 133});
    private static final int MIN_ZOOM = 1, MAX_ZOOM = 4;
    /** How full the sample bars, arrows and flames look. */
    private static final double SAMPLE = 0.6;

    private final Context context;
    private final Consumer<String> onApply;
    private final UiDesigner.EditorActions actions;
    private JsonObject window;
    /** The selected piece, or null for the window itself. */
    @Nullable
    private JsonObject selected;
    @Nullable
    private String placementType;
    private int zoom = 3;
    private boolean grid = true;

    private View workspace;
    private Stage stage;
    private LinearLayout props;
    private LinearLayout layers;
    private TextView info;
    private TextView zoomLabel;
    private final Map<String, View> toolButtons = new HashMap<>();

    private LegacyDesigner(Context context, JsonObject window, Consumer<String> onApply, UiDesigner.EditorActions actions) {
        this.context = context;
        this.window = window;
        this.onApply = onApply;
        this.actions = actions;
    }

    /** True when the text is a window of the Minecraft look (it opens in this maker). */
    static boolean isLegacy(String text) {
        if (text == null || text.isBlank()) return false;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() && LegacyEdit.isLegacy(parsed.getAsJsonObject());
        } catch (RuntimeException broken) {
            return false;
        }
    }

    static LegacyDesigner create(Context context, String windowId, String text, Consumer<String> onChange,
                                 UiDesigner.EditorActions actions) {
        JsonObject window = JsonParser.parseString(text).getAsJsonObject();
        if (!window.has("id")) window.addProperty("id", windowId);
        LegacyEdit.pieces(window);
        var designer = new LegacyDesigner(context, window, onChange, actions);
        designer.workspace = designer.build();
        return designer;
    }

    @Override
    public View view() {
        return workspace;
    }

    String json() {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(window) + "\n";
    }

    /** After every finished edit: the JSON goes back into the editor's document (one undo step). */
    private void changed() {
        onApply.accept(json());
        refreshSide();
    }

    static String displayName(String type) {
        return switch (type) {
            case "lg_panel" -> "Panel";
            case "lg_slot" -> "Item slot";
            case "lg_slots" -> "Slot grid";
            case "lg_player" -> "Player inventory";
            case "lg_energy" -> "Energy bar";
            case "lg_fluid" -> "Liquid gauge";
            case "lg_arrow" -> "Progress arrow";
            case "lg_flame" -> "Flame";
            case "lg_bar" -> "Value bar";
            case "lg_label" -> "Text";
            case "lg_icon" -> "Item icon";
            case "lg_image" -> "Image";
            case "lg_button" -> "Button";
            default -> type;
        };
    }

    static List<String> pieceTypes(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return LegacyEdit.TYPES.stream().filter(type -> displayName(type).toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    // ------------------------------------------------------------------
    // Layout of the maker
    // ------------------------------------------------------------------

    private View build() {
        var root = new LinearLayout(context) {
            @Override
            public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed()) {
                    switch (event.getKeyCode()) {
                        case KeyEvent.KEY_S -> actions.save().run();
                        case KeyEvent.KEY_Z -> {
                            if (event.isShiftPressed()) actions.redo().run(); else actions.undo().run();
                        }
                        case KeyEvent.KEY_Y -> actions.redo().run();
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
        tools.addView(button(context, "Window", R.attr.borderlessButtonStyle, v -> select(null)));
        tools.addView(button(context, "Duplicate", R.attr.borderlessButtonStyle, v -> duplicate()));
        tools.addView(button(context, "Delete", R.attr.borderlessButtonStyle, v -> delete()));
        tools.addView(button(context, "Forward", R.attr.borderlessButtonStyle, v -> reorder(1)));
        tools.addView(button(context, "Back", R.attr.borderlessButtonStyle, v -> reorder(-1)));
        tools.addView(button(context, "Grid", R.attr.borderlessButtonStyle, v -> {
            grid = !grid;
            styleChip(v, grid);
            stage.invalidate();
        }));
        zoomLabel = label(context, zoom + "x", R.attr.textAppearanceLabelMedium);
        tools.addView(button(context, "−", R.attr.borderlessButtonStyle, v -> setZoom(zoom - 1)));
        tools.addView(zoomLabel);
        tools.addView(button(context, "+", R.attr.borderlessButtonStyle, v -> setZoom(zoom + 1)));
        var toolScroll = new HorizontalScrollView(context);
        toolScroll.addView(tools);
        root.addView(toolScroll);

        var left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackground(new ColorDrawable(IdeColors.PANEL));
        left.addView(section("Minecraft pieces"));
        var tiles = new FlowLayout(context);
        tiles.setPadding(tiles.dp(6), tiles.dp(2), tiles.dp(6), tiles.dp(6));
        for (String type : LegacyEdit.TYPES) {
            var item = label(context, displayName(type), R.attr.textAppearanceBodyMedium);
            item.setPadding(item.dp(9), item.dp(5), item.dp(9), item.dp(5));
            item.setClickable(true);
            item.setTooltipText("Drag into the window, or click then click the window to place");
            item.setOnClickListener(v -> chooseTool(type.equals(placementType) ? null : type));
            item.setOnTouchListener(new PieceDrag(type));
            styleChip(item, false);
            tiles.addView(item);
            toolButtons.put(type, item);
        }
        var tilesScroll = new ScrollView(context);
        tilesScroll.addView(tiles);
        left.addView(tilesScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 2));
        left.addView(section("Layers (top first)"));
        layers = new LinearLayout(context);
        layers.setOrientation(LinearLayout.VERTICAL);
        var layersScroll = new ScrollView(context);
        layersScroll.addView(layers);
        left.addView(layersScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 3));

        stage = new Stage(context);
        var holder = new LinearLayout(context);
        holder.setGravity(Gravity.CENTER);
        holder.setPadding(holder.dp(16), holder.dp(16), holder.dp(16), holder.dp(16));
        holder.addView(stage);
        var sideways = new HorizontalScrollView(context);
        sideways.addView(holder, new icyllis.modernui.view.ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        var stageScroll = new ScrollView(context);
        stageScroll.setBackground(new ColorDrawable(IdeColors.EDITOR));
        stageScroll.addView(sideways, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var right = new LinearLayout(context);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackground(new ColorDrawable(IdeColors.PANEL));
        right.addView(section("Properties"));
        props = new LinearLayout(context);
        props.setOrientation(LinearLayout.VERTICAL);
        props.setPadding(props.dp(10), 0, props.dp(10), props.dp(10));
        var propsScroll = new ScrollView(context);
        propsScroll.addView(props);
        right.addView(propsScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        var body = row(context);
        body.setBaselineAligned(false);
        body.addView(left, new LinearLayout.LayoutParams(body.dp(190), MATCH_PARENT));
        body.addView(stageScroll, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        body.addView(right, new LinearLayout.LayoutParams(body.dp(230), MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        info = label(context, "", R.attr.textAppearanceBodySmall);
        info.setTextColor(IdeColors.TEXT_SECONDARY);
        info.setPadding(root.dp(10), root.dp(4), root.dp(10), root.dp(4));
        root.addView(info);
        refreshSide();
        return root;
    }

    private TextView section(String text) {
        var title = label(context, text, R.attr.textAppearanceLabelLarge);
        title.setTextColor(IdeColors.TEXT_SECONDARY);
        title.setPadding(title.dp(10), title.dp(8), title.dp(8), title.dp(6));
        return title;
    }

    private void setZoom(int value) {
        zoom = Math.clamp(value, MIN_ZOOM, MAX_ZOOM);
        zoomLabel.setText(zoom + "x");
        stage.requestLayout();
        stage.invalidate();
    }

    private void chooseTool(@Nullable String type) {
        placementType = type;
        toolButtons.forEach((name, view) -> styleChip(view, name.equals(type)));
        updateInfo();
    }

    private void select(@Nullable JsonObject piece) {
        selected = piece;
        refreshSide();
        stage.invalidate();
    }

    private void refreshSide() {
        refreshLayers();
        refreshProps();
        updateInfo();
        if (stage != null) {
            stage.requestLayout();
            stage.invalidate();
        }
    }

    private void updateInfo() {
        if (info == null) return;
        int[] size = LegacyEdit.size(window);
        int count = LegacyEdit.pieces(window).size();
        info.setText(placementType != null
                ? "Place " + displayName(placementType) + ": click in the window, or drag it there. Esc returns to Select."
                : "Window " + size[0] + " × " + size[1] + " units  ·  " + count + " / " + ScreenLayout.MAX_PIECES
                + " pieces  ·  drag the corner handle to resize  ·  arrows move 1 unit, Shift+arrows 6  ·  slots snap to 18-unit rows, Shift drags freely");
    }

    // ------------------------------------------------------------------
    // Edits
    // ------------------------------------------------------------------

    private void duplicate() {
        if (selected == null) return;
        JsonObject copy = LegacyEdit.duplicate(window, selected);
        if (copy == null) {
            info.setText("A window has at most " + ScreenLayout.MAX_PIECES + " pieces");
            return;
        }
        selected = copy;
        changed();
    }

    private void delete() {
        if (selected == null) return;
        LegacyEdit.remove(window, selected);
        selected = null;
        changed();
    }

    private void reorder(int by) {
        if (selected != null && LegacyEdit.reorder(window, selected, by)) changed();
    }

    private void place(String type, int x, int y) {
        JsonObject node = LegacyEdit.add(window, type, x, y);
        if (node == null) {
            info.setText("A window has at most " + ScreenLayout.MAX_PIECES + " pieces");
            return;
        }
        int[] snapped = LegacyEdit.snap(window, node, node.get("x").getAsInt(), node.get("y").getAsInt());
        LegacyEdit.moveTo(window, node, snapped[0], snapped[1]);
        selected = node;
        chooseTool(null);
        changed();
    }

    /** Esc: a gesture, then a chosen tool, then the selection. */
    @Override
    public boolean back() {
        if (stage.cancel()) return true;
        if (placementType != null) {
            chooseTool(null);
            stage.invalidate();
            return true;
        }
        if (selected != null) {
            select(null);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Layers and properties
    // ------------------------------------------------------------------

    private void refreshLayers() {
        if (layers == null) return;
        layers.removeAllViews();
        var pieces = LegacyEdit.pieces(window);
        for (int i = pieces.size() - 1; i >= 0; i--) {
            if (!pieces.get(i).isJsonObject()) continue;
            JsonObject node = pieces.get(i).getAsJsonObject();
            var piece = LegacyEdit.read(node, window);
            String detail = piece.type().equals("lg_label") ? " \"" + piece.text() + "\"" : piece.slot().isEmpty() ? "" : " " + piece.slot();
            var rowView = label(context, displayName(piece.type()) + detail + "   " + piece.x() + ", " + piece.y(), R.attr.textAppearanceBodySmall);
            rowView.setSingleLine();
            rowView.setPadding(rowView.dp(10), rowView.dp(4), rowView.dp(8), rowView.dp(4));
            rowView.setClickable(true);
            rowView.setOnClickListener(v -> select(node));
            if (node == selected) {
                rowView.setBackground(new ColorDrawable(IdeColors.SELECTED));
            }
            layers.addView(rowView, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
    }

    private void refreshProps() {
        if (props == null) return;
        props.removeAllViews();
        if (selected == null || LegacyEdit.indexOf(LegacyEdit.pieces(window), selected) < 0) {
            selected = null;
            windowProps();
            return;
        }
        var piece = LegacyEdit.read(selected, window);
        props.addView(label(context, displayName(piece.type()), R.attr.textAppearanceTitleSmall));
        numberField("x", piece.x(), value -> LegacyEdit.moveTo(window, selected, value, LegacyEdit.read(selected, window).y()));
        numberField("y", piece.y(), value -> LegacyEdit.moveTo(window, selected, LegacyEdit.read(selected, window).x(), value));
        switch (piece.type()) {
            case "lg_slots" -> {
                textField("slot", piece.slot(), "The slot group of Inventory(...), for example storage", value -> selected.addProperty("slot", value));
                numberField("columns", piece.columns(), value -> LegacyEdit.resize(window, selected, value * LegacyEdit.SLOT_PITCH - 2,
                        LegacyEdit.read(selected, window).height()));
                numberField("rows", piece.rows(), value -> LegacyEdit.resize(window, selected, LegacyEdit.read(selected, window).width(),
                        value * LegacyEdit.SLOT_PITCH - 2));
            }
            case "lg_slot" -> textField("slot", piece.slot(), "group.number, for example input.0, fuel.0, output.1",
                    value -> selected.addProperty("slot", value));
            default -> {
            }
        }
        if (LegacyEdit.resizable(piece.type()) && !piece.type().equals("lg_slots")) {
            numberField("width", piece.width(), value -> LegacyEdit.resize(window, selected, value, LegacyEdit.read(selected, window).height()));
            numberField("height", piece.height(), value -> LegacyEdit.resize(window, selected, LegacyEdit.read(selected, window).width(), value));
        }
        if (!piece.bind().isEmpty()) {
            textField("bind", piece.bind(), "energy, fluid or progress of the machine, or value:name for a number Python sets",
                    value -> selected.addProperty("bind", value));
            if (piece.bind().startsWith("value:") || piece.type().equals("lg_bar")) {
                textField("max", trim(piece.max()), "The value that fills it", value -> {
                    try {
                        selected.add("max", new JsonPrimitive(Double.parseDouble(value)));
                    } catch (NumberFormatException bad) {
                        // keeps the old value until the text is a number
                    }
                });
            }
        }
        if (piece.type().equals("lg_label")) {
            textField("text", piece.text(), "{title}, {inventory} and {name} of a value are filled in by the game",
                    value -> selected.addProperty("text", value));
        }
        if (piece.type().equals("lg_label") || piece.type().equals("lg_bar")) {
            textField("color", String.format("#%06X", piece.color() & 0xFFFFFF), "#RRGGBB", value -> selected.addProperty("color", value));
        }
        if (piece.type().equals("lg_button")) {
            textField("text", piece.text(), "The caption; {key} shows a value Python set", value -> selected.addProperty("text", value));
            textField("action", piece.bind(), "The name @on.screen_button(button=\"name\") hears when it is clicked",
                    value -> selected.addProperty("bind", value));
        }
        if (piece.type().equals("lg_image")) {
            textField("image", piece.text(), "A GUI sprite (minecraft:container/slot) or a texture file with its part: "
                    + "mekanism:gui/energy.png#0,0,18,18,18,18 (u, v, width, height, texture width, texture height)",
                    value -> {
                        selected.remove("text");
                        selected.addProperty("image", value);
                    });
        }
        if (piece.type().equals("lg_icon")) {
            textField("item", piece.text(), "An item id, for example minecraft:diamond", value -> {
                selected.remove("text");
                selected.addProperty("item", value);
            });
        }
    }

    private void windowProps() {
        props.addView(label(context, "Window", R.attr.textAppearanceTitleSmall));
        textField("id", WindowFragment.str(window, "id", ""), "Screen(layout=\"id\") or ui.open_screen(player, \"id\")",
                value -> window.addProperty("id", value));
        textField("title", WindowFragment.str(window, "title", ""), "Shown by a Text piece with {title}", value -> window.addProperty("title", value));
        int[] size = LegacyEdit.size(window);
        numberField("width", size[0], value -> LegacyEdit.resizeWindow(window, value, LegacyEdit.size(window)[1]));
        numberField("height", size[1], value -> LegacyEdit.resizeWindow(window, LegacyEdit.size(window)[0], value));
        props.addView(section("Look"));
        String style = WindowFragment.str(window, "style", "auto");
        var looks = new FlowLayout(context);
        for (String[] look : new String[][]{{"auto", "Minecraft"}, {"mekanism", "Mekanism"}, {"plain", "Plain"}}) {
            var choice = button(context, look[1], R.attr.borderlessButtonStyle, v -> {
                if (look[0].equals("auto")) window.remove("style"); else window.addProperty("style", look[0]);
                changed();
            });
            choice.setTooltipText(switch (look[0]) {
                case "auto" -> "Minecraft's own slots, arrow, flame and panel; Mekanism's energy bar and tanks when it is installed";
                case "mekanism" -> "Mekanism's look for everything it has, when it is installed (else Minecraft's)";
                default -> "Plain drawn shapes, no textures";
            });
            styleChip(choice, look[0].equals(style));
            looks.addView(choice);
        }
        props.addView(looks, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        props.addView(section("Size"));
        var presets = new FlowLayout(context);
        for (Object[] preset : PRESETS) {
            presets.addView(button(context, preset[0] + " " + preset[1] + "×" + preset[2], R.attr.borderlessButtonStyle, v -> {
                LegacyEdit.resizeWindow(window, (int) preset[1], (int) preset[2]);
                changed();
            }));
        }
        props.addView(presets, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        props.addView(section("Start from"));
        props.addView(button(context, "The standard machine window", R.attr.borderlessButtonStyle, v -> {
            LegacyEdit.fill(window, ScreenLayout.defaults(true, true, true));
            selected = null;
            changed();
        }));
        var note = label(context, "Sizes are in GUI units like vanilla windows (" + ScreenLayout.MIN_SIZE + " to " + ScreenLayout.MAX_SIZE
                + "). The game draws the window exactly like this.", R.attr.textAppearanceBodySmall);
        note.setTextColor(IdeColors.TEXT_SECONDARY);
        props.addView(note);
    }

    private static String trim(double value) {
        return value % 1 == 0 ? Long.toString((long) value) : Double.toString(value);
    }

    /** A number property: applied on Enter or when the field loses focus, never half typed. */
    private void numberField(String key, int value, java.util.function.IntConsumer apply) {
        textField(key, Integer.toString(value), key, text -> {
            try {
                apply.accept(Integer.parseInt(text.trim()));
            } catch (NumberFormatException bad) {
                // the old value stays; refreshProps shows it again
            }
        });
    }

    /** A text property: applied on Enter or when the field loses focus (one undo step per change). */
    private void textField(String key, String value, String hint, Consumer<String> apply) {
        var line = row(context);
        line.setGravity(Gravity.CENTER_VERTICAL);
        var name = label(context, key, R.attr.textAppearanceLabelMedium);
        name.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        name.setTooltipText(hint);
        line.addView(name, new LinearLayout.LayoutParams(name.dp(64), WRAP_CONTENT));
        EditText input = CraftPyFragment.field(context);
        input.setSingleLine();
        input.setContentDescription(key);
        input.setText(value);
        String[] last = {value}; // a field rebuilt by the change loses focus too: that must not apply it twice
        Runnable commit = () -> {
            String text = input.getText().toString();
            if (text.equals(last[0])) return;
            last[0] = text;
            apply.accept(text);
            changed();
        };
        input.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEY_ENTER || keyCode == KeyEvent.KEY_KP_ENTER)) {
                commit.run();
                return true;
            }
            return false;
        });
        input.setOnFocusChangeListener((v, focused) -> {
            if (!focused) commit.run();
        });
        line.addView(input, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.topMargin = line.dp(6);
        props.addView(line, lp);
    }

    // ------------------------------------------------------------------
    // The drawn window
    // ------------------------------------------------------------------

    /** Drags a piece from the toolbox into the window; a short click chooses it as the tool instead. */
    private final class PieceDrag implements View.OnTouchListener {
        private final String type;
        private float downX, downY;
        private boolean dragging;

        PieceDrag(String type) {
            this.type = type;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            int[] at = new int[2];
            view.getLocationInWindow(at);
            float x = at[0] + event.getX(), y = at[1] + event.getY();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> {
                    downX = x;
                    downY = y;
                    dragging = false;
                    return false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (!dragging && Math.abs(x - downX) + Math.abs(y - downY) < view.dp(6)) return false;
                    if (!dragging && Math.abs(y - downY) > Math.abs(x - downX)) return false; // vertical: scrolls the toolbox
                    dragging = true;
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    view.setPressed(false);
                    stage.ghost(type, x, y);
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    if (!dragging) return false;
                    dragging = false;
                    int[] unit = stage.ghost(null, x, y);
                    if (event.getAction() == MotionEvent.ACTION_UP && unit != null) place(type, unit[0], unit[1]);
                    return true;
                }
                default -> {
                    return dragging;
                }
            }
        }
    }

    /**
     * The window drawn at a whole number of screen pixels per GUI unit, with a margin for the resize handle. A press on a piece
     * selects it and a drag moves it (slots snap to the 18-unit rows of other slots, Shift drags freely), the selected piece's
     * corner resizes it, the window's corner resizes the window. Every gesture starts from a copy of the window, so dragging
     * out and back leaves nothing squeezed, and is one edit when released.
     */
    private final class Stage extends View {
        private enum Mode { NONE, PRESS, MOVE, RESIZE, WINDOW }

        private final Paint paint = new Paint();
        private final TextPaint text = TextPaint.obtain();
        private final Map<String, StaticLayout> textCache = new HashMap<>();
        private Mode mode = Mode.NONE;
        private JsonObject before;
        private int selectedIndex = -1;
        private float downX, downY;
        private int startX, startY, startW, startH;
        private boolean moved;
        @Nullable
        private String ghostType;
        private int ghostX, ghostY;

        Stage(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        private int unit() {
            return Math.max(1, dp(1)) * zoom;
        }

        private int margin() {
            return dp(12);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int[] size = LegacyEdit.size(window);
            setMeasuredDimension(size[0] * unit() + 2 * margin(), size[1] * unit() + 2 * margin());
        }

        /** Unit coordinates of a point in this view. */
        private int ux(float x) {
            return (int) Math.floor((x - margin()) / unit());
        }

        private int uy(float y) {
            return (int) Math.floor((y - margin()) / unit());
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            int u = unit(), m = margin();
            int[] size = LegacyEdit.size(window);
            // the same pieces and art as the game's screen (LegacySkin), drawn on this canvas
            String style = WindowFragment.str(window, "style", "auto");
            net.xmilon.craftpy.client.LegacySkin skin = new net.xmilon.craftpy.client.LegacySkin(PreviewSkin.target(canvas, paint, u, m), 0, 0, style);
            paint.setStroke(false);
            paint.setColor(0x33000000);
            canvas.drawRect(m + 2 * u, m + 2 * u, m + (size[0] + 2) * u, m + (size[1] + 2) * u, paint); // drop shadow
            paint.setColor(0x22FFFFFF);
            canvas.drawRect(m, m, m + size[0] * u, m + size[1] * u, paint); // the window area without a panel
            for (JsonElement element : LegacyEdit.pieces(window)) {
                if (element.isJsonObject()) draw(canvas, skin, LegacyEdit.read(element.getAsJsonObject(), window));
            }
            if (grid && u >= 3) {
                paint.setColor(0x14000000);
                for (int x = 1; x < size[0]; x++) canvas.drawRect(m + x * u, m, m + x * u + 1, m + size[1] * u, paint);
                for (int y = 1; y < size[1]; y++) canvas.drawRect(m, m + y * u, m + size[0] * u, m + y * u + 1, paint);
            }
            if (selected != null && LegacyEdit.indexOf(LegacyEdit.pieces(window), selected) >= 0) {
                int[] box = LegacyEdit.box(selected, window);
                outline(canvas, m + box[0] * u, m + box[1] * u, m + box[2] * u, m + box[3] * u, IdeColors.ACCENT);
                if (LegacyEdit.resizable(LegacyEdit.read(selected, window).type())) {
                    handle(canvas, m + box[2] * u, m + box[3] * u, IdeColors.ACCENT);
                }
            }
            if (ghostType != null) {
                JsonObject ghost = LegacyEdit.newPiece(ghostType);
                var piece = ScreenLayout.pieceOf(ghost, size[0], size[1]);
                outline(canvas, m + ghostX * u, m + ghostY * u, m + (ghostX + piece.width()) * u, m + (ghostY + piece.height()) * u, IdeColors.ACCENT);
            }
            outline(canvas, m, m, m + size[0] * u, m + size[1] * u, 0x66FFFFFF);
            handle(canvas, m + size[0] * u, m + size[1] * u, 0xFFFFFFFF);
        }

        private void draw(Canvas canvas, net.xmilon.craftpy.client.LegacySkin skin, ScreenLayout.Piece piece) {
            int u = unit(), m = margin();
            LegacyShapes.Surface surface = skin.surface();
            switch (piece.type()) {
                case "lg_panel" -> skin.panel(piece.x(), piece.y(), piece.width(), piece.height());
                case "lg_slot" -> skin.slot(piece.x(), piece.y(), piece.slot().contains(".") ? piece.slot().substring(0, piece.slot().indexOf('.')) : piece.slot());
                case "lg_slots" -> {
                    for (int row = 0; row < piece.rows(); row++) {
                        for (int column = 0; column < piece.columns(); column++) {
                            skin.slot(piece.x() + column * 18, piece.y() + row * 18, piece.slot());
                        }
                    }
                }
                case "lg_player" -> {
                    for (int row = 0; row < 3; row++) {
                        for (int column = 0; column < 9; column++) skin.slot(piece.x() + column * 18, piece.y() + row * 18, "");
                    }
                    for (int column = 0; column < 9; column++) skin.slot(piece.x() + column * 18, piece.y() + 58, "");
                }
                case "lg_energy" -> skin.energy(piece.x(), piece.y(), piece.width(), piece.height(), SAMPLE);
                case "lg_fluid" -> skin.gauge(piece.x(), piece.y(), piece.width(), piece.height(), () -> {
                    int filled = (int) Math.round(piece.height() * SAMPLE);
                    surface.fill(piece.x(), piece.y() + piece.height() - filled, piece.x() + piece.width(), piece.y() + piece.height(),
                            piece.bind().equals("fluid:1") ? 0xFFE0602A : 0xFF3F76E4);
                });
                case "lg_bar" -> skin.bar(piece.x(), piece.y(), piece.width(), piece.height(), SAMPLE, piece.color());
                case "lg_arrow" -> skin.arrow(piece.x(), piece.y(), piece.width(), piece.height(), SAMPLE);
                case "lg_flame" -> skin.flame(piece.x(), piece.y(), piece.width(), piece.height(), SAMPLE);
                case "lg_image" -> skin.image(piece.x(), piece.y(), piece.width(), piece.height(), piece.text());
                case "lg_button" -> {
                    skin.button(piece.x(), piece.y(), piece.width(), piece.height(), false);
                    String caption = shownText(piece.text());
                    float width = Math.min(piece.width() * u, caption.length() * 6f * u);
                    label(canvas, caption, m + piece.x() * u + (piece.width() * u - width) / 2f, m + (piece.y() + (piece.height() - 8) / 2) * u,
                            0xFFFFFFFF, piece.width() * u);
                }
                case "lg_icon" -> {
                    // the game draws the item itself; here a framed square with the item's name
                    surface.fill(piece.x(), piece.y(), piece.x() + 16, piece.y() + 16, 0xFF8B8B8B);
                    surface.fill(piece.x() + 1, piece.y() + 1, piece.x() + 15, piece.y() + 15, 0xFFB0B0B0);
                    String name = piece.text().contains(":") ? piece.text().substring(piece.text().indexOf(':') + 1) : piece.text();
                    label(canvas, name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT), m + (piece.x() + 5) * u,
                            m + (piece.y() + 4) * u, 0xFF303030, 16 * u);
                }
                case "lg_label" -> label(canvas, shownText(piece.text()), m + piece.x() * u, m + piece.y() * u,
                        0xFF000000 | piece.color(), piece.width() * u);
                default -> {
                }
            }
        }

        private String shownText(String text) {
            return text.replace("{title}", WindowFragment.str(window, "title", "Title")).replace("{inventory}", "Inventory");
        }

        /** A line of text 8 units tall like the game's font, cut at {@code width} pixels. */
        private void label(Canvas canvas, String value, float x, float y, int argb, int width) {
            int u = unit();
            String key = value + "|" + u + "|" + width;
            StaticLayout layout = textCache.get(key);
            if (layout == null) {
                if (textCache.size() > 200) textCache.clear();
                text.setTextSize(7.5f * u);
                layout = StaticLayout.builder(value, 0, value.length(), text, Math.max(1, width)).setMaxLines(1)
                        .setEllipsize(icyllis.modernui.text.TextUtils.TruncateAt.END).setIncludePad(false).build();
                textCache.put(key, layout);
            }
            text.setColor(argb);
            canvas.save();
            canvas.translate(x, y);
            layout.draw(canvas);
            canvas.restore();
        }

        private void outline(Canvas canvas, float x1, float y1, float x2, float y2, int argb) {
            paint.setStroke(true);
            paint.setStrokeWidth(Math.max(1, dp(1)));
            paint.setColor(argb);
            canvas.drawRect(x1, y1, x2, y2, paint);
            paint.setStroke(false);
        }

        private void handle(Canvas canvas, float x, float y, int argb) {
            int half = dp(4);
            paint.setStroke(false);
            paint.setColor(argb);
            canvas.drawRect(x - half, y - half, x + half, y + half, paint);
        }

        private boolean near(float x, float y, float hx, float hy) {
            return Math.abs(x - hx) <= dp(7) && Math.abs(y - hy) <= dp(7);
        }

        /** Shows where a piece dragged from the toolbox lands; with a null type, ends that and gives the units (or null when outside). */
        @Nullable
        int[] ghost(@Nullable String type, float windowX, float windowY) {
            int[] at = new int[2];
            getLocationInWindow(at);
            float x = windowX - at[0], y = windowY - at[1];
            int[] size = LegacyEdit.size(window);
            boolean inside = ux(x) >= 0 && uy(y) >= 0 && ux(x) < size[0] && uy(y) < size[1];
            ghostType = inside ? type : null;
            ghostX = ux(x);
            ghostY = uy(y);
            invalidate();
            return type == null && inside ? new int[]{ux(x), uy(y)} : null;
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            float x = event.getX(), y = event.getY();
            int u = unit(), m = margin();
            int[] size = LegacyEdit.size(window);
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> {
                    requestFocus();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    downX = x;
                    downY = y;
                    moved = false;
                    if (placementType != null) {
                        if (ux(x) >= 0 && uy(y) >= 0 && ux(x) < size[0] && uy(y) < size[1]) place(placementType, ux(x), uy(y));
                        mode = Mode.NONE;
                        return true;
                    }
                    before = window.deepCopy();
                    if (near(x, y, m + size[0] * u, m + size[1] * u)) {
                        mode = Mode.WINDOW;
                        startW = size[0];
                        startH = size[1];
                        selectedIndex = selected == null ? -1 : LegacyEdit.indexOf(LegacyEdit.pieces(window), selected);
                        return true;
                    }
                    if (selected != null && LegacyEdit.resizable(LegacyEdit.read(selected, window).type())) {
                        int[] box = LegacyEdit.box(selected, window);
                        if (near(x, y, m + box[2] * u, m + box[3] * u)) {
                            mode = Mode.RESIZE;
                            var piece = LegacyEdit.read(selected, window);
                            startW = piece.width();
                            startH = piece.height();
                            selectedIndex = LegacyEdit.indexOf(LegacyEdit.pieces(window), selected);
                            return true;
                        }
                    }
                    JsonObject hit = LegacyEdit.pieceAt(window, ux(x), uy(y));
                    // the panel that fills the window is the window's background: a press on it selects the window
                    if (hit != null && "lg_panel".equals(LegacyEdit.read(hit, window).type()) && LegacyEdit.pieces(window).size() > 1
                            && hit == LegacyEdit.pieces(window).get(0)) {
                        hit = null;
                    }
                    if (hit != selected) select(hit);
                    if (hit != null) {
                        var piece = LegacyEdit.read(hit, window);
                        startX = piece.x();
                        startY = piece.y();
                        selectedIndex = LegacyEdit.indexOf(LegacyEdit.pieces(window), hit);
                        before = window.deepCopy();
                        mode = Mode.PRESS;
                    } else {
                        mode = Mode.NONE;
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    int du = Math.round((x - downX) / u), dv = Math.round((y - downY) / u);
                    if (mode == Mode.PRESS && Math.abs(x - downX) + Math.abs(y - downY) > dp(3)) mode = Mode.MOVE;
                    if (mode == Mode.NONE || mode == Mode.PRESS) return true;
                    restoreBefore();
                    switch (mode) {
                        case MOVE -> {
                            int[] to = event.isShiftPressed() ? new int[]{startX + du, startY + dv}
                                    : LegacyEdit.snap(window, selected, startX + du, startY + dv);
                            LegacyEdit.moveTo(window, selected, to[0], to[1]);
                        }
                        case RESIZE -> LegacyEdit.resize(window, selected, startW + du, startH + dv);
                        case WINDOW -> LegacyEdit.resizeWindow(window, startW + du, startH + dv);
                        default -> {
                        }
                    }
                    moved = du != 0 || dv != 0;
                    if (mode == Mode.WINDOW) requestLayout();
                    updateInfo();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    getParent().requestDisallowInterceptTouchEvent(false);
                    Mode was = mode;
                    mode = Mode.NONE;
                    before = null;
                    if ((was == Mode.MOVE || was == Mode.RESIZE || was == Mode.WINDOW) && moved) changed();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    cancel();
                    return true;
                }
                default -> {
                    return super.onTouchEvent(event);
                }
            }
        }

        /** Puts the window back as it was when the gesture started (the selection is kept by its place in the list). */
        private void restoreBefore() {
            window = before.deepCopy();
            var pieces = LegacyEdit.pieces(window);
            selected = selectedIndex >= 0 && selectedIndex < pieces.size() && pieces.get(selectedIndex).isJsonObject()
                    ? pieces.get(selectedIndex).getAsJsonObject() : null;
        }

        /** Ends a gesture without changing anything; false when there was none. */
        boolean cancel() {
            if (mode == Mode.NONE || before == null) return false;
            restoreBefore();
            mode = Mode.NONE;
            before = null;
            getParent().requestDisallowInterceptTouchEvent(false);
            refreshSide();
            return true;
        }

        @Override
        public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
            if (event.isCtrlPressed() && keyCode == KeyEvent.KEY_D) {
                duplicate();
                return true;
            }
            if (keyCode == KeyEvent.KEY_DELETE || keyCode == KeyEvent.KEY_BACKSPACE) {
                delete();
                return true;
            }
            int dx = keyCode == KeyEvent.KEY_LEFT ? -1 : keyCode == KeyEvent.KEY_RIGHT ? 1 : 0;
            int dy = keyCode == KeyEvent.KEY_UP ? -1 : keyCode == KeyEvent.KEY_DOWN ? 1 : 0;
            if (selected == null || (dx == 0 && dy == 0) || event.isCtrlPressed()) return super.onKeyDown(keyCode, event);
            int step = event.isShiftPressed() ? 6 : 1;
            var piece = LegacyEdit.read(selected, window);
            LegacyEdit.moveTo(window, selected, piece.x() + dx * step, piece.y() + dy * step);
            changed();
            return true;
        }
    }

    /** For the checks: the pieces of the window as the game reads them. */
    List<ScreenLayout.Piece> pieces() {
        List<ScreenLayout.Piece> out = new ArrayList<>();
        for (JsonElement element : LegacyEdit.pieces(window)) {
            if (element.isJsonObject()) out.add(LegacyEdit.read(element.getAsJsonObject(), window));
        }
        return out;
    }
}
