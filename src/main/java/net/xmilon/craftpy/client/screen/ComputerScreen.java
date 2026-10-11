package net.xmilon.craftpy.client.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.assets.AssetReference;
import net.xmilon.craftpy.assets.AssetReferences;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.CodeProblems;
import net.xmilon.craftpy.client.assets.AssetIndex;
import net.xmilon.craftpy.menu.ComputerMenu;
import net.xmilon.craftpy.network.ProjectAction;

public class ComputerScreen extends Screen implements MenuAccess<ComputerMenu> {
    private static final int BG = 0xFF10141A;
    private static final int PANEL = 0xFF1B222C;
    private static final int PANEL_ALT = 0xFF232C38;
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int TEXT = 0xFFD7E1EA;
    private static final int MUTED = 0xFF7D8B9A;
    private static final int BORDER = 0xFF2E3A48;
    private static final int SELECTED = 0xFF2C5B7A;
    private static final int ERROR_COLOR = 0xFFFF6B6B;
    private static final int DIMMER = 0xAA000000;

    private static final String[] MENU_TITLES = { "File", "Edit", "Run", "Assets", "Help" };
    private static final int MENU_BAR_HEIGHT = 18;
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

    private CodeEditor editor;
    private EditBox searchBox;
    private boolean searchOpen;

    private Button runButton;
    private Button assetsButton;

    private int fileScroll;
    private int tabScroll;
    private int bottomTab;
    private int bottomScroll;
    private int focus;
    private int lastLogSize = -1;

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
    private boolean contextOpen;
    private int contextX;
    private int contextY;
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

    private record Row(String text, int color) {
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

    @Override
    protected void init() {
        // init() also runs on window resize / GUI-scale change. The editor and the
        // widgets are recreated, so any open popup is dropped and the current file
        // is reloaded into the fresh editor (otherwise it would stay empty).
        this.displayedFile = null;
        this.promptOpen = false;
        this.confirmOpen = false;
        this.contextOpen = false;
        this.openMenu = -1;
        this.focus = FOCUS_EDITOR;

        ClientProjectStore store = ClientProjectStore.getInstance();
        store.setListener(storeListener);
        store.refresh();

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
        store.setDefinitionCallback(this::onDefinitionFound);

        buildMenus();

        this.searchBox = new EditBox(this.font, 0, 0, 120, 14, Component.literal("search"));
        this.searchBox.setMaxLength(200);
        this.searchBox.setHint(Component.literal("find"));
        this.searchBox.setResponder(query -> this.editor.setSearchQuery(query));
        this.addRenderableWidget(this.searchBox);

        this.promptBox = new EditBox(this.font, 0, 0, 200, 14, Component.literal("input"));
        this.promptBox.setMaxLength(240);
        this.promptBox.setVisible(false);
        this.addRenderableWidget(this.promptBox);

        this.consoleInput = new EditBox(this.font, 0, 0, 120, 12, Component.literal("console"));
        this.consoleInput.setMaxLength(400);
        this.consoleInput.setHint(Component.literal("Python or command; Tab completes"));
        this.consoleInput.setVisible(false);
        this.consoleInput.setResponder(this::updateConsoleHint);
        this.addRenderableWidget(this.consoleInput);

        this.runButton = Button.builder(Component.literal("Run"), b -> runProject()).build();
        this.assetsButton = Button.builder(Component.literal("Assets"), b -> openAssetPickerAtCursor()).build();
        this.addRenderableWidget(this.runButton);
        this.addRenderableWidget(this.assetsButton);

        layoutWidgets();
        refreshFromCache();
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
                new MenuItem("Clear Console", store::clearLog)));
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
        this.menus.add(List.of(
                new MenuItem("API Reference", () -> showBottomTab(3)),
                new MenuItem("Controls & Tips", this::showHelp),
                new MenuItem("About CraftPy", () -> ClientProjectStore.getInstance()
                        .append("CraftPy IDE - write Python to add blocks, items, recipes and more."))));
    }

    /** Saves and (re)loads the active project - the primary "run" action. */
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

    private void showBottomTab(int tab) {
        this.bottomTab = tab;
        // Console follows the newest line; other tabs start at the top.
        this.bottomScroll = tab == 0 ? Integer.MAX_VALUE : 0;
        if (tab != 0 && this.focus == FOCUS_CONSOLE) {
            this.focus = FOCUS_EDITOR;
            if (this.consoleInput != null) {
                this.consoleInput.setFocused(false);
            }
        }
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
            this.editor.setFocused(true);
            this.focus = FOCUS_EDITOR;
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
            this.editor.setFocused(true);
            this.focus = FOCUS_EDITOR;
            store.append("Jumped to definition (line " + (line + 1) + ")");
        }
    }

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
                net.xmilon.craftpy.assets.AssetKind.TEXTURE, false);
    }

    private void openAssetPicker(int line, int start, int end, net.xmilon.craftpy.assets.AssetKind kind,
                                 boolean existing) {
        if (this.minecraft == null) {
            return;
        }
        CodeEditor editor = this.editor;
        net.xmilon.craftpy.client.ClientProjectStore store = net.xmilon.craftpy.client.ClientProjectStore.getInstance();
        String projectId = store.selectedId() != null ? store.selectedId() : store.activeProjectId();
        this.minecraft.setScreen(new AssetPickerScreen(this, kind, id -> {
            if (existing) {
                editor.replaceRange(line, start, end, id);
            } else {
                String helper = kind == net.xmilon.craftpy.assets.AssetKind.SOUND ? "snd"
                        : kind == net.xmilon.craftpy.assets.AssetKind.MODEL ? "mdl" : "tex";
                editor.replaceRange(line, start, end, helper + "(\"" + id + "\")");
            }
        }, projectId));
    }

    private void layoutWidgets() {
        int pad = layoutPad();
        int leftW = leftPanelWidth();
        int bottomH = bottomPanelHeight();
        int w = this.width;

        int runW = 46;
        int assetsW = 58;
        this.runButton.setPosition(w - pad - runW, 2);
        this.runButton.setSize(runW, 14);
        this.assetsButton.setPosition(w - pad - runW - 4 - assetsW, 2);
        this.assetsButton.setSize(assetsW, 14);

        int editorX = pad + leftW + pad;
        int editorY = topBarHeight() + pad + 22;
        int editorWidth = w - editorX - pad;
        int editorHeight = height - bottomH - pad - editorY;
        this.editor.setBounds(editorX + 2, editorY + 2, editorWidth - 4, editorHeight - 4);

        // Console input sits on the last row of the bottom panel (CONSOLE tab only).
        boolean consoleVisible = this.bottomTab == 0 && !this.promptOpen && !this.confirmOpen;
        this.consoleInput.visible = consoleVisible;
        this.consoleInput.setX(pad + 6);
        this.consoleInput.setY(height - pad - 14);
        this.consoleInput.setWidth(w - pad * 2 - 12);
    }

    private void refreshFromCache() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        try {
            ClientProjectStore store = ClientProjectStore.getInstance();
            // Only clear a *stale* selection; calling select(null) when nothing is
            // selected re-notifies the listener and loops forever (freezing the game).
            String selected = store.selectedId();
            if (selected != null && store.find(selected) == null) {
                store.select(null);
            }
            String active = store.activeFile();
            if (active != null && !active.equals(this.displayedFile)) {
                this.displayedFile = active;
                syncGuard = true;
                this.editor.setContent(store.fileContent(active));
                syncGuard = false;
                focus = FOCUS_EDITOR;
                if (pendingDefinitionFile != null && pendingDefinitionFile.equals(active)) {
                    this.editor.scrollToLine(this.pendingDefinitionLine);
                    this.pendingDefinitionFile = null;
                    this.pendingDefinitionLine = -1;
                }
            } else if (active == null && this.displayedFile != null) {
                // The last tab was closed: clear the buffer instead of keeping stale text.
                this.displayedFile = null;
                syncGuard = true;
                this.editor.setContent("");
                syncGuard = false;
            }
            if (store.activeProjectId() == null || store.activeFile() == null) {
                this.editor.setFocused(false);
            } else {
                this.editor.setFocused(focus == FOCUS_EDITOR);
            }
        } finally {
            refreshing = false;
        }
    }

    // ------------------------------------------------------------------
    // Layout helpers
    // ------------------------------------------------------------------

    private int layoutPad() {
        return Math.max(6, Math.min(width, height) / 60);
    }

    private int menuBarHeight() {
        return MENU_BAR_HEIGHT;
    }

    private int topBarHeight() {
        return menuBarHeight();
    }

    private int leftPanelWidth() {
        return Math.max(140, width / 5);
    }

    private int bottomPanelHeight() {
        return Math.max(72, height / 4);
    }

    // ------------------------------------------------------------------
    // Menu bar geometry
    // ------------------------------------------------------------------

    private int menuTitleStartX() {
        return layoutPad() + this.font.width("CraftPy IDE") + 12;
    }

    private int menuTitleX(int index) {
        int x = menuTitleStartX();
        for (int i = 0; i < index; i++) {
            x += this.font.width(MENU_TITLES[i]) + 14;
        }
        return x;
    }

    private int menuTitleW(int index) {
        return this.font.width(MENU_TITLES[index]) + 10;
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
            max = Math.max(max, this.font.width(item.label()) + 24);
        }
        return max;
    }

    private int dropdownX(int index) {
        int x = menuTitleX(index);
        return Math.min(x, this.width - dropdownWidth(index) - layoutPad());
    }

    private int dropdownItemY(int itemIndex) {
        return menuBarHeight() + 3 + itemIndex * MENU_ITEM_HEIGHT;
    }

    private int dropdownItemAt(double mouseX, double mouseY) {
        if (this.openMenu < 0) {
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

    private boolean insideDropdownPanel(double mouseX, double mouseY) {
        if (this.openMenu < 0) {
            return false;
        }
        int x = dropdownX(this.openMenu);
        int w = dropdownWidth(this.openMenu);
        int h = 6 + this.menus.get(this.openMenu).size() * MENU_ITEM_HEIGHT;
        return mouseX >= x && mouseX <= x + w && mouseY >= menuBarHeight() && mouseY <= menuBarHeight() + h;
    }

    // ------------------------------------------------------------------
    // Editor tab geometry (shared by renderTabs and the click handler)
    // ------------------------------------------------------------------

    private record TabRect(String path, int x, int width, int labelX, int closeX, boolean dirty) {
    }

    /** One rectangle per open editor tab, laid out left to right. */
    private List<TabRect> tabRects() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        List<TabRect> rects = new ArrayList<>();
        int x = tabX() - this.tabScroll;
        for (String path : store.openTabs()) {
            String label = path.substring(Math.max(0, path.lastIndexOf('/') + 1));
            boolean dirty = store.isDirty(path);
            int labelW = this.font.width(label) + (dirty ? this.font.width("*") : 0);
            int width = labelW + 21;
            rects.add(new TabRect(path, x, width, x + 5, x + 11 + labelW, dirty));
            x += width + 2;
        }
        return rects;
    }

    private int tabStripWidth() {
        int total = 0;
        for (TabRect rect : tabRects()) {
            total += rect.width() + 2;
        }
        return total;
    }

    /** Mouse-wheel horizontal scrolling for long tab strips. */
    private void scrollTabs(double delta) {
        int visible = (this.width - layoutPad()) - tabX();
        int max = Math.max(0, tabStripWidth() - visible + 12);
        this.tabScroll = (int) Math.max(0, Math.min(this.tabScroll - delta, max));
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

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

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
        if (this.contextOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.contextOpen = false;
            return true;
        }
        if (this.openMenu >= 0 && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.openMenu = -1;
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
                default -> {
                }
            }
        }
        if (focus == FOCUS_CONSOLE) {
            if (this.consoleInput.isFocused()) {
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    // Esc leaves the console instead of being swallowed.
                    this.consoleInput.setFocused(false);
                    this.setFocused(null);
                    this.focus = FOCUS_EDITOR;
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
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (focus == FOCUS_EDITOR) {
            if (this.editor.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
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
            return this.promptBox.charTyped(codePoint, modifiers);
        }
        if (focus == FOCUS_SEARCH || focus == FOCUS_CONSOLE) {
            return super.charTyped(codePoint, modifiers);
        }
        if (focus == FOCUS_EDITOR) {
            if (this.editor.charTyped(codePoint, modifiers)) {
                return true;
            }
            return super.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.confirmOpen) {
            if (confirmYesAt(mouseX, mouseY) && button == 0) {
                acceptConfirm();
            } else if (confirmNoAt(mouseX, mouseY) && button == 0) {
                closeConfirm();
            }
            return true;
        }
        if (this.promptOpen) {
            if (button == 0) {
                this.promptBox.mouseClicked(mouseX, mouseY, button);
            }
            return true;
        }
        if (this.contextOpen) {
            int item = contextItemAt(mouseX, mouseY);
            if (item >= 0) {
                MenuItem chosen = this.contextItems.get(item);
                this.contextOpen = false;
                chosen.action().run();
                return true;
            }
            this.contextOpen = false;
        }
        // Menu bar / open dropdown first (but let the Run/Assets buttons through).
        if (mouseY < menuBarHeight()) {
            int title = menuTitleAt(mouseX);
            if (title >= 0) {
                this.openMenu = this.openMenu == title ? -1 : title;
                return true;
            }
        }
        if (this.openMenu >= 0) {
            int item = dropdownItemAt(mouseX, mouseY);
            if (item >= 0) {
                MenuItem chosen = this.menus.get(this.openMenu).get(item);
                this.openMenu = -1;
                chosen.action().run();
                return true;
            }
            if (insideDropdownPanel(mouseX, mouseY)) {
                return true;
            }
            this.openMenu = -1;
        }

        // Explorer right-click opens a context menu.
        if (button == 1 && insideExplorer(mouseX, mouseY)) {
            int index = fileRowAt(mouseX, mouseY);
            List<String> files = ClientProjectStore.getInstance().files();
            String target = index >= 0 ? files.get(index) : null;
            openContextMenu((int) mouseX, (int) mouseY, target);
            return true;
        }

        boolean widgetHandled = super.mouseClicked(mouseX, mouseY, button);
        if (widgetHandled) {
            if (this.searchBox.isFocused()) {
                this.focus = FOCUS_SEARCH;
            } else if (this.consoleInput.isFocused()) {
                this.focus = FOCUS_CONSOLE;
                this.editor.setFocused(false);
                this.consoleHint = "";
            }
            return true;
        }
        // file list (only the drawn row area)
        int fileIndex = fileRowAt(mouseX, mouseY);
        if (fileIndex >= 0) {
            openProjectEntry(ClientProjectStore.getInstance().files().get(fileIndex));
            return true;
        }

        // bottom tabs (the drawn 60x18 boxes)
        int bottomIndex = bottomTabAt(mouseX, mouseY);
        if (bottomIndex >= 0) {
            showBottomTab(bottomIndex);
            return true;
        }

        // editor tab bar (center): same 20px bar that render() draws
        int tabY = editorTabBarTop();
        if (mouseY >= tabY && mouseY < tabY + EDITOR_TAB_BAR_H) {
            for (TabRect rect : tabRects()) {
                if (mouseX >= rect.x() && mouseX <= rect.x() + rect.width()) {
                    if (button == 0 && mouseX >= rect.closeX() && mouseX <= rect.closeX() + 10) {
                        requestCloseTab(rect.path());
                    } else if (button == 1 || button == 2) {
                        // right- or middle-click closes the tab
                        requestCloseTab(rect.path());
                    } else if (button == 0) {
                        ClientProjectStore.getInstance().openFile(rect.path());
                    }
                    return true;
                }
            }
        }

        // editor
        if (this.editor.mouseClicked(mouseX, mouseY, button)) {
            this.focus = FOCUS_EDITOR;
            this.editor.setFocused(true);
            if (this.consoleInput != null) {
                this.consoleInput.setFocused(false);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        boolean handled = super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        if (!handled && focus == FOCUS_EDITOR) {
            this.editor.mouseDragged(mouseX, mouseY, button);
            return true;
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.confirmOpen || this.promptOpen) {
            return true;
        }
        boolean handled = super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        if (handled) {
            return true;
        }
        int pad = layoutPad();
        int leftW = leftPanelWidth();
        double vertical = verticalAmount;
        if (vertical == 0) {
            vertical = horizontalAmount;
        }
        if (mouseX >= pad && mouseX <= pad + leftW) {
            if (mouseY >= fileListTop() && mouseY <= fileListTop() + fileListHeight()) {
                this.fileScroll = (int) Math.max(0, this.fileScroll - vertical * 16);
                return true;
            }
        }
        int tabY = editorTabBarTop();
        if (mouseY >= tabY && mouseY < tabY + EDITOR_TAB_BAR_H) {
            scrollTabs(vertical * 24);
            return true;
        }
        int panelY = bottomPanelY();
        if (mouseY >= panelY && mouseY < height - pad && mouseX >= pad && mouseX <= width - pad) {
            this.bottomScroll = (int) Math.max(0, (long) this.bottomScroll - (long) (vertical * 16));
            return true;
        }
        if (focus == FOCUS_EDITOR && this.editor.mouseScrolled(horizontalAmount, verticalAmount)) {
            return true;
        }
        return false;
    }

    private void toggleSearch() {
        this.searchOpen = !this.searchOpen;
        if (this.searchOpen) {
            this.focus = FOCUS_SEARCH;
            this.searchBox.setFocused(true);
            this.searchBox.setValue("");
            this.editor.setSearchQuery("");
        } else {
            this.focus = FOCUS_EDITOR;
            this.searchBox.setFocused(false);
            this.editor.setSearchQuery("");
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
    // Explorer context menu
    // ------------------------------------------------------------------

    private void openContextMenu(int x, int y, String path) {
        ClientProjectStore store = ClientProjectStore.getInstance();
        this.contextPath = path;
        this.openMenu = -1;

        List<MenuItem> items = new ArrayList<>();
        if (path != null) {
            items.add(new MenuItem("Open", () -> openProjectEntry(path)));
            items.add(new MenuItem("Rename...", () -> promptRename(path)));
            items.add(new MenuItem("Delete...", () -> confirmDelete(path)));
        }
        items.add(new MenuItem("New File...", () -> promptNewFile(path)));
        items.add(new MenuItem("New Folder...", () -> promptNewFolder(path)));
        items.add(new MenuItem("Refresh", store::refresh));
        this.contextItems = items;

        int w = contextWidth();
        int h = 6 + items.size() * CONTEXT_ITEM_HEIGHT;
        this.contextX = Math.max(2, Math.min(x, this.width - w - 2));
        this.contextY = Math.max(2, Math.min(y, this.height - h - 2));
        this.contextOpen = true;
    }

    private int contextWidth() {
        int max = 110;
        for (MenuItem item : this.contextItems) {
            max = Math.max(max, this.font.width(item.label()) + 20);
        }
        return max;
    }

    private int contextItemAt(double mouseX, double mouseY) {
        if (!this.contextOpen) {
            return -1;
        }
        int w = contextWidth();
        if (mouseX < this.contextX || mouseX > this.contextX + w) {
            return -1;
        }
        for (int i = 0; i < this.contextItems.size(); i++) {
            int y = this.contextY + 3 + i * CONTEXT_ITEM_HEIGHT;
            if (mouseY >= y && mouseY <= y + CONTEXT_ITEM_HEIGHT) {
                return i;
            }
        }
        return -1;
    }

    private void renderContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        int w = contextWidth();
        int h = 6 + this.contextItems.size() * CONTEXT_ITEM_HEIGHT;
        graphics.fill(this.contextX - 1, this.contextY - 1, this.contextX + w + 1, this.contextY + h + 1, BORDER);
        graphics.fill(this.contextX, this.contextY, this.contextX + w, this.contextY + h, PANEL);
        for (int i = 0; i < this.contextItems.size(); i++) {
            int iy = this.contextY + 3 + i * CONTEXT_ITEM_HEIGHT;
            boolean hovered = mouseX >= this.contextX && mouseX <= this.contextX + w && mouseY >= iy
                    && mouseY <= iy + CONTEXT_ITEM_HEIGHT;
            if (hovered) {
                graphics.fill(this.contextX + 1, iy, this.contextX + w - 1, iy + CONTEXT_ITEM_HEIGHT, SELECTED);
            }
            graphics.drawString(this.font, this.contextItems.get(i).label(), this.contextX + 8, iy + 3,
                    hovered ? ACCENT : TEXT, false);
        }
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
            sendProjectAction("CREATE_FILE", "{\"path\":\"" + json(full) + "\"}");
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
            sendProjectAction("CREATE_FOLDER", "{\"path\":\"" + json(full) + "\"}");
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
            sendProjectAction("RENAME_FILE", "{\"from\":\"" + json(path) + "\",\"to\":\"" + json(target) + "\"}");
        });
    }

    private void confirmDelete(String path) {
        openConfirm("Delete '" + path + "'? This cannot be undone.",
                () -> sendProjectAction("DELETE_FILE", "{\"path\":\"" + json(path) + "\"}"));
    }

    private void fileError(String message) {
        ClientProjectStore.getInstance().append("ERROR: " + message);
        showBottomTab(0);
    }

    // ------------------------------------------------------------------
    // Modal prompt
    // ------------------------------------------------------------------

    private int promptW() {
        return Math.min(260, this.width - 20);
    }

    private int promptX() {
        return (this.width - promptW()) / 2;
    }

    private int promptY() {
        return this.height / 2 - 30;
    }

    private void unfocusAll() {
        this.editor.setFocused(false);
        this.searchBox.setFocused(false);
        this.consoleInput.setFocused(false);
    }

    private void openPrompt(String title, String initial, Consumer<String> accept) {
        this.openMenu = -1;
        this.contextOpen = false;
        this.promptOpen = true;
        this.promptTitle = title;
        this.promptAccept = accept;
        this.focus = FOCUS_MODAL;
        unfocusAll();
        this.promptBox.visible = true;
        this.promptBox.setValue(initial);
        this.setFocused(this.promptBox);
        this.promptBox.setFocused(true);
    }

    private void closePrompt() {
        this.promptOpen = false;
        this.promptAccept = null;
        this.promptBox.setFocused(false);
        this.promptBox.visible = false;
        this.promptBox.setPosition(-200, -200);
        this.setFocused(null);
        this.focus = FOCUS_EDITOR;
    }

    private void submitPrompt() {
        String value = this.promptBox.getValue().trim();
        Consumer<String> accept = this.promptAccept;
        closePrompt();
        if (!value.isEmpty() && accept != null) {
            accept.accept(value);
        }
    }

    private void renderPrompt(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int pw = promptW();
        int px = promptX();
        int py = promptY();
        graphics.fill(0, 0, this.width, this.height, DIMMER);
        graphics.fill(px - 1, py - 1, px + pw + 1, py + 57, BORDER);
        graphics.fill(px, py, px + pw, py + 56, PANEL);
        graphics.drawString(this.font, this.font.plainSubstrByWidth(this.promptTitle, pw - 16), px + 8, py + 7, ACCENT,
                false);
        this.promptBox.visible = true;
        this.promptBox.setX(px + 8);
        this.promptBox.setY(py + 22);
        this.promptBox.setWidth(pw - 16);
        this.promptBox.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(this.font, "Enter = OK    Esc = Cancel", px + 8, py + 42, MUTED, false);
    }

    // ------------------------------------------------------------------
    // Confirm dialog
    // ------------------------------------------------------------------

    private int confirmW() {
        return Math.min(300, this.width - 20);
    }

    private int confirmX() {
        return (this.width - confirmW()) / 2;
    }

    private int confirmY() {
        return this.height / 2 - 30;
    }

    private boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private boolean confirmYesAt(double mx, double my) {
        return inRect(mx, my, confirmX() + confirmW() / 2 - 66, confirmY() + 38, 60, 14);
    }

    private boolean confirmNoAt(double mx, double my) {
        return inRect(mx, my, confirmX() + confirmW() / 2 + 6, confirmY() + 38, 60, 14);
    }

    private void openConfirm(String message, Runnable accept) {
        this.openMenu = -1;
        this.contextOpen = false;
        this.confirmOpen = true;
        this.confirmMessage = message;
        this.confirmAccept = accept;
        this.focus = FOCUS_MODAL;
        unfocusAll();
    }

    private void closeConfirm() {
        this.confirmOpen = false;
        this.confirmAccept = null;
        this.focus = FOCUS_EDITOR;
    }

    private void acceptConfirm() {
        Runnable accept = this.confirmAccept;
        closeConfirm();
        if (accept != null) {
            accept.run();
        }
    }

    private void renderConfirm(GuiGraphics graphics, int mouseX, int mouseY) {
        int cw = confirmW();
        int cx = confirmX();
        int cy = confirmY();
        graphics.fill(0, 0, this.width, this.height, DIMMER);
        graphics.fill(cx - 1, cy - 1, cx + cw + 1, cy + 61, BORDER);
        graphics.fill(cx, cy, cx + cw, cy + 60, PANEL);
        graphics.drawString(this.font, "Confirm", cx + 8, cy + 7, ACCENT, false);
        graphics.drawString(this.font, this.font.plainSubstrByWidth(this.confirmMessage, cw - 16), cx + 8, cy + 22,
                TEXT, false);

        int yesX = cx + cw / 2 - 66;
        int noX = cx + cw / 2 + 6;
        int by = cy + 38;
        boolean yesHover = confirmYesAt(mouseX, mouseY);
        boolean noHover = confirmNoAt(mouseX, mouseY);
        graphics.fill(yesX, by, yesX + 60, by + 14, yesHover ? ERROR_COLOR : SELECTED);
        graphics.fill(noX, by, noX + 60, by + 14, noHover ? BORDER : PANEL_ALT);
        graphics.drawString(this.font, "Yes", yesX + (60 - this.font.width("Yes")) / 2, by + 3, TEXT, false);
        graphics.drawString(this.font, "No", noX + (60 - this.font.width("No")) / 2, by + 3, TEXT, false);
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
        this.bottomScroll = Integer.MAX_VALUE;
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
            case "api" -> showBottomTab(3);
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
        store.append("  api              show the API reference");
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
            this.bottomScroll = Integer.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------
    // Position helpers
    // ------------------------------------------------------------------

    private int fileListTop() {
        return topBarHeight() + layoutPad() + 40;
    }

    private int fileListHeight() {
        int pad = layoutPad();
        int bottomH = bottomPanelHeight();
        return Math.max(40, height - bottomH - pad - fileListTop());
    }

    private int tabX() {
        return layoutPad() + leftPanelWidth() + layoutPad() + 6;
    }

    // Shared hit-box geometry: render and click code both use these, so a
    // clickable area always matches what is drawn.

    private static final int BOTTOM_TAB_W = 60;
    private static final int BOTTOM_TAB_H = 18;
    private static final int BOTTOM_TAB_COUNT = 4;
    private static final int EDITOR_TAB_BAR_H = 20;

    /** Top edge of the bottom panel exactly as render() draws it. */
    private int bottomPanelY() {
        return height - bottomPanelHeight() - layoutPad() + 1;
    }

    private int bottomTabX(int index) {
        return layoutPad() + 6 + index * 62;
    }

    /** Index of the bottom tab under the mouse, or -1 (uses the drawn 60x18 box). */
    private int bottomTabAt(double mouseX, double mouseY) {
        int y = bottomPanelY();
        if (mouseY < y || mouseY >= y + BOTTOM_TAB_H) {
            return -1;
        }
        for (int i = 0; i < BOTTOM_TAB_COUNT; i++) {
            int x = bottomTabX(i);
            if (mouseX >= x && mouseX < x + BOTTOM_TAB_W) {
                return i;
            }
        }
        return -1;
    }

    private int editorTabBarTop() {
        return topBarHeight() + 1 + layoutPad();
    }

    private int explorerTop() {
        return topBarHeight() + 1 + layoutPad();
    }

    private int explorerBottom() {
        return height - bottomPanelHeight() - layoutPad();
    }

    private boolean insideExplorer(double mouseX, double mouseY) {
        int pad = layoutPad();
        return mouseX >= pad && mouseX < pad + leftPanelWidth() && mouseY >= explorerTop()
                && mouseY < explorerBottom();
    }

    /** Index of the file row under the mouse (inside the drawn row area), or -1. */
    private int fileRowAt(double mouseX, double mouseY) {
        int x = layoutPad() + 4;
        int w = leftPanelWidth() - 8;
        int y = fileListTop();
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + fileListHeight()) {
            return -1;
        }
        int index = (int) Math.floor((mouseY - y + this.fileScroll) / 16.0);
        int count = ClientProjectStore.getInstance().files().size();
        return index >= 0 && index < count ? index : -1;
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Skip the vanilla blurred-menu layer. Screen.render() calls this during
        // super.render(), which painted the world blur *over* the editor content.
        // The IDE fills its own opaque background in render() instead.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int w = this.width;
        int h = this.height;
        int pad = layoutPad();
        int topBar = topBarHeight();
        int leftW = leftPanelWidth();
        int bottomH = bottomPanelHeight();

        graphics.fill(0, 0, w, h, BG);
        graphics.fill(0, 0, w, topBar, PANEL_ALT);
        graphics.fill(0, topBar - 1, w, topBar, BORDER);

        int bodyTop = topBar + 1;
        int bodyBottom = h - bottomH - pad;

        // Explorer (files of the active project).
        graphics.fill(pad, bodyTop + pad, pad + leftW, bodyBottom, PANEL);
        graphics.drawString(this.font, "EXPLORER", pad + 6, bodyTop + pad + 6, ACCENT, false);
        String activeProj = ClientProjectStore.getInstance().activeProjectId();
        graphics.drawString(this.font, activeProj == null ? "no project" : activeProj, pad + 6, bodyTop + pad + 18,
                MUTED, false);
        int fileTop = fileListTop();
        graphics.drawString(this.font, "FILES", pad + 6, fileTop - 12, ACCENT, false);
        renderFileList(graphics, pad + 4, fileTop, leftW - 8, fileListHeight());

        // Editor area
        int editorX = pad + leftW + pad;
        int editorW = w - editorX - pad;
        graphics.fill(editorX, bodyTop + pad, editorX + editorW, bodyBottom, PANEL);
        // tab bar
        graphics.fill(editorX, bodyTop + pad, editorX + editorW, bodyTop + pad + 20, PANEL_ALT);
        renderTabs(graphics, mouseX, mouseY, editorX, editorW);
        renderStatusBar(graphics, editorX, editorW, bodyTop + pad + 3);
        if (searchOpen) {
            this.searchBox.setX(tabX() + 8);
            this.searchBox.setY(bodyTop + pad + 3);
            this.searchBox.setWidth(Math.min(160, editorW - (tabX() - editorX) - 20));
            this.searchBox.visible = true;
        } else {
            this.searchBox.setPosition(-200, -200);
        }

        layoutWidgets();
        this.editor.setFocused(focus == FOCUS_EDITOR && displayedFile != null);
        this.editor.render(graphics, mouseX, mouseY);

        // Bottom panel
        int panelY = bodyBottom + 1;
        graphics.fill(pad, panelY, w - pad, h - pad, PANEL);
        graphics.fill(pad, panelY, w - pad, panelY + 18, PANEL_ALT);
        String[] tabs = { "CONSOLE", "PROBLEMS", "ASSETS", "API" };
        for (int i = 0; i < tabs.length; i++) {
            int tx = bottomTabX(i);
            boolean active = bottomTab == i;
            graphics.fill(tx, panelY, tx + BOTTOM_TAB_W, panelY + BOTTOM_TAB_H, active ? SELECTED : PANEL_ALT);
            graphics.drawString(this.font, tabs[i], tx + 6, panelY + 5, active ? ACCENT : MUTED, false);
        }
        int contentX = pad + 6;
        int contentY = panelY + 20;
        int contentW = w - pad - 12;
        int contentH = h - pad - contentY;
        switch (bottomTab) {
            case 0 -> renderConsole(graphics, contentX, contentY, contentW, contentH);
            case 1 -> renderProblems(graphics, contentX, contentY, contentW, contentH);
            case 2 -> renderAssetPanel(graphics, contentX, contentY, contentW, contentH);
            default -> renderApiReference(graphics, contentX, contentY, contentW, contentH);
        }
        String matches = this.editor.searchQuery().isEmpty() ? "" : " matches: " + this.editor.matchCount();
        if (!matches.isEmpty()) {
            graphics.drawString(this.font, matches, w - pad - this.font.width(matches) - 2, panelY + 5, MUTED, false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        renderConsoleHint(graphics);
        renderMenuBar(graphics, mouseX, mouseY);
        if (this.contextOpen) {
            renderContextMenu(graphics, mouseX, mouseY);
        }
        if (this.promptOpen) {
            renderPrompt(graphics, mouseX, mouseY, partialTick);
        }
        if (this.confirmOpen) {
            renderConfirm(graphics, mouseX, mouseY);
        }
    }

    /** Draws the grey completion suffix right after the typed console text. */
    private void renderConsoleHint(GuiGraphics graphics) {
        if (this.bottomTab != 0 || this.consoleHint.isEmpty() || !this.consoleInput.visible
                || !this.consoleInput.isFocused()) {
            return;
        }
        String value = this.consoleInput.getValue();
        int x = this.consoleInput.getX() + 4 + this.font.width(value);
        int y = this.consoleInput.getY() + 2;
        if (x < this.consoleInput.getX() + this.consoleInput.getWidth() - 8) {
            graphics.drawString(this.font, this.consoleHint, x, y, MUTED, false);
        }
    }

    // ------------------------------------------------------------------
    // Menu bar
    // ------------------------------------------------------------------

    private void renderMenuBar(GuiGraphics graphics, int mouseX, int mouseY) {
        int h = menuBarHeight();
        int textY = (h - 8) / 2;

        graphics.drawString(this.font, "CraftPy IDE", layoutPad(), textY, ACCENT, false);
        for (int i = 0; i < MENU_TITLES.length; i++) {
            int x = menuTitleX(i);
            int tw = menuTitleW(i);
            boolean hovered = mouseX >= x && mouseX <= x + tw && mouseY < h;
            if (this.openMenu == i) {
                graphics.fill(x, 1, x + tw, h - 1, SELECTED);
            } else if (hovered) {
                graphics.fill(x, 1, x + tw, h - 1, PANEL);
            }
            graphics.drawString(this.font, MENU_TITLES[i], x + 5, textY, this.openMenu == i ? ACCENT : TEXT, false);
        }

        // Hovering another title while a menu is open switches to it.
        if (this.openMenu >= 0 && !this.promptOpen && !this.confirmOpen) {
            int hoverTitle = menuTitleAt(mouseX);
            if (hoverTitle >= 0 && hoverTitle != this.openMenu && mouseY < h) {
                this.openMenu = hoverTitle;
            }
        }

        // Active project name, right-aligned before the Run/Assets cluster.
        int clusterLeft = this.assetsButton.getX();
        String label = ClientProjectStore.getInstance().activeProjectId();
        String project = label == null ? "no project loaded" : "project: " + label;
        int lastTitle = MENU_TITLES.length - 1;
        int menuEnd = menuTitleX(lastTitle) + menuTitleW(lastTitle);
        int projectX = menuEnd + 16;
        int maxProjectX = clusterLeft - 8;
        // Only draw if there is room; otherwise it would collide with the buttons.
        if (maxProjectX - projectX > this.font.width(project)) {
            graphics.drawString(this.font, project, projectX, textY, MUTED, false);
        }

        if (this.openMenu >= 0) {
            renderDropdown(graphics, mouseX, mouseY);
        }
    }

    private void renderDropdown(GuiGraphics graphics, int mouseX, int mouseY) {
        int index = this.openMenu;
        if (index < 0 || index >= this.menus.size()) {
            return;
        }
        List<MenuItem> items = this.menus.get(index);
        int x = dropdownX(index);
        int w = dropdownWidth(index);
        int h = 6 + items.size() * MENU_ITEM_HEIGHT;
        int y = menuBarHeight();
        graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        graphics.fill(x, y, x + w, y + h, PANEL);
        for (int i = 0; i < items.size(); i++) {
            int iy = dropdownItemY(i);
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= iy && mouseY <= iy + MENU_ITEM_HEIGHT;
            if (hovered) {
                graphics.fill(x + 1, iy, x + w - 1, iy + MENU_ITEM_HEIGHT, SELECTED);
            }
            graphics.drawString(this.font, items.get(i).label(), x + 8, iy + 3, hovered ? ACCENT : TEXT, false);
        }
    }

    /**
     * A small offline "definitions" reference for the declarative Python API,
     * grouped by category and scrollable (mouse wheel over the API tab).
     */
    private static final String[][] API_ROWS = {
            { "DECLARATIONS", "" },
            { "@block(\"id\")", "declare a block; fields: name, texture, hardness, resistance, light, sound, tool, drops" },
            { "@item(\"id\")", "declare an item; fields: name, texture, max_stack, fuel, food" },
            { "@entity(\"id\")", "reskinned base mob; fields: base (mob_passive/mob_hostile), health, speed, texture" },
            { "@effect(\"id\")", "mob effect; fields: name, color, duration, amplifier" },
            {},
            { "RECIPES & LOOT", "" },
            { "recipe_shaped(...)", "shaped crafting recipe from a pattern + key" },
            { "recipe_shapeless(...)", "shapeless crafting recipe from ingredients" },
            { "recipe_smelting(...)", "furnace recipe (input -> result, xp, time)" },
            { "loot_table(...)", "drops for a block/entity" },
            { "tag(\"#id\", items=[...])", "add ids to a datapack tag" },
            {},
            { "EVENTS & CONTEXT", "" },
            { "@on(\"event\")", "event handler: player_join, block_break, tick(every=20) ..." },
            { "ctx.player / ctx.world / ctx.pos", "context helpers available inside callbacks" },
            {},
            { "ASSET HELPERS", "" },
            { "tex(\"id\") / snd(\"id\") / mdl(\"id\")", "asset reference helpers used with the picker" },
    };

    /** Renders a scrollable list of text rows with a scrollbar when needed. */
    private void renderRows(GuiGraphics graphics, List<Row> rows, int x, int y, int width, int height) {
        int lineH = 11;
        int contentH = rows.size() * lineH + 2;
        int viewH = Math.max(1, height - 4);
        int maxScroll = Math.max(0, contentH - viewH);
        if (this.bottomScroll > maxScroll) {
            this.bottomScroll = maxScroll;
        }
        graphics.enableScissor(x, y, x + width, y + height);
        int lineY = y + 2 - this.bottomScroll;
        for (Row row : rows) {
            if (lineY + lineH >= y && lineY <= y + height) {
                graphics.drawString(this.font, row.text(), x, lineY, row.color(), false);
            }
            lineY += lineH;
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int barH = Math.max(14, (int) ((height - 8) * Math.max(0.15, (double) viewH / contentH)));
            int barY = y + (int) ((height - barH) * ((double) this.bottomScroll / maxScroll));
            graphics.fill(x + width - 4, barY, x + width - 1, barY + barH, ACCENT);
        }
    }

    private void renderApiReference(GuiGraphics graphics, int x, int y, int width, int height) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("PYFORGE API DEFINITIONS (offline reference, scroll to browse)", ACCENT));
        for (String[] row : API_ROWS) {
            if (row.length == 0) {
                rows.add(new Row("", MUTED));
                continue;
            }
            boolean header = row[1].isEmpty();
            rows.add(new Row(header ? row[0] : "  " + row[0] + "  -  " + row[1], header ? ACCENT : TEXT));
        }
        renderRows(graphics, rows, x, y, width, height);
    }

    /** ASSETS tab group overview: one line per category plus the bank launcher. */
    private void renderAssetPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        AssetIndex index = AssetIndex.get();
        int lineY = y + 2;
        graphics.drawString(this.font, "ASSETS - grouped by category (counts refresh as packs reload)", x, lineY, ACCENT,
                false);
        lineY += 14;
        String[][] groups = {
                { "TEXTURES", String.valueOf(index.ids(AssetKind.TEXTURE).size()) },
                { "ITEMS", String.valueOf(index.ids(AssetKind.ITEM).size()) },
                { "BLOCKS", String.valueOf(index.ids(AssetKind.BLOCK).size()) },
                { "MODELS", String.valueOf(index.ids(AssetKind.MODEL).size()) },
                { "SOUNDS", String.valueOf(index.ids(AssetKind.SOUND).size()) },
        };
        for (String[] group : groups) {
            graphics.drawString(this.font, String.format("%-9s", group[0]), x, lineY, TEXT, false);
            graphics.drawString(this.font, group[1] + "  (shift-click inside the bank to preview/edit)", x + 70, lineY,
                    MUTED, false);
            lineY += 11;
        }
        graphics.drawString(this.font, "Open the full asset bank from the Assets menu or the Assets button above.", x,
                lineY + 4, MUTED, false);
    }

    /**
     * The open-file tab strip with per-tab close buttons. Unsaved tabs show a
     * trailing "*"; the close box highlights on hover. Long strips clip at the
     * editor edge and scroll with the mouse wheel.
     */
    private void renderTabs(GuiGraphics graphics, int mouseX, int mouseY, int editorX, int editorW) {
        ClientProjectStore store = ClientProjectStore.getInstance();
        String active = store.activeFile();
        int y = editorTabBarTop();
        graphics.enableScissor(editorX, y, editorX + editorW, y + EDITOR_TAB_BAR_H);
        for (TabRect rect : tabRects()) {
            boolean isActive = rect.path().equals(active);
            if (isActive) {
                graphics.fill(rect.x(), y + 1, rect.x() + rect.width(), y + EDITOR_TAB_BAR_H - 1, SELECTED);
            }
            boolean closeHover = mouseX >= rect.closeX() && mouseX <= rect.closeX() + 10
                    && mouseY >= y && mouseY < y + EDITOR_TAB_BAR_H;
            if (closeHover) {
                graphics.fill(rect.closeX() - 1, y + 4, rect.closeX() + 9, y + 14, 0x40FF6B6B);
            }
            graphics.drawString(this.font, rect.path().substring(Math.max(0, rect.path().lastIndexOf('/') + 1))
                    + (rect.dirty() ? "*" : ""), rect.labelX(), y + 6, isActive ? ACCENT : MUTED, false);
            graphics.drawString(this.font, "x", rect.closeX() + 2, y + 6, closeHover ? ERROR_COLOR : MUTED, false);
        }
        graphics.disableScissor();
    }

    /** PyCharm-style editor status line: language, active file, cursor position. */
    private void renderStatusBar(GuiGraphics graphics, int editorX, int editorW, int y) {
        String file = this.displayedFile == null ? "no file" : this.displayedFile;
        String title = "Python";
        String currentLine = Integer.toString(this.editor.cursorLineIndex() + 1);
        String currentCol = Integer.toString(this.editor.cursorColumn() + 1);
        String status = title + " | " + file + " | Ln " + currentLine + ", Col " + currentCol;
        graphics.drawString(this.font, status, editorX + editorW - this.font.width(status) - 4, y, MUTED, false);
    }

    private void renderFileList(GuiGraphics graphics, int x, int y, int width, int height) {
        List<String> files = ClientProjectStore.getInstance().files();
        int rowH = 16;
        int maxScroll = Math.max(0, files.size() * rowH - height);
        if (this.fileScroll > maxScroll) {
            this.fileScroll = maxScroll;
        }
        graphics.enableScissor(x, y, x + width, y + height);
        String active = ClientProjectStore.getInstance().activeFile();
        for (int i = 0; i < files.size(); i++) {
            int rowY = y + i * rowH - this.fileScroll;
            if (rowY + rowH < y || rowY > y + height) {
                continue;
            }
            boolean isActive = files.get(i).equals(active);
            if (isActive) {
                graphics.fill(x, rowY, x + width, rowY + rowH, SELECTED);
            }
            String label = files.get(i);
            String shortLabel = label.startsWith("code/") ? label.substring(5) : label;
            graphics.drawString(this.font, shortLabel, x + 4, rowY + 3, isActive ? TEXT : MUTED, false);
        }
        graphics.disableScissor();
        if (files.isEmpty()) {
            graphics.drawString(this.font, "Open a project", x + 4, y + 2, MUTED, false);
        }
    }

    private void renderConsole(GuiGraphics graphics, int x, int y, int width, int height) {
        List<Row> rows = new ArrayList<>();
        for (String line : ClientProjectStore.getInstance().log()) {
            rows.add(new Row(line, line.contains("ERROR") || line.contains("Error") ? ERROR_COLOR : TEXT));
        }
        // Follow the newest line whenever the log grows.
        if (rows.size() != this.lastLogSize) {
            this.lastLogSize = rows.size();
            this.bottomScroll = Integer.MAX_VALUE;
        }
        if (rows.isEmpty()) {
            rows.add(new Row("Console is empty. Use Run or Validate, or type a command below ('help').", MUTED));
        }
        // The last 16px belong to the input box.
        renderRows(graphics, rows, x, y, width, Math.max(12, height - 16));
    }

    private void renderProblems(GuiGraphics graphics, int x, int y, int width, int height) {
        List<Row> rows = new ArrayList<>();
        for (net.xmilon.craftpy.python.ValidationProblem problem : ClientProjectStore.getInstance()
                .validationProblems()) {
            int color = problem.isError() ? ERROR_COLOR : 0xFFFFB86C;
            String label = (problem.line() + 1) + ":" + problem.column() + " " + problem.message()
                    + (problem.file().isEmpty() ? "" : " [" + problem.file() + "]");
            rows.add(new Row(label, color));
        }
        for (CodeProblems.Problem problem : CodeProblems.snapshot()) {
            int color = switch (problem.severity()) {
                case ERROR -> ERROR_COLOR;
                case WARNING -> 0xFFFFB86C;
                case INFO -> MUTED;
            };
            rows.add(new Row((problem.line() + 1) + ":" + problem.column() + " " + problem.message(), color));
        }
        String active = ClientProjectStore.getInstance().activeFile();
        if (active != null) {
            AssetIndex.get().ensureBuilt();
            String content = ClientProjectStore.getInstance().fileContent(active);
            if (content != null) {
                int lineNo = 0;
                for (String line : content.split("\n", -1)) {
                    for (AssetReference ref : AssetReferences.parse(line)) {
                        if (!AssetIndex.get().exists(ref.kind(), ref.value())) {
                            rows.add(new Row((lineNo + 1) + " missing "
                                    + ref.kind().name().toLowerCase(java.util.Locale.ROOT) + " '" + ref.value() + "'",
                                    0xFFFFB86C));
                        }
                    }
                    lineNo++;
                }
            }
        }
        if (rows.isEmpty()) {
            rows.add(new Row("No problems detected. Use Validate (Ctrl+Enter) to run the Python compiler.", MUTED));
        }
        renderRows(graphics, rows, x, y, width, height);
    }
}