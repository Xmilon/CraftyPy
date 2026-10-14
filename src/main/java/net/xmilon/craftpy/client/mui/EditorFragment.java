package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.mc.ui.ThemeControl;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.SpannableString;
import icyllis.modernui.text.Spanned;
import icyllis.modernui.text.TextPaint;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.text.Typeface;
import icyllis.modernui.text.style.ForegroundColorSpan;
import icyllis.modernui.text.style.StyleSpan;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.Menu;
import icyllis.modernui.view.MenuItem;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.HorizontalScrollView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.PopupMenu;
import icyllis.modernui.widget.PopupWindow;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import icyllis.modernui.widget.Toast;
import net.minecraft.client.Minecraft;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.CodeProblems;
import net.xmilon.craftpy.client.EditorSettings;
import net.xmilon.craftpy.client.EditorTheme;
import net.xmilon.craftpy.client.Syntax;
import net.xmilon.craftpy.client.TextOps;
import net.xmilon.craftpy.client.screen.AssetFileScreen;
import net.xmilon.craftpy.client.screen.AssetPickerScreen;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.project.ProjectSummary;
import net.xmilon.craftpy.python.ValidationProblem;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * Editor menu: explorer, tabs, the code area ({@link CodeView}), a find bar and the
 * Console / Problems / Bookmarks panel. Text operations live in {@link TextOps}, preferences in
 * {@link EditorSettings}; project state in {@link ClientProjectStore}. This class wires them to views.
 */
public class EditorFragment extends Fragment {

    private static final int CONSOLE_TEXT = 0xFFBCBEC4;
    private static final long EDIT_DELAY_MS = 150;
    private static final long DOUBLE_CLICK_MS = 400;
    private static final int PANEL_CONSOLE = 0;
    private static final int PANEL_PROBLEMS = 1;
    private static final int PANEL_BOOKMARKS = 2;

    /** Immutable copy of the store state, taken on the client thread. */
    private record State(@Nullable String projectId, String projectName, String projectVersion,
                         List<String> files, List<String> tabs,
                         List<String> dirtyTabs, @Nullable String active, @Nullable String activeContent,
                         boolean activeVirtual, List<String> log, List<ValidationProblem> problems,
                         List<CodeProblems.Problem> localProblems) {
    }

    private final EditorSettings settings = EditorSettings.get();

    private View toolbar;
    private TextView title;
    private View explorerColumn;
    private View explorerSplitter;
    private LinearLayout explorer;
    private View tabBar;
    private LinearLayout tabs;
    private LinearLayout center;
    private View codeArea;
    private ScrollView codeScroll;
    private CodeView code;
    private TextView gutter;
    private LinearLayout findBar;
    private EditText findInput;
    private EditText replaceInput;
    private View replaceRow;
    private TextView findCount;
    private TextView caseToggle;
    private TextView selectionToggle;
    private View bottomSplitter;
    private View bottomPanel;
    private TextView console;
    private ScrollView consoleScroll;
    private LinearLayout problems;
    private LinearLayout bookmarkList;
    private final TextView[] panelTabs = new TextView[3];
    private final View[] panelPanes = new View[3];

    private State state;
    private List<String> shownFiles = List.of();
    @Nullable
    private String shownPath;
    /** Text last loaded from or pushed to the store; differs from the editor while edits are unsynced. */
    private String synced = "";
    private boolean loading;
    private boolean internalEdit;
    private int autoCloserAt = -1;
    @Nullable
    private String previewPath;
    private String lastClickPath;
    private long lastClickTime;
    private boolean zen;
    private int panel = PANEL_CONSOLE;
    /** Line to jump to once {@code pendingGotoPath} is shown (bookmarks/problems in other files). */
    private String pendingGotoPath;
    private int pendingGotoLine;
    private final List<String> history = new ArrayList<>();
    private int historyIndex;
    private int findHistoryIndex = -1;
    private boolean matchCase;
    private boolean inSelection;
    private int selectionFrom;
    private int selectionTo;
    private List<int[]> matches = List.of();

    // Captured by beforeTextChanged for the typed-character helpers and bookmark shifting.
    private int changeStart;
    private int changeAdded;
    private String changeReplaced = "";

    private final Runnable onEdited = this::afterEdit;
    private final Runnable rebuildGutter = this::updateGutter;

    /** Client thread: snapshot the store, render on the UI thread. */
    private final Runnable storeListener = () -> {
        ClientProjectStore store = ClientProjectStore.getInstance();
        String id = store.activeProjectId();
        ProjectSummary summary = store.find(id);
        String active = store.activeFile();
        List<String> openTabs = store.openTabs();
        List<String> dirtyTabs = openTabs.stream().filter(store::isDirty).toList();
        State snapshot = new State(id, summary == null ? Objects.requireNonNullElse(id, "") : summary.name,
                summary == null ? "" : summary.version, List.copyOf(store.files()), List.copyOf(openTabs), dirtyTabs,
                active, active == null ? null : store.fileContent(active), store.isVirtual(active),
                List.copyOf(store.log()), List.copyOf(store.validationProblems()), CodeProblems.snapshot());
        View view = code;
        if (view != null) {
            view.post(() -> render(snapshot));
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        Context context = requireContext();
        var page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);

        toolbar = createToolbar(context);
        page.addView(toolbar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var body = row(context);
        body.setBaselineAligned(false);
        explorerColumn = createExplorer(context);
        body.addView(explorerColumn, new LinearLayout.LayoutParams(body.dp(settings.explorerWidth), MATCH_PARENT));
        explorerSplitter = new Splitter(context, true, this::dragExplorer, this::saveLayout);
        body.addView(explorerSplitter, new LinearLayout.LayoutParams(body.dp(6), MATCH_PARENT));

        center = new LinearLayout(context);
        center.setOrientation(LinearLayout.VERTICAL);
        tabBar = createTabBar(context);
        center.addView(tabBar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        findBar = createFindBar(context);
        center.addView(findBar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        codeArea = createCodeArea(context);
        center.addView(codeArea, new LinearLayout.LayoutParams(MATCH_PARENT, 0, settings.codeWeight));
        bottomSplitter = new Splitter(context, false, this::dragBottom, this::saveLayout);
        center.addView(bottomSplitter, new LinearLayout.LayoutParams(MATCH_PARENT, center.dp(6)));
        bottomPanel = createBottomPanel(context);
        center.addView(bottomPanel, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1 - settings.codeWeight));
        body.addView(center, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));

        var divider = new View(context);
        divider.setBackground(ThemeControl.makeDivider(page));
        page.addView(divider, new LinearLayout.LayoutParams(MATCH_PARENT, page.dp(1)));
        page.addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        applyTheme();
        applyFontSize();
        findBar.setVisibility(View.GONE);
        render(new State(null, "", "", List.of(), List.of(), List.of(), null, null, false,
                List.of(), List.of(), List.of()));
        store(s -> {
            s.setListener(storeListener);
            s.refresh();
            storeListener.run();
        });
        return page;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        flush();
        if (zen) {
            CraftPyFragment.setNavVisible(this, true);
        }
        store(s -> s.clearListener(storeListener));
        code = null;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private View createToolbar(Context context) {
        var bar = row(context);
        bar.setPadding(bar.dp(16), bar.dp(6), bar.dp(12), bar.dp(6));
        title = label(context, "", R.attr.textAppearanceTitleMedium);
        bar.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        bar.addView(toolButton(context, "Edit", "Find, replace, line tools, bookmarks", R.attr.borderlessButtonStyle,
                this::showEditMenu));
        bar.addView(toolButton(context, "View", "Word wrap, zoom, theme, zen mode", R.attr.borderlessButtonStyle,
                this::showViewMenu));
        bar.addView(toolButton(context, "Save", "Write the file to the project (Ctrl+S)", R.attr.borderlessButtonStyle,
                v -> save()));
        bar.addView(toolButton(context, "Validate", "Check the file on the Python worker (Ctrl+Enter)",
                R.attr.borderlessButtonStyle, v -> validate()));
        bar.addView(toolButton(context, "Assets", "Browse textures; the picked reference is copied to the clipboard",
                R.attr.borderlessButtonStyle, v -> openAssetPicker()));
        bar.addView(toolButton(context, "Build", "Pack the project into a .cpm module", R.attr.borderlessButtonStyle,
                this::build));
        bar.addView(toolButton(context, "Stop", "Interrupt the running Python code", R.attr.buttonOutlinedStyle,
                v -> store(ClientProjectStore::interrupt)));
        bar.addView(toolButton(context, "Reload", "Save, re-run this project and reload every loaded module (F5)",
                R.attr.buttonOutlinedStyle, v -> reloadAll()));
        bar.addView(toolButton(context, "Run", "Save and run the project (Ctrl+R)", R.attr.buttonStyle, v -> run()));
        return bar;
    }

    private static View toolButton(Context context, String text, String tooltip, icyllis.modernui.resources.ResourceId style,
                                   View.OnClickListener onClick) {
        var button = button(context, text, style, onClick);
        button.setTooltipText(tooltip);
        var lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        lp.leftMargin = button.dp(4);
        button.setLayoutParams(lp);
        return button;
    }

    private View createExplorer(Context context) {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackground(new ColorDrawable(color(context, R.attr.colorSurfaceContainerLow)));
        var header = row(context);
        header.setPadding(header.dp(12), header.dp(4), header.dp(4), header.dp(4));
        var label = label(context, "Files", R.attr.textAppearanceLabelLarge);
        label.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        header.addView(label, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        header.addView(button(context, "New", R.attr.borderlessButtonStyle, v -> showNewMenu(v, "code/")));
        column.addView(header);

        explorer = new LinearLayout(context);
        explorer.setOrientation(LinearLayout.VERTICAL);
        explorer.setPadding(column.dp(6), 0, column.dp(6), column.dp(6));
        var scroll = new ScrollView(context);
        scroll.addView(explorer, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        return column;
    }

    private View createTabBar(Context context) {
        var bar = row(context);
        var tabScroll = new HorizontalScrollView(context);
        tabs = row(context);
        tabs.setPadding(bar.dp(6), bar.dp(4), bar.dp(6), bar.dp(4));
        tabScroll.addView(tabs);
        bar.addView(tabScroll, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        var all = label(context, "▾", R.attr.textAppearanceTitleSmall);
        all.setPadding(bar.dp(10), bar.dp(4), bar.dp(10), bar.dp(4));
        all.setClickable(true);
        all.setTooltipText("All open tabs");
        hoverBackground(all);
        all.setOnClickListener(this::showTabMenu);
        bar.addView(all);
        return bar;
    }

    private LinearLayout createFindBar(Context context) {
        var bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackground(new ColorDrawable(color(context, R.attr.colorSurfaceContainerLow)));
        int dp4 = bar.dp(4);
        bar.setPadding(bar.dp(8), dp4, bar.dp(8), dp4);

        var findRow = row(context);
        findInput = new EditText(context, null, R.attr.editTextOutlinedStyle);
        findInput.setSingleLine();
        findInput.setHint("Find (Enter = next, Up/Down = history)");
        findInput.addTextChangedListener(simpleWatcher(() -> {
            findHistoryIndex = -1;
            updateMatches(true);
        }));
        findInput.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN && onFindKey(keyCode, event));
        findRow.addView(findInput, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        findRow.addView(smallButton(context, "History", "Recent searches", this::showFindHistory));
        caseToggle = smallButton(context, "Aa", "Match case", v -> {
            matchCase = !matchCase;
            styleChip(caseToggle, matchCase);
            updateMatches(true);
        });
        findRow.addView(caseToggle);
        selectionToggle = smallButton(context, "In selection", "Only search the text that was selected", v -> {
            inSelection = !inSelection;
            if (inSelection) {
                selectionFrom = Math.min(code.getSelectionStart(), code.getSelectionEnd());
                selectionTo = Math.max(code.getSelectionStart(), code.getSelectionEnd());
                if (selectionTo <= selectionFrom) {
                    inSelection = false;
                    toast("Select some text first");
                }
            }
            styleChip(selectionToggle, inSelection);
            updateMatches(true);
        });
        findRow.addView(selectionToggle);
        findRow.addView(smallButton(context, "↑", "Previous match (Shift+F3)", v -> findStep(-1)));
        findRow.addView(smallButton(context, "↓", "Next match (F3)", v -> findStep(1)));
        findCount = label(context, "", R.attr.textAppearanceLabelMedium);
        findCount.setPadding(bar.dp(8), 0, bar.dp(8), 0);
        findRow.addView(findCount);
        findRow.addView(smallButton(context, "×", "Close (Esc)", v -> closeFind()));
        bar.addView(findRow);

        var replace = row(context);
        replaceInput = new EditText(context, null, R.attr.editTextOutlinedStyle);
        replaceInput.setSingleLine();
        replaceInput.setHint("Replace with");
        replace.addView(replaceInput, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        replace.addView(smallButton(context, "Replace", "Replace this match and go to the next", v -> replaceOne()));
        replace.addView(smallButton(context, "Replace all", "Replace every match", v -> replaceAll()));
        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.topMargin = dp4;
        bar.addView(replace, lp);
        replaceRow = replace;
        styleChip(caseToggle, false);
        styleChip(selectionToggle, false);
        return bar;
    }

    private static TextView smallButton(Context context, String text, String tooltip, View.OnClickListener onClick) {
        var view = label(context, text, R.attr.textAppearanceLabelLarge);
        int dp6 = view.dp(6);
        view.setPadding(view.dp(10), dp6, view.dp(10), dp6);
        view.setClickable(true);
        view.setTooltipText(tooltip);
        hoverBackground(view);
        view.setOnClickListener(onClick);
        var lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        lp.leftMargin = view.dp(4);
        view.setLayoutParams(lp);
        return view;
    }

    private View createCodeArea(Context context) {
        codeScroll = new ScrollView(context);
        codeScroll.setFillViewport(true);
        var row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int dp8 = row.dp(8);

        gutter = new TextView(context);
        gutter.setTypeface(Typeface.MONOSPACED);
        gutter.setGravity(Gravity.END | Gravity.TOP);
        gutter.setPadding(dp8, dp8, dp8, dp8);
        row.addView(gutter, new LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT));

        code = new CodeView(context);
        code.setTypeface(Typeface.MONOSPACED);
        code.setGravity(Gravity.START | Gravity.TOP);
        code.setHorizontallyScrolling(true);
        code.setBackground(null);
        code.setPadding(dp8, dp8, dp8, dp8);
        code.setHint("Open a file from the explorer (Ctrl+P: quick open)");
        code.setOnZoom(this::zoom);
        code.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                changeStart = start;
                changeAdded = after;
                changeReplaced = s.subSequence(start, start + count).toString();
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (loading) {
                    return;
                }
                shiftBookmarks(s);
                if (!internalEdit) {
                    afterTyped(s);
                }
                code.removeCallbacks(onEdited);
                code.postDelayed(onEdited, EDIT_DELAY_MS);
            }
        });
        code.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                && onCodeKey(keyCode, event));
        code.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) {
                gutter.removeCallbacks(rebuildGutter);
                gutter.post(rebuildGutter);
            }
        });
        row.addView(code, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));

        codeScroll.addView(row, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        return codeScroll;
    }

    private View createBottomPanel(Context context) {
        var panelView = new LinearLayout(context);
        panelView.setOrientation(LinearLayout.VERTICAL);

        var header = row(context);
        header.setPadding(header.dp(8), header.dp(2), header.dp(8), header.dp(2));
        String[] names = { "Console", "Problems", "Bookmarks" };
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            panelTabs[i] = panelTab(context, names[i], v -> showPanel(index));
            header.addView(panelTabs[i]);
        }
        header.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
        header.addView(button(context, "Clear", R.attr.borderlessButtonStyle,
                v -> store(ClientProjectStore::clearLog)));
        panelView.addView(header);

        var consoleColumn = new LinearLayout(context);
        consoleColumn.setOrientation(LinearLayout.VERTICAL);
        console = new TextView(context);
        console.setTypeface(Typeface.MONOSPACED);
        console.setTextSize(12);
        console.setTextColor(CONSOLE_TEXT);
        console.setTextIsSelectable(true);
        int dp8 = panelView.dp(8);
        console.setPadding(dp8, dp8 / 2, dp8, dp8 / 2);
        consoleScroll = new ScrollView(context);
        consoleScroll.addView(console, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        consoleColumn.addView(consoleScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        var input = new EditText(context, null, R.attr.editTextOutlinedStyle);
        input.setSingleLine();
        input.setTypeface(Typeface.MONOSPACED);
        input.setTextSize(12);
        input.setHint(">>> Python, runs in the project's worker (Up/Down = history)");
        input.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            switch (keyCode) {
                case KeyEvent.KEY_ENTER, KeyEvent.KEY_KP_ENTER -> {
                    String line = input.getText().toString();
                    if (!line.isBlank()) {
                        history.add(line);
                        historyIndex = history.size();
                        store(s -> {
                            s.append(">>> " + line);
                            s.evaluate(line);
                        });
                        input.setText("");
                    }
                    return true;
                }
                case KeyEvent.KEY_UP, KeyEvent.KEY_DOWN -> {
                    if (history.isEmpty()) {
                        return true;
                    }
                    historyIndex = Math.clamp(historyIndex + (keyCode == KeyEvent.KEY_UP ? -1 : 1), 0, history.size());
                    String value = historyIndex < history.size() ? history.get(historyIndex) : "";
                    input.setText(value);
                    input.setSelection(value.length());
                    return true;
                }
                default -> {
                    return false;
                }
            }
        });
        var inputLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        inputLp.setMargins(dp8, dp8 / 2, dp8, dp8 / 2);
        consoleColumn.addView(input, inputLp);
        panelPanes[PANEL_CONSOLE] = consoleColumn;
        panelView.addView(consoleColumn, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        problems = new LinearLayout(context);
        problems.setOrientation(LinearLayout.VERTICAL);
        problems.setPadding(dp8, dp8 / 2, dp8, dp8 / 2);
        var problemsScroll = new ScrollView(context);
        problemsScroll.addView(problems, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        panelPanes[PANEL_PROBLEMS] = problemsScroll;
        panelView.addView(problemsScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        bookmarkList = new LinearLayout(context);
        bookmarkList.setOrientation(LinearLayout.VERTICAL);
        bookmarkList.setPadding(dp8, dp8 / 2, dp8, dp8 / 2);
        var bookmarkScroll = new ScrollView(context);
        bookmarkScroll.addView(bookmarkList, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        panelPanes[PANEL_BOOKMARKS] = bookmarkScroll;
        panelView.addView(bookmarkScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        showPanel(PANEL_CONSOLE);
        return panelView;
    }

    private static TextView panelTab(Context context, String text, View.OnClickListener onClick) {
        var tab = label(context, text, R.attr.textAppearanceLabelLarge);
        int dp6 = tab.dp(6);
        tab.setPadding(tab.dp(12), dp6, tab.dp(12), dp6);
        tab.setClickable(true);
        tab.setOnClickListener(onClick);
        return tab;
    }

    private void showPanel(int index) {
        panel = index;
        Context context = panelTabs[0].getContext();
        for (int i = 0; i < panelTabs.length; i++) {
            boolean selected = i == index;
            panelPanes[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            styleChip(panelTabs[i], selected);
            panelTabs[i].setTextColor(color(context, selected ? R.attr.colorOnSecondaryContainer : R.attr.colorOnSurfaceVariant));
        }
        if (index == PANEL_BOOKMARKS) {
            renderBookmarks();
        }
    }

    /** Rounded pill background for the selected tab/chip; hover highlight otherwise. */
    private static void styleChip(View view, boolean selected) {
        if (selected) {
            var bg = new ShapeDrawable();
            bg.setCornerRadius(view.dp(8));
            bg.setColor(color(view.getContext(), R.attr.colorSecondaryContainer));
            view.setBackground(bg);
        } else {
            hoverBackground(view);
        }
    }

    // ------------------------------------------------------------------
    // View settings: theme, zoom, wrap, panels, zen
    // ------------------------------------------------------------------

    private void applyTheme() {
        EditorTheme theme = settings.theme();
        code.setTheme(theme);
        code.setRulerColumn(settings.ruler);
        codeScroll.setBackground(new ColorDrawable(theme.bg));
        gutter.setBackground(new ColorDrawable(theme.gutterBg));
        gutter.setTextColor(theme.gutterText);
        consoleScroll.setBackground(new ColorDrawable(theme.bg));
        highlight();
    }

    private void applyFontSize() {
        code.setTextSize(settings.fontSize);
        gutter.setTextSize(settings.fontSize);
        gutter.post(rebuildGutter);
    }

    private void zoom(int step) {
        settings.fontSize = step == 0 ? 13 : Math.clamp(settings.fontSize + step, 8, 32);
        applyFontSize();
        settings.save();
        toast("Zoom: " + Math.round(settings.fontSize / 13f * 100) + "%");
    }

    private boolean isWrapped() {
        return shownPath != null && settings.wrapped.contains(fileKey(shownPath));
    }

    private void applyWrap() {
        code.setHorizontallyScrolling(!isWrapped());
        gutter.post(rebuildGutter);
    }

    private void toggleWrap() {
        if (shownPath == null) {
            return;
        }
        String key = fileKey(shownPath);
        if (!settings.wrapped.remove(key)) {
            settings.wrapped.add(key);
        }
        settings.save();
        applyWrap();
    }

    private void dragExplorer(int dx) {
        var lp = explorerColumn.getLayoutParams();
        int max = Math.max(explorerColumn.dp(160), ((View) explorerColumn.getParent()).getWidth() / 2);
        lp.width = Math.clamp(lp.width + dx, explorerColumn.dp(120), max);
        explorerColumn.setLayoutParams(lp);
    }

    private void dragBottom(int dy) {
        int codeHeight = codeArea.getHeight();
        int panelHeight = bottomPanel.getHeight();
        int total = codeHeight + panelHeight;
        if (total <= 0) {
            return;
        }
        float weight = Math.clamp((codeHeight + dy) / (float) total, 0.15f, 0.9f);
        setCodeWeight(weight);
    }

    private void setCodeWeight(float weight) {
        var codeLp = (LinearLayout.LayoutParams) codeArea.getLayoutParams();
        var panelLp = (LinearLayout.LayoutParams) bottomPanel.getLayoutParams();
        codeLp.weight = weight;
        panelLp.weight = 1 - weight;
        codeArea.setLayoutParams(codeLp);
        bottomPanel.setLayoutParams(panelLp);
        settings.codeWeight = weight;
    }

    private void saveLayout() {
        settings.explorerWidth = Math.round(explorerColumn.getLayoutParams().width / (float) explorerColumn.dp(1));
        settings.save();
    }

    private void toggleZen() {
        zen = !zen;
        int visibility = zen ? View.GONE : View.VISIBLE;
        toolbar.setVisibility(visibility);
        explorerColumn.setVisibility(visibility);
        explorerSplitter.setVisibility(visibility);
        tabBar.setVisibility(visibility);
        bottomSplitter.setVisibility(visibility);
        bottomPanel.setVisibility(visibility);
        CraftPyFragment.setNavVisible(this, !zen);
        if (zen) {
            toast("Zen mode - F11 to leave");
        }
        code.requestFocus();
    }

    // ------------------------------------------------------------------
    // Rendering store state (UI thread)
    // ------------------------------------------------------------------

    private void render(State next) {
        if (code == null) {
            return;
        }
        state = next;
        title.setText(next.projectId() == null ? "No project open - pick one on the Home page" : next.projectName());

        if (!next.files().equals(shownFiles)) {
            shownFiles = next.files();
            renderExplorer();
        } else {
            highlightExplorer();
        }
        if (previewPath != null && !next.tabs().contains(previewPath)) {
            previewPath = null;
        }
        if (previewPath != null && next.dirtyTabs().contains(previewPath)) {
            previewPath = null; // edited: it becomes a normal tab
        }
        renderTabs();
        renderEditor(next);
        renderConsole(next.log());
        renderProblems(next);
        if (panel == PANEL_BOOKMARKS) {
            renderBookmarks();
        }
    }

    private void renderEditor(State next) {
        String active = next.active();
        String content = next.activeContent();
        if (active == null || content == null) {
            if (shownPath != null) {
                setEditorText(null, "");
            }
            return;
        }
        if (!active.equals(shownPath)) {
            flush();
            setEditorText(active, content);
            settings.touchRecent(next.projectId(), active);
            settings.save();
        } else if (!content.equals(synced) && code.getText().toString().equals(synced)) {
            // Changed on the server side (reload) while the user had no unsynced edits.
            setEditorText(active, content);
        }
        if (pendingGotoPath != null && pendingGotoPath.equals(shownPath)) {
            pendingGotoPath = null;
            goToLine(pendingGotoLine);
        }
    }

    private void setEditorText(@Nullable String path, String content) {
        loading = true;
        try {
            shownPath = path;
            synced = content;
            code.setText(content);
            code.setSelection(0);
        } finally {
            loading = false;
        }
        autoCloserAt = -1;
        applyWrap();
        highlight();
        if (findBar.getVisibility() == View.VISIBLE) {
            inSelection = false;
            styleChip(selectionToggle, false);
            updateMatches(false);
        }
    }

    private void renderExplorer() {
        explorer.removeAllViews();
        Context context = explorer.getContext();
        if (shownFiles.isEmpty()) {
            var empty = label(context, state.projectId() == null ? "No project open." : "No files.",
                    R.attr.textAppearanceBodySmall);
            empty.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
            empty.setPadding(empty.dp(6), empty.dp(6), 0, 0);
            explorer.addView(empty);
            return;
        }
        List<String> sorted = new ArrayList<>(shownFiles);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        String folder = null;
        for (String path : sorted) {
            int slash = path.lastIndexOf('/');
            String dir = slash < 0 ? "" : path.substring(0, slash);
            if (!dir.equals(folder)) {
                folder = dir;
                var header = label(context, dir.isEmpty() ? "/" : dir + "/", R.attr.textAppearanceLabelMedium);
                header.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
                header.setPadding(header.dp(6), header.dp(8), 0, header.dp(2));
                final String newIn = dir.isEmpty() ? "" : dir + "/";
                header.setOnCreateContextMenuListener((menu, v, info) -> addNewItems(menu, newIn));
                explorer.addView(header);
            }
            explorer.addView(fileRow(context, path, slash < 0 ? path : path.substring(slash + 1)));
        }
        highlightExplorer();
    }

    private View fileRow(Context context, String path, String name) {
        var row = label(context, name, R.attr.textAppearanceBodyMedium);
        row.setTag(path);
        row.setSingleLine();
        row.setPadding(row.dp(14), row.dp(4), row.dp(6), row.dp(4));
        row.setClickable(true);
        row.setTooltipText(path + "  (click: preview, double click: keep open)");
        hoverBackground(row);
        row.setOnClickListener(v -> openFromExplorer(path));
        row.setOnCreateContextMenuListener((menu, v, info) -> {
            menu.add("Open").setOnMenuItemClickListener(item -> {
                openEntry(path, false);
                return true;
            });
            menu.add("Rename...").setOnMenuItemClickListener(item -> {
                prompt(v, "Rename " + name, path, "Rename", to -> store(s -> s.renameEntry(path, to)));
                return true;
            });
            menu.add("Delete...").setOnMenuItemClickListener(item -> {
                prompt(v, "Delete " + path + "?", null, "Delete", ignored -> store(s -> {
                    s.closeFile(path);
                    s.deleteEntry(path);
                }));
                return true;
            });
            int slash = path.lastIndexOf('/');
            addNewItems(menu, slash < 0 ? "" : path.substring(0, slash + 1));
        });
        return row;
    }

    private void highlightExplorer() {
        String active = state == null ? null : state.active();
        Context context = explorer.getContext();
        for (int i = 0; i < explorer.getChildCount(); i++) {
            View child = explorer.getChildAt(i);
            if (child instanceof TextView tv && child.getTag() instanceof String path) {
                tv.setTextColor(color(context, path.equals(active) ? R.attr.colorPrimary : R.attr.colorOnSurface));
            }
        }
    }

    /** Pinned tabs first, then the store's order. */
    private List<String> orderedTabs() {
        List<String> ordered = new ArrayList<>();
        for (String path : state.tabs()) {
            if (isPinned(path)) {
                ordered.add(path);
            }
        }
        for (String path : state.tabs()) {
            if (!isPinned(path)) {
                ordered.add(path);
            }
        }
        return ordered;
    }

    private void renderTabs() {
        tabs.removeAllViews();
        Context context = tabs.getContext();
        for (String path : orderedTabs()) {
            boolean active = path.equals(state.active());
            boolean dirty = state.dirtyTabs().contains(path);
            boolean pinned = isPinned(path);
            var chip = row(context);
            chip.setPadding(chip.dp(10), chip.dp(2), chip.dp(4), chip.dp(2));
            styleChip(chip, active);
            chip.setTooltipText(path + (pinned ? " (pinned)" : path.equals(previewPath) ? " (preview)" : ""));
            String text = (pinned ? "📌 " : "") + (dirty ? "● " : "") + path.substring(path.lastIndexOf('/') + 1);
            var name = label(context, "", R.attr.textAppearanceLabelLarge);
            if (path.equals(previewPath)) {
                var italic = new SpannableString(text);
                italic.setSpan(new StyleSpan(TextPaint.ITALIC), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                name.setText(italic);
            } else {
                name.setText(text);
            }
            name.setTextColor(color(context, active ? R.attr.colorOnSecondaryContainer : R.attr.colorOnSurfaceVariant));
            chip.addView(name);
            if (!pinned) {
                var close = label(context, "×", R.attr.textAppearanceTitleSmall);
                close.setPadding(close.dp(8), 0, close.dp(6), 0);
                close.setClickable(true);
                close.setTooltipText("Close (Ctrl+W)");
                close.setOnClickListener(v -> closeTab(v, path));
                chip.addView(close);
            } else {
                chip.addView(new View(context), new LinearLayout.LayoutParams(chip.dp(6), 1));
            }
            chip.setClickable(true);
            chip.setOnClickListener(v -> {
                flush();
                store(s -> s.openFile(path));
            });
            chip.setOnCreateContextMenuListener((menu, v, info) -> {
                menu.add(pinned ? "Unpin" : "Pin").setOnMenuItemClickListener(item -> {
                    togglePin(path);
                    return true;
                });
                if (path.equals(previewPath)) {
                    menu.add("Keep open").setOnMenuItemClickListener(item -> {
                        previewPath = null;
                        renderTabs();
                        return true;
                    });
                }
                menu.add("Close others").setOnMenuItemClickListener(item -> {
                    for (String other : state.tabs()) {
                        if (!other.equals(path) && !isPinned(other) && !state.dirtyTabs().contains(other)) {
                            store(s -> s.closeFile(other));
                        }
                    }
                    return true;
                });
            });
            var lp = new LinearLayout.LayoutParams(WRAP_CONTENT, chip.dp(30));
            lp.rightMargin = chip.dp(4);
            tabs.addView(chip, lp);
        }
    }

    private void showTabMenu(View anchor) {
        if (state == null || state.tabs().isEmpty()) {
            toast("No open tabs");
            return;
        }
        var menu = new PopupMenu(anchor.getContext(), anchor);
        for (String path : orderedTabs()) {
            MenuItem item = menu.getMenu().add(path);
            item.setCheckable(true).setChecked(path.equals(state.active()));
            item.setOnMenuItemClickListener(i -> {
                flush();
                store(s -> s.openFile(path));
                return true;
            });
        }
        menu.show();
    }

    private boolean isPinned(String path) {
        return settings.pinned.contains(fileKey(path));
    }

    private void togglePin(String path) {
        String key = fileKey(path);
        if (!settings.pinned.remove(key)) {
            settings.pinned.add(key);
            if (path.equals(previewPath)) {
                previewPath = null;
            }
        }
        settings.save();
        renderTabs();
    }

    private void closeTab(View anchor, String path) {
        if (isPinned(path)) {
            toast("Pinned tabs stay open - unpin it first (right click)");
            return;
        }
        if (path.equals(shownPath)) {
            flush();
        }
        boolean dirty = state.dirtyTabs().contains(path)
                || (path.equals(shownPath) && !code.getText().toString().equals(synced));
        if (dirty) {
            prompt(anchor, "Close " + path + " and discard unsaved changes?", null, "Discard",
                    ignored -> store(s -> s.closeFile(path)));
        } else {
            store(s -> s.closeFile(path));
        }
    }

    private void renderConsole(List<String> log) {
        boolean atBottom = !consoleScroll.canScrollVertically(1);
        console.setText(String.join("\n", log));
        if (atBottom) {
            consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void renderProblems(State next) {
        problems.removeAllViews();
        Context context = problems.getContext();
        int count = 0;
        for (ValidationProblem p : next.problems()) {
            String file = p.file().isEmpty() ? shownPath : p.file();
            problems.addView(problemRow(context, file, p.line(), (p.line() + 1) + ":" + p.column() + "  " + p.message()
                    + (p.file().isEmpty() ? "" : "  [" + p.file() + "]"),
                    p.isError() ? R.attr.colorError : R.attr.colorTertiary));
            count++;
        }
        for (CodeProblems.Problem p : next.localProblems()) {
            problems.addView(problemRow(context, shownPath, p.line(), (p.line() + 1) + ":" + p.column() + "  " + p.message(),
                    switch (p.severity()) {
                        case ERROR -> R.attr.colorError;
                        case WARNING -> R.attr.colorTertiary;
                        case INFO -> R.attr.colorOnSurfaceVariant;
                    }));
            count++;
        }
        if (count == 0) {
            var none = label(context, "No problems. Validate (Ctrl+Enter) checks the file on the Python worker.",
                    R.attr.textAppearanceBodySmall);
            none.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
            problems.addView(none);
        }
        panelTabs[PANEL_PROBLEMS].setText(count == 0 ? "Problems" : "Problems (" + count + ")");
    }

    private View problemRow(Context context, @Nullable String file, int line, String text, String colorAttr) {
        var row = label(context, text, R.attr.textAppearanceBodySmall);
        row.setTextColor(color(context, colorAttr));
        row.setPadding(row.dp(6), row.dp(3), row.dp(6), row.dp(3));
        row.setClickable(true);
        hoverBackground(row);
        row.setOnClickListener(v -> openAt(file, line));
        return row;
    }

    private void renderBookmarks() {
        bookmarkList.removeAllViews();
        Context context = bookmarkList.getContext();
        String project = state == null ? null : state.projectId();
        String prefix = EditorSettings.key(project, "");
        int count = 0;
        String text = code.getText().toString();
        for (var entry : settings.bookmarks.entrySet()) {
            if (!entry.getKey().startsWith(prefix) || entry.getValue().isEmpty()) {
                continue;
            }
            String path = entry.getKey().substring(prefix.length());
            for (int line : entry.getValue()) {
                String preview = "";
                if (path.equals(shownPath)) {
                    int start = TextOps.offsetOfLine(text, line);
                    preview = "  " + text.substring(start, TextOps.lineEnd(text, start)).strip();
                }
                var row = label(context, "◆ " + path + ":" + (line + 1) + preview, R.attr.textAppearanceBodySmall);
                row.setPadding(row.dp(6), row.dp(3), row.dp(6), row.dp(3));
                row.setClickable(true);
                hoverBackground(row);
                row.setOnClickListener(v -> openAt(path, line));
                row.setOnCreateContextMenuListener((menu, v, info) -> menu.add("Remove bookmark").setOnMenuItemClickListener(i -> {
                    settings.bookmarks(project, path).remove(line);
                    settings.save();
                    renderBookmarks();
                    updateGutter();
                    return true;
                }));
                bookmarkList.addView(row);
                count++;
            }
        }
        if (count == 0) {
            var none = label(context, "No bookmarks. Ctrl+F2 toggles one on the current line; F2 / Shift+F2 jump between them.",
                    R.attr.textAppearanceBodySmall);
            none.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
            bookmarkList.addView(none);
        }
        panelTabs[PANEL_BOOKMARKS].setText(count == 0 ? "Bookmarks" : "Bookmarks (" + count + ")");
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    /** Debounced after typing: push the text to the store and re-highlight. */
    private void afterEdit() {
        if (code == null) {
            return;
        }
        flush();
        highlight();
        if (findBar.getVisibility() == View.VISIBLE) {
            updateMatches(false);
        }
    }

    /** Pushes unsynced editor text into the store (marks the file dirty). */
    private void flush() {
        if (code == null || shownPath == null || (state != null && state.activeVirtual())) {
            return;
        }
        String text = code.getText().toString();
        if (text.equals(synced)) {
            return;
        }
        synced = text;
        if (shownPath.equals(previewPath)) {
            previewPath = null;
        }
        String path = shownPath;
        store(s -> s.markDirty(path, text));
    }

    private void highlight() {
        Editable text = code.getText();
        for (ForegroundColorSpan span : text.getSpans(0, text.length(), ForegroundColorSpan.class)) {
            text.removeSpan(span);
        }
        EditorTheme theme = settings.theme();
        String language = Syntax.language(shownPath);
        String all = text.toString();
        int lineStart = 0;
        while (lineStart <= all.length()) {
            int lineEnd = all.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = all.length();
            }
            int pos = lineStart;
            for (Syntax.Token token : Syntax.tokenize(all.substring(lineStart, lineEnd), language)) {
                int end = pos + token.text().length();
                if (token.kind() != Syntax.Kind.TEXT && end > pos) {
                    text.setSpan(new ForegroundColorSpan(theme.color(token.kind())), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                pos = end;
            }
            if (lineEnd == all.length()) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        gutter.removeCallbacks(rebuildGutter);
        gutter.post(rebuildGutter);
    }

    /** Line numbers (one per logical line, blank rows for wrapped continuation lines) and bookmark marks. */
    private void updateGutter() {
        if (code == null) {
            return;
        }
        String all = code.getText().toString();
        var layout = code.getLayout();
        TreeSet<Integer> marks = shownPath == null ? new TreeSet<>() : settings.bookmarks(projectId(), shownPath);
        var numbers = new StringBuilder();
        int line = 0;
        int start = 0;
        while (true) {
            int end = all.indexOf('\n', start);
            boolean last = end < 0;
            if (last) {
                end = all.length();
            }
            numbers.append(marks.contains(line) ? "◆ " : "").append(line + 1);
            if (layout != null && isWrapped()) {
                int visual = layout.getLineForOffset(end) - layout.getLineForOffset(start);
                numbers.append("\n".repeat(Math.max(0, visual)));
            }
            if (last) {
                break;
            }
            numbers.append('\n');
            start = end + 1;
            line++;
        }
        gutter.setText(numbers);
    }

    private void shiftBookmarks(Editable s) {
        if (shownPath == null) {
            return;
        }
        int removed = (int) changeReplaced.chars().filter(c -> c == '\n').count();
        int added = 0;
        for (int i = changeStart; i < changeStart + changeAdded && i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                added++;
            }
        }
        if (removed == added) {
            return;
        }
        TreeSet<Integer> marks = settings.bookmarks(projectId(), shownPath);
        if (marks.isEmpty()) {
            return;
        }
        TreeSet<Integer> shifted = TextOps.shiftLines(marks, TextOps.lineOf(s.toString(), changeStart), removed, added);
        marks.clear();
        marks.addAll(shifted);
        settings.save();
    }

    /** Auto-pairs, surround, closer step-over and else/except dedent after a single typed character. */
    private void afterTyped(Editable s) {
        if (changeAdded != 1 || changeStart >= s.length()) {
            autoCloserAt = -1;
            return;
        }
        int at = changeStart;
        char typed = s.charAt(at);
        String text = s.toString();
        if (autoCloserAt == at && changeReplaced.isEmpty() && at + 1 < text.length() && text.charAt(at + 1) == typed
                && TextOps.shouldSkipCloser(text, at + 1, typed)) {
            autoCloserAt = -1;
            applyEdit(new TextOps.Edit(at + 1, at + 2, "", at + 1));
            return;
        }
        TextOps.Edit edit = TextOps.afterTyped(text, at, typed, changeReplaced);
        if (edit != null) {
            applyEdit(edit);
            autoCloserAt = changeReplaced.isEmpty() ? at + 1 : -1;
            return;
        }
        autoCloserAt = -1;
        if (typed == ':') {
            TextOps.Edit dedent = TextOps.dedentBlockKeyword(text, at + 1);
            if (dedent != null) {
                applyEdit(dedent);
            }
        }
    }

    private void applyEdit(TextOps.Edit edit) {
        Editable text = code.getText();
        internalEdit = true;
        try {
            text.replace(edit.start(), edit.end(), edit.text());
        } finally {
            internalEdit = false;
        }
        int length = text.length();
        code.setSelection(Math.min(edit.cursor(), length), Math.min(edit.selectionEnd(), length));
    }

    private boolean onCodeKey(int keyCode, KeyEvent event) {
        boolean ctrl = event.isCtrlPressed();
        boolean shift = event.isShiftPressed();
        if (ctrl) {
            switch (keyCode) {
                case KeyEvent.KEY_S -> save();
                case KeyEvent.KEY_R -> run();
                case KeyEvent.KEY_ENTER, KeyEvent.KEY_KP_ENTER -> {
                    if (shift) {
                        applyEdit(TextOps.splitLine(code.getText().toString(), code.getSelectionStart()));
                    } else {
                        validate();
                    }
                }
                case KeyEvent.KEY_J -> joinLines();
                case KeyEvent.KEY_F -> openFind(false);
                case KeyEvent.KEY_H -> openFind(true);
                case KeyEvent.KEY_P -> quickOpen();
                case KeyEvent.KEY_W -> {
                    if (shownPath != null) {
                        closeTab(code, shownPath);
                    }
                }
                case KeyEvent.KEY_EQUAL, KeyEvent.KEY_KP_ADD -> zoom(1);
                case KeyEvent.KEY_MINUS, KeyEvent.KEY_KP_SUBTRACT -> zoom(-1);
                case KeyEvent.KEY_0 -> zoom(0);
                case KeyEvent.KEY_SLASH -> showShortcuts();
                case KeyEvent.KEY_F2 -> toggleBookmark();
                default -> {
                    return false;
                }
            }
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEY_F1 -> {
                showShortcuts();
                return true;
            }
            case KeyEvent.KEY_F2 -> {
                jumpBookmark(shift ? -1 : 1);
                return true;
            }
            case KeyEvent.KEY_F3 -> {
                findStep(shift ? -1 : 1);
                return true;
            }
            case KeyEvent.KEY_F5 -> {
                reloadAll();
                return true;
            }
            case KeyEvent.KEY_F11 -> {
                toggleZen();
                return true;
            }
            default -> {
            }
        }
        Editable text = code.getText();
        int start = code.getSelectionStart();
        int end = code.getSelectionEnd();
        if (start < 0) {
            return false;
        }
        int from = Math.min(start, end);
        int to = Math.max(start, end);
        String all = text.toString();
        switch (keyCode) {
            case KeyEvent.KEY_HOME -> {
                int target = TextOps.smartHome(all, start == end ? start : end);
                if (shift) {
                    code.setSelection(start, target);
                } else {
                    code.setSelection(target);
                }
                return true;
            }
            case KeyEvent.KEY_TAB -> {
                if (shift || all.substring(from, to).contains("\n")) {
                    indentLines(from, to, !shift);
                } else {
                    applyEdit(new TextOps.Edit(from, to, "    ", from + 4));
                }
                return true;
            }
            case KeyEvent.KEY_ENTER, KeyEvent.KEY_KP_ENTER -> {
                // Keep the current line's indentation; indent one level after a ':'.
                int lineStart = TextOps.lineStart(all, from);
                String line = all.substring(lineStart, from);
                String indent = TextOps.leadingWhitespace(line);
                if (line.stripTrailing().endsWith(":")) {
                    indent += "    ";
                }
                applyEdit(new TextOps.Edit(from, to, "\n" + indent, from + 1 + indent.length()));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void indentLines(int from, int to, boolean indent) {
        String all = code.getText().toString();
        int first = TextOps.lineStart(all, from);
        int last = TextOps.lineEnd(all, to > from && all.charAt(to - 1) == '\n' ? to - 1 : to);
        StringBuilder out = new StringBuilder();
        for (String line : all.substring(first, last).split("\n", -1)) {
            if (out.length() > 0) {
                out.append('\n');
            }
            if (indent) {
                out.append("    ").append(line);
            } else {
                int remove = 0;
                while (remove < 4 && remove < line.length() && line.charAt(remove) == ' ') {
                    remove++;
                }
                out.append(line.substring(remove));
            }
        }
        applyEdit(new TextOps.Edit(first, last, out.toString(), first, first + out.length()));
    }

    private void joinLines() {
        TextOps.Edit edit = TextOps.joinLines(code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd());
        if (edit != null) {
            applyEdit(edit);
        }
    }

    private void lineCommand(TextOps.Edit edit) {
        if (edit != null && !edit.text().equals(code.getText().toString().substring(edit.start(), edit.end()))) {
            applyEdit(edit);
        }
    }

    private void goToLine(int line) {
        String all = code.getText().toString();
        int offset = TextOps.offsetOfLine(all, line);
        code.requestFocus();
        code.setSelection(Math.min(offset, all.length()));
        bringIntoView(offset);
    }

    private void bringIntoView(int offset) {
        var layout = code.getLayout();
        if (layout == null) {
            return;
        }
        int line = layout.getLineForOffset(Math.min(offset, code.getText().length()));
        int y = layout.getLineTop(line) + code.getExtendedPaddingTop();
        int height = codeScroll.getHeight();
        if (y < codeScroll.getScrollY() || y > codeScroll.getScrollY() + height - code.getLineHeight() * 2) {
            codeScroll.smoothScrollTo(Math.max(0, y - height / 3));
        }
    }

    // ------------------------------------------------------------------
    // Bookmarks
    // ------------------------------------------------------------------

    private void toggleBookmark() {
        if (shownPath == null) {
            return;
        }
        int line = TextOps.lineOf(code.getText().toString(), code.getSelectionStart());
        TreeSet<Integer> marks = settings.bookmarks(projectId(), shownPath);
        if (!marks.remove(line)) {
            marks.add(line);
        }
        settings.save();
        updateGutter();
        if (panel == PANEL_BOOKMARKS) {
            renderBookmarks();
        }
    }

    private void jumpBookmark(int direction) {
        if (shownPath == null) {
            return;
        }
        TreeSet<Integer> marks = settings.bookmarks(projectId(), shownPath);
        if (marks.isEmpty()) {
            toast("No bookmarks in this file (Ctrl+F2 adds one)");
            return;
        }
        int line = TextOps.lineOf(code.getText().toString(), code.getSelectionStart());
        Integer target = direction > 0 ? marks.higher(line) : marks.lower(line);
        if (target == null) {
            target = direction > 0 ? marks.first() : marks.last();
        }
        goToLine(target);
    }

    // ------------------------------------------------------------------
    // Find / replace
    // ------------------------------------------------------------------

    private void openFind(boolean replace) {
        findBar.setVisibility(View.VISIBLE);
        replaceRow.setVisibility(replace ? View.VISIBLE : View.GONE);
        int start = code.getSelectionStart();
        int end = code.getSelectionEnd();
        if (start >= 0 && end > start) {
            String selected = code.getText().toString().substring(start, end);
            if (!selected.contains("\n")) {
                findInput.setText(selected);
            }
        }
        findInput.requestFocus();
        findInput.selectAll();
        updateMatches(false);
    }

    private void closeFind() {
        settings.rememberSearch(findInput.getText().toString());
        settings.save();
        findBar.setVisibility(View.GONE);
        matches = List.of();
        code.setFindMatches(matches);
        code.requestFocus();
    }

    private boolean onFindKey(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEY_ENTER, KeyEvent.KEY_KP_ENTER, KeyEvent.KEY_F3 -> {
                settings.rememberSearch(findInput.getText().toString());
                findStep(event.isShiftPressed() ? -1 : 1);
                return true;
            }
            case KeyEvent.KEY_ESCAPE -> {
                closeFind();
                return true;
            }
            case KeyEvent.KEY_UP, KeyEvent.KEY_DOWN -> {
                List<String> recent = settings.findHistory;
                if (recent.isEmpty()) {
                    return true;
                }
                findHistoryIndex = Math.clamp(findHistoryIndex + (keyCode == KeyEvent.KEY_UP ? 1 : -1), 0, recent.size() - 1);
                String value = recent.get(findHistoryIndex);
                int keep = findHistoryIndex;
                findInput.setText(value);
                findHistoryIndex = keep;
                findInput.setSelection(value.length());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void showFindHistory(View anchor) {
        if (settings.findHistory.isEmpty()) {
            toast("No recent searches");
            return;
        }
        var menu = new PopupMenu(anchor.getContext(), anchor);
        for (String query : settings.findHistory) {
            menu.getMenu().add(query).setOnMenuItemClickListener(item -> {
                findInput.setText(query);
                findInput.setSelection(query.length());
                return true;
            });
        }
        menu.show();
    }

    private void updateMatches(boolean jump) {
        String query = findInput.getText().toString();
        String all = code.getText().toString();
        int from = inSelection ? Math.min(selectionFrom, all.length()) : 0;
        int to = inSelection ? Math.min(selectionTo, all.length()) : all.length();
        matches = TextOps.findAll(all, query, matchCase, from, to);
        code.setFindMatches(matches);
        int current = currentMatchIndex();
        findCount.setText(query.isEmpty() ? "" : matches.isEmpty() ? "No results"
                : (current >= 0 ? current + 1 : 0) + " of " + matches.size());
        if (jump && !matches.isEmpty() && current < 0) {
            int cursor = code.getSelectionStart();
            for (int[] match : matches) {
                if (match[0] >= cursor) {
                    bringIntoView(match[0]);
                    return;
                }
            }
            bringIntoView(matches.get(0)[0]);
        }
    }

    private int currentMatchIndex() {
        int start = code.getSelectionStart();
        int end = code.getSelectionEnd();
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i)[0] == start && matches.get(i)[1] == end) {
                return i;
            }
        }
        return -1;
    }

    private void findStep(int direction) {
        if (findBar.getVisibility() != View.VISIBLE) {
            openFind(false);
            return;
        }
        if (matches.isEmpty()) {
            updateMatches(false);
            if (matches.isEmpty()) {
                return;
            }
        }
        int cursor = direction > 0 ? Math.max(code.getSelectionStart(), code.getSelectionEnd()) : Math.min(code.getSelectionStart(), code.getSelectionEnd());
        int[] target = null;
        if (direction > 0) {
            for (int[] match : matches) {
                if (match[0] >= cursor) {
                    target = match;
                    break;
                }
            }
            if (target == null) {
                target = matches.get(0);
            }
        } else {
            for (int i = matches.size() - 1; i >= 0; i--) {
                if (matches.get(i)[1] <= cursor) {
                    target = matches.get(i);
                    break;
                }
            }
            if (target == null) {
                target = matches.get(matches.size() - 1);
            }
        }
        code.setSelection(target[0], target[1]);
        bringIntoView(target[0]);
        updateMatches(false);
    }

    private void replaceOne() {
        int index = currentMatchIndex();
        if (index < 0) {
            findStep(1);
            return;
        }
        int[] match = matches.get(index);
        String replacement = replaceInput.getText().toString();
        applyEdit(new TextOps.Edit(match[0], match[1], replacement, match[0] + replacement.length()));
        if (inSelection) {
            selectionTo += replacement.length() - (match[1] - match[0]);
        }
        updateMatches(false);
        findStep(1);
    }

    private void replaceAll() {
        if (matches.isEmpty()) {
            return;
        }
        String all = code.getText().toString();
        String replacement = replaceInput.getText().toString();
        StringBuilder out = new StringBuilder(all.length());
        int last = 0;
        for (int[] match : matches) {
            out.append(all, last, match[0]).append(replacement);
            last = match[1];
        }
        out.append(all.substring(last));
        int count = matches.size();
        if (inSelection) {
            selectionTo += count * (replacement.length() - (matches.get(0)[1] - matches.get(0)[0]));
        }
        int cursor = Math.min(code.getSelectionStart(), out.length());
        applyEdit(new TextOps.Edit(0, all.length(), out.toString(), cursor));
        highlight();
        updateMatches(false);
        toast("Replaced " + count + " match" + (count == 1 ? "" : "es"));
    }

    // ------------------------------------------------------------------
    // Popups: menus, quick open, shortcuts
    // ------------------------------------------------------------------

    private void showEditMenu(View anchor) {
        var menu = new PopupMenu(anchor.getContext(), anchor);
        Menu m = menu.getMenu();
        m.add("Find...   Ctrl+F").setOnMenuItemClickListener(i -> run(() -> openFind(false)));
        m.add("Replace...   Ctrl+H").setOnMenuItemClickListener(i -> run(() -> openFind(true)));
        m.add("Quick open...   Ctrl+P").setOnMenuItemClickListener(i -> run(this::quickOpen));
        m.add("Sort lines").setOnMenuItemClickListener(i -> run(() -> lineCommand(TextOps.sortLines(
                code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd()))));
        m.add("Remove duplicate lines").setOnMenuItemClickListener(i -> run(() -> lineCommand(TextOps.removeDuplicateLines(
                code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd()))));
        m.add("Join lines   Ctrl+J").setOnMenuItemClickListener(i -> run(this::joinLines));
        m.add("Split line   Ctrl+Shift+Enter").setOnMenuItemClickListener(i -> run(() ->
                applyEdit(TextOps.splitLine(code.getText().toString(), code.getSelectionStart()))));
        m.add("Toggle bookmark   Ctrl+F2").setOnMenuItemClickListener(i -> run(this::toggleBookmark));
        m.add("Next bookmark   F2").setOnMenuItemClickListener(i -> run(() -> jumpBookmark(1)));
        m.add("Previous bookmark   Shift+F2").setOnMenuItemClickListener(i -> run(() -> jumpBookmark(-1)));
        menu.show();
    }

    private void showViewMenu(View anchor) {
        var menu = new PopupMenu(anchor.getContext(), anchor);
        Menu m = menu.getMenu();
        m.add("Word wrap (this file)").setCheckable(true).setChecked(isWrapped())
                .setOnMenuItemClickListener(i -> run(this::toggleWrap));
        m.add("Zen mode   F11").setCheckable(true).setChecked(zen).setOnMenuItemClickListener(i -> run(this::toggleZen));
        m.add("Zoom in   Ctrl+=").setOnMenuItemClickListener(i -> run(() -> zoom(1)));
        m.add("Zoom out   Ctrl+-").setOnMenuItemClickListener(i -> run(() -> zoom(-1)));
        m.add("Reset zoom   Ctrl+0").setOnMenuItemClickListener(i -> run(() -> zoom(0)));
        var themes = m.addSubMenu("Theme");
        for (EditorTheme theme : EditorTheme.values()) {
            themes.add(theme.label).setCheckable(true).setChecked(theme == settings.theme())
                    .setOnMenuItemClickListener(i -> run(() -> {
                        settings.theme = theme.name();
                        settings.save();
                        applyTheme();
                    }));
        }
        m.add("Ruler column (" + settings.ruler + ")...").setOnMenuItemClickListener(i -> run(() ->
                prompt(anchor, "Ruler column (0 = off)", String.valueOf(settings.ruler), "Apply", value -> {
                    try {
                        settings.ruler = Math.clamp(Integer.parseInt(value.trim()), 0, 400);
                        settings.save();
                        code.setRulerColumn(settings.ruler);
                    } catch (NumberFormatException e) {
                        toast("Not a number: " + value);
                    }
                })));
        m.add("Trim whitespace on save").setCheckable(true).setChecked(settings.trimOnSave)
                .setOnMenuItemClickListener(i -> run(() -> {
                    settings.trimOnSave = !settings.trimOnSave;
                    settings.save();
                }));
        m.add("Keyboard shortcuts   F1").setOnMenuItemClickListener(i -> run(this::showShortcuts));
        menu.show();
    }

    private static boolean run(Runnable action) {
        action.run();
        return true;
    }

    private void quickOpen() {
        if (state == null || state.projectId() == null) {
            toast("Open a project first");
            return;
        }
        Context context = code.getContext();
        var content = card(context);
        var bg = new ShapeDrawable();
        bg.setCornerRadius(content.dp(12));
        bg.setColor(color(context, R.attr.colorSurfaceContainerHigh));
        content.setBackground(bg);
        var input = new EditText(context, null, R.attr.editTextOutlinedStyle);
        input.setSingleLine();
        input.setHint("Type part of a file name");
        content.addView(input, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        var results = new LinearLayout(context);
        results.setOrientation(LinearLayout.VERTICAL);
        var scroll = new ScrollView(context);
        scroll.addView(results, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        content.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, content.dp(260)));
        var popup = new PopupWindow(content, content.dp(460), WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(content.dp(8));
        List<String> shown = new ArrayList<>();
        Runnable refresh = () -> {
            results.removeAllViews();
            shown.clear();
            shown.addAll(quickOpenMatches(input.getText().toString()));
            for (String path : shown) {
                var row = label(context, path, R.attr.textAppearanceBodyMedium);
                row.setPadding(row.dp(8), row.dp(5), row.dp(8), row.dp(5));
                row.setClickable(true);
                hoverBackground(row);
                row.setOnClickListener(v -> {
                    popup.dismiss();
                    openEntry(path, false);
                });
                results.addView(row);
            }
        };
        input.addTextChangedListener(simpleWatcher(refresh));
        input.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEY_ENTER || keyCode == KeyEvent.KEY_KP_ENTER)) {
                if (!shown.isEmpty()) {
                    popup.dismiss();
                    openEntry(shown.get(0), false);
                }
                return true;
            }
            return false;
        });
        refresh.run();
        popup.showAtLocation(code, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, code.dp(80));
        input.requestFocus();
    }

    /** Recent files of this project first, then every file whose path contains the typed letters in order. */
    private List<String> quickOpenMatches(String query) {
        String q = query.toLowerCase(Locale.ROOT).replace(" ", "");
        List<String> out = new ArrayList<>();
        String prefix = EditorSettings.key(state.projectId(), "");
        for (String key : settings.recent) {
            if (key.startsWith(prefix)) {
                String path = key.substring(prefix.length());
                if (state.files().contains(path) && fuzzy(path, q)) {
                    out.add(path);
                }
            }
        }
        List<String> rest = new ArrayList<>(state.files());
        rest.sort(String.CASE_INSENSITIVE_ORDER);
        for (String path : rest) {
            if (!out.contains(path) && fuzzy(path, q)) {
                out.add(path);
            }
            if (out.size() >= 50) {
                break;
            }
        }
        return out;
    }

    private static boolean fuzzy(String path, String query) {
        String hay = path.toLowerCase(Locale.ROOT);
        int index = 0;
        for (char c : query.toCharArray()) {
            index = hay.indexOf(c, index);
            if (index < 0) {
                return false;
            }
            index++;
        }
        return true;
    }

    private void showShortcuts() {
        Context context = code.getContext();
        var content = card(context);
        var bg = new ShapeDrawable();
        bg.setCornerRadius(content.dp(12));
        bg.setColor(color(context, R.attr.colorSurfaceContainerHigh));
        content.setBackground(bg);
        content.addView(label(context, "Keyboard shortcuts", R.attr.textAppearanceTitleMedium));
        String[][] rows = {
                { "Ctrl+S", "Save (trims trailing spaces if enabled)" },
                { "Ctrl+R / F5", "Run / reload project and modules" },
                { "Ctrl+Enter", "Validate" },
                { "Ctrl+F / Ctrl+H", "Find / replace" },
                { "F3 / Shift+F3", "Next / previous match" },
                { "Ctrl+P", "Quick open" },
                { "Ctrl+W", "Close tab" },
                { "Ctrl+J", "Join lines" },
                { "Ctrl+Shift+Enter", "Split line at the cursor" },
                { "Ctrl+F2", "Toggle bookmark" },
                { "F2 / Shift+F2", "Next / previous bookmark" },
                { "Home", "First non-blank character, then column 0" },
                { "Tab / Shift+Tab", "Indent / unindent (lines when several are selected)" },
                { "Ctrl+= / Ctrl+- / Ctrl+0", "Zoom in / out / reset (also Ctrl + mouse wheel)" },
                { "F11", "Zen mode" },
                { "Ctrl+Z / Ctrl+Y", "Undo / redo" },
                { "F1 or Ctrl+/", "This list" },
        };
        for (String[] row : rows) {
            var line = row(context);
            var key = label(context, row[0], R.attr.textAppearanceLabelLarge);
            key.setTextColor(color(context, R.attr.colorPrimary));
            line.addView(key, new LinearLayout.LayoutParams(content.dp(170), WRAP_CONTENT));
            line.addView(label(context, row[1], R.attr.textAppearanceBodyMedium), new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            line.setPadding(0, content.dp(3), 0, content.dp(3));
            content.addView(line);
        }
        var popup = new PopupWindow(content, content.dp(520), WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(content.dp(8));
        popup.showAtLocation(code, Gravity.CENTER, 0, 0);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Applies "trim whitespace on save" to the editor, then pushes the text. */
    private void prepareSave() {
        if (settings.trimOnSave && shownPath != null && state != null && !state.activeVirtual()
                && "py".equals(Syntax.language(shownPath))) {
            String all = code.getText().toString();
            String trimmed = TextOps.trimForSave(all);
            if (!trimmed.equals(all)) {
                int cursor = Math.min(code.getSelectionStart(), trimmed.length());
                applyEdit(new TextOps.Edit(0, all.length(), trimmed, cursor));
                highlight();
            }
        }
        flush();
    }

    private void save() {
        prepareSave();
        store(ClientProjectStore::saveActiveFile);
    }

    private void validate() {
        flush();
        store(ClientProjectStore::validateActiveFile);
        showPanel(PANEL_PROBLEMS);
    }

    private void run() {
        prepareSave();
        store(s -> {
            String id = s.activeProjectId();
            if (id == null) {
                s.append("No project open - pick one on the Home page.");
                return;
            }
            s.saveActiveFile();
            s.request(ProjectAction.LOAD, id, s.debugMode() ? "{\"debug\":true}" : "");
            s.append("Running '" + id + "' ...");
        });
        showPanel(PANEL_CONSOLE);
    }

    /** Run + reload all modules, so new content and handlers are live immediately. */
    private void reloadAll() {
        run();
        store(s -> {
            s.reloadModule("*");
            s.append("Reloading modules ...");
        });
    }

    private void build(View anchor) {
        flush();
        String version = state == null || state.projectVersion().isBlank() ? "1.0.0" : state.projectVersion();
        prompt(anchor, "Build module - version", version, "Build", v -> store(s -> s.buildProject(v)));
        showPanel(PANEL_CONSOLE);
    }

    private void addNewItems(Menu menu, String folder) {
        menu.add("New file...").setOnMenuItemClickListener(item -> {
            prompt(explorer, "New file", folder + "new_file.py", "Create", path -> store(s -> s.createEntry(path, "file")));
            return true;
        });
        menu.add("New folder...").setOnMenuItemClickListener(item -> {
            prompt(explorer, "New folder", folder + "new_folder", "Create", path -> store(s -> s.createEntry(path, "folder")));
            return true;
        });
    }

    private void showNewMenu(View anchor, String folder) {
        var menu = new PopupMenu(anchor.getContext(), anchor);
        addNewItems(menu.getMenu(), folder);
        menu.show();
    }

    /** Opens the asset bank; the picked {@code tex("...")} reference goes to the clipboard for pasting. */
    private void openAssetPicker() {
        flush();
        store(s -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new AssetPickerScreen(CraftPyFragment.createScreen(PAGE_EDITOR), AssetKind.TEXTURE, id -> {
                String reference = "\"" + id + "\"";
                mc.keyboardHandler.setClipboard(reference);
                s.append("Copied " + reference + " - paste it as texture=... with Ctrl+V.");
            }, s.activeProjectId()));
        });
    }

    /** Single click: preview tab (replaced by the next preview). Double click: a normal tab. */
    private void openFromExplorer(String path) {
        long now = System.currentTimeMillis();
        boolean doubleClick = path.equals(lastClickPath) && now - lastClickTime < DOUBLE_CLICK_MS;
        lastClickPath = path;
        lastClickTime = now;
        openEntry(path, !doubleClick);
    }

    /** Text files open as tabs; textures and sounds open the asset viewer. */
    private void openEntry(String path, boolean preview) {
        String lower = path.toLowerCase(Locale.ROOT);
        boolean asset = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".mp3");
        flush();
        if (asset) {
            store(s -> {
                String projectId = s.activeProjectId();
                if (projectId == null) {
                    return;
                }
                // The viewer is a separate screen; it returns to a fresh Editor page when closed.
                s.requestAsset(projectId, path, data -> Minecraft.getInstance().setScreen(new AssetFileScreen(
                        CraftPyFragment.createScreen(PAGE_EDITOR), projectId, path, data.data(), data.error())));
            });
            return;
        }
        boolean alreadyOpen = state != null && state.tabs().contains(path);
        if (alreadyOpen) {
            if (!preview && path.equals(previewPath)) {
                previewPath = null;
                renderTabs();
            }
            store(s -> s.openFile(path));
            return;
        }
        String old = previewPath;
        boolean replaceOld = preview && old != null && state != null && !state.dirtyTabs().contains(old)
                && !isPinned(old) && !(old.equals(shownPath) && !code.getText().toString().equals(synced));
        previewPath = preview ? path : null;
        store(s -> {
            if (replaceOld) {
                s.closeFile(old);
            }
            s.openFile(path);
        });
    }

    /** Opens a file and moves the cursor to a 0-based line. */
    private void openAt(@Nullable String path, int line) {
        if (path == null || path.equals(shownPath)) {
            goToLine(line);
            return;
        }
        pendingGotoPath = path;
        pendingGotoLine = line;
        openEntry(path, false);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String projectId() {
        return state == null ? null : state.projectId();
    }

    private String fileKey(String path) {
        return EditorSettings.key(projectId(), path);
    }

    private void toast(String text) {
        Toast.makeText(code.getContext(), text, Toast.LENGTH_SHORT).show();
    }

    private static TextWatcher simpleWatcher(Runnable onChange) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onChange.run();
            }
        };
    }
}
