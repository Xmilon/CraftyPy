package net.xmilon.craftpy.client.mui;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * The UI maker tool window: the windows of the project ({@code ui/*.json}) with a live preview of the one open in the
 * editor, New to make one, Design to open the visual designer. Python opens them with {@code ui.open(player, "id")}.
 */
final class UiMakerPanel {
    interface Host {
        List<String> files();

        String activePath();

        String activeText();

        void open(String path);

        void design();

        void newWindow();

        void hide();
    }

    private final Host host;
    private final LinearLayout view;
    private final LinearLayout list;
    private final LinearLayout preview;
    private final TextView hint;
    private String lastPreview = "";

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
        header.addView(EditorFragment.action(context, IdeIcon.Glyph.PLUS, "New window (ui/<id>.json)", v -> host.newWindow()));
        header.addView(EditorFragment.action(context, IdeIcon.Glyph.BRUSH, "Design the open window visually", v -> host.design()));
        header.addView(EditorFragment.action(context, IdeIcon.Glyph.MINUS, "Hide", v -> host.hide()));
        view.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, header.dp(36)));

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        view.addView(list);

        hint = CraftPyFragment.label(context, "", R.attr.textAppearanceBodySmall);
        hint.setTextColor(IdeColors.TEXT_SECONDARY);
        hint.setPadding(view.dp(10), view.dp(4), view.dp(10), view.dp(4));
        view.addView(hint);

        preview = new LinearLayout(context);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(view.dp(10), view.dp(6), view.dp(10), view.dp(6));
        var scroll = new ScrollView(context);
        scroll.setBackground(new ColorDrawable(IdeColors.EDITOR));
        scroll.addView(preview, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        view.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
    }

    View view() {
        return view;
    }

    /** Called when files or the editor text change: the list, and the preview of the open window. */
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
        hint.setText(windows.isEmpty() ? "No windows yet: + makes ui/<id>.json, then the brush opens the designer."
                : open ? "Preview of " + active + " - Python opens it with ui.open(player, \"" + active.substring(3, active.length() - 5) + "\")"
                : "Click a window to open it; its preview shows here.");
        String text = open ? host.activeText() : "";
        if (!text.equals(lastPreview)) {
            lastPreview = text;
            preview.removeAllViews();
            if (open) {
                try {
                    JsonObject window = JsonParser.parseString(text).getAsJsonObject();
                    if (window.has("root") && window.get("root").isJsonObject()) {
                        preview.addView(WindowFragment.previewView(context, window.getAsJsonObject("root")));
                    }
                } catch (RuntimeException broken) {
                    var error = CraftPyFragment.label(context, "Not a valid window yet: " + broken.getMessage(), R.attr.textAppearanceBodySmall);
                    error.setTextColor(IdeColors.ERROR);
                    preview.addView(error);
                }
            }
        }
    }
}
