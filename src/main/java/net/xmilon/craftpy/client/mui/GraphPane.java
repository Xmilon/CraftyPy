package net.xmilon.craftpy.client.mui;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.HorizontalScrollView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.client.graph.Graph;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * A {@link GraphView} with its tools: search (Enter and the arrows go through what it found), Fit, folding for JSON, and the
 * path of the selected card or row with a Copy button. Used by the mod map and by the Graph tab of JSON files.
 */
final class GraphPane {
    private final LinearLayout view;
    private final GraphView graph;
    private final EditText search;
    private final TextView found;
    private final TextView path;
    private final View foldTools;
    private final Runnable runSearch;
    private String selectedPath = "";

    GraphPane(Context context) {
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(new ColorDrawable(IdeColors.PANEL));
        graph = new GraphView(context);

        var tools = row(context);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(tools.dp(6), tools.dp(2), tools.dp(6), tools.dp(2));
        search = CraftPyFragment.field(context);
        search.setSingleLine();
        search.setHint("Find in the graph");
        search.setContentDescription("Find in the graph");
        tools.addView(search, new LinearLayout.LayoutParams(tools.dp(180), WRAP_CONTENT));
        found = label(context, "", R.attr.textAppearanceLabelMedium);
        found.setTextColor(IdeColors.TEXT_SECONDARY);
        found.setPadding(tools.dp(6), 0, tools.dp(6), 0);
        tools.addView(found);
        tools.addView(button(context, "▲", R.attr.borderlessButtonStyle, v -> step(-1)));
        tools.addView(button(context, "▼", R.attr.borderlessButtonStyle, v -> step(1)));
        tools.addView(button(context, "Fit", R.attr.borderlessButtonStyle, v -> graph.fit()));
        var folds = row(context);
        folds.addView(button(context, "Collapse", R.attr.borderlessButtonStyle, v -> graph.foldBelow(1)));
        folds.addView(button(context, "Expand all", R.attr.borderlessButtonStyle, v -> graph.foldBelow(Integer.MAX_VALUE)));
        foldTools = folds;
        tools.addView(folds);
        var toolScroll = new HorizontalScrollView(context);
        toolScroll.addView(tools);
        view.addView(toolScroll, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        view.addView(graph, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        var pathRow = row(context);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        pathRow.setPadding(pathRow.dp(8), pathRow.dp(1), pathRow.dp(4), pathRow.dp(1));
        path = label(context, "Drag to move, wheel to zoom, click a title to fold it", R.attr.textAppearanceBodySmall);
        path.setTextColor(IdeColors.TEXT_SECONDARY);
        path.setSingleLine();
        pathRow.addView(path, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        pathRow.addView(button(context, "Copy path", R.attr.borderlessButtonStyle, v -> {
            if (!selectedPath.isEmpty()) net.xmilon.craftpy.client.IdeHost.get().setClipboard(selectedPath);
        }));
        view.addView(pathRow, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        // searching a big graph on every keystroke would stutter: it waits until typing stops
        runSearch = () -> {
            String query = search.getText().toString();
            int count = graph.search(query);
            found.setText(query.isBlank() ? "" : count == 0 ? "no match" : "1 / " + count);
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                search.removeCallbacks(runSearch);
                search.postDelayed(runSearch, 200);
            }
        });
        search.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEY_ENTER || keyCode == KeyEvent.KEY_KP_ENTER)) {
                step(event.isShiftPressed() ? -1 : 1);
                return true;
            }
            return false;
        });
        graph.setOnSelect((node, row) -> {
            String where = node.path + (row >= 0 && !node.rows.get(row).key.isEmpty()
                    ? (node.accent == 1 ? "[" + node.rows.get(row).key + "]" : "." + node.rows.get(row).key) : "");
            selectedPath = graph.graph() != null && graph.graph().tree ? where : node.path;
            path.setText(selectedPath + (row >= 0 ? "   " + node.rows.get(row).value : ""));
            onSelect.selected(node, row);
        });
    }

    private GraphView.OnSelect onSelect = (node, row) -> { };

    private void step(int direction) {
        int at = graph.next(direction);
        String text = found.getText().toString();
        int slash = text.indexOf(" / ");
        if (at > 0 && slash > 0) found.setText(at + text.substring(slash));
    }

    View view() {
        return view;
    }

    GraphView graphView() {
        return graph;
    }

    /** Shows a graph; {@code fit} zooms it to fit (a refresh of the same thing keeps the view where it is). */
    void show(Graph next, boolean fit) {
        foldTools.setVisibility(next.tree ? View.VISIBLE : View.GONE);
        graph.setGraph(next, fit);
        if (!search.getText().toString().isBlank()) search.postDelayed(runSearch, 50);
    }

    /** Also told when a card or row is clicked (after the path is shown). */
    void setOnSelect(GraphView.OnSelect listener) {
        onSelect = listener;
    }
}
