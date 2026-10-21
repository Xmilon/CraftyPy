package net.xmilon.craftpy.client.mui;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.MuiScreen;
import icyllis.modernui.mc.ScreenCallback;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.CheckBox;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.PopupMenu;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.SeekBar;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.xmilon.craftpy.network.ProjectAction;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * An in-game window described by Python (or drawn in the UI editor) as a JSON widget tree:
 * {@code {id, title, size:[w,h], root:{type, ...children}}}. The same builder draws the live preview
 * of the UI editor ({@link #build}). Clicks go back to the server as {@code WINDOW_EVENT}; the Python side
 * answers by sending the (possibly changed) tree again, which {@link #update} swaps in place.
 * <p>
 * Callbacks are opaque strings (a token or the name of an {@code @action}); the server only accepts the ones
 * it issued for this window. Input values travel with every event under the widget {@code id}.
 */
public final class WindowFragment extends Fragment implements ScreenCallback {
    private JsonObject tree;
    private final String windowId;
    private final Map<String, JsonElement> values = new HashMap<>();
    private Map<String, JsonElement> serverValues = new HashMap<>();
    private final Map<String, EditText> inputs = new HashMap<>();
    private String editingId = "";
    private final Map<String, Integer> tabSelections = new HashMap<>();
    private int tabCounter;
    private boolean parentEnabled = true;
    @Nullable private JsonObject previewData;
    private java.util.List<String> bindingProblems = new java.util.ArrayList<>();
    private boolean closedByServer;
    /** Opened from a Hub app: a Back button and Esc return to the Hub. */
    private boolean fromHub;
    @Nullable
    private LinearLayout content;
    @Nullable
    private TextView title;
    /** Null in the editor preview: nothing is sent. */
    private final boolean live;

    private WindowFragment(JsonObject tree, boolean live) {
        this.tree = tree;
        this.windowId = str(tree, "id", "");
        this.live = live;
    }

    // ------------------------------------------------------------------
    // Entry points (Minecraft client thread)
    // ------------------------------------------------------------------

    /** Handles a {@code WINDOW} response: mode {@code open} (also an update of an open window) or {@code close}. */
    public static void apply(String mode, String json) {
        Minecraft minecraft = Minecraft.getInstance();
        WindowFragment open = openWindow(minecraft);
        if ("close".equals(mode)) {
            if (open != null && (json == null || json.isBlank() || json.equals(open.windowId))) {
                open.closedByServer = true;
                minecraft.setScreen(null);
            }
            return;
        }
        JsonObject tree;
        try {
            tree = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        if (open != null && open.windowId.equals(str(tree, "id", ""))) {
            open.update(tree);
        } else {
            var fragment = new WindowFragment(tree, true);
            fragment.fromHub = net.xmilon.craftpy.client.HubApps.takeReturn(str(tree, "id", ""));
            minecraft.setScreen(MuiModApi.get().createScreen(fragment));
        }
    }

    @Nullable
    private static WindowFragment openWindow(Minecraft minecraft) {
        return minecraft.screen instanceof MuiScreen mui && mui.getFragment() instanceof WindowFragment w ? w : null;
    }

    /** Fragment for a window preview inside another screen (the UI editor): events do nothing. */
    public static WindowFragment preview(JsonObject tree) {
        return new WindowFragment(tree, false);
    }

    /** The views of one widget tree without a screen around it (UI editor preview; nothing is sent). */
    static McCanvas.Painter itemPainter(String id) {
        return (g, w, h, mx, my, partial) -> {
            net.minecraft.world.item.ItemStack stack = McPainters.stack(id);
            if (stack == null) {
                return;
            }
            float k = Math.min(w, h) / 16f;
            g.pose().scale(k, k, 1);
            g.renderItem(stack, 0, 0);
            if (mx >= 0) {
                g.pose().scale(1 / k, 1 / k, 1);
                g.renderTooltip(net.minecraft.client.Minecraft.getInstance().font, stack, mx, my);
            }
        };
    }

    /** "minecraft:item/diamond", "ns:textures/gui/x.png" or a project texture name ("ruby.png"). */
    static net.minecraft.resources.ResourceLocation textureOf(String source, String project) {
        String clean = source.strip();
        String ns = clean.contains(":") ? clean.substring(0, clean.indexOf(':')) : (project == null ? "minecraft" : project);
        String path = clean.contains(":") ? clean.substring(clean.indexOf(':') + 1) : "item/" + clean;
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        if (!path.endsWith(".png")) {
            path += ".png";
        }
        return net.minecraft.resources.ResourceLocation.tryBuild(ns, path.toLowerCase(java.util.Locale.ROOT));
    }

    private static McCanvas.Painter imagePainter(String source) {
        var location = textureOf(source, net.xmilon.craftpy.client.ClientProjectStore.getInstance().activeProjectId());
        return (g, w, h, mx, my, partial) -> {
            if (location != null) {
                g.blit(location, 0, 0, w, h, 0f, 0f, 1, 1, 1, 1); // the whole texture, stretched to the widget
            }
        };
    }

    static View previewView(Context context, JsonObject root) {
        return previewView(context, root, null);
    }

    /** The preview; {@code shown} (optional) receives which view shows which node of {@code root}. */
    static View previewView(Context context, JsonObject root, @Nullable Map<View, JsonObject> shown) {
        return previewView(context, root, shown, new JsonObject(), new java.util.ArrayList<>());
    }

    static View previewView(Context context, JsonObject root, @Nullable Map<View, JsonObject> shown,
                            JsonObject data, java.util.List<String> problems) {
        JsonObject wrapper = new JsonObject();
        wrapper.add("root", root);
        WindowFragment fragment = new WindowFragment(wrapper, false);
        fragment.shownNodes = shown;
        fragment.previewData = data;
        fragment.bindingProblems = problems;
        return fragment.build(context, root, new HashMap<>());
    }

    /** True while the player drags a slider: a new tree waits in {@link #pending} so the drag is not cut off. */
    private boolean tracking;
    @Nullable
    private JsonObject pending;

    /** Swaps in a new tree (any thread). Input values the server did not change keep what the player typed. */
    public void update(JsonObject next) {
        View view = content;
        if (view == null) {
            this.tree = next;
            return;
        }
        view.post(() -> {
            if (tracking) {
                pending = next; // a window that refreshes every second would rebuild the slider under the pointer
                return;
            }
            this.tree = next;
            rebuild();
        });
    }

    // ------------------------------------------------------------------
    // Fragment
    // ------------------------------------------------------------------

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        Context context = requireContext();
        var root = new Overlays.Host(context);
        var card = card(context);
        card.setBackground(filled(context));
        int pad = card.dp(12);
        card.setPadding(pad, pad, pad, pad);

        var header = row(context);
        if (fromHub) {
            var back = button(context, "‹ Hub", R.attr.borderlessButtonStyle, v -> backToHub());
            back.setTooltipText("Back to the Hub (Esc)");
            header.addView(back);
        }
        title = label(context, "", R.attr.textAppearanceTitleMedium);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        header.addView(button(context, "X", R.attr.borderlessButtonStyle, v -> Minecraft.getInstance().execute(
                () -> Minecraft.getInstance().setScreen(null))));
        card.addView(header);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        var scroll = new ScrollView(context);
        scroll.addView(content, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
        lp.topMargin = card.dp(8);
        card.addView(scroll, lp);
        rebuild();

        int[] size = size();
        var cardLp = new FrameLayout.LayoutParams(card.dp(size[0]), card.dp(size[1]), Gravity.CENTER);
        root.addView(card, cardLp);
        root.setBackground(new ColorDrawable(live ? 0x55000000 : 0));
        if (fromHub) {
            // after popups and the window's own handlers, Esc goes back to the Hub
            root.post(() -> removeBack = Overlays.onBack(root, () -> {
                backToHub();
                return true;
            }));
        }
        return root;
    }

    private Runnable removeBack = () -> { };

    private void backToHub() {
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(
                HubFragment.createScreen(HubFragment.TAB_APPS)));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        removeBack.run();
        removeBack = () -> { };
        if (live && !closedByServer) {
            send("__closed__", null);
        }
        content = null;
        title = null;
    }

    private int[] size() {
        int w = 320;
        int h = 200;
        if (tree.has("size") && tree.get("size").isJsonArray() && tree.getAsJsonArray("size").size() >= 2) {
            JsonArray size = tree.getAsJsonArray("size");
            w = Math.max(120, Math.min(900, size.get(0).getAsInt()));
            h = Math.max(80, Math.min(600, size.get(1).getAsInt()));
        }
        return new int[]{w, h};
    }

    private void rebuild() {
        LinearLayout box = content;
        if (box == null) {
            return;
        }
        if (title != null) {
            title.setText(str(tree, "title", windowId));
        }
        Map<String, JsonElement> nextServer = new HashMap<>();
        EditText focused = null;
        editingId = "";
        for (var input : inputs.entrySet()) {
            if (input.getValue().hasFocus()) { editingId = input.getKey(); focused = input.getValue(); break; }
        }
        int start = focused == null ? 0 : focused.getSelectionStart();
        int end = focused == null ? 0 : focused.getSelectionEnd();
        inputs.clear();
        tabCounter = 0;
        box.removeAllViews();
        JsonObject root = tree.has("root") && tree.get("root").isJsonObject() ? tree.getAsJsonObject("root") : new JsonObject();
        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        sized(box, root, lp);
        box.addView(build(box.getContext(), root, nextServer), lp);
        serverValues = nextServer;
        EditText nextFocus = inputs.get(editingId);
        if (nextFocus != null && nextFocus.isEnabled() && nextFocus.isShown()) {
            nextFocus.requestFocus();
            int length = nextFocus.getText().length();
            nextFocus.setSelection(Math.max(0, Math.min(start, length)), Math.max(0, Math.min(end, length)));
        }
        editingId = "";
    }

    @Override
    public boolean isBackKey(int keyCode, @NonNull KeyEvent event) {
        return keyCode == KeyEvent.KEY_ESCAPE;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldBlurBackground() {
        return false;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    private void send(@Nullable String token, @Nullable JsonElement value) {
        if (!live || token == null || token.isEmpty()) {
            return;
        }
        JsonObject event = new JsonObject();
        event.addProperty("window", windowId);
        event.addProperty("token", token);
        if (value != null) {
            event.add("value", value);
        }
        JsonObject all = new JsonObject();
        values.forEach(all::add);
        event.add("values", all);
        String data = event.toString();
        if (tree.has("local") && tree.get("local").getAsBoolean()) {
            net.xmilon.craftpy.client.ClientPython.get().windowEvent(data); // drawn by client-side Python
            return;
        }
        store(s -> s.request(ProjectAction.WINDOW_EVENT, "", data));
    }

    // ------------------------------------------------------------------
    // Widget tree -> views
    // ------------------------------------------------------------------

    /** Which view shows which node; filled only for the UI maker's preview. */
    @Nullable
    private Map<View, JsonObject> shownNodes;

    /** Builds the views of one node (recursive). {@code nextServer} collects the server-side value of each input. */
    View build(Context context, JsonObject node, Map<String, JsonElement> nextServer) {
        JsonObject effective = previewData == null ? node : UiBindings.node(node, previewData, bindingProblems);
        boolean inherited = parentEnabled;
        boolean enabled = inherited && (!effective.has("enabled") || bool(effective, "enabled"));
        parentEnabled = enabled;
        View view;
        try { view = buildView(context, effective, nextServer); }
        finally { parentEnabled = inherited; }
        view.setEnabled(enabled);
        if (effective.has("visible") && !bool(effective, "visible")) view.setVisibility(previewData == null ? View.GONE : View.INVISIBLE);
        if (shownNodes != null) {
            shownNodes.put(view, node);
        }
        return view;
    }

    private View buildView(Context context, JsonObject node, Map<String, JsonElement> nextServer) {
        String type = str(node, "type", "");
        switch (type) {
            case "label" -> {
                var tv = label(context, str(node, "text", ""), switch (str(node, "style", "")) {
                    case "title" -> R.attr.textAppearanceTitleSmall;
                    case "headline" -> R.attr.textAppearanceHeadlineSmall;
                    default -> R.attr.textAppearanceBodyMedium;
                });
                if (node.has("size")) {
                    tv.setTextSize(node.get("size").getAsFloat());
                }
                Integer color = color(str(node, "color", ""));
                if (color != null) {
                    tv.setTextColor(color);
                }
                return tv;
            }
            case "button" -> {
                String token = str(node, "on_click", "");
                return button(context, str(node, "text", "Button"),
                        "text".equals(str(node, "style", "")) ? R.attr.borderlessButtonStyle : R.attr.buttonTonalStyle,
                        v -> send(token, null));
            }
            case "text_input", "text_area" -> {
                var input = CraftPyFragment.field(context);
                boolean single = "text_input".equals(type);
                if (single) {
                    input.setSingleLine();
                }
                String id = str(node, "id", "");
                if (!id.isEmpty()) inputs.put(id, input);
                JsonElement shown = shownValue(id, new JsonPrimitive(str(node, "value", "")), nextServer);
                input.setText(shown.getAsString());
                if (node.has("hint")) {
                    input.setHint(str(node, "hint", ""));
                }
                String submit = str(node, "on_submit", "");
                input.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        if (!id.isEmpty()) {
                            values.put(id, new JsonPrimitive(s.toString()));
                        }
                    }
                });
                if (single && !submit.isEmpty()) {
                    input.setOnKeyListener((v, keyCode, event) -> {
                        if (event.getAction() == KeyEvent.ACTION_DOWN
                                && (keyCode == KeyEvent.KEY_ENTER || keyCode == KeyEvent.KEY_KP_ENTER)) {
                            send(submit, new JsonPrimitive(input.getText().toString()));
                            return true;
                        }
                        return false;
                    });
                }
                return input;
            }
            case "checkbox" -> {
                var box = new CheckBox(context);
                box.setText(str(node, "label", ""));
                String id = str(node, "id", "");
                boolean checked = shownValue(id, new JsonPrimitive(bool(node, "checked")), nextServer).getAsBoolean();
                box.setChecked(checked);
                String token = str(node, "on_change", "");
                box.setOnCheckedChangeListener((v, now) -> {
                    if (!id.isEmpty()) {
                        values.put(id, new JsonPrimitive(now));
                    }
                    send(token, new JsonPrimitive(now));
                });
                return box;
            }
            case "slider" -> {
                int min = (int) num(node, "min", 0);
                int max = Math.max(min + 1, (int) num(node, "max", 100));
                var bar = new SeekBar(context);
                bar.setMinimumWidth(bar.dp(120));
                bar.setMax(max - min);
                String id = str(node, "id", "");
                int value = (int) Math.round(shownValue(id, new JsonPrimitive(num(node, "value", min)), nextServer)
                        .getAsDouble());
                bar.setProgress(Math.max(min, Math.min(max, value)) - min);
                String token = str(node, "on_change", "");
                bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser && !id.isEmpty()) {
                            values.put(id, new JsonPrimitive(progress + min));
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                        tracking = true;
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        tracking = false;
                        if (!id.isEmpty()) {
                            values.put(id, new JsonPrimitive(seekBar.getProgress() + min));
                        }
                        send(token, new JsonPrimitive(seekBar.getProgress() + min));
                        if (pending != null) {
                            JsonObject next = pending;
                            pending = null;
                            update(next);
                        }
                    }
                });
                return bar;
            }
            case "dropdown" -> {
                JsonArray options = node.has("options") && node.get("options").isJsonArray()
                        ? node.getAsJsonArray("options") : new JsonArray();
                String id = str(node, "id", "");
                int selected = shownValue(id, new JsonPrimitive((int) num(node, "selected", 0)), nextServer).getAsInt();
                var button = button(context, optionText(options, selected), R.attr.buttonTonalStyle, null);
                String token = str(node, "on_change", "");
                button.setOnClickListener(v -> {
                    var menu = new PopupMenu(context, v);
                    for (int i = 0; i < options.size(); i++) {
                        menu.getMenu().add(0, i, i, options.get(i).getAsString());
                    }
                    menu.setOnMenuItemClickListener(item -> {
                        int index = item.getItemId();
                        button.setText(optionText(options, index));
                        if (!id.isEmpty()) {
                            values.put(id, new JsonPrimitive(index));
                        }
                        send(token, new JsonPrimitive(index));
                        return true;
                    });
                    menu.show();
                });
                return button;
            }
            case "list" -> {
                var list = new LinearLayout(context);
                list.setOrientation(LinearLayout.VERTICAL);
                String token = str(node, "on_select", "");
                JsonArray items = node.has("items") && node.get("items").isJsonArray()
                        ? node.getAsJsonArray("items") : new JsonArray();
                String id = str(node, "id", "");
                int selected = shownValue(id, new JsonPrimitive((int) num(node, "selected", -1)), nextServer).getAsInt();
                for (int i = 0; i < items.size(); i++) {
                    var rowView = label(context, items.get(i).isJsonPrimitive() ? items.get(i).getAsString()
                            : items.get(i).toString(), R.attr.textAppearanceBodyMedium);
                    rowView.setPadding(rowView.dp(8), rowView.dp(6), rowView.dp(8), rowView.dp(6));
                    hoverBackground(rowView);
                    if (i == selected) rowView.setTextColor(CraftPyFragment.color(context, R.attr.colorPrimary));
                    int index = i;
                    rowView.setEnabled(parentEnabled);
                    rowView.setOnClickListener(v -> {
                        if (!id.isEmpty()) values.put(id, new JsonPrimitive(index));
                        send(token, new JsonPrimitive(index));
                    });
                    list.addView(rowView, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                }
                return list;
            }
            case "progress" -> {
                double fraction = Math.max(0, Math.min(1, num(node, "value", 0) / Math.max(1e-9, num(node, "max", 1))));
                var track = new LinearLayout(context);
                track.setOrientation(LinearLayout.HORIZONTAL);
                var bg = new ShapeDrawable();
                bg.setCornerRadius(track.dp(4));
                bg.setColor(CraftPyFragment.color(context, R.attr.colorSurfaceContainerHighest));
                track.setBackground(bg);
                var fill = new View(context);
                var fillBg = new ShapeDrawable();
                fillBg.setCornerRadius(track.dp(4));
                fillBg.setColor(CraftPyFragment.color(context, R.attr.colorPrimary));
                fill.setBackground(fillBg);
                track.addView(fill, new LinearLayout.LayoutParams(0, track.dp(8), (float) fraction));
                track.addView(new View(context), new LinearLayout.LayoutParams(0, track.dp(8), (float) (1 - fraction)));
                return track;
            }
            case "image", "item_icon" -> {
                // drawn by Minecraft itself (McCanvas): the real item model / texture
                boolean icon = "item_icon".equals(type);
                String ref = str(node, icon ? "item" : "source", "");
                McCanvas canvas = new McCanvas(context, icon ? itemPainter(ref) : imagePainter(ref));
                canvas.setFallback(ref);
                int size = node.has("size") && node.get("size").isJsonPrimitive() ? node.get("size").getAsInt() : (icon ? 32 : 64);
                canvas.setLayoutParams(new LinearLayout.LayoutParams(canvas.dp(size), canvas.dp(size)));
                return canvas;
            }
            case "vbox", "hbox" -> {
                return linear(context, node, "hbox".equals(type), nextServer);
            }
            case "canvas" -> {
                // free placement: every child at x, y (dp from the top left) with its own width and height
                var canvas = new FrameLayout(context);
                int pad = canvas.dp((float) num(node, "padding", 0));
                canvas.setPadding(pad, pad, pad, pad);
                for (JsonObject child : children(node)) {
                    var lp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP | Gravity.START);
                    sized(canvas, child, lp);
                    lp.leftMargin = canvas.dp((float) num(child, "x", 0));
                    lp.topMargin = canvas.dp((float) num(child, "y", 0));
                    canvas.addView(build(context, child, nextServer), lp);
                }
                return canvas;
            }
            case "grid" -> {
                int columns = Math.max(1, (int) num(node, "columns", 2));
                var grid = new LinearLayout(context);
                grid.setOrientation(LinearLayout.VERTICAL);
                LinearLayout rowView = null;
                int n = 0;
                for (JsonObject child : children(node)) {
                    if (n++ % columns == 0) {
                        rowView = new LinearLayout(context);
                        rowView.setOrientation(LinearLayout.HORIZONTAL);
                        grid.addView(rowView, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                    }
                    var cell = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
                    sized(rowView, child, cell);
                    if (child.has("width")) {
                        cell.weight = 0;
                    }
                    rowView.addView(build(context, child, nextServer), cell);
                }
                return grid;
            }
            case "tabs" -> {
                var box = new LinearLayout(context);
                box.setOrientation(LinearLayout.VERTICAL);
                var bar = row(context);
                var page = new FrameLayout(context);
                box.addView(bar);
                box.addView(page, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                var pages = children(node);
                var buttons = new java.util.ArrayList<View>();
                Runnable[] show = new Runnable[1];
                String tabsId = str(node, "id", "__tabs_" + tabCounter++);
                int[] active = {Math.min(Math.max(0, tabSelections.getOrDefault(tabsId, 0)), Math.max(0, pages.size() - 1))};
                for (int i = 0; i < pages.size(); i++) {
                    int index = i;
                    var tab = button(context, str(pages.get(i), "title", "Tab " + (i + 1)), R.attr.borderlessButtonStyle,
                            v -> {
                                active[0] = index;
                                tabSelections.put(tabsId, index);
                                show[0].run();
                            });
                    tab.setEnabled(parentEnabled);
                    buttons.add(tab);
                    bar.addView(tab);
                }
                // Hidden pages are not built, so their inputs report nothing until opened.
                show[0] = () -> {
                    page.removeAllViews();
                    for (int i = 0; i < buttons.size(); i++) {
                        styleChip(buttons.get(i), i == active[0]);
                    }
                    if (active[0] < pages.size()) {
                        page.addView(build(context, pages.get(active[0]), nextServer));
                    }
                };
                show[0].run();
                return box;
            }
            case "scroll" -> {
                var scroll = new ScrollView(context);
                scroll.addView(linear(context, node, false, nextServer), new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                return scroll;
            }
            default -> {
                var tv = label(context, "Unknown widget '" + type + "'", R.attr.textAppearanceBodyMedium);
                tv.setTextColor(CraftPyFragment.color(context, R.attr.colorError));
                return tv;
            }
        }
    }

    private LinearLayout linear(Context context, JsonObject node, boolean horizontal, Map<String, JsonElement> nextServer) {
        var box = new LinearLayout(context);
        box.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        box.setGravity(horizontal ? Gravity.CENTER_VERTICAL : Gravity.START);
        int pad = box.dp((float) num(node, "padding", 0));
        box.setPadding(pad, pad, pad, pad);
        int gap = box.dp((float) num(node, "spacing", 6));
        boolean first = true;
        for (JsonObject child : children(node)) {
            View view = build(context, child, nextServer);
            double weight = num(child, "weight", 0);
            String kind = str(child, "type", "");
            if (horizontal && weight <= 0 && !child.has("width")
                    && ("slider".equals(kind) || "progress".equals(kind) || "text_input".equals(kind))) {
                weight = 1; // in a row these take the free space; at their own width a slider is a few pixels wide
            }
            LinearLayout.LayoutParams lp = horizontal
                    ? new LinearLayout.LayoutParams(weight > 0 ? 0 : WRAP_CONTENT, WRAP_CONTENT, (float) weight)
                    : new LinearLayout.LayoutParams(MATCH_PARENT, weight > 0 ? 0 : WRAP_CONTENT, (float) weight);
            sized(box, child, lp);
            if (!first) {
                if (horizontal) {
                    lp.leftMargin = gap;
                } else {
                    lp.topMargin = gap;
                }
            }
            first = false;
            box.addView(view, lp);
        }
        return box;
    }

    /**
     * The node's {@code width} / {@code height} (dp) into its layout params; an image without them keeps its square
     * {@code size}.
     */
    static void sized(View parent, JsonObject node, ViewGroup.LayoutParams lp) {
        String type = str(node, "type", "");
        if (("image".equals(type) || "item_icon".equals(type)) && !node.has("width") && !node.has("height")) {
            int size = node.has("size") && node.get("size").isJsonPrimitive() ? node.get("size").getAsInt()
                    : ("item_icon".equals(type) ? 32 : 64);
            lp.width = parent.dp(size);
            lp.height = parent.dp(size);
            return;
        }
        if (node.has("width") && num(node, "width", 0) > 0) {
            lp.width = parent.dp((float) num(node, "width", 0));
        }
        if (node.has("height") && num(node, "height", 0) > 0) {
            lp.height = parent.dp((float) num(node, "height", 0));
        }
    }

    /** What to show for an input: the player's value when the server did not change it since the last tree. */
    private JsonElement shownValue(String id, JsonElement server, Map<String, JsonElement> nextServer) {
        if (id.isEmpty()) {
            return server;
        }
        nextServer.put(id, server);
        JsonElement mine = values.get(id);
        if (mine != null && (id.equals(editingId) || Objects.equals(serverValues.get(id), server))) {
            return mine;
        }
        values.put(id, server);
        return server;
    }

    private static String optionText(JsonArray options, int index) {
        return index >= 0 && index < options.size() ? options.get(index).getAsString() : "-";
    }

    private static java.util.List<JsonObject> children(JsonObject node) {
        var out = new java.util.ArrayList<JsonObject>();
        if (node.has("children") && node.get("children").isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray("children")) {
                if (child.isJsonObject()) {
                    out.add(child.getAsJsonObject());
                }
            }
        }
        return out;
    }

    private static ShapeDrawable filled(Context context) {
        var bg = new ShapeDrawable();
        bg.setCornerRadius(context.getResources().getDisplayMetrics().density * 12);
        bg.setColor(CraftPyFragment.color(context, R.attr.colorSurfaceContainerHigh));
        return bg;
    }

    @Nullable
    private static Integer color(String hex) {
        if (hex.length() == 7 && hex.charAt(0) == '#') {
            try {
                return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    static String str(JsonObject node, String key, String fallback) {
        if (node.has(key) && node.get(key).isJsonPrimitive()) {
            return node.get(key).getAsString();
        }
        return fallback;
    }

    private static double num(JsonObject node, String key, double fallback) {
        if (node.has(key) && node.get(key).isJsonPrimitive() && node.get(key).getAsJsonPrimitive().isNumber()) {
            return node.get(key).getAsDouble();
        }
        return fallback;
    }

    private static boolean bool(JsonObject node, String key) {
        return node.has(key) && node.get(key).isJsonPrimitive() && node.get(key).getAsBoolean();
    }
}
