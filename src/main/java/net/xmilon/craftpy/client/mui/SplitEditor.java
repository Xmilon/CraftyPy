package net.xmilon.craftpy.client.mui;

import java.util.List;
import java.util.function.Consumer;

import icyllis.modernui.R;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.Menu;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.client.EditorSettings;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * The second editor of "Split Right" (IntelliJ): one more open file side by side with the main editor, resizable with the
 * splitter between them. It edits through the same store buffers as the main editor. The file the main editor shows is
 * shown read-only here, so the two never edit the same text at once (the main editor would overwrite the other's edits).
 */
final class SplitEditor {
    /** What the split needs from the editor page. */
    interface Host {
        /** Reads a file's current buffer on the store thread and hands it back on the UI thread (null when not open). */
        void read(String path, Consumer<String> content);

        /** Stores edited text (debounced). */
        void edited(String path, String text);

        void save(String path);

        List<String> openTabs();

        @Nullable
        String mainPath();

        void close();
    }

    private static final long EDIT_DELAY_MS = 200;

    private final Host host;
    private final LinearLayout view;
    private final TextView name;
    private final IdeIcon fileIcon;
    private final TextView readOnlyNote;
    private final TextView gutter;
    private final CodeView code;
    private final EditorSettings settings = EditorSettings.get();
    private String path;
    private String synced = "";
    private boolean loading;
    private final Runnable recolor = this::recolor;

    SplitEditor(Context context, Host host) {
        this.host = host;
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(new ColorDrawable(IdeColors.EDITOR));

        var header = new UnderlinedTab.Row(context);
        header.setSelected(true);
        header.setPadding(header.dp(10), 0, header.dp(4), 0);
        fileIcon = new IdeIcon(context, IdeIcon.Glyph.CODE);
        header.addView(fileIcon);
        name = CraftPyFragment.label(context, "", R.attr.textAppearanceLabelLarge);
        name.setTextColor(IdeColors.TEXT);
        name.setPadding(name.dp(6), 0, name.dp(6), 0);
        name.setClickable(true);
        name.setTooltipText("Show another open file here");
        name.setOnClickListener(EditorFragment.hidingTooltip(name.getTooltipText(), this::chooseFile));
        IdeColors.hoverBackground(name, 4);
        header.addView(name, new LinearLayout.LayoutParams(WRAP_CONTENT, header.dp(26)));
        readOnlyNote = CraftPyFragment.label(context, "read-only: open in the main editor", R.attr.textAppearanceLabelSmall);
        readOnlyNote.setTextColor(IdeColors.TEXT_DISABLED);
        header.addView(readOnlyNote, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        header.addView(EditorFragment.action(context, IdeIcon.Glyph.CLOSE, "Close the split", v -> host.close()));
        view.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, header.dp(34)));

        var scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        var row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int dp8 = row.dp(8);
        gutter = new TextView(context);
        gutter.setTypeface(EditorFont.get());
        gutter.setGravity(Gravity.END | Gravity.TOP);
        gutter.setPadding(dp8, dp8, dp8, dp8);
        row.addView(gutter, new LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT));
        code = new CodeView(context);
        code.setTypeface(EditorFont.get());
        code.setGravity(Gravity.START | Gravity.TOP);
        code.setHorizontallyScrolling(true);
        code.setBackground(null);
        code.setPadding(dp8, dp8, dp8, dp8);
        code.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (loading || path == null || !isEditable()) {
                    return;
                }
                // every keystroke reaches the store at once, in order with the clicks that follow it: switching the main
                // editor to this file right after typing here can then never read an older buffer
                String text = s.toString();
                if (!text.equals(synced)) {
                    synced = text;
                    host.edited(path, text);
                }
                code.removeCallbacks(recolor);
                code.postDelayed(recolor, EDIT_DELAY_MS);
            }
        });
        code.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed() && keyCode == KeyEvent.KEY_S) {
                if (path != null && isEditable()) {
                    host.save(path);
                }
                return true;
            }
            return false;
        });
        row.addView(code, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        scroll.addView(row, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        view.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        applySettings();
    }

    View view() {
        return view;
    }

    @Nullable
    String path() {
        return path;
    }

    void applySettings() {
        var theme = settings.theme();
        code.setTheme(theme);
        code.setTextSize(settings.fontSize);
        gutter.setTextSize(settings.fontSize);
        gutter.setBackground(new ColorDrawable(theme.gutterBg));
        gutter.setTextColor(theme.gutterText);
        if (path != null) {
            CodeView.colorize(code.getText(), path, theme);
        }
    }

    /** Shows {@code file}; its text comes from the store. */
    void show(String file) {
        path = file;
        name.setText(file.substring(file.lastIndexOf('/') + 1));
        name.setTooltipText(file + " - click to show another open file");
        fileIcon.setGlyph(EditorFragment.fileGlyph(file));
        host.read(file, content -> {
            if (!file.equals(path)) {
                return;
            }
            setText(content == null ? "" : content);
            refreshEditable();
        });
    }

    /** Called when the main editor switches file or the store changed: re-reads a read-only copy, updates the lock. */
    void refresh() {
        if (path == null) {
            return;
        }
        if (!host.openTabs().contains(path)) { // its tab was closed
            host.close();
            return;
        }
        if (!isEditable() || !code.hasFocus()) {
            String file = path;
            host.read(file, content -> {
                if (file.equals(path) && content != null && !content.equals(code.getText().toString())
                        && (!isEditable() || !code.hasFocus())) {
                    setText(content);
                }
                refreshEditable();
            });
        } else {
            refreshEditable();
        }
    }


    private boolean isEditable() {
        return path != null && !path.equals(host.mainPath());
    }

    private void refreshEditable() {
        boolean editable = isEditable();
        code.setFocusable(editable);
        code.setFocusableInTouchMode(editable);
        code.setTextIsSelectable(true);
        readOnlyNote.setVisibility(editable ? View.GONE : View.VISIBLE);
    }

    private void setText(String content) {
        loading = true;
        try {
            code.setText(content);
            synced = content;
            CodeView.colorize(code.getText(), path, settings.theme());
            updateGutter(content);
        } finally {
            loading = false;
        }
    }

    private void recolor() {
        if (path != null) {
            CodeView.colorize(code.getText(), path, settings.theme());
            updateGutter(code.getText().toString());
        }
    }

    private void updateGutter(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        var numbers = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            numbers.append(i);
            if (i < lines) {
                numbers.append('\n');
            }
        }
        gutter.setText(numbers);
    }

    private void chooseFile(View anchor) {
        var menu = Overlays.menu(anchor);
        Menu m = menu.getMenu();
        for (String tab : host.openTabs()) {
            m.add(tab).setOnMenuItemClickListener(item -> {
                show(tab);
                return true;
            });
        }
        menu.show();
    }
}
