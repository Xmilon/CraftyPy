package net.xmilon.craftpy.client.mui;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.project.BuildingBlocks;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * The Building Blocks tool window: ready-made pieces by category (UI elements, items, blocks, events...). Each one opens
 * its section of the docs, inserts its code at the cursor, or becomes a new file that {@code main.py} imports.
 */
final class BlocksPanel {
    interface Host {
        void docs(View anchor, BuildingBlocks.Block block);

        void insert(BuildingBlocks.Block block);

        void newFile(View anchor, BuildingBlocks.Block block);

        void hide();
    }

    private final Host host;
    private final LinearLayout view;
    private final LinearLayout list;
    private final EditText search;
    private final TextView count;
    private final TextView categoryPicker;
    private String category = "All categories";
    private java.util.Set<String> serverMods = java.util.Set.of(), serverFeatures = java.util.Set.of();
    private List<BuildingBlocks.Block> shown = List.of();

    BlocksPanel(Context context, Host host) {
        this.host = host;
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(new ColorDrawable(IdeColors.PANEL));

        var header = CraftPyFragment.row(context);
        header.setPadding(header.dp(12), 0, header.dp(4), 0);
        var title = CraftPyFragment.label(context, "Building blocks", R.attr.textAppearanceLabelLarge);
        title.setTextColor(IdeColors.TEXT);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        header.addView(EditorFragment.action(context, IdeIcon.Glyph.MINUS, "Hide", v -> host.hide()));
        view.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, header.dp(36)));

        search = CraftPyFragment.field(context);
        search.setSingleLine();
        search.setContentDescription("Search building blocks");
        search.setHint("Search: window, sword, ore, timer...");
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render(s.toString());
            }
        });
        var searchLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        searchLp.setMargins(view.dp(10), 0, view.dp(10), view.dp(6));
        view.addView(search, searchLp);

        var filters = CraftPyFragment.row(context);
        categoryPicker = link(context, category, "Filter by category", v -> {
            var dialog = ListDialog.open(v, "Building Blocks categories", true);
            var rows = new java.util.ArrayList<ListDialog.Row>();
            rows.add(ListDialog.Row.link("All categories", "", () -> { category = "All categories"; render(search.getText().toString()); }));
            for (String name : BuildingBlocks.byCategory(BuildingBlocks.all()).keySet())
                rows.add(ListDialog.Row.link(name, "", () -> { category = name; render(search.getText().toString()); }));
            dialog.setRows(rows);
        });
        filters.addView(categoryPicker);
        filters.addView(link(context, "Clear", "Clear search and category", v -> { category = "All categories"; search.setText(""); render(""); }));
        filters.addView(link(context, "Refresh", "Check available server integrations", v -> refreshEnvironment()));
        view.addView(filters);
        count = CraftPyFragment.label(context, "", R.attr.textAppearanceBodySmall);
        count.setTextColor(IdeColors.TEXT_SECONDARY); view.addView(count);

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(view.dp(8), 0, view.dp(8), view.dp(8));
        var scroll = new ScrollView(context);
        scroll.addView(list, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        view.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        render("");
        search.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != icyllis.modernui.view.KeyEvent.ACTION_DOWN || shown.isEmpty()) return false;
            if (key == icyllis.modernui.view.KeyEvent.KEY_ENTER) { preview(search, shown.getFirst()); return true; }
            if (key == icyllis.modernui.view.KeyEvent.KEY_DOWN) {
                if (list.getChildCount() > 1) list.getChildAt(1).requestFocus(); return true;
            }
            return false;
        });
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { refreshEnvironment(); }
            @Override public void onViewDetachedFromWindow(View v) { }
        });
    }

    private void refreshEnvironment() {
        net.xmilon.craftpy.client.ClientProjectStore.getInstance().tool("building_blocks", new com.google.gson.JsonObject(), reply -> view.post(() -> {
            serverMods = strings(reply, "mods"); serverFeatures = strings(reply, "capabilities"); render(search.getText().toString());
        }));
    }
    private static java.util.Set<String> strings(com.google.gson.JsonObject json, String key) {
        var out = new java.util.HashSet<String>();
        if (json.get(key) instanceof com.google.gson.JsonArray values) values.forEach(value -> out.add(value.getAsString()));
        return java.util.Set.copyOf(out);
    }

    View view() {
        return view;
    }

    void focusSearch() {
        search.requestFocus();
    }

    private void render(String query) {
        list.removeAllViews();
        categoryPicker.setText(category);
        Context context = list.getContext();
        var store = net.xmilon.craftpy.client.ClientProjectStore.getInstance();
        var project = store.activeProjectId() == null ? null : store.find(store.activeProjectId());
        String projectSide = project == null ? "both" : project.side;
        // only the blocks that work on this project's side (a client project has no blocks or items, a server one no HUD)
        var mods = "client".equals(projectSide) ? net.xmilon.craftpy.project.RuntimeFeatures.mods() : serverMods;
        var features = "client".equals(projectSide) ? net.xmilon.craftpy.project.RuntimeFeatures.capabilities() : serverFeatures;
        List<BuildingBlocks.Block> fitting = BuildingBlocks.all().stream().filter(b -> b.fits(projectSide) && b.available(mods, features)
                && (category.equals("All categories") || b.category().equals(category))).toList();
        List<BuildingBlocks.Block> blocks = query.isBlank() ? fitting : BuildingBlocks.search(fitting, query);
        shown = blocks;
        count.setText(blocks.size() + " results · " + category + " · unavailable integrations hidden");
        if (blocks.isEmpty()) {
            var empty = CraftPyFragment.label(context, "Nothing matches \"" + query.strip() + "\"",
                    R.attr.textAppearanceBodySmall);
            empty.setTextColor(IdeColors.TEXT_SECONDARY);
            empty.setPadding(list.dp(4), list.dp(8), list.dp(4), list.dp(8));
            list.addView(empty);
            return;
        }
        for (Map.Entry<String, List<BuildingBlocks.Block>> category : BuildingBlocks.byCategory(blocks).entrySet()) {
            var caption = CraftPyFragment.label(context, category.getKey().toUpperCase(Locale.ROOT),
                    R.attr.textAppearanceLabelSmall);
            caption.setTextColor(IdeColors.TEXT_DISABLED);
            caption.setPadding(list.dp(4), list.dp(12), 0, list.dp(4));
            list.addView(caption);
            for (BuildingBlocks.Block block : category.getValue()) {
                list.addView(row(context, block));
            }
        }
    }

    private View row(Context context, BuildingBlocks.Block block) {
        var row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(row.dp(8), row.dp(6), row.dp(6), row.dp(4));
        IdeColors.hoverBackground(row, 6);
        row.setClickable(true);
        row.setOnClickListener(v -> preview(v, block));
        row.setFocusable(true);
        row.setTooltipText("Preview code and companion files; Enter opens the preview");

        var name = CraftPyFragment.label(context, block.title(), R.attr.textAppearanceLabelLarge);
        name.setTextColor(IdeColors.TEXT);
        row.addView(name);
        var about = CraftPyFragment.label(context, block.about(), R.attr.textAppearanceBodySmall);
        about.setTextColor(IdeColors.TEXT_SECONDARY);
        about.setMaxLines(3);
        row.addView(about);

        var actions = CraftPyFragment.row(context);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.addView(link(context, "Docs", "The section of the API docs", v -> host.docs(v, block)));
        actions.addView(link(context, "Insert", "Put the code at the cursor of the open file", v -> host.insert(block)));
        actions.addView(link(context, "New file", "Add it as code/" + block.module() + ".py and import it in main.py",
                v -> host.newFile(v, block)));
        var actionsLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        actionsLp.topMargin = row.dp(2);
        row.addView(actions, actionsLp);

        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.bottomMargin = row.dp(2);
        row.setLayoutParams(lp);
        return row;
    }

    private void preview(View anchor, BuildingBlocks.Block block) {
        var dialog = ListDialog.open(anchor, block.title(), false, 620, 360);
        var store = net.xmilon.craftpy.client.ClientProjectStore.getInstance();
        String project = store.activeProjectId();
        dialog.setMessage(block.about() + "\nSide: " + block.side() + (block.mods().isEmpty() ? " · Core" : " · Requires " + String.join(", ", block.mods())));
        var rows = new java.util.ArrayList<ListDialog.Row>();
        rows.add(ListDialog.Row.card("Python", "", context -> { var text = new PlainText(context); text.setText(block.fill(block.module(), project)); text.setTextIsSelectable(true); return text; }));
        block.files(block.module(), project).forEach((path, content) -> rows.add(ListDialog.Row.card(path, "", context -> {
            var text = new PlainText(context); text.setText(path + "\n" + content); text.setTextIsSelectable(true); return text;
        })));
        dialog.setRows(rows);
        dialog.addAction("Docs", () -> host.docs(anchor, block));
        dialog.addAction("Insert", () -> { dialog.dismiss(); host.insert(block); });
        dialog.addAction("New file", () -> { dialog.dismiss(); host.newFile(anchor, block); });
    }

    /** A small accent-colored text action (IntelliJ's link buttons). */
    private static TextView link(Context context, String text, String tooltip, View.OnClickListener onClick) {
        var link = CraftPyFragment.label(context, text, R.attr.textAppearanceLabelMedium);
        link.setTextColor(IdeColors.ACCENT);
        link.setPadding(link.dp(8), link.dp(3), link.dp(8), link.dp(3));
        link.setClickable(true);
        link.setTooltipText(tooltip);
        IdeColors.hoverBackground(link, 4);
        link.setOnClickListener(EditorFragment.hidingTooltip(tooltip, onClick));
        return link;
    }
}
