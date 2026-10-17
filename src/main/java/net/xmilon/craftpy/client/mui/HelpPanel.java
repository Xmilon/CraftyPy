package net.xmilon.craftpy.client.mui;

import net.xmilon.craftpy.client.IdeHost;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.markflow.Markflow;
import icyllis.modernui.markflow.MarkflowPlugin;
import icyllis.modernui.markflow.MarkflowTheme;
import icyllis.modernui.text.Typeface;
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
 * A contents list on the left jumps to each heading; the text uses the IDE palette with larger type and spacing.
 */
final class HelpPanel {
    private static final String[][] TOPICS = {
            {"IDE guide", "guide.md"},
            {"Python API", "api.md"},
            {"Java bridge", "java.md"},
    };

    /** One heading of a document: its level (1-3) and its text without Markdown marks. */
    record Heading(int level, String title) {
    }

    private HelpPanel() {
    }

    /** A column with the topic buttons on top, the contents on the left and the text on the right. */
    static LinearLayout create(Context context, int firstTopic) {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        var chips = row(context);
        chips.setPadding(column.dp(8), column.dp(6), column.dp(8), column.dp(6));
        chips.setBackground(new ColorDrawable(IdeColors.PANEL));

        var text = docText(context);
        int pad = text.dp(28);
        text.setPadding(pad, text.dp(16), pad, pad);
        var scroll = new ScrollView(context);
        scroll.setBackground(new ColorDrawable(IdeColors.EDITOR));
        scroll.addView(text, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var contents = new LinearLayout(context);
        contents.setOrientation(LinearLayout.VERTICAL);
        contents.setPadding(contents.dp(6), contents.dp(8), contents.dp(6), contents.dp(8));
        var contentsScroll = new ScrollView(context);
        contentsScroll.setBackground(new ColorDrawable(IdeColors.PANEL));
        contentsScroll.addView(contents, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var body = row(context);
        body.addView(contentsScroll, new LinearLayout.LayoutParams(body.dp(220), MATCH_PARENT));
        var line = new View(context);
        line.setBackground(IdeColors.divider(line, true));
        body.addView(line, new LinearLayout.LayoutParams(line.dp(1), MATCH_PARENT));
        body.addView(scroll, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));

        var markflow = markflow(context);
        View[] buttons = new View[TOPICS.length];
        for (int i = 0; i < TOPICS.length; i++) {
            final int index = i;
            var button = button(context, TOPICS[i][0], R.attr.borderlessButtonStyle, v -> {
                for (int j = 0; j < buttons.length; j++) {
                    styleChip(buttons[j], j == index);
                }
                text.setText("Loading...");
                contents.removeAllViews();
                scroll.scrollTo(0, 0);
                // Resources belong to the client thread; render back on the UI thread.
                IdeHost.get().execute(() -> {
                    String markdown = load(TOPICS[index][1]);
                    text.post(() -> {
                        markflow.setMarkdown(text, markdown);
                        fillContents(contents, headings(markdown), text, scroll);
                    });
                });
            });
            buttons[i] = button;
            chips.addView(button);
        }
        column.addView(chips, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        var top = new View(context);
        top.setBackground(IdeColors.divider(top, false));
        column.addView(top, new LinearLayout.LayoutParams(MATCH_PARENT, top.dp(1)));
        column.addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        buttons[Math.max(0, Math.min(firstTopic, buttons.length - 1))].performClick();
        return column;
    }

    /** The help in a dialog over the editor. */
    static void open(View anchor) {
        var dialog = Overlays.dialog(anchor, "Help - Esc closes", 980, 24);
        int windowHeight = anchor.getRootView().getHeight();
        int height = windowHeight > 0 ? Math.max(anchor.dp(200), windowHeight - anchor.dp(120)) : anchor.dp(420);
        dialog.body.addView(create(anchor.getContext(), 0), new LinearLayout.LayoutParams(MATCH_PARENT, height));
        dialog.show();
    }

    /** One section of a bundled document (from its heading to the next one of the same level) in a dialog. */
    static void openSection(View anchor, String title, String file, String heading) {
        var dialog = Overlays.dialog(anchor, title + " - Esc closes", 680, 24);
        var text = docText(anchor.getContext());
        int pad = text.dp(16);
        text.setPadding(pad, pad / 2, pad, pad);
        text.setText("Loading...");
        var scroll = new ScrollView(anchor.getContext());
        scroll.setBackground(new ColorDrawable(IdeColors.EDITOR));
        scroll.addView(text, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        int windowHeight = anchor.getRootView().getHeight();
        int height = windowHeight > 0 ? Math.max(anchor.dp(200), Math.min(anchor.dp(520), windowHeight - anchor.dp(160)))
                : anchor.dp(420);
        dialog.body.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, height));
        var markflow = markflow(anchor.getContext());
        IdeHost.get().execute(() -> {
            String markdown = net.xmilon.craftpy.project.BuildingBlocks.section(load(file), heading);
            text.post(() -> markflow.setMarkdown(text, markdown));
        });
        dialog.show();
    }

    /** The text view the documents are drawn in: IDE colors, a larger size and more space between lines. */
    private static TextView docText(Context context) {
        var text = new PlainText(context);
        text.setTextIsSelectable(true);
        text.setTextSize(15);
        text.setLineSpacing(0, 1.35f);
        text.setTextColor(IdeColors.TEXT);
        text.setLinkTextColor(IdeColors.ACCENT);
        text.setHighlightColor(IdeColors.FOCUS);
        return text;
    }

    /** Markdown in the IDE's look: green headings rules, raised code blocks, monospaced code. */
    private static Markflow markflow(Context context) {
        return Markflow.builder(context).usePlugin(new MarkflowPlugin() {
            @Override
            public void configureTheme(MarkflowTheme.Builder theme) {
                theme.headingTextSizeMultipliers(1.9f, 1.5f, 1.25f, 1.1f, 1f, 0.9f)
                        .headingBreakColor(IdeColors.BORDER)
                        .headingBreakHeightDp(1)
                        .thematicBreakColor(IdeColors.BORDER)
                        .codeTypeface(Typeface.MONOSPACED)
                        .codeBlockTypeface(Typeface.MONOSPACED)
                        .codeTextColor(IdeColors.ACCENT)
                        .codeBackgroundColor(IdeColors.RAISED)
                        .codeBlockTextColor(IdeColors.TEXT)
                        .codeBlockBackgroundColor(IdeColors.PANEL)
                        .codeBlockMarginDp(12)
                        .blockQuoteColor(IdeColors.ACCENT)
                        .blockQuoteTextColor(IdeColors.TEXT_SECONDARY)
                        .listItemColor(IdeColors.ACCENT);
            }
        }).build();
    }

    /** The headings of a Markdown document (levels 1-3, not inside code blocks), with inline marks removed. */
    static List<Heading> headings(String markdown) {
        List<Heading> out = new ArrayList<>();
        boolean fenced = false;
        for (String line : markdown.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (fenced || !trimmed.startsWith("#")) {
                continue;
            }
            int level = 0;
            while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                level++;
            }
            if (level > 3 || level == trimmed.length() || trimmed.charAt(level) != ' ') {
                continue;
            }
            String title = trimmed.substring(level).strip()
                    .replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1")
                    .replace("`", "").replace("**", "").replace("__", "");
            if (!title.isEmpty()) {
                out.add(new Heading(level, title));
            }
        }
        return out;
    }

    /** Fills the contents list; a click scrolls the text to that heading (found in order in the rendered text). */
    private static void fillContents(LinearLayout contents, List<Heading> headings, TextView text, ScrollView scroll) {
        String rendered = text.getText().toString();
        int from = 0;
        for (Heading heading : headings) {
            int at = rendered.indexOf(heading.title(), from);
            if (at < 0) {
                continue;
            }
            from = at + heading.title().length();
            var entry = label(contents.getContext(), heading.title(), R.attr.textAppearanceBodyMedium);
            entry.setTextColor(heading.level() <= 2 ? IdeColors.TEXT : IdeColors.TEXT_SECONDARY);
            entry.setSingleLine();
            int indent = entry.dp(8 + 12 * Math.max(0, heading.level() - 1));
            entry.setPadding(indent, entry.dp(4), entry.dp(8), entry.dp(4));
            hoverBackground(entry);
            entry.setOnClickListener(v -> {
                var layout = text.getLayout();
                if (layout != null) {
                    scroll.scrollTo(0, text.getPaddingTop() + layout.getLineTop(layout.getLineForOffset(at)));
                }
            });
            contents.addView(entry, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
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
