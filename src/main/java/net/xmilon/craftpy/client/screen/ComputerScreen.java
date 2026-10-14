package net.xmilon.craftpy.client.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.assets.AssetReference;
import net.xmilon.craftpy.assets.AssetReferences;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.CodeProblems;
import net.xmilon.craftpy.client.assets.AssetIndex;
import net.xmilon.craftpy.client.ide.CodeEditorWidget;
import net.xmilon.craftpy.client.ide.EditorTabs;
import net.xmilon.craftpy.client.ide.IconButton;
import net.xmilon.craftpy.client.ide.IdeColors;
import net.xmilon.craftpy.client.ide.MarkdownRenderer;
import net.xmilon.craftpy.client.ide.ScrollView;
import net.xmilon.craftpy.client.ide.ScrollView.Row;
import net.xmilon.craftpy.menu.ComputerMenu;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.python.PyRuntime;

public class ComputerScreen extends Screen implements MenuAccess<ComputerMenu> {
    // ------------------------------------------------------------------
    // Layout metrics (official widgets are positioned by these regions)
    // ------------------------------------------------------------------
    private static final int MENU_BAR_H = 26;
    private static final int TOOLBAR_H = 30;
    private static final int STATUS_H = 20;
    private static final int EXPLORER_W = 200;
    private static final int TAB_H = 24;
    private static final int BOTTOM_TAB_H = 20;
    private static final int BOTTOM_INPUT_H = 16;
    private static final String CONSOLE_PROMPT = ">>> ";

    private static final String[] MENU_TITLES = { "File", "Edit", "Run", "Assets", "Window", "Help" };
    private static final int MENU_ITEM_HEIGHT = 13;
    private static final int CONTEXT_ITEM_HEIGHT = 13;

    /** Focus ids: 0 = editor, 2 = search box, 3 = console input, 4 = modal dialog. */
    private static final int FOCUS_EDITOR = 0;
    private static final int FOCUS_SEARCH = 2;
    private static final int FOCUS_CONSOLE = 3;
    private static final int FOCUS_MODAL = 4;

    private static final Pattern SYMBOL_DEF = Pattern.compile("^\\s*(?:def|class)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern SYMBOL_DECL = Pattern.compile("@(?:block|item|entity|effect)\\(\\s*\"([^\"]+)\"");
    private static final Pattern SYMBOL_ASSIGN = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*=");
    private static final Pattern OPEN_ARG = Pattern.compile("^open\\s+(.*)$", Pattern.CASE_INSENSITIVE);

    private final ComputerMenu menu;
    private final Inventory inventory;

    // Core editor pair: the real editor (business logic) wrapped in a widget.
    private CodeEditor editor;
    private CodeEditorWidget editorWidget;

    private EditorTabs editorTabs;
    private LinearLayout toolbarLayout;
    private IdeMenuBar menuBar;
    private ToolbarBackground toolbarBg;
    private ExplorerHeader explorerHeader;
    private ExplorerPanel explorerPanel;
    private BottomTabBar bottomTabBar;
    private PanelScrollView bottomPanel;
    private StatusView statusView;
    private MenuPopup menuPopup;
    private ContextPopup contextPopup;
    private PromptDialog promptDialog;
    private ConfirmDialog confirmDialog;
    private PalettePopup palettePopup;

    private EditBox searchBox;
    private EditBox replaceBox;
    private Button replaceButton;
    private Button replaceAllButton;
    private boolean searchOpen;

    private int bottomTab;
    private int focus;

    /** Bottom-tab order and titles; indices double as tab ids (Settings is id 5). */
    private static final int[] BOTTOM_TAB_IDS = { 0, 1, 2, 5 };
    private static final String[] BOTTOM_TAB_TITLES = { "Console", "Problems", "Preview", "Settings" };
    /** Tabs currently shown in the bottom panel; empty hides the panel entirely. */
    private final java.util.LinkedHashSet<Integer> openBottomTabs =
            new java.util.LinkedHashSet<>(java.util.List.of(0, 1, 2, 5));

    private final List<List<MenuItem>> menus = new ArrayList<>();
    private int openMenu = -1;

    private final Map<String, Integer> symbolLines = new HashMap<>();

    private String displayedFile;
    private boolean syncGuard;
    private boolean refreshing;
    private String pendingDefinitionFile;
    private int pendingDefinitionLine = -1;
    /** Stable listener instance; a new lambda each init() would never be removed. */
    private final Runnable storeListener = this::refreshFromCache;

    // Explorer right-click context menu.
    private String contextPath;
    private List<MenuItem> contextItems = new ArrayList<>();

    // Modal text prompt (rename / new file / new folder).
    private boolean promptOpen;
    private String promptTitle = "";
    private EditBox promptBox;
    private Consumer<String> promptAccept;

    // Delete confirmation.
    private boolean confirmOpen;
    private String confirmMessage = "";
    private Runnable confirmAccept;

    // Console REPL input.
    private EditBox consoleInput;
    private final List<String> consoleHistory = new ArrayList<>();
    private int historyIndex;
    private String historyDraft = "";
    /** Grey "ghost" completion suffix drawn after the typed text. */
    private String consoleHint = "";

    private static final String[] CONSOLE_COMMANDS = {
            "clear", "help", "run", "validate", "open", "files", "api", "debug", "reset"
    };
    private static final String[] CONSOLE_API_NAMES = {
            "block", "item", "effect", "on", "tex", "snd", "mdl", "loot_self",
            "recipe_shaped", "recipe_shapeless", "recipe_smelting", "loot_table", "tag", "lang"
    };
    private static final String[] CONSOLE_BUILTINS = {
            "print", "len", "range", "int", "float", "str", "bool", "list", "dict", "tuple", "set",
            "abs", "min", "max", "sum", "sorted", "enumerate", "zip", "reversed", "round", "type", "repr"
    };

    private record MenuItem(String label, Runnable action) {
    }

    public ComputerScreen(ComputerMenu menu, Inventory inventory, Component title) {
        super(title);
        this.menu = menu;
        this.inventory = inventory;
    }

    @Override
    public ComputerMenu getMenu() {
        return this.menu;
    }

    public Inventory getInventory() {
        return this.inventory;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Widget construction
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        // Apply saved theme
        String themeName = net.xmilon.craftpy.client.ClientSettings.getString(
                net.xmilon.craftpy.client.ClientSettings.THEME, "dark");
        net.xmilon.craftpy.client.ui.Theme.setCurrent(net.xmilon.craftpy.client.ui.Theme.getPreset(themeName));

        // init() also runs on window resize / GUI-scale change. Open popups are
        // dropped and the current file is reloaded into the fresh editor.
        this.promptOpen = false;
        this.confirmOpen = false;
        this.openMenu = -1;
        this.focus = FOCUS_EDITOR;

        ClientProjectStore store = ClientProjectStore.getInstance();
        store.setListener(storeListener);
        store.refresh();

        if (this.editor == null) {
            this.editor = new CodeEditor(this.font);
            this.editor.setChangeListener(() -> {
                if (!syncGuard) {
                    String active = store.activeFile();
                    if (active != null) {
                        store.markDirty(active, this.editor.getContent());
                    }
                }
            });
            this.editor.setAssetPickerRequest((line, start, end, kind, existing) ->
                    openAssetPicker(line, start, end, kind, existing));
            this.editor.setDefinitionLookup(this::goToDefinition);
            this.editor.setCompletionProvider((code, line, col, word) -> {
                PyRuntime.get().complete(code, line, col).thenAccept((List<String> results) -> {
                    List<CodeEditor.Suggestion> suggestionsList = new ArrayList<>();
                    for (String s : results) {
                        suggestionsList.add(new CodeEditor.Suggestion(s, "", "", s, s.length(), null));
                    }
                    this.minecraft.execute(() -> this.editor.setExternalSuggestions(suggestionsList, word));
                });
            });
        }

        this.clearWidgets();
        this.buildWidgets();
        this.buildMenus();
        this.buildToolbar();
        this.buildOverlays();

        // Overlays now exist; reset their open state.
        this.contextOpen(false);
        this.openMenu(-1);

        store.setDefinitionCallback(this::onDefinitionFound);

        layoutWidgets();
        loadApiDoc();
        refreshFromCache();
    }

    private void buildWidgets() {
        this.editorWidget = new CodeEditorWidget(this.editor);

        this.menuBar = new IdeMenuBar();
        this.addRenderableWidget(this.menuBar);

        this.toolbarBg = new ToolbarBackground();
        this.addRenderableWidget(this.toolbarBg);

        this.explorerHeader = new ExplorerHeader();
        this.addRenderableWidget(this.explorerHeader);

        this.explorerPanel = new ExplorerPanel();
        this.addRenderableWidget(this.explorerPanel);

        this.editorTabs = new EditorTabs(new EditorTabs.Delegate() {
            @Override
            public List<EditorTabs.TabInfo> tabs() {
                List<EditorTabs.TabInfo> out = new ArrayList<>();
                ClientProjectStore s = ClientProjectStore.getInstance();
                for (String path : s.openTabs()) {
                    String label = path.substring(Math.max(0, path.lastIndexOf('/') + 1));
                    out.add(new EditorTabs.TabInfo(label, s.isDirty(path), path));
                }
                return out;
            }

            @Override
            public int activeTab() {
                String active = ClientProjectStore.getInstance().activeFile();
                List<String> tabs = ClientProjectStore.getInstance().openTabs();
                return active == null ? -1 : tabs.indexOf(active);
            }

            @Override
            public void onSelect(int index) {
                ClientProjectStore.getInstance().openFile(ClientProjectStore.getInstance().openTabs().get(index));
            }

            @Override
            public void onClose(int index) {
                requestCloseTab(ClientProjectStore.getInstance().openTabs().get(index));
            }

            @Override
            public void onNewFile() {
                promptNewFile("");
            }
        });
        this.addRenderableWidget(this.editorTabs);

        this.addRenderableWidget(this.editorWidget);

        this.bottomTabBar = new BottomTabBar();
        this.addRenderableWidget(this.bottomTabBar);

        this.bottomPanel = new PanelScrollView();
        this.addRenderableWidget(this.bottomPanel);

        this.consoleInput = new EditBox(this.font, 0, 0, 120, 12, Component.literal("console"));
        this.consoleInput.setMaxLength(400);
        this.consoleInput.setBordered(false);
        this.consoleInput.setTextColor(IdeColors.TEXT);
        this.consoleInput.setHint(Component.literal("Python or command; Tab completes"));
        this.consoleInput.setVisible(false);
        this.consoleInput.setResponder(this::updateConsoleHint);
        this.addRenderableWidget(this.consoleInput);

        this.searchBox = new EditBox(this.font, 0, 0, 120, 14, Component.literal("search"));
        this.searchBox.setMaxLength(200);
        this.searchBox.setHint(Component.literal("find"));
        this.searchBox.setResponder(query -> this.editor.setSearchQuery(query));
        this.addRenderableWidget(this.searchBox);

        this.replaceBox = new EditBox(this.font, 0, 0, 100, 14, Component.literal("replace"));
        this.replaceBox.setMaxLength(200);
        this.replaceBox.setHint(Component.literal("replace"));
        this.replaceBox.visible = false;
        this.addRenderableWidget(this.replaceBox);

        this.replaceButton = Button.builder(Component.literal("Replace"), b -> replaceNext())
                .bounds(0, 0, 56, 14).build();
        this.replaceButton.visible = false;
        this.addRenderableWidget(this.replaceButton);

        this.replaceAllButton = Button.builder(Component.literal("All"), b -> replaceAll())
                .bounds(0, 0, 40, 14).build();
        this.replaceAllButton.visible = false;
        this.addRenderableWidget(this.replaceAllButton);

        this.promptBox = new EditBox(this.font, 0, 0, 200, 14, Component.literal("input"));
        this.promptBox.setMaxLength(240);
        this.promptBox.setVisible(false);
        this.addRenderableWidget(this.promptBox);

        this.statusView = new StatusView();
        this.addRenderableWidget(this.statusView);

        this.searchBox.setVisible(false);
        this.searchBox.setPosition(-200, -200);
        this.replaceBox.visible = false;
        this.replaceButton.visible = false;
        this.replaceAllButton.visible = false;
        this.promptBox.setVisible(false);
        this.promptBox.setPosition(-200, -200);
    }

    /**
     * Overlays are added last so they render on top and (with the reversed
     * dispatch in {@link #dispatchClick}) also receive input first.
     */
    private void buildOverlays() {
        this.menuPopup = new MenuPopup();
        this.addRenderableWidget(this.menuPopup);
        this.contextPopup = new ContextPopup();
        this.addRenderableWidget(this.contextPopup);
        this.promptDialog = new PromptDialog();
        this.addRenderableWidget(this.promptDialog);
        this.confirmDialog = new ConfirmDialog();
        this.addRenderableWidget(this.confirmDialog);
        this.palettePopup = new PalettePopup();
        this.addRenderableWidget(this.palettePopup);
    }

    private void buildToolbar() {
        this.toolbarLayout = LinearLayout.horizontal().spacing(2);
        ClientProjectStore store = ClientProjectStore.getInstance();

        this.toolbarLayout.addChild(new IconButton(IconButton.Icon.BUILD, "Build", Component.literal("Build the project (Ctrl+Enter)"), this::buildProject));
        this.toolbarLayout.addChild(new IconButton(IconButton.Icon.PLAY, "Run", Component.literal("Save and run the project (Ctrl+Shift+R)"), this::runProject));
        this.toolbarLayout.addChild(new IconButton(IconButton.Icon.STOP, "Stop", Component.literal("Interrupt the running project"), store::interrupt));
        this.toolbarLayout.addChild(SpacerElement.width(8));
        this.toolbarLayout.addChild(new IconButton(IconButton.Icon.SAVE, Component.literal("Save the active file (Ctrl+S)"), store::saveActiveFile));
        this.toolbarLayout.addChild(new IconButton(IconButton.Icon.VALIDATE, Component.literal("Validate the active file (Ctrl+Enter)"), store::validateActiveFile));
        this.toolbarLayout.addChild(SpacerElement.width(8));
        this.toolbarLayout.addChild(new IconButton(IconButton.Icon.ASSETS, "Assets", Component.literal("Open the asset bank"), this::openAssetPickerAtCursor));
        this.toolbarLayout.addChild(new IconButton(IconButton.Icon.FIND, Component.literal("Find in file (Ctrl+F)"), this::toggleSearch));
        this.toolbarLayout.addChild(SpacerElement.width(8));
        this.toolbarLayout.addChild(new IconButton(IconButton.Icon.REFRESH, Component.literal("Show the command palette (Ctrl+Shift+P)"), this::openCommandPalette));
        toolbarLayout.arrangeElements();
        toolbarLayout.setPosition(8, MENU_BAR_H + (TOOLBAR_H - 22) / 2);
        toolbarLayout.visitWidgets(widget -> this.addRenderableWidget(widget));
    }

    private void buildMenus() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        this.menus.clear();
        this.menus.add(List.of(
                new MenuItem("Projects...", this::goToChooser),
                new MenuItem("Save  (Ctrl+S)", store::saveActiveFile),
                new MenuItem("Validate  (Ctrl+Enter)", store::validateActiveFile),
                new MenuItem("Close  (Esc)", this::onClose)));
        this.menus.add(List.of(
                new MenuItem("Find  (Ctrl+F)", this::toggleSearch),
                new MenuItem("Undo  (Ctrl+Z)", () -> this.editor.undo()),
                new MenuItem("Redo  (Ctrl+Y)", () -> this.editor.redo()),
                new MenuItem("Select All  (Ctrl+A)", () -> this.editor.selectAll()),
                new MenuItem("Format Document", this::formatDocument),
                new MenuItem("Clear Console", store::clearLog),
                new MenuItem("Interrupt  (Ctrl+C)", store::interrupt)));
        this.menus.add(List.of(
                new MenuItem("Run Project  (Ctrl+Shift+R)", this::runProject),
                new MenuItem("Validate Project", store::validateActiveFile),
                new MenuItem("Show Console", () -> showBottomTab(0)),
                new MenuItem("Show Problems", () -> showBottomTab(1))));
        this.menus.add(List.of(
                new MenuItem("Open Asset Bank...", this::openAssetPickerAtCursor),
                new MenuItem("Insert Texture...", () -> openAssetPickerKind(AssetKind.TEXTURE)),
                new MenuItem("Insert Sound...", () -> openAssetPickerKind(AssetKind.SOUND)),
                new MenuItem("Insert Model...", () -> openAssetPickerKind(AssetKind.MODEL))));
        List<MenuItem> windowItems = new ArrayList<>();
        for (int i = 0; i < BOTTOM_TAB_IDS.length; i++) {
            int id = BOTTOM_TAB_IDS[i];
            boolean open = this.openBottomTabs.contains(id);
            windowItems.add(new MenuItem((open ? "\u2713 " : "   ") + BOTTOM_TAB_TITLES[i],
                    () -> toggleBottomTab(id)));
        }
        this.menus.add(windowItems);
        this.menus.add(List.of(
                new MenuItem("API Reference", this::openApiDocAsFile),
                new MenuItem("Settings", () -> showBottomTab(5)),
                new MenuItem("Controls & Tips", this::showHelp),
                new MenuItem("About CraftPy", () -> ClientProjectStore.getInstance()
                        .append("CraftPy IDE - write Python to add blocks, items, recipes and more."))));
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void formatDocument() {
        if ("json".equals(this.editor.getLanguage())) {
            String content = this.editor.getContent();
            try {
                com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                Object json = gson.fromJson(content, Object.class);
                this.editor.setContent(gson.toJson(json));
            } catch (Exception e) {
                ClientProjectStore.getInstance().append("ERROR: Failed to format JSON: " + e.getMessage());
                showBottomTab(0);
            }
        } else {
            ClientProjectStore.getInstance().append("Formatting is only supported for JSON files currently.");
            showBottomTab(0);
        }
    }

    private void runProject() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        if (store.activeProjectId() == null) {
            store.append("No project loaded - pick one via File > Projects...");
            showBottomTab(0);
            return;
        }
        store.saveActiveFile();
        store.request(ProjectAction.LOAD, store.activeProjectId(), store.debugMode() ? "{\"debug\":true}" : "");
        store.append("Running '" + store.activeProjectId() + "' ..."
                + (store.debugMode() ? " (debug trace on)" : ""));
        showBottomTab(0);
    }

    private void buildProject() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        if (store.activeProjectId() == null) {
            store.append("No project loaded - pick one via File > Projects...");
            showBottomTab(0);
            return;
        }
        store.saveActiveFile();
        store.buildProject(null);
        showBottomTab(0);
    }

    private void showBottomTab(int tab) {
        if (tab >= 0) {
            this.openBottomTabs.add(tab);
        }
        this.bottomTab = tab;
        this.bottomPanel.setRows(this::bottomRows, tab == 0);
        layoutWidgets();
    }

    /** Opens or closes a bottom tab from the Window menu. */
    private void toggleBottomTab(int id) {
        if (this.openBottomTabs.contains(id)) {
            closeBottomTab(id);
        } else {
            showBottomTab(id);
        }
        buildMenus();
    }

    /** Closes a bottom tab; when it was active, picks the next open one (or hides the panel). */
    private void closeBottomTab(int id) {
        this.openBottomTabs.remove(id);
        if (this.bottomTab == id) {
            this.bottomTab = this.openBottomTabs.isEmpty() ? -1 : this.openBottomTabs.iterator().next();
        }
        this.bottomPanel.setRows(this::bottomRows, this.bottomTab == 0);
        layoutWidgets();
        buildMenus();
    }

    private boolean bottomPanelVisible() {
        return this.bottomTab >= 0 && !this.openBottomTabs.isEmpty();
    }

    private void refreshBottomPanel() {
        this.bottomPanel.setRows(this::bottomRows, this.bottomTab == 0);
    }

    private List<ScrollView.Row> bottomRows() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        switch (this.bottomTab) {
            case 0 -> {
                return consoleRows();
            }
            case 1 -> {
                return problemsRows(store);
            }
            case 2 -> {
                return markdownRows();
            }
            default -> {
                return settingsRows();
            }
        }
    }

    private List<ScrollView.Row> consoleRows() {
        List<ScrollView.Row> rows = new ArrayList<>();
        for (String line : ClientProjectStore.getInstance().log()) {
            String text = line;
            int color = IdeColors.TEXT;
            if (line.contains("ERROR") || line.contains("Error")) {
                text = line.split("\n")[0];
                color = IdeColors.ERROR;
            } else if (line.startsWith("> ")) {
                color = IdeColors.ACCENT;
            }
            rows.add(new ScrollView.Row(text, color, false));
        }
        if (rows.isEmpty()) {
            rows.add(new ScrollView.Row("Console is empty. Use Run or Validate, or type a command below ('help').",
                    IdeColors.MUTED, false));
        }
        rows.add(new ScrollView.Row(" ", IdeColors.MUTED, false));
        return rows;
    }

    private List<ScrollView.Row> problemsRows(ClientProjectStore store) {
        List<ScrollView.Row> rows = new ArrayList<>();
        for (net.xmilon.craftpy.python.ValidationProblem problem : store.validationProblems()) {
            int color = problem.isError() ? IdeColors.ERROR : IdeColors.WARNING;
            String label = (problem.line() + 1) + ":" + problem.column() + " " + problem.message()
                    + (problem.file().isEmpty() ? "" : " [" + problem.file() + "]");
            rows.add(new ScrollView.Row(label, color, false));
        }
        for (CodeProblems.Problem problem : CodeProblems.snapshot()) {
            int color = switch (problem.severity()) {
                case ERROR -> IdeColors.ERROR;
                case WARNING -> IdeColors.WARNING;
                case INFO -> IdeColors.MUTED;
            };
            rows.add(new ScrollView.Row((problem.line() + 1) + ":" + problem.column() + " " + problem.message(), color, false));
        }
        String active = store.activeFile();
        if (active != null) {
            AssetIndex.get().ensureBuilt();
            String content = store.fileContent(active);
            if (content != null) {
                int lineNo = 0;
                for (String line : content.split("\n", -1)) {
                    for (AssetReference ref : AssetReferences.parse(line)) {
                        if (!AssetIndex.get().exists(ref.kind(), ref.value())) {
                            rows.add(new ScrollView.Row((lineNo + 1) + " missing "
                                    + ref.kind().name().toLowerCase(Locale.ROOT) + " '" + ref.value() + "'",
                                    IdeColors.WARNING, false));
                        }
                    }
                    lineNo++;
                }
            }
        }
        if (rows.isEmpty()) {
            rows.add(new ScrollView.Row("No problems detected. Use Validate (Ctrl+Enter) to run the Python compiler.",
                    IdeColors.MUTED, false));
        }
        return rows;
    }

    private List<ScrollView.Row> markdownRows() {
        String content = this.editor.getContent();
        if (content.isEmpty()) {
            List<ScrollView.Row> rows = new ArrayList<>();
            rows.add(new ScrollView.Row("No content to preview", IdeColors.MUTED, false));
            return rows;
        }
        String language = this.editor.getLanguage();
        if ("md".equals(language) || "markdown".equals(language)) {
            return MarkdownRenderer.render(content);
        }
        List<ScrollView.Row> rows = new ArrayList<>();
        for (String line : content.split("\n")) {
            rows.add(new ScrollView.Row(line, IdeColors.TEXT, false));
        }
        return rows;
    }

    private List<ScrollView.Row> settingsRows() {
        List<ScrollView.Row> rows = new ArrayList<>();
        rows.add(new ScrollView.Row("KEYMAP SETTINGS", IdeColors.ACCENT, false));
        String[][] keys = {
                { "Ctrl + S", "Save active file" },
                { "Ctrl + Enter", "Validate project" },
                { "Ctrl + Shift + R", "Run project" },
                { "Ctrl + Shift + P", "Command Palette" },
                { "Ctrl + F", "Toggle search" },
                { "Ctrl + W", "Close active tab" },
                { "F12", "Go to definition" }
        };
        for (String[] key : keys) {
            rows.add(new ScrollView.Row(String.format("%-18s %s", key[0], key[1]), IdeColors.TEXT, false));
        }
        return rows;
    }

    /** Closes a tab; unsaved buffers need an explicit confirmation first. */
    private void requestCloseTab(String path) {
        if (path == null) {
            return;
        }
        ClientProjectStore store = ClientProjectStore.getInstance();
        if (store.isDirty(path)) {
            openConfirm("Close '" + path + "' without saving? Unsaved edits will be lost.",
                    () -> store.closeFile(path));
        } else {
            store.closeFile(path);
        }
    }

    private void toggleSearch() {
        this.searchOpen = !this.searchOpen;
        if (this.searchOpen) {
            this.focus = FOCUS_SEARCH;
            this.setFocused(this.searchBox);
            this.searchBox.setFocused(true);
            this.searchBox.setValue("");
            this.editor.setSearchQuery("");
        } else {
            this.focus = FOCUS_EDITOR;
            this.searchBox.setFocused(false);
            this.replaceBox.setFocused(false);
            this.searchBox.setValue("");
            this.replaceBox.setValue("");
            this.editor.setSearchQuery("");
            this.setFocused(this.editorWidget);
            this.editorWidget.setFocused(true);
        }
    }

    /** Replaces the current search match (replace box Enter / Replace button). */
    private void replaceNext() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        if (store.isVirtual(store.activeFile())) {
            store.append("'" + store.activeFile() + "' is a built-in reference - it is read-only.");
            return;
        }
        if (this.editor.replaceCurrent(this.replaceBox.getValue()) > 0) {
            this.focus = FOCUS_SEARCH;
            this.editor.setFocused(false);
            this.setFocused(this.replaceBox);
        }
    }

    /** Replaces every occurrence of the search query. */
    private void replaceAll() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        if (store.isVirtual(store.activeFile())) {
            store.append("'" + store.activeFile() + "' is a built-in reference - it is read-only.");
            return;
        }
        int replaced = this.editor.replaceAll(this.replaceBox.getValue());
        if (replaced > 0) {
            store.append("Replaced " + replaced + " occurrence(s) of '" + this.searchBox.getValue() + "'.");
        } else {
            store.append("No matches for '" + this.searchBox.getValue() + "'.");
        }
    }

    @Override
    public void onClose() {
        ClientProjectStore.getInstance().clearListener(storeListener);
        ClientProjectStore.getInstance().setDefinitionCallback(null);
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.closeContainer();
        }
        super.onClose();
    }

    @Override
    public void removed() {
        ClientProjectStore.getInstance().clearListener(storeListener);
        ClientProjectStore.getInstance().setDefinitionCallback(null);
        super.removed();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private void layoutWidgets() {
        int w = this.width;
        int h = this.height;
        int top = MENU_BAR_H + TOOLBAR_H;
        int statusTop = h - STATUS_H;
        boolean panelVisible = bottomPanelVisible();
        int bottomTop = Math.max(top + TAB_H + 40, statusTop - Math.max(96, h / 4));
        int contentBottom = panelVisible ? bottomTop : statusTop;

        this.menuBar.setPosition(0, 0);
        this.menuBar.setSize(w, MENU_BAR_H);
        this.toolbarBg.setPosition(0, MENU_BAR_H);
        this.toolbarBg.setSize(w, TOOLBAR_H);
        this.explorerHeader.setPosition(0, top);
        this.explorerHeader.setSize(EXPLORER_W, 28);
        this.explorerPanel.setPosition(0, top + 28);
        this.explorerPanel.setSize(EXPLORER_W, contentBottom - (top + 28));
        this.editorTabs.setPosition(EXPLORER_W, top);
        this.editorTabs.setSize(w - EXPLORER_W, TAB_H);
        this.editorWidget.setPosition(EXPLORER_W, top + TAB_H);
        this.editorWidget.setSize(w - EXPLORER_W, contentBottom - (top + TAB_H));
        this.bottomTabBar.visible = panelVisible;
        this.bottomTabBar.setPosition(0, bottomTop);
        this.bottomTabBar.setSize(w, BOTTOM_TAB_H);
        this.bottomPanel.visible = panelVisible;
        this.bottomPanel.setPosition(0, bottomTop + BOTTOM_TAB_H);
        this.bottomPanel.setSize(w, statusTop - (bottomTop + BOTTOM_TAB_H));
        this.statusView.setPosition(0, statusTop);
        this.statusView.setSize(w, STATUS_H);
    }

    /** Positions floating widgets (search bar, console input) each frame. */
    private void positionFloatingWidgets() {
        int w = this.width;
        int top = MENU_BAR_H + TOOLBAR_H;

        // Search / replace bar sits in the tab strip row of the editor area.
        if (this.searchOpen) {
            int sx = EXPLORER_W + 8;
            int sy = top + 3;
            this.searchBox.visible = true;
            this.searchBox.setX(sx);
            this.searchBox.setY(sy);
            this.searchBox.setWidth(Math.min(110, Math.max(60, w - EXPLORER_W - 320)));
            int rx = sx + this.searchBox.getWidth() + 4;
            this.replaceBox.visible = true;
            this.replaceBox.setX(rx);
            this.replaceBox.setY(sy);
            this.replaceBox.setWidth(Math.min(100, Math.max(40, this.searchBox.getWidth() - 60)));
            this.replaceButton.visible = true;
            this.replaceButton.setX(rx + this.replaceBox.getWidth() + 4);
            this.replaceButton.setY(sy);
            this.replaceAllButton.visible = true;
            this.replaceAllButton.setX(this.replaceButton.getX() + 56);
            this.replaceAllButton.setY(sy);
        } else {
            this.searchBox.visible = false;
            this.replaceBox.visible = false;
            this.replaceButton.visible = false;
            this.replaceAllButton.visible = false;
            this.searchBox.setPosition(-200, -200);
            this.replaceBox.setPosition(-200, -200);
            this.replaceButton.setPosition(-200, -200);
            this.replaceAllButton.setPosition(-200, -200);
        }

        // Console input is embedded in the foot of the bottom panel (Console tab only).
        boolean consoleVisible = this.bottomTab == 0 && !this.promptOpen && !this.confirmOpen;
        this.consoleInput.visible = consoleVisible;
        int promptW = this.font.width(CONSOLE_PROMPT);
        this.consoleInput.setX(8 + promptW);
        this.consoleInput.setY(this.height - STATUS_H - BOTTOM_INPUT_H + 3);
        this.consoleInput.setWidth(w - 16 - promptW);

        // Modal prompt edit box is positioned inside the dialog panel.
        this.promptBox.visible = this.promptOpen;
        if (this.promptOpen) {
            int pw = Math.min(260, w - 20);
            int px = (w - pw) / 2;
            int py = this.height / 2 - 30;
            this.promptBox.setX(px + 8);
            this.promptBox.setY(py + 22);
            this.promptBox.setWidth(pw - 16);
        } else {
            this.promptBox.setPosition(-200, -200);
        }
    }

    // ------------------------------------------------------------------
    // State sync
    // ------------------------------------------------------------------

    private void refreshFromCache() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        try {
            ClientProjectStore store = ClientProjectStore.getInstance();
            String selected = store.selectedId();
            if (selected != null && store.find(selected) == null) {
                store.select(null);
            }
            String active = store.activeFile();
            if (active != null && !active.equals(this.displayedFile)) {
                this.displayedFile = active;
                int dot = active.lastIndexOf('.');
                String language = dot >= 0 ? active.substring(dot + 1).toLowerCase(Locale.ROOT) : "python";
                this.editor.setLanguage(language);
                syncGuard = true;
                this.editor.setContent(store.fileContent(active));
                syncGuard = false;
                focus = FOCUS_EDITOR;
                this.setFocused(this.editorWidget);
                this.editorWidget.setFocused(true);
                if ("md".equals(language) || "markdown".equals(language)) {
                    showBottomTab(2);
                }
                if (pendingDefinitionFile != null && pendingDefinitionFile.equals(active)) {
                    this.editor.scrollToLine(this.pendingDefinitionLine);
                    this.pendingDefinitionFile = null;
                    this.pendingDefinitionLine = -1;
                }
            } else if (active == null && this.displayedFile != null) {
                this.displayedFile = null;
                syncGuard = true;
                this.editor.setContent("");
                syncGuard = false;
            }
            if (store.activeProjectId() == null || store.activeFile() == null) {
                this.editorWidget.setFocused(false);
            } else if (focus == FOCUS_EDITOR) {
                this.editorWidget.setFocused(true);
            }
        } finally {
            refreshing = false;
        }
    }

    // ------------------------------------------------------------------
    // Input: single routing layer over the official widget tree.
    // widgets are dispatched topmost-first (render order reversed), matching
    // how overlays render on top; vanilla Screen children order is bottom-first.
    // ------------------------------------------------------------------

    private boolean dispatchClick(double mouseX, double mouseY, int button) {
        List<? extends GuiEventListener> kids = this.children();
        for (int i = kids.size() - 1; i >= 0; i--) {
            GuiEventListener listener = kids.get(i);
            if (listener.mouseClicked(mouseX, mouseY, button)) {
                // Do not steal focus while a modal (prompt/confirm/palette) owns
                // the keyboard: the action that just ran may have focused an EditBox.
                boolean modalOpen = this.promptOpen || this.confirmOpen || this.palettePopupOpen();
                if (button == 0 && !modalOpen) {
                    this.setFocused(listener);
                    this.setDragging(true);
                }
                return true;
            }
        }
        return false;
    }

    private boolean dispatchScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        List<? extends GuiEventListener> kids = this.children();
        for (int i = kids.size() - 1; i >= 0; i--) {
            if (kids.get(i).mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = this.dispatchClick(mouseX, mouseY, button);
        if (handled) {
            if (this.promptOpen || this.confirmOpen || this.palettePopupOpen() || this.contextOpen() || this.openMenu >= 0) {
                return true;
            }
            if (this.searchBox.isFocused() || this.replaceBox.isFocused()) {
                this.focus = FOCUS_SEARCH;
            } else if (this.consoleInput.isFocused()) {
                this.focus = FOCUS_CONSOLE;
                this.editorWidget.setFocused(false);
                this.consoleHint = "";
            } else {
                this.focus = FOCUS_EDITOR;
                this.setFocused(this.editorWidget);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.promptOpen || this.confirmOpen) {
            return true;
        }
        return this.dispatchScroll(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        boolean handled = super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        if (!handled && focus == FOCUS_EDITOR && this.editorWidget.isFocused()) {
            this.editor.mouseDragged(mouseX, mouseY, button);
            return true;
        }
        return handled;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.confirmOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeConfirm();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                acceptConfirm();
                return true;
            }
            return true;
        }
        if (this.promptOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closePrompt();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                submitPrompt();
                return true;
            }
            this.promptBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (this.palettePopupOpen()) {
            return this.paletteKeys(keyCode, scanCode, modifiers);
        }
        if (this.contextOpen() && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.contextOpen(false);
            return true;
        }
        if (this.openMenu >= 0 && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.openMenu(-1);
            return true;
        }
        if (Screen.hasControlDown()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_S -> {
                    ClientProjectStore.getInstance().saveActiveFile();
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER -> {
                    ClientProjectStore.getInstance().validateActiveFile();
                    return true;
                }
                case GLFW.GLFW_KEY_F -> {
                    toggleSearch();
                    return true;
                }
                case GLFW.GLFW_KEY_W -> {
                    requestCloseTab(ClientProjectStore.getInstance().activeFile());
                    return true;
                }
                case GLFW.GLFW_KEY_R -> {
                    if (Screen.hasShiftDown()) {
                        runProject();
                        return true;
                    }
                    break;
                }
                case GLFW.GLFW_KEY_P -> {
                    if (Screen.hasShiftDown()) {
                        openCommandPalette();
                        return true;
                    }
                    break;
                }
                default -> {
                }
            }
        }
        if (focus == FOCUS_CONSOLE) {
            if (this.consoleInput.isFocused()) {
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    this.consoleInput.setFocused(false);
                    this.setFocused(null);
                    this.focus = FOCUS_EDITOR;
                    this.setFocused(this.editorWidget);
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    submitConsole();
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_TAB) {
                    autocompleteConsole();
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_UP) {
                    historyPrev();
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_DOWN) {
                    historyNext();
                    return true;
                }
                if (this.consoleInput.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }
            return true;
        }
        if (focus == FOCUS_SEARCH) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (this.replaceBox.isFocused()) {
                    replaceNext();
                } else if (Screen.hasShiftDown()) {
                    this.editor.findPrevious();
                } else {
                    this.editor.findNext();
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                if (this.searchBox.isFocused()) {
                    this.searchBox.setFocused(false);
                    this.replaceBox.setFocused(true);
                    this.setFocused(this.replaceBox);
                } else {
                    this.replaceBox.setFocused(false);
                    this.searchBox.setFocused(true);
                    this.setFocused(this.searchBox);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                toggleSearch();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (focus == FOCUS_EDITOR) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.confirmOpen) {
            return true;
        }
        if (this.promptOpen) {
            this.promptBox.charTyped(codePoint, modifiers);
            return true;
        }
        if (this.palettePopupOpen()) {
            return this.paletteChar(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    private boolean palettePopupOpen() {
        return this.palettePopup != null && this.palettePopup.isOpen();
    }

    private boolean paletteKeys(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.palettePopup.close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.palettePopup.runSelected();
            return true;
        }
        if (this.palettePopup.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return true;
    }

    private boolean paletteChar(char codePoint, int modifiers) {
        return this.palettePopup.charTyped(codePoint, modifiers);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // No vanilla blur; the IDE fills its own opaque background in render().
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, IdeColors.BG);
        positionFloatingWidgets();
        super.render(graphics, mouseX, mouseY, partialTick);
        renderConsoleHint(graphics);
    }

    /** Draws the grey completion suffix right after the typed console text. */
    private void renderConsoleHint(GuiGraphics graphics) {
        if (this.bottomTab != 0 || !this.consoleInput.visible) {
            return;
        }
        int promptY = this.consoleInput.getY() + 1;
        graphics.drawString(this.font, CONSOLE_PROMPT, 8, promptY, IdeColors.ACCENT, false);
        if (this.consoleHint.isEmpty() || !this.consoleInput.isFocused()) {
            return;
        }
        String value = this.consoleInput.getValue();
        int x = this.consoleInput.getX() + this.font.width(value);
        int y = this.consoleInput.getY() + 1;
        if (x < this.consoleInput.getX() + this.consoleInput.getWidth() - 8) {
            graphics.drawString(this.font, this.consoleHint, x, y, IdeColors.MUTED, false);
        }
    }

    // ------------------------------------------------------------------
    // Menu bar
    // ------------------------------------------------------------------

    private int menuTitleStartX() {
        return 8 + this.font.width("CraftPy IDE") + 14;
    }

    private int menuTitleX(int index) {
        int x = menuTitleStartX();
        for (int i = 0; i < index; i++) {
            x += this.font.width(MENU_TITLES[i]) + 16;
        }
        return x;
    }

    private int menuTitleW(int index) {
        return this.font.width(MENU_TITLES[index]) + 12;
    }

    private int menuTitleAt(double mouseX) {
        for (int i = 0; i < MENU_TITLES.length; i++) {
            int x = menuTitleX(i);
            if (mouseX >= x && mouseX <= x + menuTitleW(i)) {
                return i;
            }
        }
        return -1;
    }

    private int dropdownWidth(int index) {
        int max = 140;
        for (MenuItem item : this.menus.get(index)) {
            max = Math.max(max, this.font.width(item.label()) + 30);
        }
        return max;
    }

    private int dropdownX(int index) {
        int x = menuTitleX(index);
        return Math.min(x, this.width - dropdownWidth(index) - 4);
    }

    private int dropdownItemY(int itemIndex) {
        return MENU_BAR_H + 3 + itemIndex * MENU_ITEM_HEIGHT;
    }

    private void openMenu(int index) {
        this.openMenu = index;
        this.menuPopup.setVisibility(index >= 0);
    }

    private void contextOpen(boolean open) {
        this.contextPopup.visible = open;
        if (open) {
            this.contextPopup.refreshGeometry();
        }
    }

    private boolean contextOpen() {
        return this.contextPopup.visible;
    }

    /** Hovering another title while a menu is open switches to it. */
    private void hoverMenuSwitch(double mouseX, double mouseY) {
        if (this.openMenu >= 0 && mouseY < MENU_BAR_H) {
            int title = menuTitleAt(mouseX);
            if (title >= 0 && title != this.openMenu) {
                this.openMenu = title;
                this.menuPopup.setVisibility(true);
                return;
            }
        }
    }

    private void renderDropdown(GuiGraphics graphics, int mouseX, int mouseY, MenuPopup popup) {
        if (this.openMenu < 0 || this.menus.isEmpty()) {
            return;
        }
        int index = this.openMenu;
        if (index >= this.menus.size()) {
            return;
        }
        int x = popup.getX();
        int y = popup.getY();
        int w = popup.getWidth();
        int h = popup.getHeight();
        graphics.fill(x, y, x + w, y + h, IdeColors.PANEL);
        List<MenuItem> items = this.menus.get(index);
        for (int i = 0; i < items.size(); i++) {
            int iy = y + 3 + i * MENU_ITEM_HEIGHT;
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= iy && mouseY <= iy + MENU_ITEM_HEIGHT;
            if (hovered) {
                graphics.fill(x + 1, iy, x + w - 1, iy + MENU_ITEM_HEIGHT, IdeColors.SELECTED);
            }
            graphics.drawString(this.font, items.get(i).label(), x + 8, iy + 3, hovered ? IdeColors.ACCENT : IdeColors.TEXT, false);
        }
    }

    private int dropdownItemAt(double mouseX, double mouseY) {
        if (this.openMenu < 0 || this.openMenu >= this.menus.size()) {
            return -1;
        }
        int x = dropdownX(this.openMenu);
        int w = dropdownWidth(this.openMenu);
        if (mouseX < x || mouseX > x + w) {
            return -1;
        }
        List<MenuItem> items = this.menus.get(this.openMenu);
        for (int i = 0; i < items.size(); i++) {
            int y = dropdownItemY(i);
            if (mouseY >= y && mouseY <= y + MENU_ITEM_HEIGHT) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Explorer context menu
    // ------------------------------------------------------------------

    private void openContextMenu(int x, int y, String path) {
        ClientProjectStore store = ClientProjectStore.getInstance();
        this.contextPath = path;
        this.openMenu(-1);

        List<MenuItem> items = new ArrayList<>();
        if (path != null) {
            items.add(new MenuItem(path.endsWith("/") ? "Open Folder" : "Open", () -> openProjectEntry(path)));
            items.add(new MenuItem("Rename...", () -> promptRename(path)));
            items.add(new MenuItem("Delete...", () -> confirmDelete(path)));
        }
        items.add(new MenuItem("New File...", () -> promptNewFile(path)));
        items.add(new MenuItem("New Folder...", () -> promptNewFolder(path)));
        items.add(new MenuItem("Import file(s) from PC...",
                () -> net.xmilon.craftpy.client.assets.AssetUploader.openDialog(store.activeProjectId(), null)));
        items.add(new MenuItem("Refresh", store::refresh));
        this.contextItems = items;
        this.contextPopup.setItems(items, x, y);
        this.contextOpen(true);
    }

    private void runContextItem(int index) {
        if (index < 0 || index >= this.contextItems.size()) {
            return;
        }
        MenuItem chosen = this.contextItems.get(index);
        this.contextOpen(false);
        chosen.action().run();
    }

    private int contextItemHeight() {
        return 6 + this.contextItems.size() * CONTEXT_ITEM_HEIGHT;
    }

    // ------------------------------------------------------------------
    // File operations (rename / create / delete)
    // ------------------------------------------------------------------

    private static String parentDir(String path) {
        if (path == null) {
            return "code/";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "code/" : path.substring(0, slash + 1);
    }

    private static String resolvePath(String dir, String name) {
        if (name.startsWith("code/") || name.startsWith("assets/") || name.startsWith("data/")) {
            return name;
        }
        return dir + name;
    }

    private static boolean validPath(String p) {
        return p != null && !p.isEmpty() && p.length() <= 200 && !p.contains("..") && !p.contains("//")
                && !p.startsWith("/") && !p.endsWith("/") && p.matches("[A-Za-z0-9_./-]+");
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Sends a project action by enum name. The lookup is by name so a missing
     * constant produces a console message instead of a compile error.
     */
    private void sendProjectAction(String actionName, String payload) {
        ClientProjectStore store = ClientProjectStore.getInstance();
        String projectId = store.activeProjectId();
        if (projectId == null) {
            store.append("No project loaded - pick one via File > Projects...");
            showBottomTab(0);
            return;
        }
        ProjectAction action;
        try {
            action = ProjectAction.valueOf(actionName);
        } catch (IllegalArgumentException e) {
            store.append("ERROR: ProjectAction." + actionName
                    + " does not exist yet - add it to ProjectAction and handle it on the server.");
            showBottomTab(0);
            return;
        }
        store.request(action, projectId, payload);
    }

    private void promptNewFile(String contextFile) {
        String dir = parentDir(contextFile);
        openPrompt("New file in " + dir, "", name -> {
            String full = resolvePath(dir, name);
            if (!name.contains(".")) {
                full += ".py";
            }
            if (!validPath(full)) {
                fileError("Invalid file name '" + name + "' (use letters, digits, _ - . /)");
                return;
            }
            sendProjectAction("CREATE_ENTRY", "{\"path\":\"" + json(full) + "\",\"type\":\"file\"}");
        });
    }

    private void promptNewFolder(String contextFile) {
        String dir = parentDir(contextFile);
        openPrompt("New folder in " + dir, "", name -> {
            String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            String full = resolvePath(dir, clean);
            if (!validPath(full)) {
                fileError("Invalid folder name '" + name + "'");
                return;
            }
            sendProjectAction("CREATE_ENTRY", "{\"path\":\"" + json(full) + "\",\"type\":\"dir\"}");
        });
    }

    private void promptRename(String path) {
        String dir = parentDir(path);
        String current = path.startsWith(dir) ? path.substring(dir.length()) : path;
        openPrompt("Rename " + path, current, name -> {
            String target = name.contains("/") ? name : dir + name;
            if (target.equals(path)) {
                return;
            }
            if (!validPath(target)) {
                fileError("Invalid file name '" + name + "'");
                return;
            }
            sendProjectAction("RENAME_ENTRY", "{\"from\":\"" + json(path) + "\",\"to\":\"" + json(target) + "\"}");
        });
    }

    private void confirmDelete(String path) {
        openConfirm("Delete '" + path + "'? This cannot be undone.",
                () -> sendProjectAction("DELETE_ENTRY", "{\"path\":\"" + json(path) + "\"}"));
    }

    private void fileError(String message) {
        ClientProjectStore.getInstance().append("ERROR: " + message);
        showBottomTab(0);
    }

    // ------------------------------------------------------------------
    // Modal prompt
    // ------------------------------------------------------------------

    private void unfocusAll() {
        this.editorWidget.setFocused(false);
        this.searchBox.setFocused(false);
        this.replaceBox.setFocused(false);
        this.consoleInput.setFocused(false);
    }

    private void openPrompt(String title, String initial, Consumer<String> accept) {
        this.openMenu(-1);
        this.contextOpen(false);
        this.promptOpen = true;
        this.promptTitle = title;
        this.promptAccept = accept;
        this.focus = FOCUS_MODAL;
        unfocusAll();
        this.promptBox.setValue(initial);
        this.setFocused(this.promptBox);
        this.promptBox.setFocused(true);
    }

    private void closePrompt() {
        this.promptOpen = false;
        this.promptAccept = null;
        this.promptBox.setFocused(false);
        this.setFocused(null);
        this.focus = FOCUS_EDITOR;
        this.setFocused(this.editorWidget);
    }

    private void submitPrompt() {
        String value = this.promptBox.getValue().trim();
        Consumer<String> accept = this.promptAccept;
        closePrompt();
        if (!value.isEmpty() && accept != null) {
            accept.accept(value);
        }
    }

    // ------------------------------------------------------------------
    // Confirm dialog
    // ------------------------------------------------------------------

    private void openConfirm(String message, Runnable accept) {
        this.openMenu(-1);
        this.contextOpen(false);
        this.confirmOpen = true;
        this.confirmMessage = message;
        this.confirmAccept = accept;
        this.focus = FOCUS_MODAL;
        unfocusAll();
        this.setFocused(null);
    }

    private void closeConfirm() {
        this.confirmOpen = false;
        this.confirmAccept = null;
        this.focus = FOCUS_EDITOR;
        this.setFocused(this.editorWidget);
    }

    private void acceptConfirm() {
        Runnable accept = this.confirmAccept;
        closeConfirm();
        if (accept != null) {
            accept.run();
        }
    }

    private boolean confirmYesAt(double mx, double my) {
        int cw = Math.min(300, this.width - 20);
        int cx = (this.width - cw) / 2;
        int cy = this.height / 2 - 30;
        return mx >= cx + cw / 2 - 66 && mx <= cx + cw / 2 - 6 && my >= cy + 38 && my <= cy + 52;
    }

    private boolean confirmNoAt(double mx, double my) {
        int cw = Math.min(300, this.width - 20);
        int cx = (this.width - cw) / 2;
        int cy = this.height / 2 - 30;
        return mx >= cx + cw / 2 + 6 && mx <= cx + cw / 2 + 66 && my >= cy + 38 && my <= cy + 52;
    }

    // ------------------------------------------------------------------
    // Console REPL
    // ------------------------------------------------------------------

    private void submitConsole() {
        String line = this.consoleInput.getValue().trim();
        if (line.isEmpty()) {
            return;
        }
        if (this.consoleHistory.isEmpty() || !this.consoleHistory.get(this.consoleHistory.size() - 1).equals(line)) {
            this.consoleHistory.add(line);
            if (this.consoleHistory.size() > 100) {
                this.consoleHistory.remove(0);
            }
        }
        this.historyIndex = this.consoleHistory.size();
        this.historyDraft = "";
        this.consoleInput.setValue("");
        this.consoleHint = "";
        ClientProjectStore.getInstance().append("> " + line);
        runConsoleLine(line);
        this.bottomPanel.scrollToEnd();
    }

    private void runConsoleLine(String line) {
        ClientProjectStore store = ClientProjectStore.getInstance();
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        switch (cmd) {
            case "clear" -> store.clearLog();
            case "help" -> printConsoleHelp();
            case "run" -> runProject();
            case "validate" -> store.validateActiveFile();
            case "open" -> openByName(arg);
            case "files" -> {
                List<String> files = store.files();
                if (files.isEmpty()) {
                    store.append("No files (open a project first).");
                }
                for (String file : files) {
                    store.append("  " + file);
                }
            }
            case "api" -> openApiDocAsFile();
            case "debug" -> toggleDebug();
            case "reset" -> {
                this.consoleHistory.clear();
                this.historyIndex = 0;
                this.editor.setSearchQuery("");
                store.refresh();
                store.append("Console state reset.");
            }
            default -> evalPython(line);
        }
    }

    private void printConsoleHelp() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        store.append("Console commands:");
        store.append("  clear            clear this console");
        store.append("  run              save and run the project");
        store.append("  validate         check the active file");
        store.append("  open <file>      open a file in the editor");
        store.append("  files            list project files");
        store.append("  api              open the API reference as a file");
        store.append("  debug            toggle debug trace");
        store.append("  reset            clear history and search");
        store.append("Anything else is sent to the project's Python worker. Tab completes, Up/Down = history.");
    }

    private void openByName(String name) {
        ClientProjectStore store = ClientProjectStore.getInstance();
        if (name.isEmpty()) {
            store.append("Usage: open <file>");
            return;
        }
        List<String> files = store.files();
        for (String file : files) {
            if (file.equals(name) || file.equals("code/" + name)) {
                store.openFile(file);
                return;
            }
        }
        store.append("ERROR: no such file '" + name + "' (try 'files').");
    }

    private void evalPython(String code) {
        ClientProjectStore store = ClientProjectStore.getInstance();
        if (store.activeProjectId() == null) {
            store.append("No project loaded - pick one via File > Projects...");
            return;
        }
        sendProjectAction("EVAL", "{\"code\":\"" + json(code) + "\"}");
    }

    private void toggleDebug() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        boolean next = !store.debugMode();
        try {
            store.getClass().getMethod("setDebugMode", boolean.class).invoke(store, next);
            store.append("Debug mode " + (next ? "ON" : "OFF"));
        } catch (ReflectiveOperationException e) {
            store.append("ERROR: ClientProjectStore has no setDebugMode(boolean) - add it to enable this toggle.");
        }
    }

    private void historyPrev() {
        if (this.consoleHistory.isEmpty()) {
            return;
        }
        if (this.historyIndex >= this.consoleHistory.size()) {
            this.historyDraft = this.consoleInput.getValue();
            this.historyIndex = this.consoleHistory.size();
        }
        if (this.historyIndex > 0) {
            this.historyIndex--;
        }
        this.consoleInput.setValue(this.consoleHistory.get(this.historyIndex));
    }

    private void historyNext() {
        if (this.consoleHistory.isEmpty() || this.historyIndex >= this.consoleHistory.size()) {
            return;
        }
        this.historyIndex++;
        if (this.historyIndex >= this.consoleHistory.size()) {
            this.consoleInput.setValue(this.historyDraft);
        } else {
            this.consoleInput.setValue(this.consoleHistory.get(this.historyIndex));
        }
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** The text at the end of the input that Tab would complete. */
    private String completionPrefix(String value) {
        Matcher open = OPEN_ARG.matcher(value);
        if (open.matches()) {
            return open.group(1);
        }
        int i = value.length();
        while (i > 0 && isIdentChar(value.charAt(i - 1))) {
            i--;
        }
        return value.substring(i);
    }

    private List<String> consoleCandidates(String value) {
        TreeSet<String> out = new TreeSet<>();
        Matcher open = OPEN_ARG.matcher(value);
        if (open.matches()) {
            String prefix = open.group(1);
            for (String file : ClientProjectStore.getInstance().files()) {
                String shortName = file.startsWith("code/") ? file.substring(5) : file;
                if (file.startsWith(prefix) || shortName.startsWith(prefix)) {
                    out.add(prefix.startsWith("code/") ? file : shortName);
                }
            }
            out.remove(prefix);
            return new ArrayList<>(out);
        }
        String prefix = completionPrefix(value);
        if (prefix.isEmpty()) {
            return List.of();
        }
        if (value.stripLeading().equals(prefix)) {
            for (String command : CONSOLE_COMMANDS) {
                out.add(command);
            }
        }
        for (String name : CONSOLE_API_NAMES) {
            out.add(name);
        }
        for (String name : CONSOLE_BUILTINS) {
            out.add(name);
        }
        rebuildSymbolIndex();
        out.addAll(this.symbolLines.keySet());
        out.removeIf(candidate -> !candidate.startsWith(prefix) || candidate.equals(prefix));
        return new ArrayList<>(out);
    }

    private static String commonPrefix(List<String> items) {
        String prefix = items.get(0);
        for (String item : items) {
            int n = 0;
            int max = Math.min(prefix.length(), item.length());
            while (n < max && prefix.charAt(n) == item.charAt(n)) {
                n++;
            }
            prefix = prefix.substring(0, n);
        }
        return prefix;
    }

    private void updateConsoleHint(String value) {
        if (value == null || value.isEmpty()) {
            this.consoleHint = "";
            return;
        }
        List<String> candidates = consoleCandidates(value);
        if (candidates.isEmpty()) {
            this.consoleHint = "";
            return;
        }
        String prefix = completionPrefix(value);
        String first = candidates.get(0);
        this.consoleHint = first.length() > prefix.length() ? first.substring(prefix.length()) : "";
    }

    private void autocompleteConsole() {
        String value = this.consoleInput.getValue();
        List<String> candidates = consoleCandidates(value);
        if (candidates.isEmpty()) {
            return;
        }
        String prefix = completionPrefix(value);
        String head = value.substring(0, value.length() - prefix.length());
        if (candidates.size() == 1) {
            String only = candidates.get(0);
            boolean isCommand = head.isBlank() && java.util.Arrays.asList(CONSOLE_COMMANDS).contains(only);
            this.consoleInput.setValue(head + only + (isCommand ? " " : ""));
            return;
        }
        String common = commonPrefix(candidates);
        if (common.length() > prefix.length()) {
            this.consoleInput.setValue(head + common);
        } else {
            ClientProjectStore.getInstance().append("  " + String.join("  ", candidates));
            this.bottomPanel.scrollToEnd();
        }
    }

    // ------------------------------------------------------------------
    // Definition navigation / API doc
    // ------------------------------------------------------------------

    private void rebuildSymbolIndex() {
        symbolLines.clear();
        for (int i = 0; i < this.editor.lineCount(); i++) {
            String text = this.editor.lineAt(i);
            recordMatch(SYMBOL_DEF, text, i, 1);
            recordMatch(SYMBOL_DECL, text, i, 1);
            recordMatch(SYMBOL_ASSIGN, text, i, 1);
        }
    }

    private void recordMatch(Pattern pattern, String text, int line, int group) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            symbolLines.putIfAbsent(matcher.group(group), line);
        }
    }

    /** Opens the definition (def/class/@block id) for the word under the cursor. */
    private boolean goToDefinition(int line, int col) {
        String word = this.editor.wordAt(line, col);
        if (word.isEmpty()) {
            return false;
        }
        rebuildSymbolIndex();
        Integer target = symbolLines.get(word);
        ClientProjectStore store = ClientProjectStore.getInstance();
        if (target != null) {
            this.editor.scrollToLine(target);
            focusEditor();
            store.append("Jumped to definition of '" + word + "' (line " + (target + 1) + ")");
            return true;
        }
        store.append("Searching project for '" + word + "' ...");
        store.findDefinition(word);
        return true;
    }

    /** Handles a cross-file definition result from the server. */
    private void onDefinitionFound(String file, int line) {
        ClientProjectStore store = ClientProjectStore.getInstance();
        if (file == null || file.isEmpty() || line < 0) {
            store.append("No definition found in this project");
            showBottomTab(0);
            return;
        }
        if (!file.equals(store.activeFile())) {
            this.pendingDefinitionFile = file;
            this.pendingDefinitionLine = line;
            store.openFile(file);
            store.append("Definition: " + file + ":" + (line + 1));
        } else {
            this.editor.scrollToLine(line);
            focusEditor();
            store.append("Jumped to definition (line " + (line + 1) + ")");
        }
    }

    private void focusEditor() {
        this.focus = FOCUS_EDITOR;
        this.setFocused(this.editorWidget);
        this.editorWidget.setFocused(true);
        if (this.consoleInput != null) {
            this.consoleInput.setFocused(false);
        }
    }

    private static final String API_REF_PATH = "API Reference (built-in).md";

    /** Opens the bundled API markdown as a read-only editor tab. */
    private void openApiDocAsFile() {
        if (apiDocContent.isEmpty()) {
            loadApiDoc();
        }
        focusEditor();
        ClientProjectStore.getInstance().openVirtualFile(API_REF_PATH,
                apiDocContent.isEmpty() ? "# API Reference\n\nThe bundled documentation failed to load."
                        : apiDocContent);
    }

    private void openAssetPickerKind(AssetKind kind) {
        openAssetPicker(this.editor.cursorLineIndex(), this.editor.cursorColumn(), this.editor.cursorColumn(), kind,
                false);
    }

    private void showHelp() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        store.append("Quick help:");
        store.append("  - Ctrl+S save, Ctrl+Enter validate, Ctrl+F find, Ctrl+Shift+R run.");
        store.append("  - Shift-click an identifier in the editor to jump to its definition.");
        store.append("  - Assets menu or the Assets button opens the asset bank (click to insert).");
        store.append("  - Drop image files on the window to upload them to the selected project.");
        store.append("  - Right-click the file list to create, rename or delete files.");
        store.append("  - Click the console input to type commands or Python; type 'help' for commands.");
        showBottomTab(0);
    }

    /** Returns to the standalone project browser window. */
    private void goToChooser() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ProjectChooserScreen(menu, inventory, Component.literal("CraftPy Projects")));
        }
    }

    /** Handles files dropped onto the window: uploads them to the selected project. */
    public void handleDroppedAssets(java.util.List<java.nio.file.Path> paths) {
        net.xmilon.craftpy.client.ClientProjectStore store = net.xmilon.craftpy.client.ClientProjectStore.getInstance();
        String projectId = store.selectedId() != null ? store.selectedId() : store.activeProjectId();
        if (projectId == null) {
            store.append("Select a project before dropping assets");
            return;
        }
        net.xmilon.craftpy.client.assets.AssetUploader.uploadAll(projectId, paths,
                net.xmilon.craftpy.assets.AssetKind.TEXTURE);
    }

    /**
     * Opens an explorer entry like an IDE: text files become editor tabs, asset
     * files (textures / sounds) open a preview viewer instead of raw text.
     */
    private void openProjectEntry(String path) {
        String lower = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        boolean preview = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".mp3");
        if (!preview) {
            ClientProjectStore.getInstance().openFile(path);
            return;
        }
        ClientProjectStore store = ClientProjectStore.getInstance();
        String projectId = store.activeProjectId();
        if (projectId == null) {
            return;
        }
        store.requestAsset(projectId, path, asset -> {
            if (this.minecraft != null && this.minecraft.screen == this) {
                this.minecraft.setScreen(new AssetFileScreen(this, projectId, path, asset.data(), asset.error()));
            }
        });
    }

    private void openAssetPickerAtCursor() {
        openAssetPicker(this.editor.cursorLineIndex(), this.editor.cursorColumn(), this.editor.cursorColumn(),
                AssetKind.TEXTURE, false);
    }

    private void openAssetPicker(int line, int start, int end, AssetKind kind, boolean existing) {
        if (this.minecraft == null) {
            return;
        }
        CodeEditor editor = this.editor;
        ClientProjectStore store = ClientProjectStore.getInstance();
        String projectId = store.selectedId() != null ? store.selectedId() : store.activeProjectId();
        this.minecraft.setScreen(new AssetPickerScreen(this, kind, id -> {
            if (existing) {
                editor.replaceRange(line, start, end, id);
            } else {
                String helper = kind == AssetKind.SOUND ? "snd"
                        : kind == AssetKind.MODEL ? "mdl" : "tex";
                editor.replaceRange(line, start, end, helper + "(\"" + id + "\")");
            }
        }, projectId));
    }

    // ------------------------------------------------------------------
    // Command palette
    // ------------------------------------------------------------------

    private void openCommandPalette() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        this.palettePopup.clearCommands();
        this.palettePopup.addCommand("Run Project", this::runProject);
        this.palettePopup.addCommand("Stop Project", store::interrupt);
        this.palettePopup.addCommand("Validate Project", () -> store.validateActiveFile());
        this.palettePopup.addCommand("Save File", store::saveActiveFile);
        this.palettePopup.addCommand("New File", () -> promptNewFile(""));
        this.palettePopup.addCommand("New Folder", () -> promptNewFolder(""));
        this.palettePopup.addCommand("Clear Console", store::clearLog);
        this.palettePopup.addCommand("Open API Reference", this::openApiDocAsFile);

        for (String file : store.files()) {
            this.palettePopup.addCommand("Open: " + file, () -> store.openFile(file));
        }

        this.palettePopup.open();
    }

    private String apiDocContent = "";

    private void loadApiDoc() {
        try {
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("craftpy", "docs/api.md");
            Minecraft.getInstance().getResourceManager().getResource(loc).ifPresent(res -> {
                try (java.io.InputStream is = res.open()) {
                    byte[] bytes = is.readAllBytes();
                    apiDocContent = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    apiDocContent = "# Error reading API doc\n" + e.getMessage();
                }
            });
        } catch (Exception e) {
            apiDocContent = "# Error loading API doc\n" + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Inner widgets (all official AbstractWidgets)
    // ------------------------------------------------------------------

    /** The top menu title strip with the right-aligned project label. */
    private final class IdeMenuBar extends AbstractWidget {
        private IdeMenuBar() {
            super(0, 0, 1, MENU_BAR_H, Component.literal("IDE menu"));
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            graphics.fill(x, y, x + w, y + MENU_BAR_H, IdeColors.BG);
            graphics.fill(x, y + MENU_BAR_H - 1, x + w, y + MENU_BAR_H, IdeColors.BORDER);
            int textY = (MENU_BAR_H - 8) / 2;
            graphics.drawString(font, "CraftPy IDE", 8, textY, IdeColors.ACCENT, false);
            for (int i = 0; i < MENU_TITLES.length; i++) {
                int tx = menuTitleX(i);
                int tw = menuTitleW(i);
                boolean hovered = mouseX >= tx && mouseX <= tx + tw && mouseY < MENU_BAR_H;
                if (openMenu == i || hovered) {
                    graphics.fill(tx, 1, tx + tw, MENU_BAR_H - 1, openMenu == i ? IdeColors.SELECTED : IdeColors.PANEL);
                }
                graphics.drawString(font, MENU_TITLES[i], tx + 5, textY, openMenu == i ? IdeColors.ACCENT : IdeColors.TEXT, false);
            }

            String label = ClientProjectStore.getInstance().activeProjectId();
            String project = label == null ? "no project loaded" : "project: " + label;
            int menuEnd = menuTitleX(MENU_TITLES.length - 1) + menuTitleW(MENU_TITLES.length - 1);
            int projectRight = w - 8;
            int projectX = projectRight - font.width(project);
            if (projectX > menuEnd + 12) {
                graphics.drawString(font, project, projectX, textY, IdeColors.MUTED, false);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.visible || mouseY >= MENU_BAR_H) {
                return false;
            }
            int title = menuTitleAt(mouseX);
            if (title >= 0) {
                openMenu(openMenu == title ? -1 : title);
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return false;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
            this.defaultButtonNarrationText(narrationOutput);
        }
    }

    /** The grey toolbar strip that sits behind the icon buttons. */
    private final class ToolbarBackground extends AbstractWidget {
        private ToolbarBackground() {
            super(0, 0, 1, TOOLBAR_H, Component.literal("toolbar"));
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            graphics.fill(x, y, x + w, y + TOOLBAR_H, IdeColors.PANEL_ALT);
            graphics.fill(x, y, x + w, y + 1, IdeColors.BORDER);
            graphics.fill(x, y + TOOLBAR_H - 1, x + w, y + TOOLBAR_H, IdeColors.BORDER);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return false;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
            this.defaultButtonNarrationText(narrationOutput);
        }
    }

    /** Explorer column header with new-file / new-folder / import / refresh. */
    private final class ExplorerHeader extends AbstractWidget {
        private ExplorerHeader() {
            super(0, 0, 1, 28, Component.literal("Explorer"));
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            graphics.fill(x, y, x + w, y + 28, IdeColors.PANEL_ALT);
            graphics.fill(x, y, x + w, y + 1, IdeColors.BORDER);
            graphics.fill(x, y + 27, x + w, y + 28, IdeColors.BORDER);
            graphics.drawString(font, "EXPLORER", x + 6, y + 10, IdeColors.ACCENT, false);
            renderMiniIcon(graphics, x + w - 22, y + 6, IconButton.Icon.NEW_FILE, hit(mouseX, mouseY, x + w - 24, y + 4));
            renderMiniIcon(graphics, x + w - 40, y + 6, IconButton.Icon.NEW_FOLDER, hit(mouseX, mouseY, x + w - 42, y + 4));
            renderMiniIcon(graphics, x + w - 58, y + 6, IconButton.Icon.FIND, hit(mouseX, mouseY, x + w - 60, y + 4));
            renderMiniIcon(graphics, x + w - 76, y + 6, IconButton.Icon.REFRESH, hit(mouseX, mouseY, x + w - 78, y + 4));
        }

        private boolean hit(double mx, double my, int x, int y) {
            return mx >= x && mx < x + 18 && my >= y && my < y + 18;
        }

        private void renderMiniIcon(GuiGraphics graphics, int x, int y, IconButton.Icon icon, boolean hover) {
            if (hover) {
                graphics.fill(x - 2, y - 2, x + 18, y + 18, IdeColors.PANEL_HOVER);
            }
            for (int j = 0; j < 9; j++) {
                for (int i = 0; i < 9; i++) {
                    if (rowGlyph(icon, j, i)) {
                        graphics.fill(x + i, y + j, x + i + 1, y + j + 1, IdeColors.MUTED);
                    }
                }
            }
        }

        private boolean rowGlyph(IconButton.Icon icon, int y, int x) {
            return iconGlyph(icon, y, x);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.visible || mouseY < this.getY() || mouseY >= this.getY() + 28 || button != 0) {
                return false;
            }
            int x = this.getX() + this.getWidth();
            if (mouseX >= x - 24 && mouseX < x - 6) {
                promptNewFile("");
                return true;
            }
            if (mouseX >= x - 42 && mouseX < x - 24) {
                promptNewFolder("");
                return true;
            }
            if (mouseX >= x - 60 && mouseX < x - 42) {
                net.xmilon.craftpy.client.assets.AssetUploader.openDialog(
                        ClientProjectStore.getInstance().activeProjectId(), null);
                return true;
            }
            if (mouseX >= x - 78 && mouseX < x - 60) {
                ClientProjectStore.getInstance().refresh();
                return true;
            }
            return false;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
            this.defaultButtonNarrationText(narrationOutput);
        }
    }

    /** The folder/file tree in the left column. */
    private final class ExplorerPanel extends ScrollView {
        private TreeMap<String, FNode> roots = new TreeMap<>();
        private final TreeMap<String, FNode> nodes = new TreeMap<>();
        private final TreeMap<String, Boolean> openByPath = new TreeMap<>();

        private ExplorerPanel() {
            super(Component.literal("Project files"));
        }

        private FNode ensure(String path) {
            FNode node = this.nodes.get(path);
            if (node == null) {
                node = new FNode(path, path.endsWith("/"));
                this.nodes.put(path, node);
            }
            return node;
        }

        private void buildTree() {
            this.roots = new TreeMap<>();
            this.nodes.clear();
            this.openByPath.putIfAbsent("assets/", true);
            this.openByPath.putIfAbsent("code/", true);
            this.openByPath.putIfAbsent("data/", true);
            for (String path : ClientProjectStore.getInstance().files()) {
                addPath(path);
            }
        }

        private void addPath(String path) {
            String p = path.replace('\\', '/');
            boolean dir = p.endsWith("/");
            String[] parts = p.split("/");
            StringBuilder acc = new StringBuilder();
            FNode parent = null;
            // Every ancestor directory of a file must be registered as a node, and the
            // top-most one inserted into roots - otherwise files under code/ or assets/
            // hang off a directory node that is never reached when walking the tree.
            int lastDirIndex = dir ? parts.length : parts.length - 1;
            for (int i = 0; i < lastDirIndex; i++) {
                acc.append(parts[i]).append('/');
                FNode node = ensure(acc.toString());
                node.dir = true;
                if (parent == null) {
                    insertRoot(node);
                } else {
                    attach(parent, node);
                }
                parent = node;
            }
            if (dir) {
                return;
            }
            FNode node = ensure(p);
            node.dir = false;
            if (parent != null) {
                attach(parent, node);
            } else {
                insertRoot(node);
            }
        }

        private void insertRoot(FNode node) {
            this.roots.put(node.name(), node);
        }

        private void attach(FNode parent, FNode child) {
            parent.children.putIfAbsent(child.name(), child);
        }

        @Override
        protected List<Row> rows() {
            this.buildTree();
            List<Row> out = new ArrayList<>();
            String active = ClientProjectStore.getInstance().activeFile();
            for (FNode root : this.roots.values()) {
                collect(root, 0, out, active);
            }
            if (out.isEmpty()) {
                out.add(new Row("Open a project", IdeColors.MUTED, false));
            }
            return out;
        }

        private void collect(FNode node, int depth, List<Row> out, String active) {
            String prefix = "  ".repeat(depth);
            String chevron = node.dir ? (isOpen(node.path()) ? "\u25BE " : "\u25B8 ") : "";
            int color;
            boolean highlight = false;
            if (node.dir) {
                color = isOpen(node.path()) ? IdeColors.ACCENT : IdeColors.MUTED;
            } else {
                highlight = node.path().equals(active);
                color = highlight ? IdeColors.TEXT : IdeColors.MUTED;
            }
            out.add(new Row(prefix + chevron + node.name(), color, highlight));
            if (node.dir && isOpen(node.path())) {
                for (FNode child : node.children.values()) {
                    collect(child, depth + 1, out, active);
                }
            }
        }

        private boolean isOpen(String path) {
            return this.openByPath.getOrDefault(path, true);
        }

        @Override
        public int rowHeight() {
            return 16;
        }

        @Override
        protected void onRowClicked(int index, int button, double x, double y) {
            if (index < 0 || index >= this.rows().size()) {
                return;
            }
            FNode node = nodeAt(index);
            if (node == null) {
                return;
            }
            if (node.dir) {
                if (button == 0) {
                    boolean next = !isOpen(node.path());
                    this.openByPath.put(node.path(), next);
                }
            } else if (button == 0) {
                openProjectEntry(node.path());
            }
        }

        @Override
        protected void onRowRightClicked(int index, double x, double y) {
            FNode node = index >= 0 ? nodeAt(index) : null;
            String target = node == null ? null : node.path();
            openContextMenu((int) x, (int) y, target);
        }

        private FNode nodeAt(int index) {
            List<Row> rows = this.rows();
            if (index >= rows.size()) {
                return null;
            }
            String active = ClientProjectStore.getInstance().activeFile();
            Slice rowByIndex = new Slice(rows, index, this.openByPath);
            return rowByIndex.findNode(this.roots);
        }

        @Override
        protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            buildTree();
            super.renderContents(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** Tiny helper mapping a flattened row index back to a tree node. */
    private static final class Slice {
        private final List<Row> flattened;
        private final int target;
        private final TreeMap<String, Boolean> openByPath;
        private FNode found;
        private int cursor;

        private Slice(List<Row> flattened, int target, TreeMap<String, Boolean> openByPath) {
            this.flattened = flattened;
            this.target = target;
            this.openByPath = openByPath;
        }

        private FNode findNode(TreeMap<String, FNode> roots) {
            for (FNode root : roots.values()) {
                walk(root);
                if (this.found != null) {
                    return this.found;
                }
            }
            return null;
        }

        private void walk(FNode node) {
            if (this.found != null) {
                return;
            }
            if (this.cursor == this.target) {
                this.found = node;
                return;
            }
            this.cursor++;
            if (node.dir && this.openByPath.getOrDefault(node.path(), true)) {
                for (FNode child : node.children.values()) {
                    this.walk(child);
                    if (this.found != null) {
                        return;
                    }
                }
            }
        }
    }

    private static final class FNode {
        final String path;
        final String name;
        boolean dir;
        final TreeMap<String, FNode> children = new TreeMap<>();

        private FNode(String path, boolean dir) {
            this.path = path;
            this.dir = dir;
            String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            int slash = trimmed.lastIndexOf('/');
            this.name = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        }

        String path() {
            return this.path;
        }

        String name() {
            return this.name;
        }
    }

    private boolean iconGlyph(IconButton.Icon icon, int y, int x) {
        return IconButton.cell(icon, y, x);
    }

    /** Bottom panel tab strip (Console / Problems / Preview, Settings when open). */
    private final class BottomTabBar extends AbstractWidget {
        private BottomTabBar() {
            super(0, 0, 1, BOTTOM_TAB_H, Component.literal("Bottom panel tabs"));
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            graphics.fill(x, y, x + w, y + BOTTOM_TAB_H, IdeColors.PANEL_ALT);
            graphics.fill(x, y, x + w, y + 1, IdeColors.BORDER);
            int cx = x + 8;
            for (int i = 0; i < BOTTOM_TAB_IDS.length; i++) {
                int id = BOTTOM_TAB_IDS[i];
                if (!openBottomTabs.contains(id)) {
                    continue;
                }
                boolean active = bottomTab == id;
                int tw = font.width(BOTTOM_TAB_TITLES[i]) + 20 + (active ? 12 : 0);
                graphics.fill(cx, y + 3, cx + tw, y + 17, active ? IdeColors.SELECTED : IdeColors.PANEL);
                graphics.drawString(font, BOTTOM_TAB_TITLES[i], cx + 10, y + 6,
                        active ? IdeColors.ACCENT : IdeColors.MUTED, false);
                if (active) {
                    int chx = cx + tw - 12;
                    boolean hoverClose = mouseX >= chx && mouseX < cx + tw && mouseY >= y + 3 && mouseY < y + 17;
                    graphics.drawString(font, "x", chx + 2, y + 6, hoverClose ? IdeColors.ERROR : IdeColors.MUTED, false);
                }
                cx += tw + 4;
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.visible || mouseY < this.getY() || mouseY >= this.getY() + BOTTOM_TAB_H) {
                return false;
            }
            int cx = this.getX() + 8;
            for (int i = 0; i < BOTTOM_TAB_IDS.length; i++) {
                int id = BOTTOM_TAB_IDS[i];
                if (!openBottomTabs.contains(id)) {
                    continue;
                }
                boolean active = bottomTab == id;
                int tw = font.width(BOTTOM_TAB_TITLES[i]) + 20 + (active ? 12 : 0);
                if (mouseX >= cx && mouseX < cx + tw) {
                    if (button == 0) {
                        if (active && mouseX >= cx + tw - 12) {
                            closeBottomTab(id);
                        } else {
                            showBottomTab(id);
                        }
                    }
                    return true;
                }
                cx += tw + 4;
            }
            return false;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return false;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
            this.defaultButtonNarrationText(narrationOutput);
        }
    }

    /** Scrollable bottom panel; rows provided per active tab. */
    private final class PanelScrollView extends ScrollView {
        private Supplier<List<Row>> rowsSupplier;
        private boolean follow;
        private int lastCount = -1;

        private PanelScrollView() {
            super(Component.literal("Output panel"));
        }

        private void setRows(Supplier<List<Row>> supplier, boolean follow) {
            this.rowsSupplier = supplier;
            this.follow = follow;
            this.lastCount = -1;
        }

        @Override
        protected List<Row> rows() {
            return this.rowsSupplier == null ? List.of() : this.rowsSupplier.get();
        }

        @Override
        public int rowHeight() {
            return bottomTab == 5 ? 12 : 11;
        }

        @Override
        protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            List<Row> r = this.rows();
            if (this.follow && this.lastCount != r.size()) {
                this.lastCount = r.size();
                this.scrollToEnd();
            }
            super.renderContents(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            boolean handled = super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            this.follow = this.atEnd();
            return handled;
        }

        @Override
        public void refresh() {
            this.lastCount = -1;
        }
    }

    /** Status bar: language | file | cursor position, right-aligned. */
    private final class StatusView extends AbstractWidget {
        private StatusView() {
            super(0, 0, 1, STATUS_H, Component.literal("Status bar"));
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            graphics.fill(x, y, x + w, y + STATUS_H, IdeColors.BG);
            graphics.fill(x, y, x + w, y + 1, IdeColors.BORDER);
            String file = displayedFile == null ? "no file" : displayedFile;
            String lang = editor.getLanguage();
            String title = lang.substring(0, 1).toUpperCase(Locale.ROOT) + lang.substring(1);
            String currentLine = Integer.toString(editor.cursorLineIndex() + 1);
            String currentCol = Integer.toString(editor.cursorColumn() + 1);
            String status = title + " | " + file + " | Ln " + currentLine + ", Col " + currentCol;
            graphics.drawString(font, status, x + w - font.width(status) - 8, (STATUS_H - 8) / 2, IdeColors.MUTED, false);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return false;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
            this.defaultButtonNarrationText(narrationOutput);
        }
    }

    /** Dropdown menu overlay shown when a menu title is open. */
    private final class MenuPopup extends AbstractWidget {
        private MenuPopup() {
            super(0, 0, 1, 1, Component.literal("Menu"));
            this.visible = false;
        }

        private void setVisibility(boolean open) {
            this.visible = open;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (openMenu < 0 || openMenu >= menus.size()) {
                return;
            }
            int x = dropdownX(openMenu);
            int w = dropdownWidth(openMenu);
            int h = 6 + menus.get(openMenu).size() * MENU_ITEM_HEIGHT;
            this.setPosition(x, MENU_BAR_H);
            this.setSize(w, h);
            graphics.fill(x - 1, MENU_BAR_H - 1, x + w + 1, MENU_BAR_H + h + 1, IdeColors.BORDER);
            renderDropdown(graphics, mouseX, mouseY, this);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.visible) {
                return false;
            }
            // Title row: let the menu bar underneath handle hover-switching.
            if (mouseY < MENU_BAR_H) {
                return false;
            }
            int item = dropdownItemAt(mouseX, mouseY);
            if (item >= 0) {
                MenuItem chosen = menus.get(openMenu).get(item);
                openMenu(-1);
                chosen.action().run();
                return true;
            }
            int x = dropdownX(openMenu);
            int w = dropdownWidth(openMenu);
            int h = 6 + menus.get(openMenu).size() * MENU_ITEM_HEIGHT;
            if (mouseX >= x && mouseX <= x + w && mouseY >= MENU_BAR_H && mouseY <= MENU_BAR_H + h) {
                return true;
            }
            openMenu(-1);
            return true;
        }

        @Override
        public void mouseMoved(double mouseX, double mouseY) {
            if (this.visible) {
                hoverMenuSwitch(mouseX, mouseY);
            }
            super.mouseMoved(mouseX, mouseY);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return this.visible;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
            this.defaultButtonNarrationText(narrationOutput);
        }
    }

    /** Explorer right-click context menu overlay. */
    private final class ContextPopup extends AbstractWidget {
        private int menuX;
        private int menuY;
        private int menuW;
        private int menuH;

        private ContextPopup() {
            super(0, 0, 1, 1, Component.literal("Context menu"));
            this.visible = false;
        }

        private void setItems(List<MenuItem> items, int x, int y) {
            int w = 110;
            for (MenuItem item : items) {
                w = Math.max(w, font.width(item.label()) + 26);
            }
            int h = 6 + items.size() * CONTEXT_ITEM_HEIGHT;
            this.menuW = w;
            this.menuH = h;
            this.menuX = Math.max(2, Math.min(x, width - w - 2));
            this.menuY = Math.max(2, Math.min(y, height - h - 2));
        }

        private void refreshGeometry() {
            this.setPosition(this.menuX, this.menuY);
            this.setSize(this.menuW, this.menuH);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!this.visible) {
                return;
            }
            graphics.fill(this.menuX - 1, this.menuY - 1, this.menuX + this.menuW + 1, this.menuY + this.menuH + 1, IdeColors.BORDER);
            graphics.fill(this.menuX, this.menuY, this.menuX + this.menuW, this.menuY + this.menuH, IdeColors.PANEL);
            for (int i = 0; i < contextItems.size(); i++) {
                int iy = this.menuY + 3 + i * CONTEXT_ITEM_HEIGHT;
                boolean hovered = mouseX >= this.menuX && mouseX <= this.menuX + this.menuW && mouseY >= iy
                        && mouseY <= iy + CONTEXT_ITEM_HEIGHT;
                if (hovered) {
                    graphics.fill(this.menuX + 1, iy, this.menuX + this.menuW - 1, iy + CONTEXT_ITEM_HEIGHT, IdeColors.SELECTED);
                }
                graphics.drawString(font, contextItems.get(i).label(), this.menuX + 8, iy + 3,
                        hovered ? IdeColors.ACCENT : IdeColors.TEXT, false);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.visible) {
                return false;
            }
            int index = -1;
            if (mouseX >= this.menuX && mouseX <= this.menuX + this.menuW) {
                for (int i = 0; i < contextItems.size(); i++) {
                    int iy = this.menuY + 3 + i * CONTEXT_ITEM_HEIGHT;
                    if (mouseY >= iy && mouseY <= iy + CONTEXT_ITEM_HEIGHT) {
                        index = i;
                        break;
                    }
                }
            }
            runContextItem(index);
            return true;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return this.visible;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
            this.defaultButtonNarrationText(narrationOutput);
        }
    }

    /** Modal input dialog (new file / folder / rename). */
    private final class PromptDialog extends AbstractWidget {
        private PromptDialog() {
            super(0, 0, 1, 1, Component.literal("Prompt"));
            this.visible = false;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!promptOpen) {
                return;
            }
            int pw = Math.min(260, width - 20);
            int px = (width - pw) / 2;
            int py = height / 2 - 30;
            graphics.fill(0, 0, width, height, IdeColors.DIMMER);
            graphics.fill(px - 1, py - 1, px + pw + 1, py + 57, IdeColors.BORDER);
            graphics.fill(px, py, px + pw, py + 56, IdeColors.PANEL);
            graphics.drawString(font, font.plainSubstrByWidth(promptTitle, pw - 16), px + 8, py + 7, IdeColors.ACCENT, false);
            promptBox.render(graphics, mouseX, mouseY, partialTick);
            graphics.drawString(font, "Enter = OK    Esc = Cancel", px + 8, py + 42, IdeColors.MUTED, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!promptOpen) {
                return false;
            }
            if (button == 0) {
                promptBox.mouseClicked(mouseX, mouseY, button);
            }
            return true;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return promptOpen;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
            this.defaultButtonNarrationText(narrationOutput);
        }
    }

    /** Modal confirm dialog (delete / close dirty tab). */
    private final class ConfirmDialog extends AbstractWidget {
        private ConfirmDialog() {
            super(0, 0, 1, 1, Component.literal("Confirm"));
            this.visible = false;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!confirmOpen) {
                return;
            }
            int cw = Math.min(300, width - 20);
            int cx = (width - cw) / 2;
            int cy = height / 2 - 30;
            graphics.fill(0, 0, width, height, IdeColors.DIMMER);
            graphics.fill(cx - 1, cy - 1, cx + cw + 1, cy + 61, IdeColors.BORDER);
            graphics.fill(cx, cy, cx + cw, cy + 60, IdeColors.PANEL);
            graphics.drawString(font, "Confirm", cx + 8, cy + 7, IdeColors.ACCENT, false);
            graphics.drawString(font, font.plainSubstrByWidth(confirmMessage, cw - 16), cx + 8, cy + 22, IdeColors.TEXT, false);

            int yesX = cx + cw / 2 - 66;
            int noX = cx + cw / 2 + 6;
            int by = cy + 38;
            boolean yesHover = confirmYesAt(mouseX, mouseY);
            boolean noHover = confirmNoAt(mouseX, mouseY);
            graphics.fill(yesX, by, yesX + 60, by + 14, yesHover ? IdeColors.ERROR : IdeColors.SELECTED);
            graphics.fill(noX, by, noX + 60, by + 14, noHover ? IdeColors.BORDER : IdeColors.PANEL_ALT);
            graphics.drawString(font, "Yes", yesX + (60 - font.width("Yes")) / 2, by + 3, IdeColors.TEXT, false);
            graphics.drawString(font, "No", noX + (60 - font.width("No")) / 2, by + 3, IdeColors.TEXT, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!confirmOpen) {
                return false;
            }
            if (button == 0) {
                if (confirmYesAt(mouseX, mouseY)) {
                    acceptConfirm();
                } else if (confirmNoAt(mouseX, mouseY)) {
                    closeConfirm();
                }
            }
            return true;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return confirmOpen;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
            this.defaultButtonNarrationText(narrationOutput);
        }
    }

    /** Command palette overlay (Ctrl+Shift+P). */
    private final class PalettePopup extends AbstractWidget {
        private final List<String[]> commands = new ArrayList<>();
        private final EditBox box;
        private boolean open;

        private PalettePopup() {
            super(0, 0, 1, 1, Component.literal("Command palette"));
            this.box = new EditBox(font, 0, 0, 240, 12, Component.literal("command"));
            this.box.setMaxLength(200);
            this.box.setHint(Component.literal("Type to filter commands"));
            this.box.setResponder(s -> {
            });
            this.visible = false;
        }

        private void clearCommands() {
            this.commands.clear();
        }

        private void addCommand(String label, Runnable action) {
            this.commands.add(new String[] { label, Integer.toString(this.commands.size()) });
            this.actions.put(label, action);
        }

        private final Map<String, Runnable> actions = new HashMap<>();

        private boolean isOpen() {
            return this.visible && this.open;
        }

        private void open() {
            this.open = true;
            this.visible = true;
            this.box.setValue("");
            ComputerScreen.this.setFocused(this.box);
            this.box.setFocused(true);
        }

        private void close() {
            this.open = false;
            this.visible = false;
            this.box.setFocused(false);
            ComputerScreen.this.setFocused(editorWidget);
            focus = FOCUS_EDITOR;
        }

        private List<String[]> filtered() {
            String q = this.box.getValue().trim().toLowerCase(Locale.ROOT);
            List<String[]> out = new ArrayList<>();
            for (String[] command : this.commands) {
                if (q.isEmpty() || command[0].toLowerCase(Locale.ROOT).contains(q)) {
                    out.add(command);
                }
            }
            return out;
        }

        private void runSelected() {
            List<String[]> matches = this.filtered();
            if (matches.isEmpty()) {
                return;
            }
            Runnable action = this.actions.get(matches.get(0)[0]);
            this.close();
            if (action != null) {
                action.run();
            }
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!isOpen()) {
                return;
            }
            int pw = Math.min(340, width - 40);
            int px = (width - pw) / 2;
            int py = Math.max(40, height / 3);
            int ph = Math.min(240, height - py - 40);
            List<String[]> matches = this.filtered();
            int listH = Math.min(ph, matches.size() * 14 + 6);
            graphics.fill(0, 0, width, height, IdeColors.DIMMER);
            graphics.fill(px - 1, py - 1, px + pw + 1, py + 26 + 1, IdeColors.BORDER);
            graphics.fill(px, py, px + pw, py + 25, IdeColors.PANEL);
            this.box.setX(px + 6);
            this.box.setY(py + 6);
            this.box.setWidth(pw - 12);
            this.box.render(graphics, mouseX, mouseY, partialTick);
            graphics.fill(px - 1, py + 26, px + pw + 1, py + 26 + listH + 1, IdeColors.BORDER);
            graphics.fill(px, py + 26, px + pw, py + 26 + listH, IdeColors.PANEL_ALT);
            int iy = py + 30;
            for (String[] match : matches) {
                boolean hover = mouseX >= px && mouseX <= px + pw && mouseY >= iy && mouseY < iy + 14;
                if (hover) {
                    graphics.fill(px + 1, iy, px + pw - 1, iy + 14, IdeColors.SELECTED);
                }
                graphics.drawString(font, match[0], px + 8, iy + 3, hover ? IdeColors.ACCENT : IdeColors.TEXT, false);
                iy += 14;
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!isOpen()) {
                return false;
            }
            int px = (width - Math.min(340, width - 40)) / 2;
            int py = Math.max(40, height / 3);
            if (button == 0 && mouseY >= py + 26) {
                int index = (int) ((mouseY - (py + 30)) / 14);
                List<String[]> matches = this.filtered();
                if (index >= 0 && index < matches.size()) {
                    Runnable action = this.actions.get(matches.get(index)[0]);
                    this.close();
                    if (action != null) {
                        action.run();
                    }
                    return true;
                }
            }
            if (button == 0) {
                this.box.mouseClicked(mouseX, mouseY, button);
            }
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return this.box.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char codePoint, int modifiers) {
            return this.box.charTyped(codePoint, modifiers);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return isOpen();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
            this.defaultButtonNarrationText(narrationOutput);
        }
    }
}