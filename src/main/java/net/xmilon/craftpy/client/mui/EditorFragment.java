package net.xmilon.craftpy.client.mui;

import net.xmilon.craftpy.client.IdeHost;
import java.util.ArrayList;
import java.util.List;
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
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.SpannableString;
import icyllis.modernui.text.Spanned;
import icyllis.modernui.text.TextPaint;
import icyllis.modernui.text.TextWatcher;
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
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.CodeProblems;
import net.xmilon.craftpy.client.EditorSettings;
import net.xmilon.craftpy.client.EditorTheme;
import net.xmilon.craftpy.client.FileTree;
import net.xmilon.craftpy.client.Syntax;
import net.xmilon.craftpy.client.LineEdits;
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
    private static final int PANEL_DEBUG = 4;

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
    private final TextView[] panelTabs = new TextView[5];
    private final View[] panelPanes = new View[5];
    private TextView breadcrumb;
    private TextView sticky;
    private View statusBar;
    private ToolStripe leftStripe;
    private ToolStripe rightStripe;
    private TextView avatar;
    private SplitEditor split;
    /** Tabs closed in this session, newest first (Ctrl+Shift+T reopens them). */
    private final java.util.ArrayDeque<String> closedTabs = new java.util.ArrayDeque<>();
    /** Which tool windows were open before Ctrl+Shift+F12 hid them all: explorer, bottom, dock, blocks. */
    private boolean[] hiddenToolWindows;
    private BlocksPanel blocks;
    private CatalogPanel catalog;
    private ModMapPanel modMap;
    private UiMakerPanel uiMaker;
    private RecipeBookPanel recipeBook;
    private Splitter recipeBookSplitter;
    private Splitter modMapSplitter;
    private Splitter uiMakerSplitter;
    private View catalogSplitter;
    private final Runnable followCursor = this::followCatalog;
    private View blocksSplitter;
    private View splitSplitter;
    private View mainEditor;
    private TextView statusPosition;
    private TextView statusEnding;
    private TextView statusEncoding;
    private TextView statusProblems;
    /** Where each line number starts in the gutter text, to brighten the caret's line number. */
    private int[] gutterLineStarts = new int[0];
    private CharSequence gutterText = "";
    private int gutterCaretLine = -1;
    private long lastTabClick;
    private String lastTabClickPath;
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

        @Override
        public java.util.Map<String, TreeSet<Integer>> breakpoints() {
            return settings.breakpointsOf(projectId());
        }

        @Override
        public void clearBreakpoints() {
            settings.clearBreakpoints(projectId());
            settings.save();
            updateGutter();
        }

        @Override
        public void saveAll() {
            prepareSave();
            java.util.Set<String> paths = EditorFragment.this.dirtyTexts().keySet();
            store(s -> paths.forEach(s::saveFile));
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
        recentProjects = store.projects().stream().limit(8)
                .map(p -> new String[]{p.id, p.name == null || p.name.isBlank() ? p.id : p.name}).toList();
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
        page.addView(toolbar, new LinearLayout.LayoutParams(MATCH_PARENT, page.dp(40)));
        page.addView(separator(context, false), new LinearLayout.LayoutParams(MATCH_PARENT, page.dp(1)));

        var body = row(context);
        body.setBaselineAligned(false);
        leftStripe = createLeftStripe(context);
        body.addView(leftStripe, new LinearLayout.LayoutParams(body.dp(40), MATCH_PARENT));
        body.addView(separator(context, true), new LinearLayout.LayoutParams(body.dp(1), MATCH_PARENT));
        explorerColumn = createExplorer(context);
        body.addView(explorerColumn, new LinearLayout.LayoutParams(body.dp(settings.explorerWidth), MATCH_PARENT));
        explorerSplitter = new Splitter(context, true, this::dragExplorer, this::saveLayout);
        body.addView(explorerSplitter, new LinearLayout.LayoutParams(body.dp(5), MATCH_PARENT));

        center = new LinearLayout(context);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setBackground(new ColorDrawable(IdeColors.EDITOR));
        tabBar = createTabBar(context);
        center.addView(tabBar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        breadcrumb = label(context, "", R.attr.textAppearanceLabelMedium);
        breadcrumb.setTextColor(IdeColors.TEXT_SECONDARY);
        breadcrumb.setSingleLine();
        breadcrumb.setPadding(center.dp(12), center.dp(3), center.dp(12), center.dp(3));
        breadcrumb.setClickable(true);
        breadcrumb.setTooltipText("Where the cursor is. Click for the outline of this file");
        hoverBackground(breadcrumb);
        breadcrumb.setOnClickListener(hidingTooltip(breadcrumb.getTooltipText(), tools::showOutline));
        center.addView(breadcrumb, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        findBar = createFindBar(context);
        center.addView(findBar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        // the editor row: main editor, and while split a resizable second editor to its right
        var editors = row(context);
        editors.setBaselineAligned(false);
        mainEditor = createCodeArea(context);
        editors.addView(mainEditor, new LinearLayout.LayoutParams(0, MATCH_PARENT, settings.splitWeight));
        splitSplitter = new Splitter(context, true, this::dragSplit, this::saveLayout);
        splitSplitter.setVisibility(View.GONE);
        editors.addView(splitSplitter, new LinearLayout.LayoutParams(editors.dp(5), MATCH_PARENT));
        split = new SplitEditor(context, splitHost());
        split.view().setVisibility(View.GONE);
        editors.addView(split.view(), new LinearLayout.LayoutParams(0, MATCH_PARENT, 1 - settings.splitWeight));
        codeArea = editors;
        center.addView(codeArea, new LinearLayout.LayoutParams(MATCH_PARENT, 0, settings.codeWeight));
        bottomSplitter = new Splitter(context, false, this::dragBottom, this::saveLayout);
        center.addView(bottomSplitter, new LinearLayout.LayoutParams(MATCH_PARENT, center.dp(5)));
        bottomPanel = createBottomPanel(context);
        center.addView(bottomPanel, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1 - settings.codeWeight));
        body.addView(center, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        dock = new AssetDock(context, this::projectId, () -> shownFiles, this::toast, () -> store(ClientProjectStore::refresh),
                visible -> {
                    if (dockSplitter != null) {
                        dockSplitter.setVisibility(visible && !zen ? View.VISIBLE : View.GONE);
                    }
                    if (visible && blocks != null) {
                        setBlocksVisible(false); // one tool window on the right at a time
                        setCatalogVisible(false);
                        hideSidePanels();
                    }
                    if (rightStripe != null) {
                        rightStripe.refresh();
                    }
                }, this::dropAsset);
        dockSplitter = new Splitter(context, true, this::dragDock, this::saveLayout);
        dockSplitter.setVisibility(View.GONE);
        body.addView(dockSplitter, new LinearLayout.LayoutParams(body.dp(5), MATCH_PARENT));
        body.addView(dock.view(), new LinearLayout.LayoutParams(body.dp(settings.dockWidth), MATCH_PARENT));
        blocks = new BlocksPanel(context, blocksHost());
        blocksSplitter = new Splitter(context, true, this::dragBlocks, this::saveLayout);
        body.addView(blocksSplitter, new LinearLayout.LayoutParams(body.dp(5), MATCH_PARENT));
        body.addView(blocks.view(), new LinearLayout.LayoutParams(body.dp(settings.blocksWidth), MATCH_PARENT));
        setBlocksVisible(false);
        catalog = new CatalogPanel(context, catalogHost());
        catalogSplitter = new Splitter(context, true, this::dragCatalog, this::saveLayout);
        body.addView(catalogSplitter, new LinearLayout.LayoutParams(body.dp(5), MATCH_PARENT));
        body.addView(catalog.view(), new LinearLayout.LayoutParams(body.dp(settings.blocksWidth + 40), MATCH_PARENT));
        setCatalogVisible(false);
        modMap = new ModMapPanel(context, new ModMapPanel.Host() {
            @Override
            public void request(com.google.gson.JsonObject args, java.util.function.Consumer<com.google.gson.JsonObject> reply) {
                View anchor = modMap.view();
                store(s -> s.tool("modmap", args, answer -> anchor.post(() -> reply.accept(answer))));
            }

            @Override
            public void hide() {
                setSidePanel(modMap.view(), modMapSplitter, false);
            }
        });
        modMapSplitter = new Splitter(context, true, dx -> dragPanel(modMap.view(), dx), this::saveLayout);
        body.addView(modMapSplitter, new LinearLayout.LayoutParams(body.dp(5), MATCH_PARENT));
        body.addView(modMap.view(), new LinearLayout.LayoutParams(body.dp(settings.blocksWidth + 120), MATCH_PARENT));
        setSidePanel(modMap.view(), modMapSplitter, false);
        recipeBook = new RecipeBookPanel(context, new RecipeBookPanel.Host() {
            @Override
            public void request(String op, com.google.gson.JsonObject args, java.util.function.Consumer<com.google.gson.JsonObject> reply) {
                View anchor = recipeBook.view();
                store(s -> s.tool(op, args, answer -> anchor.post(() -> reply.accept(answer))));
            }

            @Override
            public void hide() {
                setSidePanel(recipeBook.view(), recipeBookSplitter, false);
            }
        });
        recipeBookSplitter = new Splitter(context, true, dx -> dragPanel(recipeBook.view(), dx), this::saveLayout);
        body.addView(recipeBookSplitter, new LinearLayout.LayoutParams(body.dp(5), MATCH_PARENT));
        body.addView(recipeBook.view(), new LinearLayout.LayoutParams(body.dp(settings.blocksWidth + 120), MATCH_PARENT));
        setSidePanel(recipeBook.view(), recipeBookSplitter, false);
        uiMaker = new UiMakerPanel(context, new UiMakerPanel.Host() {
            @Override
            public List<String> files() {
                return shownFiles;
            }

            @Override
            public String activePath() {
                return shownPath;
            }

            @Override
            public String activeText() {
                return code == null ? "" : code.getText().toString();
            }

            @Override
            public void open(String path) {
                openEntry(path, false);
            }

            @Override
            public void design() {
                tools.uiDesigner();
            }

            @Override
            public void newWindow() {
                tools.newWindow();
            }

            @Override
            public void hide() {
                setSidePanel(uiMaker.view(), uiMakerSplitter, false);
            }
        });
        uiMakerSplitter = new Splitter(context, true, dx -> dragPanel(uiMaker.view(), dx), this::saveLayout);
        body.addView(uiMakerSplitter, new LinearLayout.LayoutParams(body.dp(5), MATCH_PARENT));
        body.addView(uiMaker.view(), new LinearLayout.LayoutParams(body.dp(settings.blocksWidth + 40), MATCH_PARENT));
        setSidePanel(uiMaker.view(), uiMakerSplitter, false);
        body.addView(separator(context, true), new LinearLayout.LayoutParams(body.dp(1), MATCH_PARENT));
        rightStripe = createRightStripe(context);
        body.addView(rightStripe, new LinearLayout.LayoutParams(body.dp(40), MATCH_PARENT));
        page.addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        body.setLayoutTransition(toolWindowTransition());
        center.setLayoutTransition(toolWindowTransition());
        page.addView(separator(context, false), new LinearLayout.LayoutParams(MATCH_PARENT, page.dp(1)));
        statusBar = createStatusBar(context);
        page.addView(statusBar, new LinearLayout.LayoutParams(MATCH_PARENT, page.dp(26)));
        applyToolWindows();
        var mainLp = (LinearLayout.LayoutParams) mainEditor.getLayoutParams();
        mainLp.weight = 1;

        applyTheme();
        applyFontSize();
        code.setIndentGuides(settings.indentGuides);
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
            IdeHost.get().closeScreen();
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

    /**
     * IntelliJ's main toolbar: the project widget (colored initials, name, menu), the menus, Search Everywhere in the
     * middle and the run widget on the right. Every action keeps its keyboard shortcut in the tooltip.
     */
    private View createToolbar(Context context) {
        var bar = row(context);
        bar.setBackground(new ColorDrawable(IdeColors.PANEL));
        bar.setPadding(bar.dp(6), 0, bar.dp(8), 0);

        var project = row(context);
        project.setPadding(project.dp(6), project.dp(4), project.dp(8), project.dp(4));
        project.setClickable(true);
        project.setTooltipText("Project: switch, build, reload");
        hoverBackground(project);
        avatar = label(context, "", R.attr.textAppearanceLabelMedium);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextColor(0xFFFFFFFF);
        project.addView(avatar, new LinearLayout.LayoutParams(project.dp(22), project.dp(22)));
        title = label(context, "", R.attr.textAppearanceLabelLarge);
        title.setTextColor(IdeColors.TEXT);
        title.setSingleLine();
        title.setPadding(project.dp(8), 0, project.dp(4), 0);
        project.addView(title);
        project.addView(new IdeIcon(context, IdeIcon.Glyph.CHEVRON_DOWN));
        project.setOnClickListener(hidingTooltip(project.getTooltipText(), this::showProjectMenu));
        bar.addView(project, new LinearLayout.LayoutParams(WRAP_CONTENT, bar.dp(30)));

        bar.addView(menuButton(context, "Edit", "Find, replace, line tools, bookmarks", this::showEditMenu));
        bar.addView(menuButton(context, "View", "Tool windows, word wrap, zoom, theme, zen mode", this::showViewMenu));
        bar.addView(menuButton(context, "Tools", "Symbols, rename everywhere, previews, profiler, event tracer",
                this::showToolsMenu));
        bar.addView(menuButton(context, "Help", "How the IDE works, the Python API and the Java bridge (F1)",
                v -> HelpPanel.open(code)));

        bar.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
        var search = row(context);
        var searchBg = new icyllis.modernui.graphics.drawable.ShapeDrawable();
        searchBg.setCornerRadius(search.dp(6));
        searchBg.setColor(IdeColors.EDITOR);
        searchBg.setStroke(Math.max(1, search.dp(1)), IdeColors.HOVER);
        search.setBackground(searchBg);
        search.setPadding(search.dp(8), 0, search.dp(10), 0);
        search.setClickable(true);
        search.setTooltipText("Search Everywhere: files of the project (Ctrl+P), symbols (Ctrl+T)");
        search.addView(new IdeIcon(context, IdeIcon.Glyph.SEARCH));
        var hint = label(context, "Search files and symbols", R.attr.textAppearanceLabelLarge);
        hint.setTextColor(IdeColors.TEXT_DISABLED);
        hint.setPadding(search.dp(8), 0, 0, 0);
        search.addView(hint, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        var keys = label(context, "Ctrl+P", R.attr.textAppearanceLabelSmall);
        keys.setTextColor(IdeColors.TEXT_DISABLED);
        search.addView(keys);
        search.setOnClickListener(hidingTooltip(search.getTooltipText(), v -> quickOpen()));
        bar.addView(search, new LinearLayout.LayoutParams(bar.dp(300), bar.dp(28)));
        bar.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));

        // Run, Debug, Stop: the three things a toolbar is for. Save (Ctrl+S), Validate (Ctrl+Enter), Reload (F5) and
        // Build module are in the project menu and Find action.
        var runWidget = row(context);
        runWidget.setPadding(runWidget.dp(8), 0, runWidget.dp(10), 0);
        runWidget.setClickable(true);
        runWidget.setTooltipText("Save and run the project in the world (Ctrl+R)");
        hoverBackground(runWidget);
        runWidget.addView(new IdeIcon(context, IdeIcon.Glyph.PLAY));
        var runLabel = label(context, "Run", R.attr.textAppearanceLabelLarge);
        runLabel.setTextColor(IdeColors.TEXT);
        runLabel.setPadding(runWidget.dp(6), 0, 0, 0);
        runWidget.addView(runLabel);
        runWidget.setOnClickListener(hidingTooltip(runWidget.getTooltipText(), v -> run()));
        bar.addView(runWidget, new LinearLayout.LayoutParams(WRAP_CONTENT, bar.dp(30)));
        bar.addView(action(context, IdeIcon.Glyph.BUG, "Debug: run with breakpoints and show the Debug panel (Shift+F9)",
                v -> debugRun()));
        bar.addView(action(context, IdeIcon.Glyph.STOP, "Stop the running Python code", v -> store(ClientProjectStore::interrupt)));
        return bar;
    }

    private static TextView menuButton(Context context, String text, String tooltip, View.OnClickListener onClick) {
        var view = label(context, text, R.attr.textAppearanceLabelLarge);
        view.setTextColor(IdeColors.TEXT);
        view.setGravity(Gravity.CENTER);
        view.setPadding(view.dp(9), 0, view.dp(9), 0);
        view.setClickable(true);
        view.setTooltipText(tooltip);
        hoverBackground(view);
        view.setOnClickListener(hidingTooltip(tooltip, onClick));
        var lp = new LinearLayout.LayoutParams(WRAP_CONTENT, view.dp(28));
        lp.leftMargin = view.dp(2);
        view.setLayoutParams(lp);
        return view;
    }

    /** A toolbar action: an icon with a hover square and a tooltip. */
    static IdeIcon action(Context context, IdeIcon.Glyph glyph, String tooltip, View.OnClickListener onClick) {
        var icon = new IdeIcon(context, glyph);
        int pad = icon.dp(6);
        icon.setPadding(pad, pad, pad, pad);
        icon.setClickable(true);
        icon.setTooltipText(tooltip);
        hoverBackground(icon);
        icon.setOnClickListener(hidingTooltip(tooltip, onClick));
        var lp = new LinearLayout.LayoutParams(icon.dp(30), icon.dp(30));
        lp.leftMargin = icon.dp(2);
        icon.setLayoutParams(lp);
        return icon;
    }

    private static LinearLayout.LayoutParams toolbarSeparator(View bar) {
        var lp = new LinearLayout.LayoutParams(Math.max(1, bar.dp(1)), bar.dp(20));
        lp.leftMargin = bar.dp(8);
        lp.rightMargin = bar.dp(6);
        return lp;
    }

    /** A 1dp line in the separator color. */
    static View separator(Context context, boolean vertical) {
        var line = new View(context);
        line.setBackground(new ColorDrawable(vertical ? IdeColors.SEPARATOR : IdeColors.SEPARATOR));
        return line;
    }

    private void updateAvatar(@Nullable String name) {
        IdeColors.avatar(avatar, name);
    }

    /** id and name of up to 8 projects, refreshed from the store whenever it changes. */
    private volatile List<String[]> recentProjects = List.of();

    private void showProjectMenu(View anchor) {
        var menu = Overlays.menu(anchor);
        Menu m = menu.getMenu();
        for (String[] project : recentProjects) {
            if (!project[0].equals(projectId())) {
                m.add(project[1]).setOnMenuItemClickListener(i -> run(() -> {
                    flush();
                    store(s -> s.select(project[0]));
                }));
            }
        }
        m.add("Open another project...").setOnMenuItemClickListener(i -> run(() -> CraftPyFragment.navigate(this, CraftPyFragment.PAGE_HOME)));
        m.add("Settings...   Ctrl+Alt+S").setOnMenuItemClickListener(i -> run(this::showSettings));
        m.add("Run   Ctrl+R").setOnMenuItemClickListener(i -> run(this::run));
        m.add("Reload project and modules   F5").setOnMenuItemClickListener(i -> run(this::reloadAll));
        m.add("Run tests   Ctrl+Shift+F10").setOnMenuItemClickListener(i -> run(() -> tools.runTests(false)));
        m.add("Build module...").setOnMenuItemClickListener(i -> run(() -> build(anchor)));
        m.add("Installed modules").setOnMenuItemClickListener(i -> run(() -> CraftPyFragment.navigate(this, CraftPyFragment.PAGE_MODULES)));
        menu.show();
    }

    // ---- tool windows (IntelliJ): stripes on both edges open and close them

    private ToolStripe createLeftStripe(Context context) {
        var stripe = new ToolStripe(context);
        stripe.add(IdeIcon.Glyph.FOLDER, "Project (Alt+1)", () -> settings.explorerVisible, v -> toggleExplorer());
        stripe.add(IdeIcon.Glyph.STRUCTURE, "Structure: the outline of this file (Alt+7)", () -> false, tools::showOutline);
        stripe.add(IdeIcon.Glyph.BOOKMARK, "Bookmarks (Alt+2)", () -> bottomShows(PANEL_BOOKMARKS), v -> toggleBottom(PANEL_BOOKMARKS));
        stripe.addSpacer();
        stripe.add(IdeIcon.Glyph.TERMINAL, "Python console (Alt+4)", () -> bottomShows(PANEL_CONSOLE), v -> toggleBottom(PANEL_CONSOLE));
        stripe.add(IdeIcon.Glyph.WARNING, "Problems (Alt+6)", () -> bottomShows(PANEL_PROBLEMS), v -> toggleBottom(PANEL_PROBLEMS));
        stripe.add(IdeIcon.Glyph.PULSE, "Event tracer", () -> bottomShows(PANEL_EVENTS), v -> toggleBottom(PANEL_EVENTS));
        stripe.add(IdeIcon.Glyph.EYE, "Debugger (Alt+5)", () -> bottomShows(PANEL_DEBUG), v -> toggleBottom(PANEL_DEBUG));
        return stripe;
    }

    private ToolStripe createRightStripe(Context context) {
        var stripe = new ToolStripe(context);
        stripe.add(IdeIcon.Glyph.MODULES, "Building blocks: windows, items, blocks, events... ready to insert (Alt+8)",
                this::blocksVisible, v -> setBlocksVisible(!blocksVisible()));
        stripe.add(IdeIcon.Glyph.CATALOG, "Mod catalog: every id, attribute, tag, recipe type and class of the installed mods (Alt+0)",
                this::catalogVisible, v -> setCatalogVisible(!catalogVisible()));
        stripe.add(IdeIcon.Glyph.GRAPH, "Mod map: mods, modules and projects, and what ties them together",
                () -> sidePanelShown(modMap.view()), v -> toggleSidePanel(modMap.view(), modMapSplitter));
        stripe.add(IdeIcon.Glyph.HAMMER, "Recipes & drops: every recipe and every mob drop of the game and its mods (Ctrl+Shift+R)",
                () -> sidePanelShown(recipeBook.view()), v -> toggleSidePanel(recipeBook.view(), recipeBookSplitter));
        stripe.add(IdeIcon.Glyph.LAYOUT, "UI maker: the windows of this project, with a live preview",
                () -> sidePanelShown(uiMaker.view()), v -> toggleSidePanel(uiMaker.view(), uiMakerSplitter));
        stripe.add(IdeIcon.Glyph.IMAGE, "Assets: every texture, model and sound (Alt+9)", () -> dockShows(AssetDock.ASSETS),
                v -> toggleDock(AssetDock.ASSETS));
        stripe.add(IdeIcon.Glyph.EYE, "Preview: models and textures", () -> dockShows(AssetDock.PREVIEW),
                v -> toggleDock(AssetDock.PREVIEW));
        stripe.add(IdeIcon.Glyph.BRUSH, "Texture editor", () -> dockShows(AssetDock.TEXTURE), v -> toggleDock(AssetDock.TEXTURE));
        stripe.add(IdeIcon.Glyph.SOUND, "Sound editor", () -> dockShows(AssetDock.SOUND), v -> toggleDock(AssetDock.SOUND));
        return stripe;
    }

    /** Shows a bottom tool window, opening the panel when it was closed (results of Run and Validate land there). */
    private void openPanel(int index) {
        showPanel(index);
        if (!settings.bottomVisible) {
            settings.bottomVisible = true;
            settings.save();
            applyToolWindows();
        }
    }

    /** Opening and closing a tool window animates the neighbours into place (only visibility changes, not dragging). */
    private static icyllis.modernui.animation.LayoutTransition toolWindowTransition() {
        var transition = new icyllis.modernui.animation.LayoutTransition();
        transition.setDuration(160);
        return transition;
    }

    private String tabsRestoredFor;
    private String tabsRemembered = "";

    /** The first time a project's files arrive with no tab open, its last tabs reopen; afterwards the tabs are remembered. */
    private void rememberOrRestoreTabs(State next) {
        String id = next.projectId();
        if (id == null || next.files().isEmpty()) {
            return;
        }
        if (!id.equals(tabsRestoredFor)) {
            tabsRestoredFor = id;
            List<String> saved = settings.openTabs.getOrDefault(id, List.of());
            if (next.tabs().isEmpty() && !saved.isEmpty()) {
                for (String path : saved) {
                    if (next.files().contains(path)) {
                        store(s -> s.openFile(path)); // the last one opened ends up active
                    }
                }
                return;
            }
        }
        List<String> tabs = new ArrayList<>();
        for (String tab : next.tabs()) {
            if (!tab.equals(next.active()) && next.files().contains(tab)) {
                tabs.add(tab);
            }
        }
        if (next.active() != null && next.files().contains(next.active())) {
            tabs.add(next.active());
        }
        String signature = id + "|" + tabs;
        if (!signature.equals(tabsRemembered)) {
            tabsRemembered = signature;
            settings.openTabs.put(id, tabs);
            settings.save();
        }
    }

    // ---- settings (Ctrl+Alt+S)

    private void showSettings() {
        var dialog = Overlays.dialog(code, "Settings - Esc closes", 420, -1);
        Context context = code.getContext();
        dialog.body.addView(settingToggle(context, "Minimap", () -> settings.minimap, value -> {
            settings.minimap = value;
            updateMinimap();
        }));
        dialog.body.addView(settingToggle(context, "Indent guides", () -> settings.indentGuides, value -> {
            settings.indentGuides = value;
            code.setIndentGuides(value);
        }));
        dialog.body.addView(settingToggle(context, "Sticky scroll (pin the def you are in)", () -> settings.stickyScroll, value -> {
            settings.stickyScroll = value;
            updateSticky();
        }));
        dialog.body.addView(settingToggle(context, "Focus mode (dim all but this function)", () -> settings.focusMode, value -> {
            settings.focusMode = value;
            updateFocus();
        }));
        dialog.body.addView(settingToggle(context, "Trim trailing spaces on save", () -> settings.trimOnSave,
                value -> settings.trimOnSave = value));
        dialog.body.addView(settingStepper(context, "Font size", () -> Math.round(settings.fontSize) + "", step -> zoom(step)));
        dialog.body.addView(settingStepper(context, "Right margin (ruler)", () -> settings.ruler == 0 ? "off" : settings.ruler + "",
                step -> {
                    settings.ruler = Math.clamp(settings.ruler + step * 10, 0, 200);
                    applyTheme();
                }));
        dialog.body.addView(settingStepper(context, "Color scheme", () -> settings.theme().label, step -> {
            EditorTheme[] all = EditorTheme.values();
            settings.theme = all[Math.floorMod(settings.theme().ordinal() + step, all.length)].name();
            applyTheme();
            if (split != null) {
                split.applySettings();
            }
        }));
        dialog.show();
    }

    private View settingToggle(Context context, String text, java.util.function.BooleanSupplier get,
                               java.util.function.Consumer<Boolean> set) {
        var row = row(context);
        row.setPadding(row.dp(4), row.dp(4), row.dp(4), row.dp(4));
        var name = label(context, text, R.attr.textAppearanceBodyMedium);
        name.setTextColor(IdeColors.TEXT);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        var value = label(context, "", R.attr.textAppearanceLabelLarge);
        Runnable show = () -> {
            value.setText(get.getAsBoolean() ? "On" : "Off");
            value.setTextColor(get.getAsBoolean() ? IdeColors.ACCENT : IdeColors.TEXT_SECONDARY);
        };
        show.run();
        value.setPadding(value.dp(12), value.dp(4), value.dp(12), value.dp(4));
        value.setClickable(true);
        hoverBackground(value);
        value.setOnClickListener(v -> {
            set.accept(!get.getAsBoolean());
            settings.save();
            show.run();
        });
        row.addView(value);
        return row;
    }

    private View settingStepper(Context context, String text, java.util.function.Supplier<String> get,
                                java.util.function.IntConsumer step) {
        var row = row(context);
        row.setPadding(row.dp(4), row.dp(4), row.dp(4), row.dp(4));
        var name = label(context, text, R.attr.textAppearanceBodyMedium);
        name.setTextColor(IdeColors.TEXT);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        var value = label(context, get.get(), R.attr.textAppearanceLabelLarge);
        value.setTextColor(IdeColors.TEXT);
        value.setGravity(Gravity.CENTER);
        row.addView(action(context, IdeIcon.Glyph.MINUS, "Less", v -> {
            step.accept(-1);
            settings.save();
            value.setText(get.get());
        }));
        row.addView(value, new LinearLayout.LayoutParams(row.dp(110), WRAP_CONTENT));
        row.addView(action(context, IdeIcon.Glyph.PLUS, "More", v -> {
            step.accept(1);
            settings.save();
            value.setText(get.get());
        }));
        return row;
    }

    /** Ctrl+Alt+Shift+C: "code/main.py:12" of the caret, for a chat message or an issue. */
    private void copyReference() {
        if (shownPath == null) {
            return;
        }
        String reference = shownPath + ":" + (TextOps.lineOf(code.getText().toString(), Math.max(0, code.getSelectionStart())) + 1);
        EditorTools.copyToClipboard(reference);
        toast("Copied " + reference);
    }

    /** Alt+Shift+E (IntelliJ "Execute selection in console"): the selection, or the caret's line, runs in the console. */
    private void executeSelection() {
        String text = code.getText().toString();
        int from = Math.min(code.getSelectionStart(), code.getSelectionEnd());
        int to = Math.max(code.getSelectionStart(), code.getSelectionEnd());
        String source = from == to ? text.substring(TextOps.lineStart(text, from), TextOps.lineEnd(text, from)) : text.substring(from, to);
        if (source.isBlank()) {
            return;
        }
        String dedented = java.util.Arrays.stream(source.stripIndent().split("\n")).collect(java.util.stream.Collectors.joining("\n"));
        openPanel(PANEL_CONSOLE);
        store(s -> {
            s.append(">>> " + dedented.replace("\n", "\n... "));
            s.evaluate(dedented);
        });
    }

    // ---- line commands, navigation and actions (IntelliJ shortcuts)

    private void lineEdit(@Nullable TextOps.Edit edit) {
        if (edit != null && code != null) {
            applyEdit(edit);
        }
    }

    private void reformatFile() {
        String text = code.getText().toString();
        String formatted = LineEdits.reformat(text);
        if (formatted.equals(text)) {
            toast("Already formatted");
            return;
        }
        int caret = TextOps.lineOf(text, code.getSelectionStart());
        applyEdit(new TextOps.Edit(0, text.length(), formatted, 0));
        String now = code.getText().toString();
        code.setSelection(Math.min(TextOps.offsetOfLine(now, caret), now.length()));
        toast("Reformatted (whitespace only)");
    }

    private void renameInFile() {
        String text = code.getText().toString();
        int caret = code.getSelectionStart();
        int[] word = TextOps.wordAt(text, caret);
        if (word == null) {
            toast("Put the cursor on a name");
            return;
        }
        String name = text.substring(word[0], word[1]);
        prompt(code, "Rename '" + name + "' in this file (Shift+F6)", name, "Rename", value -> {
            TextOps.Edit edit = LineEdits.renameInFile(code.getText().toString(), caret, value.trim());
            if (edit == null) {
                toast("Not a valid Python name");
            } else {
                applyEdit(edit);
            }
        });
    }

    private void goToLinePrompt() {
        String text = code.getText().toString();
        int current = TextOps.lineOf(text, Math.max(0, code.getSelectionStart())) + 1;
        prompt(code, "Go to line (1-" + (TextOps.lineOf(text, text.length()) + 1) + ")", String.valueOf(current), "Go",
                value -> {
                    try {
                        goToLine(Math.max(0, Integer.parseInt(value.trim()) - 1));
                    } catch (NumberFormatException e) {
                        toast("Type a line number");
                    }
                });
    }

    private void rememberClosed(String path) {
        closedTabs.remove(path);
        closedTabs.addFirst(path);
        while (closedTabs.size() > 20) {
            closedTabs.removeLast();
        }
    }

    private void reopenClosedTab() {
        String path = closedTabs.pollFirst();
        if (path == null) {
            toast("No closed tab to reopen");
            return;
        }
        if (!shownFiles.contains(path)) {
            toast(path + " is not in the project any more");
            return;
        }
        flush();
        store(s -> s.openFile(path));
    }

    /** Ctrl+Shift+F12: hides every tool window, the next press brings back the ones that were open. */
    private void hideAllToolWindows() {
        if (hiddenToolWindows == null) {
            hiddenToolWindows = new boolean[]{settings.explorerVisible, settings.bottomVisible, dock.visible(), blocksVisible()};
            settings.explorerVisible = false;
            settings.bottomVisible = false;
            dock.setVisible(false);
            setBlocksVisible(false);
        } else {
            settings.explorerVisible = hiddenToolWindows[0];
            settings.bottomVisible = hiddenToolWindows[1];
            if (hiddenToolWindows[2]) {
                dock.setVisible(true);
            }
            if (hiddenToolWindows[3]) {
                setBlocksVisible(true);
            }
            hiddenToolWindows = null;
        }
        settings.save();
        applyToolWindows();
    }

    /** One entry of Find Action: what it is called, its shortcut and what it does. */
    private record ActionEntry(String name, String keys, Runnable run) {
    }

    private List<ActionEntry> actions() {
        List<ActionEntry> a = new ArrayList<>();
        a.add(new ActionEntry("Save", "Ctrl+S", this::save));
        a.add(new ActionEntry("Validate", "Ctrl+Enter", this::validate));
        a.add(new ActionEntry("Run", "Ctrl+R", this::run));
        a.add(new ActionEntry("Debug: run with breakpoints", "Shift+F9", this::debugRun));
        a.add(new ActionEntry("Run tests (code/test_*.py)", "Ctrl+Shift+F10", () -> tools.runTests(false)));
        a.add(new ActionEntry("Run tests of this file", "", () -> tools.runTests(true)));
        a.add(new ActionEntry("Reload project and modules", "F5", this::reloadAll));
        a.add(new ActionEntry("Stop", "", () -> store(ClientProjectStore::interrupt)));
        a.add(new ActionEntry("Build module", "", () -> build(code)));
        a.add(new ActionEntry("Find", "Ctrl+F", () -> openFind(false)));
        a.add(new ActionEntry("Replace", "Ctrl+H", () -> openFind(true)));
        a.add(new ActionEntry("Find in files", "Ctrl+Shift+F", this::findInFiles));
        a.add(new ActionEntry("TODO list", "", this::todoList));
        a.add(new ActionEntry("Open file (quick open)", "Ctrl+P", this::quickOpen));
        a.add(new ActionEntry("Go to symbol", "Ctrl+T", tools::symbolSearch));
        a.add(new ActionEntry("Go to line", "Ctrl+G", this::goToLinePrompt));
        a.add(new ActionEntry("Go to definition", "Ctrl+B / F12", () -> tools.goToDefinition(code.getSelectionStart())));
        a.add(new ActionEntry("Back", "Alt+Left", tools::back));
        a.add(new ActionEntry("Forward", "Alt+Right", tools::forward));
        a.add(new ActionEntry("Recent locations", "Ctrl+E", tools::showRecentLocations));
        a.add(new ActionEntry("Reopen closed tab", "Ctrl+Shift+T", this::reopenClosedTab));
        a.add(new ActionEntry("Toggle line comment", "Ctrl+/", () -> lineEdit(LineEdits.toggleComment(
                code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd()))));
        a.add(new ActionEntry("Duplicate line or selection", "Ctrl+D", () -> lineEdit(LineEdits.duplicate(
                code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd()))));
        a.add(new ActionEntry("Delete line", "Ctrl+Shift+K", () -> lineEdit(LineEdits.deleteLines(
                code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd()))));
        a.add(new ActionEntry("Move line up", "Alt+Shift+Up", () -> lineEdit(LineEdits.moveLines(
                code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd(), true))));
        a.add(new ActionEntry("Move line down", "Alt+Shift+Down", () -> lineEdit(LineEdits.moveLines(
                code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd(), false))));
        a.add(new ActionEntry("Reformat file", "Ctrl+Alt+L", this::reformatFile));
        a.add(new ActionEntry("Rename in file", "Shift+F6", this::renameInFile));
        a.add(new ActionEntry("Rename block / item id everywhere", "", tools::renameId));
        a.add(new ActionEntry("Move function / class to another file", "F6", tools::moveSymbol));
        a.add(new ActionEntry("Organise imports", "Ctrl+Alt+O", tools::organiseImports));
        a.add(new ActionEntry("Add missing imports", "Ctrl+Alt+I", tools::addMissingImports));
        a.add(new ActionEntry("Generate docstring", "Ctrl+Shift+D", tools::generateDocstring));
        a.add(new ActionEntry("Extract variable", "Ctrl+Alt+V", tools::extractVariable));
        a.add(new ActionEntry("Extract function", "Ctrl+Alt+M", tools::extractFunction));
        a.add(new ActionEntry("Toggle bookmark", "Ctrl+F2", this::toggleBookmark));
        a.add(new ActionEntry("Toggle breakpoint", "Ctrl+F8", this::toggleBreakpoint));
        a.add(new ActionEntry("Debugger: breakpoint hits with stack and variables", "Alt+5", () -> openPanel(PANEL_DEBUG)));
        a.add(new ActionEntry("Split editor right", "Ctrl+\\", () -> {
            if (splitOpen()) {
                closeSplit();
            } else {
                openSplit(null);
            }
        }));
        a.add(new ActionEntry("Project tool window", "Alt+1", this::toggleExplorer));
        a.add(new ActionEntry("Python console", "Alt+4", () -> toggleBottom(PANEL_CONSOLE)));
        a.add(new ActionEntry("Problems", "Alt+6", () -> toggleBottom(PANEL_PROBLEMS)));
        a.add(new ActionEntry("Bookmarks", "Alt+2", () -> toggleBottom(PANEL_BOOKMARKS)));
        a.add(new ActionEntry("Event tracer", "", () -> openPanel(PANEL_EVENTS)));
        a.add(new ActionEntry("Structure of this file", "Alt+7", () -> tools.showOutline(breadcrumb)));
        a.add(new ActionEntry("Building blocks", "Alt+8", () -> setBlocksVisible(true)));
        a.add(new ActionEntry("Mod catalog: ids, attributes, tags, recipe types, classes of the mods", "Alt+0",
                () -> setCatalogVisible(true)));
        a.add(new ActionEntry("Assets", "Alt+9", () -> toggleDock(AssetDock.ASSETS)));
        a.add(new ActionEntry("Mod map: mods, modules, projects and their links", "",
                () -> setSidePanel(modMap.view(), modMapSplitter, true)));
        a.add(new ActionEntry("Recipes & drops: every recipe and mob drop", "Ctrl+Shift+R", this::recipesAtCaret));
        a.add(new ActionEntry("UI maker: windows of this project with a preview", "",
                () -> setSidePanel(uiMaker.view(), uiMakerSplitter, true)));
        a.add(new ActionEntry("Hide all tool windows", "Ctrl+Shift+F12", this::hideAllToolWindows));
        a.add(new ActionEntry("Zen mode", "F11", this::toggleZen));
        a.add(new ActionEntry("Word wrap (this file)", "", this::toggleWrap));
        a.add(new ActionEntry("Zoom in", "Ctrl+=", () -> zoom(1)));
        a.add(new ActionEntry("Zoom out", "Ctrl+-", () -> zoom(-1)));
        a.add(new ActionEntry("Handler profiler", "", tools::profiler));
        a.add(new ActionEntry("Why isn't my block showing?", "", tools::explain));
        a.add(new ActionEntry("Asset usage report", "", tools::assetUsage));
        a.add(new ActionEntry("Change log", "", tools::changeLog));
        a.add(new ActionEntry("UI designer", "", tools::uiDesigner));
        a.add(new ActionEntry("New UI window", "", tools::newWindow));
        a.add(new ActionEntry("Keyboard shortcuts", "F1", this::showShortcuts));
        a.add(new ActionEntry("Settings", "Ctrl+Alt+S", this::showSettings));
        a.add(new ActionEntry("Undo", "Ctrl+Z", () -> undoText(false)));
        a.add(new ActionEntry("Redo", "Ctrl+Y / Ctrl+Shift+Z", () -> undoText(true)));
        a.add(new ActionEntry("Undo file operation", "Ctrl+Z in the Project tree", () -> undoFileAction(false)));
        a.add(new ActionEntry("Redo file operation", "Ctrl+Y in the Project tree", () -> undoFileAction(true)));
        a.add(new ActionEntry("Copy reference (file:line)", "Ctrl+Alt+Shift+C", this::copyReference));
        a.add(new ActionEntry("Execute selection in console", "Alt+Shift+E", this::executeSelection));
        a.add(new ActionEntry("Help", "", () -> HelpPanel.open(code)));
        return a;
    }

    /** Ctrl+Shift+A (IntelliJ's Find Action): every command of the editor by name. */
    private void findAction() {
        var dialog = ListDialog.open(code, "Find action", true, 520, 380);
        List<ListDialog.Row> rows = new ArrayList<>();
        for (ActionEntry entry : actions()) {
            rows.add(ListDialog.Row.link(entry.name(), entry.keys(), () -> {
                dialog.dismiss();
                entry.run().run();
            }));
        }
        dialog.setRows(rows);
    }

    /** Ctrl+Shift+F: every line of the project's files that contains the text (unsaved tabs are searched as saved). */
    private void findInFiles() {
        String start = code.getSelectionStart() != code.getSelectionEnd()
                ? code.getText().toString().substring(Math.min(code.getSelectionStart(), code.getSelectionEnd()),
                        Math.max(code.getSelectionStart(), code.getSelectionEnd()))
                : "";
        prompt(code, "Find in files", start.contains("\n") ? "" : start, "Find", query -> searchProject(query, false));
    }

    private void todoList() {
        searchProject("", true);
    }

    private void searchProject(String query, boolean todo) {
        if (projectId() == null) {
            toast("Open a project first");
            return;
        }
        var dialog = ListDialog.open(code, todo ? "TODO" : "Find in files: " + query, true, 720, 380);
        dialog.setMessage("Searching...");
        var args = new com.google.gson.JsonObject();
        args.addProperty("q", query);
        args.addProperty("todo", todo);
        store(s -> s.tool("grep", args, reply -> code.post(() -> {
            if (!dialog.isShowing()) {
                return;
            }
            if (reply.has("error")) {
                dialog.setMessage(reply.get("error").getAsString());
                return;
            }
            List<ListDialog.Row> rows = new ArrayList<>();
            for (var element : reply.getAsJsonArray("rows")) {
                var row = element.getAsJsonObject();
                String file = row.get("f").getAsString();
                int line = row.get("l").getAsInt();
                rows.add(ListDialog.Row.link(row.get("t").getAsString().strip(), file + ":" + (line + 1), () -> {
                    dialog.dismiss();
                    openAt(file, line);
                }));
            }
            dialog.setMessage(rows.isEmpty() ? "Nothing found" : rows.size() + " match(es)"
                    + (reply.has("cut") && reply.get("cut").getAsBoolean() ? " (first 500)" : ""));
            dialog.setRows(rows);
        })));
    }

    // ---- building blocks (ready-made code: docs, insert at the cursor, new file imported by main.py)

    private boolean blocksVisible() {
        return blocks != null && blocks.view().getVisibility() == View.VISIBLE;
    }

    private void setBlocksVisible(boolean visible) {
        int visibility = visible && !zen ? View.VISIBLE : View.GONE;
        blocks.view().setVisibility(visibility);
        blocksSplitter.setVisibility(visibility);
        if (visible) {
            if (dock.visible()) {
                dock.setVisible(false);
            }
            if (catalogVisible()) {
                setCatalogVisible(false);
                hideSidePanels();
            }
            blocks.focusSearch();
        }
        if (rightStripe != null) {
            rightStripe.refresh();
        }
    }

    // ---- mod map and UI maker: right-side tool windows, one at a time like the others

    private boolean sidePanelShown(View panel) {
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    private void toggleSidePanel(View panel, Splitter splitter) {
        setSidePanel(panel, splitter, !sidePanelShown(panel));
    }

    private void setSidePanel(View panel, Splitter splitter, boolean visible) {
        int visibility = visible && !zen ? View.VISIBLE : View.GONE;
        panel.setVisibility(visibility);
        splitter.setVisibility(visibility);
        if (visible) {
            if (dock.visible()) {
                dock.setVisible(false);
            }
            if (blocksVisible()) {
                setBlocksVisible(false);
            }
            if (catalogVisible()) {
                setCatalogVisible(false);
            }
            if (modMap != null && panel != modMap.view()) {
                setSidePanel(modMap.view(), modMapSplitter, false);
            }
            if (uiMaker != null && panel != uiMaker.view()) {
                setSidePanel(uiMaker.view(), uiMakerSplitter, false);
            }
            if (recipeBook != null && panel != recipeBook.view()) {
                setSidePanel(recipeBook.view(), recipeBookSplitter, false);
            }
            if (recipeBook != null && panel == recipeBook.view()) {
                recipeBook.focusSearch();
            }
            if (modMap != null && panel == modMap.view()) {
                modMap.refresh();
                modMap.focusSearch();
            }
            if (uiMaker != null && panel == uiMaker.view()) {
                uiMaker.refresh();
            }
        }
        if (rightStripe != null) {
            rightStripe.refresh();
        }
    }

    /** Other right-side windows close these two. */
    private void hideSidePanels() {
        if (modMap != null && sidePanelShown(modMap.view())) {
            setSidePanel(modMap.view(), modMapSplitter, false);
        }
        if (uiMaker != null && sidePanelShown(uiMaker.view())) {
            setSidePanel(uiMaker.view(), uiMakerSplitter, false);
        }
        if (recipeBook != null && sidePanelShown(recipeBook.view())) {
            setSidePanel(recipeBook.view(), recipeBookSplitter, false);
        }
    }

    /** Opens Recipes & drops, on the item id under the caret when there is one. */
    private void recipesAtCaret() {
        setSidePanel(recipeBook.view(), recipeBookSplitter, true);
        String id = tools.contentIdAtCaret();
        if (id != null && !id.isBlank()) {
            recipeBook.showItem(id.contains(":") || projectId() == null ? id : projectId() + ":" + id);
        }
    }

    private void dragPanel(View panel, int dx) {
        var lp = panel.getLayoutParams();
        int max = Math.max(panel.dp(320), ((View) panel.getParent()).getWidth() / 2);
        lp.width = Math.clamp(lp.width - dx, panel.dp(240), max);
        panel.setLayoutParams(lp);
    }

    // ---- mod catalog (Alt+0): everything the installed mods bring, connected to the cursor like completion

    private boolean catalogVisible() {
        return catalog != null && catalog.view().getVisibility() == View.VISIBLE;
    }

    private void setCatalogVisible(boolean visible) {
        int visibility = visible && !zen ? View.VISIBLE : View.GONE;
        catalog.view().setVisibility(visibility);
        catalogSplitter.setVisibility(visibility);
        if (visible) {
            if (dock.visible()) {
                dock.setVisible(false);
            }
            if (blocksVisible()) {
                setBlocksVisible(false);
            }
            hideSidePanels();
            catalog.refresh();
            followCatalog();
            catalog.focusSearch();
        }
        if (rightStripe != null) {
            rightStripe.refresh();
        }
    }

    private void dragCatalog(int dx) {
        var lp = catalog.view().getLayoutParams();
        int max = Math.max(catalog.view().dp(300), ((View) catalog.view().getParent()).getWidth() / 2);
        lp.width = Math.clamp(lp.width - dx, catalog.view().dp(240), max);
        catalog.view().setLayoutParams(lp);
    }

    private CatalogPanel.Host catalogHost() {
        return new CatalogPanel.Host() {
            @Override
            public void request(com.google.gson.JsonObject args, java.util.function.Consumer<com.google.gson.JsonObject> reply) {
                View view = code;
                store(s -> s.tool("catalog", args, answer -> {
                    if (view != null) {
                        view.post(() -> reply.accept(answer));
                    }
                }));
            }

            @Override
            public void insert(String id) {
                insertCatalogId(id);
            }

            @Override
            public void hide() {
                setCatalogVisible(false);
            }
        };
    }

    /** The cursor moved: the catalog shows what the string under it takes (debounced; a slice of the file around it). */
    private void followCatalog() {
        if (!catalogVisible() || code == null) {
            return;
        }
        String all = code.getText().toString();
        int caret = Math.max(0, code.getSelectionStart());
        int[] slice = CodeAssist.slice(all, caret);
        catalog.follow(all.substring(slice[0], slice[1]), CodeAssist.lineOf(all, caret) - CodeAssist.lineOf(all, slice[0]),
                caret - CodeAssist.lineStart(all, caret));
    }

    /** A catalog entry clicked: it replaces the string the cursor is in, or goes in as a new string. */
    private void insertCatalogId(String id) {
        if (shownPath == null || (state != null && state.activeVirtual())) {
            EditorTools.copyToClipboard(id);
            toast("Copied " + id + " (no file open)");
            return;
        }
        String all = code.getText().toString();
        int caret = Math.max(0, code.getSelectionStart());
        for (net.xmilon.craftpy.analysis.PyLex.Span span : net.xmilon.craftpy.analysis.PyLex.spans(all)) {
            if (span.kind() == net.xmilon.craftpy.analysis.PyLex.Kind.STRING && caret >= span.contentStart()
                    && caret <= span.contentEnd()) {
                applyEdit(new TextOps.Edit(span.contentStart(), span.contentEnd(), id, span.contentStart() + id.length()));
                code.requestFocus();
                return;
            }
        }
        String quoted = "\"" + id + "\"";
        applyEdit(new TextOps.Edit(caret, caret, quoted, caret + quoted.length()));
        code.requestFocus();
    }

    private void dragBlocks(int dx) {
        var lp = blocks.view().getLayoutParams();
        int max = Math.max(blocks.view().dp(300), ((View) blocks.view().getParent()).getWidth() / 2);
        lp.width = Math.clamp(lp.width - dx, blocks.view().dp(240), max);
        blocks.view().setLayoutParams(lp);
        settings.blocksWidth = Math.round(lp.width / (float) blocks.view().dp(1));
    }

    private BlocksPanel.Host blocksHost() {
        return new BlocksPanel.Host() {
            @Override
            public void docs(View anchor, net.xmilon.craftpy.project.BuildingBlocks.Block block) {
                HelpPanel.openSection(anchor, block.title(), block.docFile(), block.docHeading());
            }

            @Override
            public void insert(net.xmilon.craftpy.project.BuildingBlocks.Block block) {
                insertBlock(block);
            }

            @Override
            public void newFile(View anchor, net.xmilon.craftpy.project.BuildingBlocks.Block block) {
                newBlockFile(anchor, block);
            }

            @Override
            public void hide() {
                setBlocksVisible(false);
            }
        };
    }

    /** Puts the block's code on a new line after the cursor's line (or on the empty line the cursor is on). */
    private void insertBlock(net.xmilon.craftpy.project.BuildingBlocks.Block block) {
        if (shownPath == null || !shownPath.endsWith(".py") || (state != null && state.activeVirtual())) {
            toast("Open a Python file of the project first (or use New file)");
            return;
        }
        String all = code.getText().toString();
        // content ids come from the name: a second copy of the same block gets name_2, name_3...
        String name = block.module();
        for (int n = 2; java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b").matcher(all).find(); n++) {
            name = block.module() + "_" + n;
        }
        String snippet = block.snippet(name, projectId());
        int cursor = Math.max(0, code.getSelectionEnd());
        int lineStart = TextOps.lineStart(all, cursor);
        int lineEnd = TextOps.lineEnd(all, cursor);
        boolean blank = all.substring(lineStart, lineEnd).isBlank();
        String text = blank ? snippet : "\n\n" + snippet;
        int start = blank ? lineStart : lineEnd;
        int end = blank ? lineEnd : lineEnd;
        applyEdit(new TextOps.Edit(start, end, text, start + text.length()));
        code.requestFocus();
        toast("Inserted \"" + block.title() + "\"");
    }

    /** Adds the block as code/<name>.py and connects it: main.py gets "import <name>". Undo takes both back. */
    private void newBlockFile(View anchor, net.xmilon.craftpy.project.BuildingBlocks.Block block) {
        String project = projectId();
        if (project == null) {
            toast("Open a project first");
            return;
        }
        String suggested = net.xmilon.craftpy.project.BuildingBlocks.freeModule(block.module(), shownFiles);
        prompt(anchor, "New file from \"" + block.title() + "\"", suggested, "Create", value -> {
            String module = value.trim().toLowerCase(Locale.ROOT).replaceAll("\\.py$", "").replaceAll("[^a-z0-9_]", "_");
            if (!module.matches("[a-z_][a-z0-9_]*")) {
                toast("Use lowercase letters, digits and _ for the file name");
                return;
            }
            String path = "code/" + module + ".py";
            if (shownFiles.contains(path)) {
                toast(path + " already exists");
                return;
            }
            var args = new com.google.gson.JsonObject();
            args.addProperty("module", module);
            args.addProperty("content", block.fill(module, project));
            args.addProperty("patchMain", false);
            flush();
            View view = code;
            store(s -> s.tool("add_part", args, reply -> {
                if (view == null) {
                    return;
                }
                view.post(() -> {
                    if (reply.has("error")) {
                        toast(reply.get("error").getAsString());
                        return;
                    }
                    patchMainImport(module, false);
                    store(st -> st.select(project, path));
                    toast("Added " + path + " and imported it in main.py");
                    recordAction("New file " + path, () -> {
                        store(st -> st.closeFile(path));
                        store(st -> st.deleteEntry(path));
                        patchMainImport(module, true);
                    }, () -> {
                        restoreEntry(path);
                        patchMainImport(module, false);
                    });
                });
            }));
        });
    }

    /**
     * Adds or removes "import <module>" in code/main.py: in the editor's text when main.py is shown, in its buffer when it
     * is open in a tab (saved unless it had other unsaved edits), on disk otherwise. An open main.py is never overwritten.
     */
    private void patchMainImport(String module, boolean remove) {
        String main = "code/main.py";
        java.util.function.UnaryOperator<String> patch = text -> remove
                ? net.xmilon.craftpy.analysis.PyImports.removeImport(text, module)
                : net.xmilon.craftpy.analysis.PyImports.addImport(text, module);
        if (main.equals(shownPath)) {
            String text = code.getText().toString();
            String patched = patch.apply(text);
            if (!patched.equals(text)) {
                boolean wasDirty = state != null && state.dirtyTabs().contains(main) || !text.equals(synced);
                int caret = code.getSelectionStart();
                applyEdit(new TextOps.Edit(0, text.length(), patched, Math.min(caret, patched.length())));
                flush();
                if (!wasDirty) {
                    store(st -> st.saveFile(main));
                }
            }
        } else if (state != null && state.tabs().contains(main)) {
            store(st -> {
                String text = st.fileContent(main);
                if (text == null) {
                    return;
                }
                String patched = patch.apply(text);
                if (!patched.equals(text)) {
                    boolean dirty = st.isDirty(main);
                    st.markDirty(main, patched);
                    if (!dirty) {
                        st.saveFile(main);
                    }
                }
            });
        } else {
            var args = new com.google.gson.JsonObject();
            args.addProperty("module", module);
            args.addProperty("remove", remove);
            store(st -> st.tool("main_import", args, reply -> {
                if (reply.has("error")) {
                    View view = code;
                    if (view != null) {
                        view.post(() -> toast(reply.get("error").getAsString()));
                    }
                }
            }));
        }
    }

    // ---- undo / redo (IntelliJ: Ctrl+Z, Ctrl+Y or Ctrl+Shift+Z) for the text and for file operations

    /** One undoable change to the project's files (create, delete, rename, a building block's new file). */
    private record FileAction(String label, Runnable undo, Runnable redo) {
    }

    private final java.util.ArrayDeque<FileAction> fileUndo = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<FileAction> fileRedo = new java.util.ArrayDeque<>();

    private void recordAction(String label, Runnable undo, Runnable redo) {
        fileUndo.push(new FileAction(label, undo, redo));
        fileRedo.clear();
        while (fileUndo.size() > 50) {
            fileUndo.removeLast();
        }
    }

    /** Undo (or redo) of the last file operation; in the explorer and from the Edit menu. */
    private void undoFileAction(boolean redo) {
        FileAction action = (redo ? fileRedo : fileUndo).poll();
        if (action == null) {
            toast(redo ? "No file operation to redo" : "No file operation to undo");
            return;
        }
        (redo ? fileUndo : fileRedo).push(action);
        flush();
        (redo ? action.redo() : action.undo()).run();
        toast((redo ? "Redone: " : "Undone: ") + action.label());
    }

    private void restoreEntry(String path) {
        var args = new com.google.gson.JsonObject();
        args.addProperty("path", path);
        store(s -> s.tool("restore_entry", args, reply -> {
            View view = code;
            if (reply.has("error") && view != null) {
                view.post(() -> toast(reply.get("error").getAsString()));
            }
            ClientProjectStore.getInstance().listFiles();
        }));
    }

    private final java.util.Map<String, net.xmilon.craftpy.client.TextHistory> histories = new java.util.HashMap<>();
    /** The text each file had when the editor left it: a different text when it comes back means the history no longer fits. */
    private final java.util.Map<String, String> leftTexts = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> carets = new java.util.HashMap<>();
    private int changeCaret;
    private boolean undoing;

    private net.xmilon.craftpy.client.TextHistory history(String path) {
        return histories.computeIfAbsent(fileKey(path), k -> new net.xmilon.craftpy.client.TextHistory());
    }

    /** Ctrl+Z / Ctrl+Y in the code: the file's own history, grouped by words like IntelliJ. */
    private void undoText(boolean redo) {
        if (shownPath == null || (state != null && state.activeVirtual())) {
            return;
        }
        var history = history(shownPath);
        var step = redo ? history.redo() : history.undo();
        if (step == null) {
            toast(redo ? "Nothing to redo" : "Nothing to undo");
            return;
        }
        Editable text = code.getText();
        undoing = true;
        internalEdit = true;
        try {
            var edits = step.edits();
            if (redo) {
                for (var e : edits) {
                    text.replace(e.start(), e.start() + e.removed().length(), e.inserted());
                }
            } else {
                for (int i = edits.size() - 1; i >= 0; i--) {
                    var e = edits.get(i);
                    text.replace(e.start(), e.start() + e.inserted().length(), e.removed());
                }
            }
        } catch (IndexOutOfBoundsException e) {
            history.clear(); // the text changed behind the history's back: never apply a step to the wrong place
            toast("The undo history no longer matched this file and was cleared");
            return;
        } finally {
            undoing = false;
            internalEdit = false;
        }
        int caret = Math.min(redo ? step.caretAfter() : step.caretBefore(), text.length());
        code.setSelection(Math.max(0, caret));
        bringIntoView(Math.max(0, caret));
    }

    // ---- split editor (IntelliJ "Split Right")

    private SplitEditor.Host splitHost() {
        return new SplitEditor.Host() {
            @Override
            public void read(String path, java.util.function.Consumer<String> content) {
                View view = code;
                store(s -> {
                    String text = s.fileContent(path);
                    if (view != null) {
                        view.post(() -> content.accept(text));
                    }
                });
            }

            @Override
            public void edited(String path, String text) {
                store(s -> s.markDirty(path, text));
            }

            @Override
            public void save(String path) {
                store(s -> s.saveFile(path));
            }

            @Override
            public List<String> openTabs() {
                return state == null ? List.of() : state.tabs();
            }

            @Override
            public String mainPath() {
                return shownPath;
            }

            @Override
            public void close() {
                closeSplit();
            }
        };
    }

    /** Opens the split with {@code path}; when that is the main editor's file, another open file is shown instead. */
    private void openSplit(@Nullable String path) {
        if (state == null || state.tabs().isEmpty()) {
            toast("Open a file first");
            return;
        }
        String file = path;
        if (file == null || file.equals(shownPath)) {
            file = state.tabs().stream().filter(tab -> !tab.equals(shownPath)).reduce((a, b) -> b).orElse(shownPath);
        }
        flush();
        split.view().setVisibility(View.VISIBLE);
        splitSplitter.setVisibility(View.VISIBLE);
        var lp = (LinearLayout.LayoutParams) mainEditor.getLayoutParams();
        lp.weight = settings.splitWeight;
        mainEditor.setLayoutParams(lp);
        split.applySettings();
        split.show(file);
    }

    private void closeSplit() {
        split.view().setVisibility(View.GONE);
        splitSplitter.setVisibility(View.GONE);
        var lp = (LinearLayout.LayoutParams) mainEditor.getLayoutParams();
        lp.weight = 1;
        mainEditor.setLayoutParams(lp);
        code.requestFocus();
    }

    private boolean splitOpen() {
        return split != null && split.view().getVisibility() == View.VISIBLE;
    }

    private void dragSplit(int dx) {
        int total = mainEditor.getWidth() + split.view().getWidth();
        if (total <= 0) {
            return;
        }
        float weight = Math.clamp((mainEditor.getWidth() + dx) / (float) total, 0.15f, 0.85f);
        var mainLp = (LinearLayout.LayoutParams) mainEditor.getLayoutParams();
        var splitLp = (LinearLayout.LayoutParams) split.view().getLayoutParams();
        mainLp.weight = weight;
        splitLp.weight = 1 - weight;
        mainEditor.setLayoutParams(mainLp);
        split.view().setLayoutParams(splitLp);
        settings.splitWeight = weight;
    }

    private boolean bottomShows(int index) {
        return settings.bottomVisible && panel == index;
    }

    private boolean dockShows(int tab) {
        return dock != null && dock.visible() && dock.tab() == tab;
    }

    /** Opens the tool window, or closes it when it is already the one showing. */
    private void toggleBottom(int index) {
        if (bottomShows(index)) {
            settings.bottomVisible = false;
        } else {
            settings.bottomVisible = true;
            showPanel(index);
        }
        settings.save();
        applyToolWindows();
    }

    private void toggleExplorer() {
        settings.explorerVisible = !settings.explorerVisible;
        settings.save();
        applyToolWindows();
    }

    private void toggleDock(int tab) {
        if (dockShows(tab)) {
            dock.setVisible(false);
        } else {
            dock.show(tab);
            if (tab == AssetDock.ASSETS) {
                dock.focusSearch();
            }
        }
        rightStripe.refresh();
    }

    /** Shows the tool windows the settings say are open (zen mode hides everything). */
    private void applyToolWindows() {
        int explorer = settings.explorerVisible && !zen ? View.VISIBLE : View.GONE;
        explorerColumn.setVisibility(explorer);
        explorerSplitter.setVisibility(explorer);
        int bottom = settings.bottomVisible && !zen ? View.VISIBLE : View.GONE;
        bottomPanel.setVisibility(bottom);
        bottomSplitter.setVisibility(bottom);
        var codeLp = (LinearLayout.LayoutParams) codeArea.getLayoutParams();
        codeLp.weight = bottom == View.VISIBLE ? settings.codeWeight : 1;
        codeArea.setLayoutParams(codeLp);
        leftStripe.refresh();
        rightStripe.refresh();
    }

    /** A tooltip stays on screen while a popup opened by the same click is up; hide it on click. */
    static View.OnClickListener hidingTooltip(CharSequence tooltip, View.OnClickListener onClick) {
        return v -> {
            v.setTooltipText(null);
            onClick.onClick(v);
            v.postDelayed(() -> v.setTooltipText(tooltip), 400);
        };
    }

    private View createExplorer(Context context) {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackground(new ColorDrawable(IdeColors.PANEL));
        var header = row(context);
        header.setPadding(header.dp(12), header.dp(2), header.dp(4), header.dp(2));
        var label = label(context, "Project", R.attr.textAppearanceLabelLarge);
        label.setTextColor(IdeColors.TEXT);
        header.addView(label, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        header.addView(action(context, IdeIcon.Glyph.PLUS, "New file or folder (in the selected folder)",
                v -> showNewMenu(v, newEntryFolder())));
        header.addView(smallButton(context, "⊟", "Collapse all folders", v -> collapseAll()));
        header.addView(smallButton(context, "⊞", "Expand all folders", v -> expandAll()));
        header.addView(action(context, IdeIcon.Glyph.MINUS, "Hide (Alt+1)", v -> toggleExplorer()));
        column.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, header.dp(36)));

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
        bar.setBackground(new ColorDrawable(IdeColors.EDITOR));
        var tabScroll = new HorizontalScrollView(context);
        tabs = row(context);
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
        findInput = CraftPyFragment.field(context);
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
        replaceInput = CraftPyFragment.field(context);
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
        code.setOnCaret(offset -> {
            updateBreadcrumb();
            highlightGutterLine();
            if (catalogVisible()) {
                code.removeCallbacks(followCursor);
                code.postDelayed(followCursor, 250);
            }
        });
        code.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                changeStart = start;
                changeAdded = after;
                changeReplaced = s.subSequence(start, start + count).toString();
                changeCaret = code.getSelectionStart();
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
                if (!undoing && shownPath != null && changeStart + changeAdded <= s.length()) {
                    history(shownPath).record(new net.xmilon.craftpy.client.TextHistory.Edit(changeStart, changeReplaced,
                                    s.subSequence(changeStart, changeStart + changeAdded).toString()),
                            changeCaret, changeStart + changeAdded, System.currentTimeMillis());
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
        bar.setPadding(bar.dp(10), 0, bar.dp(10), 0);
        bar.setBackground(new ColorDrawable(IdeColors.PANEL));
        statusProblems = smallButton(context, "", "Problems of this file (Alt+6)", v -> openPanel(PANEL_PROBLEMS));
        bar.addView(statusProblems);
        statusPosition = label(context, "", R.attr.textAppearanceLabelMedium);
        statusPosition.setTextColor(IdeColors.TEXT_SECONDARY);
        statusPosition.setPadding(bar.dp(8), 0, 0, 0);
        bar.addView(statusPosition, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        var indent = label(context, "4 spaces", R.attr.textAppearanceLabelMedium);
        indent.setTextColor(IdeColors.TEXT_SECONDARY);
        indent.setTooltipText("Indentation: 4 spaces (Tab inserts them, Ctrl+Alt+L turns tabs into spaces)");
        indent.setPadding(bar.dp(8), 0, bar.dp(8), 0);
        bar.addView(indent);
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
        String[] names = { "Console", "Problems", "Bookmarks", "Events", "Debug" };
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            panelTabs[i] = panelTab(context, names[i], v -> showPanel(index));
            header.addView(panelTabs[i]);
        }
        header.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
        header.addView(button(context, "Copy", R.attr.borderlessButtonStyle, v -> copyPanel()));
        header.addView(button(context, "Clear", R.attr.borderlessButtonStyle,
                v -> store(ClientProjectStore::clearLog)));
        header.addView(action(context, IdeIcon.Glyph.MINUS, "Hide", v -> {
            settings.bottomVisible = false;
            settings.save();
            applyToolWindows();
        }));
        panelView.setBackground(new ColorDrawable(IdeColors.PANEL));
        panelView.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, header.dp(34)));

        var consoleColumn = new LinearLayout(context);
        consoleColumn.setOrientation(LinearLayout.VERTICAL);
        // A read-only editor view: long lines scroll sideways instead of wrapping, and selecting with the mouse behaves
        // like in the code (a selectable TextView inside the ScrollView jumped while dragging).
        var consoleView = new CodeView(context);
        consoleView.setFilters((source, start, end, dest, dstart, dend) ->
                consoleWriting ? null : dest.subSequence(dstart, dend));
        consoleView.setCursorVisible(false);
        consoleView.setHorizontallyScrolling(true);
        consoleView.setBackground(null);
        consoleView.setGravity(Gravity.START | Gravity.TOP);
        consoleView.setOnCtrlClick(this::jumpFromConsole);
        console = consoleView;
        console.setTypeface(EditorFont.get());
        console.setTextSize(12);
        console.setTextColor(CONSOLE_TEXT);
        int dp8 = panelView.dp(8);
        console.setPadding(dp8, dp8 / 2, dp8, dp8 / 2);
        consoleScroll = new ScrollView(context);
        consoleScroll.addView(console, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        consoleColumn.addView(consoleScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        var input = CraftPyFragment.field(context);
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

        panelPanes[PANEL_DEBUG] = tools.createDebugPane(context);
        panelView.addView(panelPanes[PANEL_DEBUG], new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        showPanel(PANEL_CONSOLE);
        return panelView;
    }

    private static TextView panelTab(Context context, String text, View.OnClickListener onClick) {
        var tab = new UnderlinedTab(context);
        tab.setText(text);
        tab.setTextAppearance(R.attr.textAppearanceLabelLarge);
        hoverBackground(tab);
        int dp6 = tab.dp(6);
        tab.setPadding(tab.dp(12), dp6, tab.dp(12), dp6);
        tab.setClickable(true);
        tab.setOnClickListener(onClick);
        return tab;
    }

    private void showPanel(int index) {
        panel = index;
        for (int i = 0; i < panelTabs.length; i++) {
            boolean selected = i == index;
            panelPanes[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            panelTabs[i].setSelected(selected);
            panelTabs[i].invalidate();
            panelTabs[i].setTextColor(selected ? IdeColors.TEXT : IdeColors.TEXT_SECONDARY);
        }
        if (index == PANEL_BOOKMARKS) {
            renderBookmarks();
        }
        if (leftStripe != null) {
            leftStripe.refresh();
        }
        if (index == PANEL_EVENTS) {
            tools.eventsPanelShown();
        } else {
            tools.eventsPanelHidden();
        }
        if (index == PANEL_DEBUG) {
            tools.debugPanelShown();
        } else {
            tools.debugPanelHidden();
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
        leftStripe.setVisibility(visibility);
        rightStripe.setVisibility(visibility);
        if (zen) {
            dockBeforeZen = dock.visible();
            dock.setVisible(false); // also stops a playing sound
            setBlocksVisible(false);
            setCatalogVisible(false);
            dockSplitter.setVisibility(View.GONE);
        } else if (dockBeforeZen) {
            dock.setVisible(true);
        }
        tabBar.setVisibility(visibility);
        statusBar.setVisibility(visibility);
        applyToolWindows();
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
        title.setText(next.projectId() == null ? "No project" : next.projectName());
        updateAvatar(next.projectId() == null ? null : next.projectName());

        if (splitOpen()) {
            split.refresh();
        }
        rememberOrRestoreTabs(next);
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
        if (shownPath != null && code != null) {
            leftTexts.put(fileKey(shownPath), code.getText().toString());
            carets.put(fileKey(shownPath), code.getSelectionStart());
        }
        String key = path == null ? null : fileKey(path);
        if (key != null && leftTexts.containsKey(key) && !leftTexts.get(key).equals(content)) {
            histories.remove(key); // changed on disk or by a refactoring while away
            carets.remove(key);
        }
        loading = true;
        try {
            shownPath = path;
            synced = content;
            code.setText(content);
            int caret = key == null ? 0 : Math.min(carets.getOrDefault(key, 0), content.length());
            code.setSelection(Math.max(0, caret)); // back where you were in this file, like IntelliJ
            if (caret > 0) {
                code.post(() -> bringIntoView(Math.min(caret, code.getText().length())));
            }
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
    private List<FileTree.Row> treeRows = List.of();

    /** Open folders of this project's tree, remembered across sessions (settings.expanded). */
    private Set<String> expandedSet() {
        return settings.expanded.computeIfAbsent(projectId() == null ? "" : projectId(), k -> new TreeSet<>(List.of("code")));
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
        var icon = new IdeIcon(context, entry.folder() ? IdeIcon.Glyph.FOLDER : fileGlyph(path));
        icon.setTint(entry.folder() ? 0xFFB9A26B : path.endsWith(".py") ? 0xFF6C9BD2
                : isAsset(path) ? 0xFFC77DBB : IdeColors.TEXT_SECONDARY);
        icon.setTooltipText(badge(entry));
        var iconLp = new LinearLayout.LayoutParams(row.dp(16), row.dp(16));
        iconLp.rightMargin = row.dp(6);
        row.addView(icon, iconLp);
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
        settings.save();
        renderExplorer();
    }

    private void collapseAll() {
        expandedSet().clear();
        settings.save();
        renderExplorer();
    }

    private void expandAll() {
        expandedSet().addAll(FileTree.folders(shownFiles));
        settings.save();
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
            IdeHost.get().setClipboard(path);
            toast("Copied " + path);
            return true;
        });
    }

    private void renameEntry(FileTree.Row entry) {
        String from = entry.path();
        prompt(explorer, "Rename " + entry.name(), from, "Rename", to -> {
            store(s -> s.renameEntry(from, to));
            recordAction("Rename " + from, () -> store(s -> s.renameEntry(to, from)), () -> store(s -> s.renameEntry(from, to)));
        });
    }

    private void deleteEntry(FileTree.Row entry) {
        String path = entry.path();
        prompt(explorer, "Delete " + path + (entry.folder() ? "/ and everything in it?" : "?"), null, "Delete",
                ignored -> {
                    store(s -> {
                        if (!entry.folder()) {
                            s.closeFile(path);
                        }
                        s.deleteEntry(path);
                    });
                    recordAction("Delete " + path, () -> restoreEntry(path), () -> store(s -> {
                        s.closeFile(path);
                        s.deleteEntry(path);
                    }));
                });
    }

    /** Keyboard in the tree: arrows move/open/close, Enter opens, F2 renames, Delete deletes. */
    private boolean onExplorerKey(int keyCode, KeyEvent event) {
        if (event.isCtrlPressed() && (keyCode == KeyEvent.KEY_Z || keyCode == KeyEvent.KEY_Y)) {
            undoFileAction(keyCode == KeyEvent.KEY_Y || event.isShiftPressed());
            return true;
        }
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
                && (path.contains("/models/") || path.contains("/blockstates/") || path.contains("/items/")
                || path.endsWith(".geo.json"));
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
            var chip = new UnderlinedTab.Row(context);
            chip.setPadding(chip.dp(10), 0, chip.dp(4), 0);
            chip.setSelected(active);
            IdeColors.hoverBackground(chip, 0);
            var fileIcon = new IdeIcon(context, fileGlyph(path));
            fileIcon.setTint(path.endsWith(".py") ? 0xFF6C9BD2 : IdeColors.TEXT_SECONDARY);
            chip.addView(fileIcon);
            chip.setTooltipText(path + (pinned ? " (pinned)" : path.equals(previewPath) ? " (preview)" : ""));
            String text = path.substring(path.lastIndexOf('/') + 1);
            var name = label(context, "", R.attr.textAppearanceLabelLarge);
            name.setPadding(name.dp(6), 0, 0, 0);
            if (path.equals(previewPath)) {
                var italic = new SpannableString(text);
                italic.setSpan(new StyleSpan(TextPaint.ITALIC), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                name.setText(italic);
            } else {
                name.setText(text);
            }
            name.setTextColor(active ? IdeColors.TEXT : IdeColors.TEXT_SECONDARY);
            chip.addView(name);
            if (dirty) { // VS Code: a dot where the close button is while the file has unsaved changes
                var dot = label(context, "●", R.attr.textAppearanceLabelMedium);
                dot.setTextColor(IdeColors.ACCENT);
                dot.setPadding(dot.dp(8), 0, dot.dp(6), 0);
                dot.setTooltipText("Unsaved changes (Ctrl+S)");
                chip.addView(dot);
            } else if (!pinned) {
                var close = new IdeIcon(context, IdeIcon.Glyph.CLOSE);
                close.setPadding(close.dp(3), close.dp(3), close.dp(3), close.dp(3));
                close.setClickable(true);
                close.setTooltipText("Close (Ctrl+W)");
                IdeColors.hoverBackground(close, 4);
                close.setOnClickListener(v -> closeTab(v, path));
                var closeLp = new LinearLayout.LayoutParams(chip.dp(18), chip.dp(18));
                closeLp.leftMargin = chip.dp(6);
                closeLp.rightMargin = chip.dp(2);
                chip.addView(close, closeLp);
            } else {
                var pin = label(context, "📌", R.attr.textAppearanceLabelSmall);
                pin.setPadding(pin.dp(6), 0, pin.dp(4), 0);
                chip.addView(pin);
            }
            chip.setClickable(true);
            chip.setOnClickListener(v -> {
                long now = System.currentTimeMillis();
                if (path.equals(lastTabClickPath) && now - lastTabClick < DOUBLE_CLICK_MS) {
                    hideAllToolWindows(); // IntelliJ: double click a tab to maximise the editor
                    lastTabClickPath = null;
                    return;
                }
                lastTabClick = now;
                lastTabClickPath = path;
                flush();
                store(s -> s.openFile(path));
            });
            // middle click closes a tab (button presses arrive as generic motion events, not touches)
            chip.setOnGenericMotionListener((v, event) -> {
                if (event.getAction() == icyllis.modernui.view.MotionEvent.ACTION_BUTTON_PRESS
                        && event.getActionButton() == icyllis.modernui.view.MotionEvent.BUTTON_TERTIARY && !pinned) {
                    closeTab(v, path);
                    return true;
                }
                return false;
            });
            Overlays.contextMenu(chip, menu -> {
                menu.add("Copy path").setOnMenuItemClickListener(item -> {
                    EditorTools.copyToClipboard(path);
                    toast("Copied " + path);
                    return true;
                });
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
                menu.add("Split right   Ctrl+\\").setOnMenuItemClickListener(item -> {
                    openSplit(path);
                    return true;
                });
                menu.add("Close all").setOnMenuItemClickListener(item -> {
                    for (String other : state.tabs()) {
                        if (!isPinned(other) && !state.dirtyTabs().contains(other)) {
                            rememberClosed(other);
                            store(s -> s.closeFile(other));
                        }
                    }
                    return true;
                });
                menu.add("Close tabs to the right").setOnMenuItemClickListener(item -> {
                    List<String> order = orderedTabs();
                    for (String other : order.subList(order.indexOf(path) + 1, order.size())) {
                        if (!isPinned(other) && !state.dirtyTabs().contains(other)) {
                            rememberClosed(other);
                            store(s -> s.closeFile(other));
                        }
                    }
                    return true;
                });
                menu.add("Close others").setOnMenuItemClickListener(item -> {
                    for (String other : state.tabs()) {
                        if (!other.equals(path) && !isPinned(other) && !state.dirtyTabs().contains(other)) {
                            store(s -> s.closeFile(other));
                        }
                    }
                    return true;
                });
            });
            tabs.addView(chip, new LinearLayout.LayoutParams(WRAP_CONTENT, chip.dp(34)));
        }
    }

    static IdeIcon.Glyph fileGlyph(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py")) {
            return IdeIcon.Glyph.PYTHON;
        }
        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            return IdeIcon.Glyph.BOOK;
        }
        if (lower.endsWith(".png")) {
            return IdeIcon.Glyph.IMAGE;
        }
        if (lower.endsWith(".ogg") || lower.endsWith(".wav")) {
            return IdeIcon.Glyph.SOUND;
        }
        return IdeIcon.Glyph.CODE;
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
            prompt(anchor, "Close " + path + " and discard unsaved changes?", null, "Discard", ignored -> {
                rememberClosed(path);
                store(s -> s.closeFile(path));
            });
        } else {
            rememberClosed(path);
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
        consoleWriting = true;
        try {
            console.setText(consoleColors(joined));
        } finally {
            consoleWriting = false;
        }
        if (atBottom) {
            consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private String consoleText = "";
    private boolean consoleWriting;

    /** Errors red, warnings yellow, typed input in the accent color, results dimmer: the console reads at a glance. */
    private static final java.util.regex.Pattern CONSOLE_LOCATION = java.util.regex.Pattern.compile(
            "(?:File \"([^\"]+\\.py)\", line (\\d+))|((?:code|library)/[\\w/.-]+\\.py):(\\d+)");

    /** Ctrl+click on a console line naming a file and line (a traceback, a problem) opens it there. */
    private void jumpFromConsole(int offset) {
        String text = console.getText().toString();
        int start = TextOps.lineStart(text, offset);
        var m = CONSOLE_LOCATION.matcher(text.substring(start, TextOps.lineEnd(text, offset)));
        if (!m.find()) {
            return;
        }
        String file = m.group(1) != null ? m.group(1) : m.group(3);
        int line = Integer.parseInt(m.group(1) != null ? m.group(2) : m.group(4)) - 1;
        if (!shownFiles.contains(file)) {
            toast(file + " is not a file of this project");
            return;
        }
        openAt(file, Math.max(0, line));
    }

    private static CharSequence consoleColors(String text) {
        var out = new SpannableString(text);
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            String line = text.substring(start, end);
            int color = line.startsWith("ERROR") || line.contains("Traceback") || line.startsWith("  File ") ? IdeColors.ERROR
                    : line.startsWith("WARN") ? IdeColors.WARNING
                    : line.startsWith(">>> ") ? IdeColors.ACCENT
                    : line.startsWith("=> ") ? IdeColors.TEXT_SECONDARY : 0;
            if (color != 0 && end > start) {
                out.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (end == text.length()) {
                break;
            }
            start = end + 1;
        }
        return out;
    }
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
        int errors = 0;
        for (ValidationProblem p : next.problems()) {
            errors += p.isError() ? 1 : 0;
        }
        for (CodeProblems.Problem p : next.localProblems()) {
            errors += p.severity() == CodeProblems.Severity.ERROR ? 1 : 0;
        }
        int warnings = count - errors;
        java.util.Map<Integer, Integer> marks = new java.util.HashMap<>();
        for (ValidationProblem p : next.problems()) {
            if (p.file().isEmpty() || p.file().equals(shownPath)) {
                marks.merge(p.line(), p.isError() ? 2 : 1, Math::max);
            }
        }
        for (CodeProblems.Problem p : next.localProblems()) {
            if (p.severity() != CodeProblems.Severity.INFO) {
                marks.merge(p.line(), p.severity() == CodeProblems.Severity.ERROR ? 2 : 1, Math::max);
            }
        }
        if (minimap != null) {
            minimap.setMarks(marks);
        }
        if (count == 0) {
            statusProblems.setText("✓ No problems");
            statusProblems.setTextColor(IdeColors.SUCCESS);
        } else {
            statusProblems.setText((errors > 0 ? "✖ " + errors + "  " : "") + (warnings > 0 ? "⚠ " + warnings : ""));
            statusProblems.setTextColor(errors > 0 ? IdeColors.ERROR : IdeColors.WARNING);
        }
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
        CodeView.colorize(code.getText(), shownPath, settings.theme());
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
        TreeSet<Integer> points = shownPath == null ? new TreeSet<>() : settings.breakpoints(projectId(), shownPath);
        var numbers = new StringBuilder();
        int line = 0;
        int start = 0;
        while (true) {
            int end = all.indexOf('\n', start);
            boolean last = end < 0;
            if (last) {
                end = all.length();
            }
            numbers.append(points.contains(line) ? "● " : "").append(marks.contains(line) ? "◆ " : "").append(line + 1);
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
        gutterText = numbers.toString();
        String[] rows = gutterText.toString().split("\n", -1);
        gutterLineStarts = new int[rows.length];
        int at = 0;
        for (int i = 0; i < rows.length; i++) {
            gutterLineStarts[i] = at;
            at += rows[i].length() + 1;
        }
        gutterCaretLine = -1;
        highlightGutterLine();
        if (uiMaker != null && sidePanelShown(uiMaker.view())) {
            uiMaker.refresh(); // the preview follows the text of an open ui/*.json
        }
    }

    /** The caret's line number is drawn brighter, like in IntelliJ. Wrapped lines have empty rows in the gutter. */
    private void highlightGutterLine() {
        if (code == null || gutter == null) {
            return;
        }
        String all = code.getText().toString();
        int line = TextOps.lineOf(all, Math.max(0, code.getSelectionStart()));
        if (line == gutterCaretLine && gutter.getText().length() == gutterText.length()) {
            return;
        }
        gutterCaretLine = line;
        String text = gutterText.toString();
        var spans = new SpannableString(text);
        for (int dot = text.indexOf('●'); dot >= 0; dot = text.indexOf('●', dot + 1)) {
            spans.setSpan(new ForegroundColorSpan(IdeColors.ERROR), dot, dot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // the gutter has one row per logical line when not wrapped; wrapped files skip the highlight
        if (isWrapped() || line >= gutterLineStarts.length) {
            gutter.setText(spans);
            return;
        }
        int start = gutterLineStarts[line];
        int end = text.indexOf('\n', start);
        spans.setSpan(new ForegroundColorSpan(IdeColors.TEXT), start, end < 0 ? text.length() : end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        gutter.setText(spans);
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
        int at = TextOps.lineOf(s.toString(), changeStart);
        boolean changed = false;
        for (TreeSet<Integer> marks : List.of(settings.bookmarks(projectId(), shownPath),
                settings.breakpoints(projectId(), shownPath))) {
            if (marks.isEmpty()) {
                continue;
            }
            TreeSet<Integer> shifted = TextOps.shiftLines(marks, at, removed, added);
            marks.clear();
            marks.addAll(shifted);
            changed = true;
        }
        if (changed) {
            settings.save();
        }
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
        if (keyCode == KeyEvent.KEY_F6 && shift && !ctrl) {
            renameInFile();
            return true;
        }
        if (event.isAltPressed() && !ctrl && shift && keyCode == KeyEvent.KEY_E) {
            executeSelection();
            return true;
        }
        if (event.isAltPressed() && !ctrl && shift && (keyCode == KeyEvent.KEY_UP || keyCode == KeyEvent.KEY_DOWN)) {
            lineEdit(LineEdits.moveLines(code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd(),
                    keyCode == KeyEvent.KEY_UP));
            return true;
        }
        if (event.isAltPressed() && !ctrl) {
            switch (keyCode) {
                case KeyEvent.KEY_1 -> {
                    toggleExplorer();
                    return true;
                }
                case KeyEvent.KEY_2 -> {
                    toggleBottom(PANEL_BOOKMARKS);
                    return true;
                }
                case KeyEvent.KEY_4 -> {
                    toggleBottom(PANEL_CONSOLE);
                    return true;
                }
                case KeyEvent.KEY_5 -> {
                    toggleBottom(PANEL_DEBUG);
                    return true;
                }
                case KeyEvent.KEY_6 -> {
                    toggleBottom(PANEL_PROBLEMS);
                    return true;
                }
                case KeyEvent.KEY_7 -> {
                    tools.showOutline(breadcrumb);
                    return true;
                }
                case KeyEvent.KEY_9 -> {
                    toggleDock(AssetDock.ASSETS);
                    return true;
                }
                case KeyEvent.KEY_8 -> {
                    setBlocksVisible(!blocksVisible());
                    return true;
                }
                case KeyEvent.KEY_0 -> {
                    setCatalogVisible(!catalogVisible());
                    return true;
                }
                default -> {
                }
            }
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
                case KeyEvent.KEY_L -> reformatFile();
                case KeyEvent.KEY_C -> {
                    if (!shift) {
                        return false;
                    }
                    copyReference();
                }
                case KeyEvent.KEY_S -> showSettings();
                default -> {
                    return false;
                }
            }
            return true;
        }
        if (ctrl) {
            switch (keyCode) {
                case KeyEvent.KEY_T -> {
                    if (shift) {
                        reopenClosedTab();
                    } else {
                        tools.symbolSearch();
                    }
                }
                case KeyEvent.KEY_A -> {
                    if (!shift) {
                        return false; // select all
                    }
                    findAction();
                }
                case KeyEvent.KEY_K -> { // Ctrl+Y stays redo
                    if (!shift) {
                        return false;
                    }
                    lineEdit(LineEdits.deleteLines(code.getText().toString(), code.getSelectionStart(), code.getSelectionEnd()));
                }
                case KeyEvent.KEY_G -> goToLinePrompt();
                case KeyEvent.KEY_F12 -> {
                    if (!shift) {
                        return false;
                    }
                    hideAllToolWindows();
                }
                case KeyEvent.KEY_F10 -> { // IntelliJ's "run context configuration"
                    if (!shift) {
                        return false;
                    }
                    tools.runTests(false);
                }
                case KeyEvent.KEY_E -> tools.showRecentLocations();
                case KeyEvent.KEY_D -> {
                    if (shift) {
                        tools.generateDocstring();
                    } else {
                        lineEdit(LineEdits.duplicate(code.getText().toString(), code.getSelectionStart(),
                                code.getSelectionEnd()));
                    }
                }
                case KeyEvent.KEY_S -> save();
                case KeyEvent.KEY_Z -> undoText(shift);
                case KeyEvent.KEY_Y -> undoText(true);
                case KeyEvent.KEY_R -> {
                    if (shift) {
                        recipesAtCaret();
                    } else {
                        run();
                    }
                }
                case KeyEvent.KEY_ENTER, KeyEvent.KEY_KP_ENTER -> {
                    if (shift) {
                        applyEdit(TextOps.splitLine(code.getText().toString(), code.getSelectionStart()));
                    } else {
                        validate();
                    }
                }
                case KeyEvent.KEY_J -> joinLines();
                case KeyEvent.KEY_F -> {
                    if (shift) {
                        findInFiles();
                    } else {
                        openFind(false);
                    }
                }
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
                case KeyEvent.KEY_SLASH -> lineEdit(LineEdits.toggleComment(code.getText().toString(),
                        code.getSelectionStart(), code.getSelectionEnd()));
                case KeyEvent.KEY_BACKSLASH -> {
                    if (splitOpen()) {
                        closeSplit();
                    } else {
                        openSplit(null);
                    }
                }
                case KeyEvent.KEY_F2 -> toggleBookmark();
                case KeyEvent.KEY_F8 -> toggleBreakpoint();
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
            case KeyEvent.KEY_F9 -> {
                if (!shift) {
                    return false;
                }
                debugRun();
                return true;
            }
            case KeyEvent.KEY_F6 -> {
                if (shift) {
                    return false; // Shift+F6 is rename, handled with the other shortcuts
                }
                tools.moveSymbol();
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
            case KeyEvent.KEY_BACKSPACE -> {
                TextOps.Edit back = from == to && !ctrl ? LineEdits.smartBackspace(all, from) : null;
                if (back == null) {
                    return false;
                }
                applyEdit(back);
                return true;
            }
            case KeyEvent.KEY_TAB -> {
                TextOps.Edit template = !shift && from == to ? LineEdits.expandTemplate(all, from) : null;
                if (template != null) {
                    applyEdit(template);
                    return true;
                }
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

    /** Ctrl+F8: a breakpoint records the stack and variables each time the line runs (the game never pauses). */
    private void toggleBreakpoint() {
        if (shownPath == null || !shownPath.endsWith(".py")) {
            return;
        }
        int line = TextOps.lineOf(code.getText().toString(), code.getSelectionStart());
        TreeSet<Integer> points = settings.breakpoints(projectId(), shownPath);
        if (!points.remove(line)) {
            points.add(line);
        }
        settings.save();
        updateGutter();
        tools.syncBreakpoints();
    }

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
        var textHistory = shownPath == null ? null : history(shownPath);
        m.add("Undo   Ctrl+Z").setEnabled(textHistory != null && textHistory.canUndo())
                .setOnMenuItemClickListener(i -> run(() -> undoText(false)));
        m.add("Redo   Ctrl+Y").setEnabled(textHistory != null && textHistory.canRedo())
                .setOnMenuItemClickListener(i -> run(() -> undoText(true)));
        m.add(fileUndo.isEmpty() ? "Undo file operation" : "Undo: " + fileUndo.peek().label())
                .setEnabled(!fileUndo.isEmpty()).setOnMenuItemClickListener(i -> run(() -> undoFileAction(false)));
        m.add(fileRedo.isEmpty() ? "Redo file operation" : "Redo: " + fileRedo.peek().label())
                .setEnabled(!fileRedo.isEmpty()).setOnMenuItemClickListener(i -> run(() -> undoFileAction(true)));
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
        m.add("Move function / class to another file...   F6").setOnMenuItemClickListener(i -> run(tools::moveSymbol));
        m.add("Asset usage report...").setOnMenuItemClickListener(i -> run(tools::assetUsage));
        m.add("Why isn't my block showing?...").setOnMenuItemClickListener(i -> run(tools::explain));
        m.add("Handler profiler...").setOnMenuItemClickListener(i -> run(tools::profiler));
        m.add("Event tracer").setOnMenuItemClickListener(i -> run(() -> openPanel(PANEL_EVENTS)));
        m.add("Debugger   Alt+5").setOnMenuItemClickListener(i -> run(() -> openPanel(PANEL_DEBUG)));
        m.add("Toggle breakpoint   Ctrl+F8").setOnMenuItemClickListener(i -> run(this::toggleBreakpoint));
        menu.show();
    }

    private void showViewMenu(View anchor) {
        var menu = Overlays.menu(anchor);
        Menu m = menu.getMenu();
        m.add("Project   Alt+1").setCheckable(true).setChecked(settings.explorerVisible)
                .setOnMenuItemClickListener(i -> run(this::toggleExplorer));
        m.add("Bottom panel").setCheckable(true).setChecked(settings.bottomVisible)
                .setOnMenuItemClickListener(i -> run(() -> toggleBottom(settings.bottomVisible ? panel : PANEL_CONSOLE)));
        m.add("Split editor right   Ctrl+\\").setCheckable(true).setChecked(splitOpen())
                .setOnMenuItemClickListener(i -> run(() -> {
                    if (splitOpen()) {
                        closeSplit();
                    } else {
                        openSplit(null);
                    }
                }));
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
        var input = CraftPyFragment.field(context);
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
        openPanel(PANEL_PROBLEMS);
    }

    /** Run with breakpoints: they record hits, and the Debug panel opens. A plain Run ignores breakpoints. */
    private void debugRun() {
        run(true);
        openPanel(PANEL_DEBUG);
    }

    private void run() {
        run(false);
    }

    private void run(boolean debug) {
        prepareSave();
        store(s -> {
            String id = s.activeProjectId();
            if (id == null) {
                s.append("No project open - pick one on the Home page.");
                return;
            }
            s.saveActiveFile();
            var summary = s.find(id);
            if (summary != null && "client".equals(summary.side)) {
                // a client project runs in the Python of this computer (HUD, keys, windows), not in the world
                s.append("Running '" + id + "' on this computer ...");
                net.xmilon.craftpy.client.ClientPython.get().runProject(id);
                return;
            }
            tools.syncBreakpoints(debug); // before the run, so lines of main.py and its imports record too
            s.request(ProjectAction.LOAD, id, s.debugMode() ? "{\"debug\":true}" : "");
            s.append((debug ? "Debugging '" : "Running '") + id + "' ...");
        });
        if (!debug) {
            openPanel(PANEL_CONSOLE);
        }
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
            var summary = s.find(id);
            String projectSide = summary == null ? "both" : summary.side;
            host.post(() -> BuildDialog.open(host, id, name, version, installed, projectSide, request -> {
                store(st -> st.buildProject(request));
                openPanel(PANEL_CONSOLE);
            }));
        });
    }

    private void addNewItems(Menu menu, String folder) {
        menu.add("New file...").setOnMenuItemClickListener(item -> {
            prompt(explorer, "New file", folder + "new_file.py", "Create", path -> {
                store(s -> s.createEntry(path, "file"));
                recordAction("New file " + path, () -> store(s -> {
                    s.closeFile(path);
                    s.deleteEntry(path);
                }), () -> restoreEntry(path));
            });
            return true;
        });
        menu.add("New folder...").setOnMenuItemClickListener(item -> {
            prompt(explorer, "New folder", folder + "new_folder", "Create", path -> {
                store(s -> s.createEntry(path, "folder"));
                recordAction("New folder " + path, () -> store(s -> s.deleteEntry(path)), () -> restoreEntry(path));
            });
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
        IdeHost.get().execute(() -> {
            byte[] data = AssetFetch.bytes(kind, id);
            if (data == null) {
                code.post(() -> toast("Could not read " + id));
                return;
            }
            ClientProjectStore store = ClientProjectStore.getInstance();
            byte[] meta = kind == AssetKind.TEXTURE ? AssetFetch.textureMeta(id) : null; // animated textures
            // the store refreshes the file list once the server wrote the file; say "Copied" only then
            store.uploadAsset(project, target, data, error -> code.post(() -> {
                if (error != null) {
                    toast("Not copied: " + error);
                    return;
                }
                toast("Copied " + id + " to " + target);
                if (meta != null) {
                    IdeHost.get().execute(() -> ClientProjectStore.getInstance().uploadAsset(project, target + ".mcmeta", meta));
                }
            }));
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
