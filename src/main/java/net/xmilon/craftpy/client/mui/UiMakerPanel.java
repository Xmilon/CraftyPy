package net.xmilon.craftpy.client.mui;

import java.util.List;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * The UI maker tool window: the windows of the project ({@code ui/*.json}) and, for the one open in the editor, the
 * designer itself: a palette of widgets, the widget tree, their properties and the window drawn live. Every change
 * goes straight into the editor tab (undo with Ctrl+Z, save with Ctrl+S); typing in the JSON updates the designer.
 * Python opens a window with {@code ui.open(player, "id")}.
 */
final class UiMakerPanel {
    interface Host {
        List<String> files();

        String activePath();

        String activeText();

        void open(String path);

        /** Replaces the text of the open editor tab (one undoable edit). */
        void write(String text);

        void newWindow();

        void hide();
    }

    private final Host host;
    private final LinearLayout view;
    private final LinearLayout list;
    private final LinearLayout designer;
    private final TextView hint;
    /** The designer was built for this file and this text; the text it wrote itself does not rebuild it. */
    private String designedPath;
    private String designedText;

    UiMakerPanel(Context context, Host host) {
        this.host = host;
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(new ColorDrawable(IdeColors.PANEL));

        var header = CraftPyFragment.row(context);
        header.setPadding(header.dp(12), 0, header.dp(4), 0);
        var title = CraftPyFragment.label(context, "UI maker", R.attr.textAppearanceLabelLarge);
        title.setTextColor(IdeColors.TEXT);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        header.addView(CraftPyFragment.button(context, "+ New window", R.attr.borderlessButtonStyle, v -> host.newWindow()));
        header.addView(EditorFragment.action(context, IdeIcon.Glyph.MINUS, "Hide", v -> host.hide()));
        view.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, header.dp(36)));

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        view.addView(list);

        hint = CraftPyFragment.label(context, "", R.attr.textAppearanceBodySmall);
        hint.setTextColor(IdeColors.TEXT_SECONDARY);
        hint.setPadding(view.dp(10), view.dp(4), view.dp(10), view.dp(4));
        view.addView(hint);

        designer = new LinearLayout(context);
        designer.setOrientation(LinearLayout.VERTICAL);
        designer.setPadding(view.dp(6), 0, view.dp(6), view.dp(6));
        designer.setBackground(new ColorDrawable(IdeColors.EDITOR));
        view.addView(designer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
    }

    View view() {
        return view;
    }

    /** Called when the panel opens, another file opens or the editor text changed (debounced). */
    void refresh() {
        Context context = list.getContext();
        list.removeAllViews();
        List<String> windows = host.files().stream().filter(p -> p.startsWith("ui/") && p.endsWith(".json")).sorted().toList();
        String active = host.activePath();
        for (String path : windows) {
            String id = path.substring(3, path.length() - 5);
            var row = CraftPyFragment.label(context, "□  " + id, R.attr.textAppearanceBodyMedium);
            row.setTextColor(path.equals(active) ? IdeColors.ACCENT : IdeColors.TEXT);
            row.setPadding(view.dp(12), view.dp(4), view.dp(12), view.dp(4));
            row.setClickable(true);
            CraftPyFragment.hoverBackground(row);
            row.setOnClickListener(v -> host.open(path));
            list.addView(row);
        }
        boolean open = active != null && active.startsWith("ui/") && active.endsWith(".json");
        String id = open ? active.substring(3, active.length() - 5) : "";
        hint.setText(windows.isEmpty() ? "No windows yet: + New window makes ui/<id>.json and opens it here."
                : open ? "Designing " + active + " - Python opens it with ui.open(player, \"" + id + "\")"
                : "Click a window to design it.");
        if (!open) {
            designer.removeAllViews();
            designedPath = null;
            designedText = null;
            return;
        }
        String text = host.activeText();
        if (active.equals(designedPath) && text.equals(designedText)) {
            return; // the designer's own edit came back, or nothing changed: keep its state (selection, focus)
        }
        designedPath = active;
        designedText = text;
        designer.removeAllViews();
        designer.addView(UiDesigner.docked(context, id, text, json -> {
            designedText = json;
            host.write(json);
        }), new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }
}
