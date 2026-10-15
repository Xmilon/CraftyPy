package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import icyllis.modernui.widget.Toast;
import net.minecraft.client.Minecraft;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.CodeProblems;
import net.xmilon.craftpy.client.EditorSettings;
import net.xmilon.craftpy.client.EditorTheme;
import net.xmilon.craftpy.client.FileTree;
import net.xmilon.craftpy.client.Syntax;
import net.xmilon.craftpy.client.TextOps;
import net.xmilon.craftpy.client.assets.AssetDrop;
import net.xmilon.craftpy.client.assets.AssetFetch;
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
    private static final int PANEL_EVENTS = 3;

    /** Immutable copy of the store state, taken on the client thread. */
    private record State(@Nullable String projectId, String projectName, String projectVersion,
                         List<String> files, List<String> tabs,
                         List<String> dirtyTabs, @Nullable String active, @Nullable String activeContent,
                         boolean activeVirtual, List<String> log, List<ValidationProblem> problems,
                         List<CodeProblems.Problem> localProblems, java.util.Map<String, String> dirtyContents) {
    }

    private final EditorSettings settings = EditorSettings.get();

    private View toolbar;
    private TextView title;
    private View explorerColumn;
    private View explorerSplitter;
    private AssetDock dock;
    private View dockSplitter;
    private boolean dockBeforeZen;
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
    private final TextView[] panelTabs = new TextView[4];
    private final View[] panelPanes = new View[4];
    private TextView breadcrumb;
    private TextView sticky;
    private View statusBar;
    private TextView statusPosition;
    private TextView statusEnding;
    private TextView statusEncoding;
    private int textVersion;
    private final EditorTools tools = new EditorTools(new EditorTools.Host() {
        @Override
        public View anchor() {
            return code;
        }

        @Override
        public void openLibrary(String path, String text, int line) {
            if (path.equals(shownPath)) {
                goToLine(line);
                return;
            }
            pendingGotoPath = path;
            pendingGotoLine = line;
            store(s -> s.openVirtualFile(path, text));
        }

        @Override
        public String projectId() {
            return EditorFragment.this.projectId();
        }

        @Override
        public String activePath() {
            return shownPath;
        }

        @Override
        public String text() {
            return code.getText().toString();
        }

        @Override
        public int caret() {
            return Math.max(0, code.getSelectionStart());
        }

        @Override
        public int selectionStart() {
            return Math.max(0, code.getSelectionStart());
        }

        @Override
        public int selectionEnd() {
            return Math.max(0, code.getSelectionEnd());
        }

        @Override
        public int textVersion() {
            return textVersion;
        }

        @Override
        public void replace(int start, int end, String text, int cursorStart, int cursorEnd) {
            applyEdit(new TextOps.Edit(start, end, text, cursorStart, cursorEnd));
        }

        @Override
        public void openAt(String path, int line) {
            EditorFragment.this.openAt(path, line);
        }

        @Override
        public void goToLine(int line) {
            EditorFragment.this.goToLine(line);
        }

        @Override
        public void toast(String text) {
            EditorFragment.this.toast(text);
        }

        @Override
        public java.util.Map<String, String> dirtyTexts() {
            return EditorFragment.this.dirtyTexts();
        }
    });

    private State state;
    private List<String> shownFiles = List.of();
    @Nullable
    private String shownPath;
    /** Text last loaded from or pushed to the store; differs from the editor while edits are unsynced. */
    private String synced = "";
    private boolean loading;
    private boolean internalEdit;
    /** The player agreed to lose the unsaved dock edits, so Esc may close the screen. */
    private boolean leaveConfirmed;
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

    /** Client thread: the text of every unsaved open file. */
    private static java.util.Map<String, String> dirtyContents(ClientProjectStore store, List<String> dirtyTabs) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String path : dirtyTabs) {
            String content = store.fileContent(path);
            if (content != null && !store.isVirtual(path)) {
                out.put(path, content);
            }
        }
        return out;
    }

    /** UI thread: unsaved files, with the live editor text for the active one. */
    private java.util.Map<String, String> dirtyTexts() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (state != null) {
            out.putAll(state.dirtyContents());
        }
        if (shownPath != null && state != null && !state.activeVirtual() && code != null) {
            String live = code.getText().toString();
            if (!live.equals(synced) || out.containsKey(shownPath)) {
                out.put(shownPath, live);
            }
        }
        return out;
    }

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
                List.copyOf(store.log()), List.copyOf(store.validationProblems()), CodeProblems.snapshot(),
                dirtyContents(store, dirtyTabs));
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
        breadcrumb = label(context, "", R.attr.textAppearanceLabelMedium);
        breadcrumb.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        breadcrumb.setSingleLine();
        breadcrumb.setPadding(center.dp(12), center.dp(3), center.dp(12), center.dp(3));
        breadcrumb.setClickable(true);
        breadcrumb.setTooltipText("Where the cursor is. Click for the outline of this file");
        hoverBackground(breadcrumb);
        breadcrumb.setOnClickListener(hidingTooltip(breadcrumb.getTooltipText(), tools::showOutline));
        center.addView(breadcrumb, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        findBar = createFindBar(context);
        center.addView(findBar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        codeArea = createCodeArea(context);
        center.addView(codeArea, new LinearLayout.LayoutParams(MATCH_PARENT, 0, settings.codeWeight));
        bottomSplitter = new Splitter(context, false, this::dragBottom, this::saveLayout);
        center.addView(bottomSplitter, new LinearLayout.LayoutParams(MATCH_PARENT, center.dp(6)));
        bottomPanel = createBottomPanel(context);
        center.addView(bottomPanel, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1 - settings.codeWeight));
        statusBar = createStatusBar(context);
        center.addView(statusBar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        body.addView(center, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        dock = new AssetDock(context, this::projectId, () -> shownFiles, this::toast, () -> store(ClientProjectStore::refresh),
                visible -> {
                    if (dockSplitter != null) {
                        dockSplitter.setVisibility(visible && !zen ? View.VISIBLE : View.GONE);
                    }
                }, this::dropAsset);
        dockSplitter = new Splitter(context, true, this::dragDock, this::saveLayout);
        dockSplitter.setVisibility(View.GONE);
        body.addView(dockSplitter, new LinearLayout.LayoutParams(body.dp(6), MATCH_PARENT));
        body.addView(dock.view(), new LinearLayout.LayoutParams(body.dp(settings.dockWidth), MATCH_PARENT));

        var divider = new View(context);
        divider.setBackground(ThemeControl.makeDivider(page));
        page.addView(divider, new LinearLayout.LayoutParams(MATCH_PARENT, page.dp(1)));
        page.addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        applyTheme();
        applyFontSize();
        findBar.setVisibility(View.GONE);
        render(new State(null, "", "", List.of(), List.of(), List.of(), null, null, false,
                List.of(), List.of(), List.of(), java.util.Map.of()));
        store(s -> {
            s.setListener(storeListener);
            s.refresh();
            storeListener.run();
        });
        // The overlay host is only above the page once the view is attached.
        page.post(() -> removeBack = Overlays.onBack(page, this::onBack));
        return page;
    }

    private Runnable removeBack = () -> { };

    /**
     * Esc, after dialogs: closes the completion list or hint, the find bar, the tree selection, a text selection,
     * zen mode - one thing per press, the way an IDE does. False lets the screen go back.
     */
    private boolean onBack() {
        if (code == null) {
            return false;
        }
        if (assist != null && assist.dismissTransient()) {
            return true;
        }
        if (dock != null && dock.back()) {
            return true;
        }
        if (findBar.getVisibility() == View.VISIBLE) {
            closeFind();
            return true;
        }
        if (selectedPath != null) {
            selectedPath = null;
            highlightExplorer();
            code.requestFocus();
            return true;
        }
        if (code.hasFocus() && code.getSelectionStart() != code.getSelectionEnd()) {
            code.setSelection(code.getSelectionEnd());
            return true;
        }
        if (zen) {
            toggleZen();
            return true;
        }
        if (dock != null && dock.dirty() && !leaveConfirmed) {
            confirmLeave();
            return true;
        }
        return false;
    }

    /** The texture or sound editor holds edits that were never saved: leaving the screen would drop them. */
    private void confirmLeave() {
        var dialog = Overlays.dialog(code, "Unsaved changes", 380, -1);
        dialog.body.addView(label(code.getContext(), "The texture or sound in the dock has changes that are not saved. Leave and lose them?",
                R.attr.textAppearanceBodyMedium));
        var buttons = row(code.getContext());
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(button(code.getContext(), "Keep editing", R.attr.borderlessButtonStyle, v -> dialog.dismiss()));
        buttons.addView(button(code.getContext(), "Leave and discard", R.attr.buttonTonalStyle, v -> {
            dialog.dismiss();
            leaveConfirmed = true;
            var screen = Minecraft.getInstance().screen;
            if (screen != null) {
                screen.onClose();
            }
        }));
        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.topMargin = code.dp(10);
        dialog.body.addView(buttons, lp);
        dialog.show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        removeBack.run();
        if (dock != null) {
            dock.dispose();
        }
        flush();
        if (zen) {
            CraftPyFragment.setNavVisible(this, true);
        }
        store(s -> s.clearListener(storeListener));
        if (assist != null) {
            assist.hideAll();
            assist = null;
        }
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
        bar.addView(toolButton(context, "Tools", "Symbols, rename everywhere, previews, profiler, event tracer",
                R.attr.borderlessButtonStyle, this::showToolsMenu));
        bar.addView(toolButton(context, "Help", "How the IDE works, the Python API and the Java bridge (F1)",
                R.attr.borderlessButtonStyle, v -> HelpPanel.open(code)));
        bar.addView(toolButton(context, "Save", "Write the file to the project (Ctrl+S)", R.attr.borderlessButtonStyle,
                v -> save()));
        bar.addView(toolButton(context, "Validate", "Check the file on the Python worker (Ctrl+Enter)",
                R.attr.borderlessButtonStyle, v -> validate()));
        bar.addView(toolButton(context, "Assets", "Every texture, model and sound of the game: click to open, drag into the code or a folder",
                R.attr.borderlessButtonStyle, v -> {
                    dock.show(AssetDock.ASSETS);
                    dock.focusSearch();
                }));
        bar.addView(toolButton(context, "Build", "Pack the project into a .cpm module", R.attr.borderlessButtonStyle,
                this::build));
        bar.addView(toolButton(context, "Stop", "Interrupt the running Python code", R.attr.buttonOutlinedStyle,
                v -> store(ClientProjectStore::interrupt)));
        bar.addView(toolButton(context, "Reload", "Save, re-run this project and reload every loaded module (F5)",
                R.attr.buttonOutlinedStyle, v -> reloadAll()));
        bar.addView(toolButton(context, "Run", "Save and run the project (Ctrl+R)", R.attr.buttonStyle, v -> run()));
        return bar;
    }

    /** A tooltip stays on screen while a popup opened by the same click is up; hide it on click. */
    static View.OnClickListener hidingTooltip(CharSequence tooltip, View.OnClickListener onClick) {
        return v -> {
            v.setTooltipText(null);
            onClick.onClick(v);
            v.postDelayed(() -> v.setTooltipText(tooltip), 400);
        };
    }

    private static View toolButton(Context context, String text, String tooltip, icyllis.modernui.resources.ResourceId style,
                                   View.OnClickListener onClick) {
        var button = button(context, text, style, hidingTooltip(tooltip, onClick));
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
        header.addView(smallButton(context, "+", "New file or folder (in the selected folder)",
                v -> showNewMenu(v, newEntryFolder())));
        header.addView(smallButton(context, "⊟", "Collapse all folders", v -> collapseAll()));
        header.addView(smallButton(context, "⊞", "Expand all folders", v -> expandAll()));
        column.addView(header);

        explorer = new LinearLayout(context);
        explorer.setOrientation(LinearLayout.VERTICAL);
        explorer.setPadding(column.dp(6), 0, column.dp(6), column.dp(6));
        explorer.setFocusableInTouchMode(true);
        explorer.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                && onExplorerKey(keyCode, event));
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
        all.setOnClickListener(hidingTooltip("All open tabs", this::showTabMenu));
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
        replace.addView(smallButton(context, "Preview", "Review every match and accept or skip it",
                v -> tools.replacePreview(matches, replaceInput.getText().toString())));
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
        view.setOnClickListener(hidingTooltip(tooltip, onClick));
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
        code.setHint("Open a file from the explorer (Ctrl+P: quick open)");
        code.setOnZoom(this::zoom);
        code.setOnCtrlClick(tools::goToDefinition);
        code.setOnCaret(offset -> updateBreadcrumb());
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
                textVersion++;
                if (loading) {
                    return;
                }
                shiftBookmarks(s);
                if (!internalEdit) {
                    afterTyped(s);
                    assist.onEdited(s, changeStart, changeAdded);
                }
                code.removeCallbacks(onEdited);
                code.postDelayed(onEdited, EDIT_DELAY_MS);
            }
        });
        assist = new CodeAssist(code, (op, args) -> {
            if (projectId() == null) {
                return;
            }
            store(s -> s.tool(op, args, reply -> code.post(() -> {
                if (assist == null) {
                    return;
                }
                if (op.equals("complete")) {
                    assist.onComplete(reply);
                } else {
                    assist.onHoverReply(reply);
                }
            })));
        }, this::problemMessageAt);
        code.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                && (assist.onKey(keyCode, event) || onCodeKey(keyCode, event)));
        code.setOnFocusChangeListener((v, focused) -> {
            if (!focused) {
                assist.hideList();
            }
        });
        code.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) {
                gutter.removeCallbacks(rebuildGutter);
                gutter.post(rebuildGutter);
            }
        });
        row.addView(code, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));

        codeScroll.addView(row, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        codeScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            updateSticky();
            minimap.invalidate();
        });

        sticky = new TextView(context);
        sticky.setTypeface(EditorFont.get());
        sticky.setSingleLine(false);
        sticky.setVisibility(View.GONE);
        sticky.setPadding(dp8, dp8 / 2, dp8, dp8 / 2);
        var frame = new icyllis.modernui.widget.FrameLayout(context);
        frame.addView(codeScroll, new icyllis.modernui.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        frame.addView(sticky, new icyllis.modernui.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP));
        minimap = new MiniMap(context, codeScroll);
        var outer = row(context);
        outer.addView(frame, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        outer.addView(minimap, new LinearLayout.LayoutParams(outer.dp(70), MATCH_PARENT));
        return outer;
    }

    /** 11 / 12: cursor position, line ending (click to convert), encoding facts (click for options). */
    private View createStatusBar(Context context) {
        var bar = row(context);
        bar.setPadding(bar.dp(12), bar.dp(1), bar.dp(12), bar.dp(1));
        bar.setBackground(new ColorDrawable(color(context, R.attr.colorSurfaceContainerLow)));
        statusPosition = label(context, "", R.attr.textAppearanceLabelSmall);
        statusPosition.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        bar.addView(statusPosition, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        statusEnding = smallButton(context, "LF", "Line endings of this file - click to convert", this::showEndingMenu);
        bar.addView(statusEnding);
        statusEncoding = smallButton(context, "UTF-8", "Files are read and saved as UTF-8", this::showEncodingMenu);
        bar.addView(statusEncoding);
        return bar;
    }

    private void showEndingMenu(View anchor) {
        if (shownPath == null) {
            return;
        }
        var menu = Overlays.menu(anchor);
        menu.getMenu().add("Convert to LF (\\n)").setOnMenuItemClickListener(i -> run(() -> convertEndings(false)));
        menu.getMenu().add("Convert to CRLF (\\r\\n)").setOnMenuItemClickListener(i -> run(() -> convertEndings(true)));
        menu.show();
    }

    private void convertEndings(boolean crlf) {
        String all = code.getText().toString();
        String converted = net.xmilon.craftpy.client.TextFormat.convert(all, crlf);
        if (converted.equals(all)) {
            toast("Already " + (crlf ? "CRLF" : "LF"));
            return;
        }
        applyEdit(new TextOps.Edit(0, all.length(), converted, Math.min(code.getSelectionStart(), converted.length())));
        toast("Converted to " + (crlf ? "CRLF" : "LF") + " - save to keep it");
    }

    private void showEncodingMenu(View anchor) {
        if (shownPath == null) {
            return;
        }
        String all = code.getText().toString();
        var menu = Overlays.menu(anchor);
        menu.getMenu().add("CraftPy reads and saves UTF-8 only").setEnabled(false);
        if (net.xmilon.craftpy.client.TextFormat.hasBom(all)) {
            menu.getMenu().add("Remove the byte order mark (BOM)").setOnMenuItemClickListener(i -> run(() ->
                    applyEdit(new TextOps.Edit(0, 1, "", 0))));
        }
        int bad = net.xmilon.craftpy.client.TextFormat.invalidChars(all);
        if (bad > 0) {
            menu.getMenu().add(bad + " invalid byte(s) shown as \uFFFD - fix or delete them").setEnabled(false);
        }
        menu.show();
    }

    private void updateStatus() {
        if (statusPosition == null || code == null) {
            return;
        }
        String all = code.getText().toString();
        int caret = Math.max(0, Math.min(code.getSelectionStart(), all.length()));
        int line = TextOps.lineOf(all, caret);
        int column = caret - TextOps.lineStart(all, caret);
        int selected = Math.abs(code.getSelectionEnd() - code.getSelectionStart());
        statusPosition.setText(shownPath == null ? "" : "Ln " + (line + 1) + ", Col " + (column + 1)
                + (selected > 0 ? "   (" + selected + " selected)" : "") + "   " + Syntax.language(shownPath));
        statusEnding.setText(shownPath == null ? "" : net.xmilon.craftpy.client.TextFormat.detect(all).label);
        statusEncoding.setText(shownPath == null ? "" : net.xmilon.craftpy.client.TextFormat.encodingLabel(all));
    }

    /** 1: show the headers of the scopes scrolled out of view. */
    private void updateSticky() {
        if (sticky == null || code == null) {
            return;
        }
        var layout = code.getLayout();
        if (!settings.stickyScroll || layout == null || shownPath == null || !"py".equals(Syntax.language(shownPath))
                || codeScroll.getScrollY() <= 0) {
            sticky.setVisibility(View.GONE);
            return;
        }
        int visual = layout.getLineForVertical(Math.max(0, codeScroll.getScrollY() - code.getExtendedPaddingTop()));
        int firstLine = TextOps.lineOf(code.getText().toString(), layout.getLineStart(visual));
        List<String> headers = tools.stickyHeaders(firstLine);
        if (headers.isEmpty()) {
            sticky.setVisibility(View.GONE);
            return;
        }
        EditorTheme theme = settings.theme();
        sticky.setBackground(new ColorDrawable(theme.gutterBg));
        sticky.setTextColor(theme.text);
        sticky.setTextSize(settings.fontSize);
        sticky.setText(String.join("\n", headers));
        sticky.setVisibility(View.VISIBLE);
    }

    private void updateFocus() {
        if (code == null) {
            return;
        }
        int[] range = settings.focusMode ? tools.focusRange() : null;
        if (range == null) {
            code.setFocusRange(-1, -1);
        } else {
            code.setFocusRange(range[0], range[1]);
        }
    }

    private View createBottomPanel(Context context) {
        var panelView = new LinearLayout(context);
        panelView.setOrientation(LinearLayout.VERTICAL);

        var header = row(context);
        header.setPadding(header.dp(8), header.dp(2), header.dp(8), header.dp(2));
        String[] names = { "Console", "Problems", "Bookmarks", "Events" };
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            panelTabs[i] = panelTab(context, names[i], v -> showPanel(index));
            header.addView(panelTabs[i]);
        }
        header.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
        header.addView(button(context, "Copy", R.attr.borderlessButtonStyle, v -> copyPanel()));
        header.addView(button(context, "Clear", R.attr.borderlessButtonStyle,
                v -> store(ClientProjectStore::clearLog)));
        panelView.addView(header);

        var consoleColumn = new LinearLayout(context);
        consoleColumn.setOrientation(LinearLayout.VERTICAL);
        console = new TextView(context);
        console.setTypeface(EditorFont.get());
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
        input.setTypeface(EditorFont.get());
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

        panelPanes[PANEL_EVENTS] = tools.createEventsPane(context);
        panelView.addView(panelPanes[PANEL_EVENTS], new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

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
        if (index == PANEL_EVENTS) {
            tools.eventsPanelShown();
        } else {
            tools.eventsPanelHidden();
        }
    }

    /** 53: copies what the visible panel shows (problems as file:line:col lines) to the clipboard. */
    private void copyPanel() {
        String text;
        if (panel == PANEL_PROBLEMS && state != null) {
            StringBuilder out = new StringBuilder();
            for (ValidationProblem p : state.problems()) {
                String file = p.file().isEmpty() ? String.valueOf(shownPath) : p.file();
                out.append(file).append(':').append(p.line() + 1).append(':').append(p.column()).append(": ")
                        .append(p.isError() ? "error" : "warning").append(": ").append(p.message()).append('\n');
            }
            for (CodeProblems.Problem p : state.localProblems()) {
                out.append(shownPath).append(':').append(p.line() + 1).append(':').append(p.column()).append(": ")
                        .append(p.severity().name().toLowerCase(Locale.ROOT)).append(": ").append(p.message()).append('\n');
            }
            text = out.length() == 0 ? "No problems." : out.toString();
        } else if (panel == PANEL_CONSOLE && state != null) {
            text = String.join("\n", state.log());
        } else if (panel == PANEL_BOOKMARKS) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < bookmarkList.getChildCount(); i++) {
                if (bookmarkList.getChildAt(i) instanceof TextView tv) {
                    out.append(tv.getText()).append('\n');
                }
            }
            text = out.toString();
        } else {
            toast("Nothing to copy here");
            return;
        }
        EditorTools.copyToClipboard(text);
        toast("Copied to the clipboard");
    }

    // ------------------------------------------------------------------
    // View settings: theme, zoom, wrap, panels, zen
    // ------------------------------------------------------------------

    private void applyTheme() {
        EditorTheme theme = settings.theme();
        code.setTheme(theme);
        code.setRulerColumn(settings.ruler);
        codeScroll.setBackground(new ColorDrawable(theme.bg));
        updateMinimap();
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

    /** The dock sits right of the code: dragging its splitter to the right makes it narrower. */
    private void dragDock(int dx) {
        var lp = dock.view().getLayoutParams();
        int max = Math.max(dock.view().dp(300), ((View) dock.view().getParent()).getWidth() * 3 / 5);
        lp.width = Math.clamp(lp.width - dx, dock.view().dp(280), max);
        dock.view().setLayoutParams(lp);
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
        settings.dockWidth = Math.round(dock.view().getLayoutParams().width / (float) dock.view().dp(1));
        settings.save();
    }

    private void toggleZen() {
        zen = !zen;
        int visibility = zen ? View.GONE : View.VISIBLE;
        toolbar.setVisibility(visibility);
        explorerColumn.setVisibility(visibility);
        explorerSplitter.setVisibility(visibility);
        if (zen) {
            dockBeforeZen = dock.visible();
            dock.setVisible(false); // also stops a playing sound
            dockSplitter.setVisibility(View.GONE);
        } else if (dockBeforeZen) {
            dock.setVisible(true);
        }
        tabBar.setVisibility(visibility);
        bottomSplitter.setVisibility(visibility);
        bottomPanel.setVisibility(visibility);
        statusBar.setVisibility(visibility);
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

        boolean activeChanged = !java.util.Objects.equals(next.active(), revealed);
        revealed = next.active();
        if (!next.files().equals(shownFiles)) {
            shownFiles = next.files();
            renderExplorer();
        } else {
            highlightExplorer();
        }
        if (activeChanged) {
            revealInTree(next.active());
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
            refreshPreview();
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
        updateBreadcrumb();
    }

    private void updateBreadcrumb() {
        if (code != null && breadcrumb != null) {
            breadcrumb.setText(tools.breadcrumb());
            updateStatus();
            updateSticky();
            updateFocus();
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
        assist.hideAll();
        applyWrap();
        highlight();
        updateMinimap();
        if (findBar.getVisibility() == View.VISIBLE) {
            inSelection = false;
            styleChip(selectionToggle, false);
            updateMatches(false);
        }
    }

    /** Folders that are open in the tree, per project; survives page switches. */
    private static final Map<String, Set<String>> EXPANDED = new HashMap<>();
    private List<FileTree.Row> treeRows = List.of();

    private Set<String> expandedSet() {
        return EXPANDED.computeIfAbsent(projectId() == null ? "" : projectId(), k -> new HashSet<>(List.of("code")));
    }

    private void renderExplorer() {
        explorer.removeAllViews();
        Context context = explorer.getContext();
        treeRows = List.of();
        if (shownFiles.isEmpty()) {
            var empty = label(context, state.projectId() == null ? "No project open." : "No files.",
                    R.attr.textAppearanceBodySmall);
            empty.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
            empty.setPadding(empty.dp(6), empty.dp(6), 0, 0);
            explorer.addView(empty);
            return;
        }
        treeRows = FileTree.visible(shownFiles, expandedSet());
        for (FileTree.Row row : treeRows) {
            explorer.addView(treeRow(context, row));
        }
        highlightExplorer();
    }

    private static String badge(FileTree.Row row) {
        if (row.folder()) {
            return "▭";
        }
        String lower = row.name().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py")) {
            return "Py";
        } else if (lower.endsWith(".json") || lower.endsWith(".mcmeta")) {
            return "{}";
        } else if (lower.endsWith(".png") || lower.endsWith(".jpg")) {
            return "▨";
        } else if (lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".mp3")) {
            return "♪";
        } else if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            return "≡";
        }
        return "·";
    }

    private View treeRow(Context context, FileTree.Row entry) {
        String path = entry.path();
        var row = row(context);
        row.setTag(path);
        row.setClickable(true);
        row.setPadding(row.dp(2 + entry.depth() * 12), row.dp(3), row.dp(6), row.dp(3));
        int muted = color(context, R.attr.colorOnSurfaceVariant);
        var chevron = label(context, entry.folder() ? (entry.expanded() ? "▾" : "▸") : "",
                R.attr.textAppearanceBodyMedium);
        chevron.setTextColor(muted);
        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(row.dp(14), WRAP_CONTENT));
        var badge = label(context, badge(entry), R.attr.textAppearanceLabelSmall);
        badge.setTextColor(entry.folder() ? muted : color(context, path.endsWith(".py") ? R.attr.colorPrimary
                : isAsset(path) ? R.attr.colorTertiary : R.attr.colorOnSurfaceVariant));
        badge.setGravity(Gravity.CENTER);
        row.addView(badge, new LinearLayout.LayoutParams(row.dp(22), WRAP_CONTENT));
        var name = label(context, entry.name(), R.attr.textAppearanceBodyMedium);
        name.setSingleLine();
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        row.setTooltipText(entry.folder() ? path + "/  (click: open or close, right click: more)"
                : isAsset(path) ? path + "  (click: select, double click: open)"
                : path + "  (click: preview, double click: keep open)");
        hoverBackground(row);
        row.setOnClickListener(v -> treeClick(entry));
        Overlays.contextMenu(row, menu -> treeMenu(menu, entry));
        return row;
    }

    private void treeClick(FileTree.Row entry) {
        explorer.requestFocus();
        if (entry.folder()) {
            selectedPath = entry.path();
            toggleFolder(entry.path());
        } else {
            openFromExplorer(entry.path());
        }
    }

    private void toggleFolder(String folder) {
        Set<String> open = expandedSet();
        if (!open.remove(folder)) {
            open.add(folder);
        }
        renderExplorer();
    }

    private void collapseAll() {
        expandedSet().clear();
        renderExplorer();
    }

    private void expandAll() {
        expandedSet().addAll(FileTree.folders(shownFiles));
        renderExplorer();
    }

    /** Opens the folders above the active file so it is visible in the tree (auto reveal). */
    private void revealInTree(@Nullable String path) {
        if (path != null && expandedSet().addAll(FileTree.ancestors(path))) {
            renderExplorer();
        }
    }

    /** Folder a new file goes into: the selected folder, the folder of the selected file, else code/. */
    private String newEntryFolder() {
        if (selectedPath != null) {
            for (FileTree.Row row : treeRows) {
                if (row.path().equals(selectedPath)) {
                    return row.prefix();
                }
            }
        }
        return "code/";
    }

    private void treeMenu(Menu menu, FileTree.Row entry) {
        String path = entry.path();
        if (entry.folder()) {
            menu.add(entry.expanded() ? "Collapse" : "Expand").setOnMenuItemClickListener(item -> {
                toggleFolder(path);
                return true;
            });
        } else {
            menu.add("Open").setOnMenuItemClickListener(item -> {
                openEntry(path, false);
                return true;
            });
        }
        addNewItems(menu, entry.prefix());
        menu.add("Rename...").setOnMenuItemClickListener(item -> {
            renameEntry(entry);
            return true;
        });
        menu.add("Delete...").setOnMenuItemClickListener(item -> {
            deleteEntry(entry);
            return true;
        });
        if (!entry.folder() && AssetDock.isAsset(path)) {
            menu.add("Open in the dock").setOnMenuItemClickListener(item -> {
                dock.open(path);
                return true;
            });
        }
        menu.add("Copy path").setOnMenuItemClickListener(item -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(path);
            toast("Copied " + path);
            return true;
        });
    }

    private void renameEntry(FileTree.Row entry) {
        prompt(explorer, "Rename " + entry.name(), entry.path(), "Rename",
                to -> store(s -> s.renameEntry(entry.path(), to)));
    }

    private void deleteEntry(FileTree.Row entry) {
        String path = entry.path();
        prompt(explorer, "Delete " + path + (entry.folder() ? "/ and everything in it?" : "?"), null, "Delete",
                ignored -> store(s -> {
                    if (!entry.folder()) {
                        s.closeFile(path);
                    }
                    s.deleteEntry(path);
                }));
    }

    /** Keyboard in the tree: arrows move/open/close, Enter opens, F2 renames, Delete deletes. */
    private boolean onExplorerKey(int keyCode, KeyEvent event) {
        int at = -1;
        for (int i = 0; i < treeRows.size(); i++) {
            if (treeRows.get(i).path().equals(selectedPath)) {
                at = i;
            }
        }
        FileTree.Row current = at < 0 ? null : treeRows.get(at);
        switch (keyCode) {
            case KeyEvent.KEY_DOWN, KeyEvent.KEY_UP -> {
                if (treeRows.isEmpty()) {
                    return true;
                }
                int next = Math.clamp(at < 0 ? 0 : at + (keyCode == KeyEvent.KEY_DOWN ? 1 : -1), 0, treeRows.size() - 1);
                selectedPath = treeRows.get(next).path();
                highlightExplorer();
            }
            case KeyEvent.KEY_RIGHT -> {
                if (current != null && current.folder()) {
                    if (!current.expanded()) {
                        toggleFolder(current.path());
                    } else if (at + 1 < treeRows.size()) {
                        selectedPath = treeRows.get(at + 1).path();
                        highlightExplorer();
                    }
                }
            }
            case KeyEvent.KEY_LEFT -> {
                if (current != null && current.folder() && current.expanded()) {
                    toggleFolder(current.path());
                } else if (current != null) {
                    int slash = current.path().lastIndexOf('/');
                    if (slash > 0) {
                        selectedPath = current.path().substring(0, slash);
                        highlightExplorer();
                    }
                }
            }
            case KeyEvent.KEY_ENTER, KeyEvent.KEY_KP_ENTER -> {
                if (current != null) {
                    if (current.folder()) {
                        toggleFolder(current.path());
                    } else {
                        openEntry(current.path(), false);
                    }
                }
            }
            case KeyEvent.KEY_F2 -> {
                if (current != null) {
                    renameEntry(current);
                }
            }
            case KeyEvent.KEY_DELETE -> {
                if (current != null) {
                    deleteEntry(current);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Selected row in a pill; the open file in the accent colour. */
    private void highlightExplorer() {
        String active = state == null ? null : state.active();
        Context context = explorer.getContext();
        for (int i = 0; i < explorer.getChildCount(); i++) {
            View child = explorer.getChildAt(i);
            if (child instanceof LinearLayout rowView && rowView.getTag() instanceof String path
                    && rowView.getChildCount() == 3 && rowView.getChildAt(2) instanceof TextView name) {
                styleChip(rowView, path.equals(selectedPath));
                name.setTextColor(color(context, path.equals(active) ? R.attr.colorPrimary : R.attr.colorOnSurface));
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

    /** True for model, block state and item files, which the dock can draw. */
    private static boolean isModelJson(@Nullable String path) {
        return path != null && path.startsWith("assets/") && path.endsWith(".json")
                && (path.contains("/models/") || path.contains("/blockstates/") || path.contains("/items/"));
    }

    /** Keeps the dock's preview in step with the model file being edited (only while that tab is showing). */
    private void refreshPreview() {
        if (dock != null && dock.visible() && dock.tab() == AssetDock.PREVIEW && isModelJson(shownPath)) {
            dock.previewModel(shownPath, code.getText().toString());
        }
    }

    private String tabsSignature = "";
    private CodeAssist assist;
    private MiniMap minimap;

    private void updateMinimap() {
        if (minimap == null || code == null) {
            return;
        }
        minimap.setVisibility(settings.minimap && shownPath != null ? View.VISIBLE : View.GONE);
        minimap.setTheme(settings.theme());
        minimap.setText(code.getText().toString());
    }

    /** The first problem message on a 0-based line of the shown file, or null. */
    private String problemMessageAt(int line) {
        if (state == null) {
            return null;
        }
        for (CodeProblems.Problem p : state.localProblems()) {
            if (p.line() == line) {
                return p.message();
            }
        }
        for (ValidationProblem p : state.problems()) {
            if (p.line() == line && (p.file().isEmpty() || p.file().equals(shownPath))) {
                return p.message();
            }
        }
        return null;
    }

    private void renderTabs() {
        List<String> ordered = orderedTabs();
        StringBuilder sig = new StringBuilder(String.valueOf(state.active())).append('|').append(previewPath);
        for (String path : ordered) {
            sig.append('|').append(path).append(state.dirtyTabs().contains(path) ? '*' : '-')
                    .append(isPinned(path) ? 'p' : '-');
        }
        // Rebuilding on every snapshot dropped clicks that were in progress and left hover tooltips on dead views.
        if (sig.toString().equals(tabsSignature)) {
            return;
        }
        tabsSignature = sig.toString();
        tabs.removeAllViews();
        Context context = tabs.getContext();
        for (String path : ordered) {
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
            Overlays.contextMenu(chip, menu -> {
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
        var menu = Overlays.menu(anchor);
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
        String joined = String.join("\n", log);
        if (joined.equals(consoleText)) {
            return; // same text: keep the user's selection and scroll position
        }
        consoleText = joined;
        boolean atBottom = !consoleScroll.canScrollVertically(1);
        console.setText(joined);
        if (atBottom) {
            consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private String consoleText = "";
    private String problemsSignature;

    private void renderProblems(State next) {
        // Rebuilt rows would swallow clicks in progress, so only rebuild when the problems changed.
        String signature = shownPath + "|" + next.problems() + "|" + next.localProblems();
        if (signature.equals(problemsSignature)) {
            return;
        }
        problemsSignature = signature;
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
        row.setOnClickListener(v -> {
            tools.recordJump();
            openAt(file, line);
        });
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
                row.setOnClickListener(v -> {
                    tools.recordJump();
                    openAt(path, line);
                });
                Overlays.contextMenu(row, menu -> menu.add("Remove bookmark").setOnMenuItemClickListener(i -> {
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
        updateMinimap();
        updateBreadcrumb();
        refreshPreview();
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
        if (keyCode == KeyEvent.KEY_F12 || (ctrl && !shift && !event.isAltPressed() && keyCode == KeyEvent.KEY_B)) {
            tools.goToDefinition(code.getSelectionStart());
            return true;
        }
        if (event.isAltPressed() && !ctrl) {
            if (keyCode == KeyEvent.KEY_LEFT) {
                tools.back();
                return true;
            }
            if (keyCode == KeyEvent.KEY_RIGHT) {
                tools.forward();
                return true;
            }
        }
        if (ctrl && event.isAltPressed()) {
            switch (keyCode) {
                case KeyEvent.KEY_O -> tools.organiseImports();
                case KeyEvent.KEY_I -> tools.addMissingImports();
                case KeyEvent.KEY_V -> tools.extractVariable();
                case KeyEvent.KEY_M -> tools.extractFunction();
                default -> {
                    return false;
                }
            }
            return true;
        }
        if (ctrl) {
            switch (keyCode) {
                case KeyEvent.KEY_T -> tools.symbolSearch();
                case KeyEvent.KEY_E -> tools.showRecentLocations();
                case KeyEvent.KEY_D -> {
                    if (shift) {
                        tools.generateDocstring();
                    } else {
                        return false;
                    }
                }
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
        tools.recordJump();
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
        var menu = Overlays.menu(anchor);
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
        var menu = Overlays.menu(anchor);
        Menu m = menu.getMenu();
        m.add("Find...   Ctrl+F").setOnMenuItemClickListener(i -> run(() -> openFind(false)));
        m.add("Replace...   Ctrl+H").setOnMenuItemClickListener(i -> run(() -> openFind(true)));
        m.add("Quick open...   Ctrl+P").setOnMenuItemClickListener(i -> run(this::quickOpen));
        m.add("New UI window...").setOnMenuItemClickListener(i -> run(tools::newWindow));
        m.add("UI designer (ui/*.json)...").setOnMenuItemClickListener(i -> run(tools::uiDesigner));
        for (int size : new int[]{16, 32, 64}) {
            m.add("New texture " + size + "x" + size + "...").setOnMenuItemClickListener(i -> run(() -> {
                String id = projectId();
                if (id == null) {
                    toast("Open a project first");
                } else {
                    dock.newTexture(size);
                }
            }));
        }
        m.add("Change log...").setOnMenuItemClickListener(i -> run(tools::changeLog));
        m.add("Go to symbol...   Ctrl+T").setOnMenuItemClickListener(i -> run(tools::symbolSearch));
        m.add("Back   Alt+Left").setOnMenuItemClickListener(i -> run(tools::back));
        m.add("Forward   Alt+Right").setOnMenuItemClickListener(i -> run(tools::forward));
        m.add("Recent locations...   Ctrl+E").setOnMenuItemClickListener(i -> run(tools::showRecentLocations));
        m.add("Replace with preview...").setOnMenuItemClickListener(i -> run(() -> {
            openFind(true);
            toast("Type what to find and the replacement, then press Preview");
        }));
        m.add("Organise imports   Ctrl+Alt+O").setOnMenuItemClickListener(i -> run(tools::organiseImports));
        m.add("Add missing imports   Ctrl+Alt+I").setOnMenuItemClickListener(i -> run(tools::addMissingImports));
        m.add("Generate docstring   Ctrl+Shift+D").setOnMenuItemClickListener(i -> run(tools::generateDocstring));
        m.add("Extract variable...   Ctrl+Alt+V").setOnMenuItemClickListener(i -> run(tools::extractVariable));
        m.add("Extract function...   Ctrl+Alt+M").setOnMenuItemClickListener(i -> run(tools::extractFunction));
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

    private void showToolsMenu(View anchor) {
        var menu = Overlays.menu(anchor);
        Menu m = menu.getMenu();
        m.add("Go to symbol...   Ctrl+T").setOnMenuItemClickListener(i -> run(tools::symbolSearch));
        m.add("Rename block / item id everywhere...").setOnMenuItemClickListener(i -> run(tools::renameId));
        m.add("Asset usage report...").setOnMenuItemClickListener(i -> run(tools::assetUsage));
        m.add("Why isn't my block showing?...").setOnMenuItemClickListener(i -> run(tools::explain));
        m.add("Preview block / item (3D)...").setOnMenuItemClickListener(i -> run(tools::previewContent));
        m.add("Recipes...").setOnMenuItemClickListener(i -> run(tools::recipes));
        m.add("Loot tables...").setOnMenuItemClickListener(i -> run(tools::loot));
        m.add("Handler profiler...").setOnMenuItemClickListener(i -> run(tools::profiler));
        m.add("Event tracer").setOnMenuItemClickListener(i -> run(() -> showPanel(PANEL_EVENTS)));
        menu.show();
    }

    private void showViewMenu(View anchor) {
        var menu = Overlays.menu(anchor);
        Menu m = menu.getMenu();
        m.add("Word wrap (this file)").setCheckable(true).setChecked(isWrapped())
                .setOnMenuItemClickListener(i -> run(this::toggleWrap));
        m.add("Zen mode   F11").setCheckable(true).setChecked(zen).setOnMenuItemClickListener(i -> run(this::toggleZen));
        m.add("Focus mode (dim all but this function)").setCheckable(true).setChecked(settings.focusMode)
                .setOnMenuItemClickListener(i -> run(() -> {
                    settings.focusMode = !settings.focusMode;
                    settings.save();
                    updateFocus();
                }));
        m.add("Sticky scroll (pin scrolled-out headers)").setCheckable(true).setChecked(settings.stickyScroll)
                .setOnMenuItemClickListener(i -> run(() -> {
                    settings.stickyScroll = !settings.stickyScroll;
                    settings.save();
                    updateSticky();
                }));
        m.add("Asset dock (preview, texture, sound)").setCheckable(true).setChecked(dock.visible())
                .setOnMenuItemClickListener(i -> run(() -> dock.setVisible(!dock.visible())));
        m.add("Preview this file in the dock").setOnMenuItemClickListener(i -> run(() -> {
            if (isModelJson(shownPath)) {
                dock.previewModel(shownPath, code.getText().toString());
            } else {
                toast("Open a model, block state or item file under assets/ first");
            }
        }));
        m.add("Minimap").setCheckable(true).setChecked(settings.minimap)
                .setOnMenuItemClickListener(i -> run(() -> {
                    settings.minimap = !settings.minimap;
                    settings.save();
                    updateMinimap();
                }));
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
        m.add("Help and keyboard shortcuts   F1").setOnMenuItemClickListener(i -> run(this::showShortcuts));
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
        var popup = Overlays.dialog(code, "Go to file", 460, 80);
        var content = popup.body;
        var input = new EditText(context, null, R.attr.editTextOutlinedStyle);
        input.setSingleLine();
        input.setHint("Type part of a file name");
        content.addView(input, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        var results = new LinearLayout(context);
        results.setOrientation(LinearLayout.VERTICAL);
        var scroll = new ScrollView(context);
        scroll.addView(results, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        content.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, content.dp(260)));
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
        popup.show();
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
                if (state.files().contains(path) && !path.endsWith("/") && fuzzy(path, q)) {
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

    /** F1 / Ctrl+/ / Help menu: the IDE guide (its last section lists every shortcut). */
    private void showShortcuts() {
        HelpPanel.open(code);
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
        if (state == null || state.projectId() == null) {
            toast("Open a project first");
            return;
        }
        final String id = state.projectId();
        final String name = state.projectName();
        final String version = state.projectVersion().isBlank() ? "1.0.0" : state.projectVersion();
        final View host = code;
        store(s -> {
            List<net.xmilon.craftpy.project.ModuleInfo> installed = new ArrayList<>(s.modules());
            s.refreshModules(); // fresher next time
            host.post(() -> BuildDialog.open(host, id, name, version, installed, request -> {
                store(st -> st.buildProject(request));
                showPanel(PANEL_CONSOLE);
            }));
        });
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
        var menu = Overlays.menu(anchor);
        addNewItems(menu.getMenu(), folder);
        menu.show();
    }

    /**
     * An asset was dragged out of the dock's Assets tab and released at a window position: over the code it inserts the
     * asset (see {@link AssetDrop#snippet}), over the explorer it copies the file into the folder under the pointer.
     */
    private void dropAsset(AssetKind kind, String id, float windowX, float windowY) {
        View explorerArea = (View) explorer.getParent();
        if (insideWindow(explorerArea, windowX, windowY)) {
            copyAssetInto(kind, id, explorerFolderAt(kind, windowY));
        } else if (insideWindow(code, windowX, windowY) && shownPath != null && !shownPath.endsWith(".py")) {
            toast("Drop it into a .py file: " + shownPath + " is not Python");
        } else if (insideWindow(code, windowX, windowY) && shownPath != null) {
            int[] at = new int[2];
            code.getLocationInWindow(at);
            int length = code.getText().length();
            int offset = Math.clamp(code.getOffsetForPosition(windowX - at[0], windowY - at[1]), 0, length);
            String snippet = AssetDrop.snippet(kind, id);
            applyEdit(new TextOps.Edit(offset, offset, snippet, offset + snippet.length()));
            code.requestFocus();
            toast("Inserted " + snippet);
        } else {
            toast("Drop it on the code to insert it, or on a folder of the explorer to copy the file");
        }
    }

    private static boolean insideWindow(View view, float windowX, float windowY) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return view.getVisibility() == View.VISIBLE && windowX >= at[0] && windowX < at[0] + view.getWidth()
                && windowY >= at[1] && windowY < at[1] + view.getHeight();
    }

    /** The folder of the explorer row at a window height; the asset's usual folder under the rows. */
    private String explorerFolderAt(AssetKind kind, float windowY) {
        String folder = AssetDrop.folder(kind);
        String usual = "assets/" + (folder == null ? "" : folder + "/");
        for (int i = 0; i < explorer.getChildCount(); i++) {
            View child = explorer.getChildAt(i);
            int[] at = new int[2];
            child.getLocationInWindow(at);
            if (windowY >= at[1] && windowY < at[1] + child.getHeight() && child.getTag() instanceof String path) {
                for (FileTree.Row row : treeRows) {
                    if (row.path().equals(path)) {
                        // a file or folder outside assets/ (code/, the project root) is no place for a texture
                        return row.prefix().startsWith("assets/") ? row.prefix() : usual;
                    }
                }
            }
        }
        return usual;
    }

    /** Copies the file behind an asset into the project; an existing file is never overwritten. */
    private void copyAssetInto(AssetKind kind, String id, String folder) {
        String project = projectId();
        if (project == null) {
            return;
        }
        if (!AssetDrop.isFile(kind)) {
            toast(id + " is not a file - drop it on the code instead");
            return;
        }
        String target = AssetDrop.intoFolder(kind, id, folder);
        if (shownFiles.contains(target)) {
            toast(target + " already exists");
            return;
        }
        Minecraft.getInstance().execute(() -> {
            byte[] data = AssetFetch.bytes(kind, id);
            if (data == null) {
                code.post(() -> toast("Could not read " + id));
                return;
            }
            ClientProjectStore store = ClientProjectStore.getInstance();
            store.uploadAsset(project, target, data);
            store.refresh();
            code.post(() -> toast("Copied " + id + " to " + target));
        });
    }

    /** Single click: preview tab (replaced by the next preview). Double click: a normal tab. */
    private void openFromExplorer(String path) {
        long now = System.currentTimeMillis();
        boolean doubleClick = path.equals(lastClickPath) && now - lastClickTime < DOUBLE_CLICK_MS;
        lastClickPath = path;
        lastClickTime = now;
        if (isAsset(path) && !doubleClick) {
            selectInExplorer(path);
            return;
        }
        selectInExplorer(null);
        openEntry(path, !doubleClick);
    }

    private static boolean isAsset(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".mp3");
    }

    /** Assets are only selected by one click (double click opens the viewer). */
    private void selectInExplorer(@Nullable String path) {
        selectedPath = path;
        highlightExplorer();
        if (path != null && isAsset(path)) {
            breadcrumb.setText(path + "   (double click to open)");
        }
    }

    private String selectedPath;
    @Nullable
    private String revealed;

    /** Text files open as tabs; textures and sounds open the asset viewer. */
    private void openEntry(String path, boolean preview) {
        boolean asset = isAsset(path);
        flush();
        if (asset) {
            dock.open(path);
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
