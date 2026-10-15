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

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(view.dp(8), 0, view.dp(8), view.dp(8));
        var scroll = new ScrollView(context);
        scroll.addView(list, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        view.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        render("");
    }

    View view() {
        return view;
    }

    void focusSearch() {
        search.requestFocus();
    }

    private void render(String query) {
        list.removeAllViews();
        Context context = list.getContext();
        List<BuildingBlocks.Block> blocks = query.isBlank() ? BuildingBlocks.all()
                : BuildingBlocks.search(BuildingBlocks.all(), query);
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
        row.setOnClickListener(v -> host.docs(v, block));
        row.setTooltipText("Click for the docs of this block");

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
