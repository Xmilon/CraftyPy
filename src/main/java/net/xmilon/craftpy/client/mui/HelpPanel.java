package net.xmilon.craftpy.client.mui;

import net.xmilon.craftpy.client.IdeHost;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.markflow.Markflow;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The help: the IDE guide, the Python API reference and the Java bridge, as Markdown files shipped in
 * {@code assets/craftpy/docs}. Used by the Docs page and by the Help button (F1) in the editor.
 */
final class HelpPanel {
    private static final String[][] TOPICS = {
            {"IDE guide", "guide.md"},
            {"Python API", "api.md"},
            {"Java bridge", "java.md"},
    };

    private HelpPanel() {
    }

    /** A column with the topic buttons on top and the text under them, filling the space it is given. */
    static LinearLayout create(Context context, int firstTopic) {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        var chips = row(context);
        chips.setPadding(column.dp(8), column.dp(6), column.dp(8), column.dp(6));
        var text = new PlainText(context);
        text.setTextIsSelectable(true);
        int pad = text.dp(16);
        text.setPadding(pad, pad / 2, pad, pad);
        var scroll = new ScrollView(context);
        scroll.addView(text, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        var markflow = Markflow.create(context);

        View[] buttons = new View[TOPICS.length];
        for (int i = 0; i < TOPICS.length; i++) {
            final int index = i;
            var button = button(context, TOPICS[i][0], R.attr.borderlessButtonStyle, v -> {
                for (int j = 0; j < buttons.length; j++) {
                    styleChip(buttons[j], j == index);
                }
                text.setText("Loading...");
                scroll.scrollTo(0, 0);
                // Resources belong to the client thread; render back on the UI thread.
                IdeHost.get().execute(() -> {
                    String markdown = load(TOPICS[index][1]);
                    text.post(() -> markflow.setMarkdown(text, markdown));
                });
            });
            buttons[i] = button;
            chips.addView(button);
        }
        column.addView(chips, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        buttons[Math.max(0, Math.min(firstTopic, buttons.length - 1))].performClick();
        return column;
    }

    /** The help in a dialog over the editor. */
    static void open(View anchor) {
        var dialog = Overlays.dialog(anchor, "Help - Esc closes", 860, 24);
        int windowHeight = anchor.getRootView().getHeight();
        int height = windowHeight > 0 ? Math.max(anchor.dp(200), windowHeight - anchor.dp(120)) : anchor.dp(420);
        dialog.body.addView(create(anchor.getContext(), 0), new LinearLayout.LayoutParams(MATCH_PARENT, height));
        dialog.show();
    }

    /** One section of a bundled document (from its heading to the next one of the same level) in a dialog. */
    static void openSection(View anchor, String title, String file, String heading) {
        var dialog = Overlays.dialog(anchor, title + " - Esc closes", 640, 24);
        var text = new PlainText(anchor.getContext());
        text.setTextIsSelectable(true);
        int pad = text.dp(12);
        text.setPadding(pad, pad / 2, pad, pad);
        text.setText("Loading...");
        var scroll = new ScrollView(anchor.getContext());
        scroll.addView(text, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        int windowHeight = anchor.getRootView().getHeight();
        int height = windowHeight > 0 ? Math.max(anchor.dp(200), Math.min(anchor.dp(520), windowHeight - anchor.dp(160)))
                : anchor.dp(420);
        dialog.body.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, height));
        var markflow = Markflow.create(anchor.getContext());
        IdeHost.get().execute(() -> {
            String markdown = net.xmilon.craftpy.project.BuildingBlocks.section(load(file), heading);
            text.post(() -> markflow.setMarkdown(text, markdown));
        });
        dialog.show();
    }

    private static String load(String file) {
        try (InputStream in = IdeHost.get().doc(file)) {
            if (in == null) {
                return "# Missing\n\nThe bundled document `craftpy:docs/" + file + "` is not in this build.";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "# Could not read the document\n\n" + e.getMessage();
        }
    }
}
