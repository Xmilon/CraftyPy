package net.xmilon.craftpy.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import java.util.function.Consumer;

/**
 * The main layout of the CraftPy IDE, built using the UI toolkit.
 */
public class MainIDE extends Widget {
    private final Panel topBar = new Panel(Theme.PANEL_ALT).withBorder();
    private final HBox topBarContent = new HBox().padding(2).gap(4);
    
    private final HBox body = new HBox().padding(4).gap(4);
    private final Explorer explorer;
    private final VBox editorContainer = new VBox().padding(0).gap(0);
    private final TabStrip editorTabs = new TabStrip(new java.util.ArrayList<>(), -1, idx -> {});
    private final Panel editorArea = new Panel(Theme.PANEL).withBorder();
    private final Deck editorDeck = new Deck();
    private final EditorWidget editorWidget;
    private final AssetViewer assetViewer = new AssetViewer();
    
    private final VBox bottomContainer = new VBox().padding(0).gap(0);
    private final TabStrip bottomTabs = new TabStrip(new java.util.ArrayList<>(java.util.List.of("CONSOLE", "PROBLEMS", "ASSETS", "API", "PREVIEW", "SETTINGS")), 0, idx -> {});
    private final Panel bottomPanel = new Panel(Theme.PANEL).withBorder();
    private final Deck bottomDeck = new Deck();
    private final java.util.Map<String, ConsoleWidget> consoles = new java.util.HashMap<>();
    private final StatusBar statusBar;
    private final ProxyWidget bottomProxy;
    private Widget overlay;

    public MainIDE(EditorWidget editorWidget, Explorer explorer, StatusBar statusBar, ProxyWidget.Renderer bottomRenderer) {
        this.editorWidget = editorWidget;
        this.explorer = explorer;
        this.statusBar = statusBar;
        this.bottomProxy = new ProxyWidget(bottomRenderer);
        
        addChild(topBar);
        topBar.addChild(topBarContent);
        
        addChild(body);
        body.addChild(explorer);
        body.addChild(editorContainer);
        editorContainer.addChild(editorTabs);
        editorContainer.addChild(editorArea);
        
        editorArea.addChild(editorDeck);
        editorDeck.addChild(editorWidget);
        editorDeck.addChild(assetViewer);
        editorDeck.setActiveIndex(0);
        
        addChild(bottomContainer);
        bottomContainer.addChild(bottomTabs);
        bottomContainer.addChild(bottomPanel);
        
        bottomPanel.addChild(bottomDeck);
        bottomDeck.addChild(bottomProxy); // Index 0 of deck (initially bottomProxy is at 1 in previous code, let's fix)
        
        addChild(statusBar);
        
        getConsole("default"); // Create default console
        
        editorTabs.height = 20;
        bottomTabs.height = 18;
    }
    
    public ConsoleWidget getConsole(String sessionId) {
        return consoles.computeIfAbsent(sessionId, id -> {
            ConsoleWidget cw = new ConsoleWidget(id);
            bottomDeck.addChild(cw);
            return cw;
        });
    }

    @Override
    public void layout() {
        int topH = 18;
        int pad = 4;
        
        topBar.setBounds(x, y, width, topH);
        topBarContent.setBounds(x, y, width, topH);
        
        int bodyY = y + topH + pad;
        int statusH = 12;
        int bottomH = 120;
        int bodyH = height - topH - bottomH - statusH - 2 * pad;
        body.setBounds(x, bodyY, width, bodyH);
        
        explorer.width = 120;
        explorer.setBounds(body.x() + body.padding(), bodyY, explorer.width, bodyH);
        
        editorContainer.width = width - explorer.width - body.gap() - 2 * body.padding();
        editorContainer.setBounds(explorer.x() + explorer.width() + body.gap(), bodyY, editorContainer.width, bodyH);
        
        editorTabs.setBounds(editorContainer.x(), editorContainer.y(), editorContainer.width(), 20);
        editorArea.setBounds(editorContainer.x(), editorContainer.y() + 20, editorContainer.width(), bodyH - 20);
        editorDeck.setBounds(editorArea.x(), editorArea.y(), editorArea.width(), editorArea.height());
        // Sub-widgets of Deck are laid out by Deck itself
        
        int bcY = y + height - bottomH - statusH - pad;
        bottomContainer.setBounds(x + pad, bcY, width - 2 * pad, bottomH + 18);
        bottomTabs.setBounds(bottomContainer.x(), bottomContainer.y(), bottomContainer.width(), 18);
        bottomPanel.setBounds(bottomContainer.x(), bottomContainer.y() + 18, bottomContainer.width(), bottomH);
        bottomDeck.setBounds(bottomPanel.x() + 4, bottomPanel.y() + 4, bottomPanel.width() - 8, bottomPanel.height() - 8);
        
        statusBar.setBounds(x, y + height - statusH, width, statusH);
        
        if (overlay != null) {
            overlay.setBounds(x + width / 4, y + 20, width / 2, height / 2);
        }
    }
    
    public void updateTabs(java.util.List<String> openFiles, String activeFile, int activeBottomTab, Consumer<Integer> onSelectEditor, Consumer<Integer> onCloseEditor, Consumer<Integer> onSelectBottom) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        int activeIdx = -1;
        boolean isAsset = false;
        for (int i = 0; i < openFiles.size(); i++) {
            String path = openFiles.get(i);
            labels.add(path.substring(Math.max(0, path.lastIndexOf('/') + 1)));
            if (path.equals(activeFile)) {
                activeIdx = i;
                isAsset = path.startsWith("assets/") && (path.endsWith(".png") || path.endsWith(".ogg") || path.endsWith(".json"));
            }
        }
        editorTabs.setLabels(labels);
        editorTabs.setActiveIndex(activeIdx);
        editorTabs.setOnClose(onCloseEditor);
        editorTabs.setOnSelect(onSelectEditor);
        
        if (isAsset) {
            assetViewer.setAsset(activeFile);
            editorDeck.setActiveIndex(1);
        } else {
            editorDeck.setActiveIndex(activeFile != null ? 0 : -1);
        }
        
        bottomTabs.setActiveIndex(activeBottomTab);
        bottomTabs.setOnSelect(onSelectBottom);
        
        if (activeBottomTab == 0) {
            // Hardcoded to "default" console for now
            bottomDeck.setActiveIndex(bottomDeck.children.indexOf(getConsole("default")));
        } else {
            bottomDeck.setActiveIndex(bottomDeck.children.indexOf(bottomProxy));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;
        renderWidget(graphics, mouseX, mouseY, partialTick);
        for (Widget child : children) {
            child.render(graphics, mouseX, mouseY, partialTick);
        }
        if (overlay != null && overlay.visible) {
            overlay.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    public void setOverlay(Widget overlay) {
        this.overlay = overlay;
        if (overlay != null) {
            overlay.parent = this;
            layout();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (overlay != null && overlay.visible) {
            if (overlay.mouseClicked(mouseX, mouseY, button)) return true;
            if (!overlay.contains(mouseX, mouseY)) {
                overlay.visible = false; // click outside closes
                return true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (overlay != null && overlay.visible) {
            return overlay.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (overlay != null && overlay.visible) {
            return overlay.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (overlay != null && overlay.visible) {
            return overlay.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }
    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Draw main background
        graphics.fill(x, y, x + width, y + height, Theme.current().color(Theme.BG));
    }
}
