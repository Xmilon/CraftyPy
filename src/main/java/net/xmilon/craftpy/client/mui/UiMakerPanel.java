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
 * Lists the project's {@code ui/*.json} windows and Python window classes. The actual designer occupies the
 * main editor area alongside the Source tab.
 * Python opens a window with {@code ui.open(player, "id")}.
 */
final class UiMakerPanel {
    interface Host {
        List<String> files();

        String activePath();

        void open(String path);

        void design();

        void newWindow();

        void hide();

        /** Runs a project tool ({@code code_windows}, {@code window_from_code}); the reply arrives on the UI thread. */
        void request(String op, com.google.gson.JsonObject args, java.util.function.Consumer<com.google.gson.JsonObject> reply);

        /** A file the server just wrote: list the files again, then open it. */
        void openWhenListed(String path);

        /** A line in the IDE console. */
        void log(String line);
    }

    private final Host host;
    private final LinearLayout view;
    private final LinearLayout list;
    private final TextView hint;

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

        var codeHeader = CraftPyFragment.row(context);
        codeHeader.setPadding(view.dp(12), view.dp(4), view.dp(4), 0);
        var codeTitle = CraftPyFragment.label(context, "Windows in code", R.attr.textAppearanceLabelMedium);
        codeTitle.setTextColor(IdeColors.TEXT_SECONDARY);
        codeHeader.addView(codeTitle, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        codeHeader.addView(EditorFragment.action(context, IdeIcon.Glyph.RELOAD, "Look for @window classes again",
                v -> findCodeWindows()));
        view.addView(codeHeader);
        codeWindows = new LinearLayout(context);
        codeWindows.setOrientation(LinearLayout.VERTICAL);
        view.addView(codeWindows);

        hint = CraftPyFragment.label(context, "", R.attr.textAppearanceBodySmall);
        hint.setTextColor(IdeColors.TEXT_SECONDARY);
        hint.setPadding(view.dp(10), view.dp(4), view.dp(10), view.dp(4));
        view.addView(hint);

        view.addView(CraftPyFragment.button(context, "Open in designer", R.attr.buttonTonalStyle, v -> host.design()));
    }

    View view() {
        return view;
    }

    private final LinearLayout codeWindows;

    /** Lists the {@code @window} classes of the project's .py files (read by the game, not run). */
    void findCodeWindows() {
        Context context = codeWindows.getContext();
        codeWindows.removeAllViews();
        codeWindows.addView(note(context, "Looking ..."));
        host.request("code_windows", new com.google.gson.JsonObject(), reply -> {
            codeWindows.removeAllViews();
            if (reply.has("error")) {
                codeWindows.addView(note(context, reply.get("error").getAsString()));
                return;
            }
            var windows = reply.has("windows") ? reply.getAsJsonArray("windows") : new com.google.gson.JsonArray();
            if (windows.isEmpty()) {
                codeWindows.addView(note(context, "No @window classes in this project's code."));
                return;
            }
            for (var element : windows) {
                var w = element.getAsJsonObject();
                String id = w.get("id").getAsString();
                var row = CraftPyFragment.label(context, "{ }  " + id + "   " + w.get("file").getAsString() + ":"
                        + w.get("line").getAsInt(), R.attr.textAppearanceBodyMedium);
                row.setTextColor(IdeColors.TEXT);
                row.setPadding(view.dp(12), view.dp(4), view.dp(12), view.dp(4));
                row.setClickable(true);
                row.setTooltipText("Make ui/" + id + ".json from this class and open it here (your .py stays as it is)");
                CraftPyFragment.hoverBackground(row);
                row.setOnClickListener(v -> convert(id));
                codeWindows.addView(row);
            }
        });
    }

    private void convert(String id) {
        var args = new com.google.gson.JsonObject();
        args.addProperty("window", id);
        host.request("window_from_code", args, reply -> {
            if (reply.has("error")) {
                host.log("[UI maker] " + id + " could not be opened: " + reply.get("error").getAsString());
                hint.setText(reply.get("error").getAsString());
                return;
            }
            String path = reply.get("path").getAsString();
            host.log("[UI maker] " + id + " -> " + path + " (the Python class is unchanged; ui.open(player, \""
                    + path.substring(3, path.length() - 5) + "\") opens the new one)");
            if (reply.has("notes")) {
                reply.getAsJsonArray("notes").forEach(n -> host.log("[UI maker]   " + n.getAsString()));
            }
            host.openWhenListed(path);
        });
    }

    private TextView note(Context context, String text) {
        var label = CraftPyFragment.label(context, text, R.attr.textAppearanceBodySmall);
        label.setTextColor(IdeColors.TEXT_SECONDARY);
        label.setPadding(view.dp(12), view.dp(2), view.dp(12), view.dp(2));
        return label;
    }

    /** Called when the panel opens, another file opens or the editor text changed (debounced). */
    void refresh() {
        Context context = list.getContext();
        list.removeAllViews();
        List<String> windows = host.files().stream().filter(UiDesigner::isWindowFile).sorted().toList();
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
        boolean open = UiDesigner.isWindowFile(active);
        String id = open ? active.substring(3, active.length() - 5) : "";
        hint.setText(windows.isEmpty() ? "No windows yet: + New window makes ui/<id>.json and opens it here."
                : open ? active + " opens in the main editor. Python: ui.open(player, \"" + id + "\")"
                : "Click a window to open its Design and Source views.");
    }
}
