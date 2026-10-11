package net.xmilon.craftpy.client.screen;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.menu.ComputerMenu;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.project.ProjectSummary;

/**
 * Standalone project browser, shown as its own full window when the computer is
 * opened (the IDE is a separate window reached through "Open"). Having this as a
 * real screen - rather than an overlay on top of the editor - avoids the editor
 * and the chooser fighting for the same space.
 */
public class ProjectChooserScreen extends Screen implements MenuAccess<ComputerMenu> {
    private static final int BG = 0xFF10141A;
    private static final int PANEL = 0xFF1B222C;
    private static final int PANEL_ALT = 0xFF232C38;
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int TEXT = 0xFFD7E1EA;
    private static final int MUTED = 0xFF7D8B9A;
    private static final int BORDER = 0xFF2E3A48;
    private static final int SELECTED = 0xFF2C5B7A;
    private static final int ERROR_COLOR = 0xFFFF6B6B;

    private final ComputerMenu menu;
    private final Inventory inventory;
    private final Runnable storeListener = this::refreshFromCache;

    private EditBox nameBox;
    private int listScroll;
    private String confirmDeleteId;

    // Layout cached for input hit-testing.
    private int leftW;
    private int listTop;
    private int listBottom;
    private int rightX;
    private int rightW;

    private String lastClickId;
    private long lastClickTime;

    private boolean refreshing;
    private boolean openAfterCreate;

    public ProjectChooserScreen(ComputerMenu menu, Inventory inventory, Component title) {
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
        ClientProjectStore store = ClientProjectStore.getInstance();
        store.setListener(storeListener);
        store.refresh();

        computeLayout();

        this.nameBox = new EditBox(this.font, rightX + 8, listTop + 26, Math.max(80, rightW - 16), 16,
                Component.literal("project name"));
        this.nameBox.setMaxLength(48);
        this.nameBox.setHint(Component.literal("project name (for New / Rename)"));
        addRenderableWidget(this.nameBox);

        int bw = Math.max(70, (rightW - 24) / 2);
        int bx1 = rightX + 8;
        int bx2 = rightX + 16 + bw;
        int by = listTop + 48;
        addRenderableWidget(Button.builder(Component.literal("New"), b -> createProject())
                .bounds(bx1, by, bw, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Open"), b -> openProject())
                .bounds(bx2, by, bw, 16).build());
        by += 18;
        addRenderableWidget(Button.builder(Component.literal("Rename"), b -> renameProject())
                .bounds(bx1, by, bw, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Duplicate"), b -> duplicateProject())
                .bounds(bx2, by, bw, 16).build());
        by += 18;
        addRenderableWidget(Button.builder(Component.literal("Reload"), b -> loadProject())
                .bounds(bx1, by, bw, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Unload"), b -> unloadProject())
                .bounds(bx2, by, bw, 16).build());
        by += 18;
        addRenderableWidget(Button.builder(Component.literal("Import..."), b -> importAsset())
                .bounds(bx1, by, bw, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> deleteProject())
                .bounds(bx2, by, bw, 16).build());
        by += 18;
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> store.refresh())
                .bounds(bx1, by, bw, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(bx2, by, bw, 16).build());
    }

    private void computeLayout() {
        int titleH = 26;
        leftW = Math.max(160, (int) (width * 0.55));
        listTop = titleH + 10;
        listBottom = height - 10;
        rightX = leftW + 12;
        rightW = width - rightX - 12;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private ProjectSummary selected() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        return store.find(store.selectedId());
    }

    private void createProject() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        String name = this.nameBox.getValue().trim();
        if (name.isEmpty()) {
            store.append("Enter a project name first.");
            return;
        }
        store.request(ProjectAction.CREATE, "", name);
        this.nameBox.setValue("");
        this.openAfterCreate = true;
        store.refresh();
    }

    private void openProject() {
        ProjectSummary sel = selected();
        if (sel == null) {
            ClientProjectStore.getInstance().append("Select a project to open.");
            return;
        }
        ClientProjectStore store = ClientProjectStore.getInstance();
        if (store.selectedId() == null || !store.selectedId().equals(sel.id)) {
            store.select(sel.id);
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ComputerScreen(menu, inventory, Component.literal("CraftPy IDE")));
        }
    }

    private void renameProject() {
        ProjectSummary sel = selected();
        ClientProjectStore store = ClientProjectStore.getInstance();
        if (sel == null) {
            store.append("Select a project to rename.");
            return;
        }
        String name = this.nameBox.getValue().trim();
        if (name.isEmpty()) {
            store.append("Type the new name in the box, then press Rename.");
            return;
        }
        store.request(ProjectAction.RENAME, sel.id, name);
        this.nameBox.setValue("");
    }

    private void duplicateProject() {
        ProjectSummary sel = selected();
        if (sel != null) {
            ClientProjectStore.getInstance().request(ProjectAction.DUPLICATE, sel.id, "");
        }
    }

    private void loadProject() {
        ProjectSummary sel = selected();
        if (sel != null) {
            ClientProjectStore.getInstance().request(ProjectAction.LOAD, sel.id, "");
        }
    }

    private void unloadProject() {
        ProjectSummary sel = selected();
        if (sel != null) {
            ClientProjectStore.getInstance().request(ProjectAction.UNLOAD, sel.id, "");
        }
    }

    private void deleteProject() {
        ProjectSummary sel = selected();
        if (sel == null) {
            return;
        }
        ClientProjectStore store = ClientProjectStore.getInstance();
        if (sel.id.equals(confirmDeleteId)) {
            confirmDeleteId = null;
            store.request(ProjectAction.DELETE, sel.id, "");
            return;
        }
        confirmDeleteId = sel.id;
        store.append("Press Delete again to confirm removal of " + sel.id);
    }

    private void importAsset() {
        ProjectSummary sel = selected();
        if (sel == null) {
            ClientProjectStore.getInstance().append("Select a project before importing assets.");
            return;
        }
        net.xmilon.craftpy.client.assets.AssetUploader.openDialog(sel.id,
                net.xmilon.craftpy.assets.AssetKind.TEXTURE);
    }

    private void refreshFromCache() {
        if (refreshing || this.minecraft == null) {
            return;
        }
        refreshing = true;
        try {
            ClientProjectStore store = ClientProjectStore.getInstance();
            String selected = store.selectedId();
            if (selected != null && store.find(selected) == null) {
                store.select(null);
                selected = null;
            }
            // Right after a successful create, jump straight into the IDE so the
            // browser does not linger on screen.
            if (openAfterCreate && selected != null) {
                openAfterCreate = false;
                openProject();
            }
        } finally {
            refreshing = false;
        }
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Skip the vanilla blurred-menu layer (Screen.render() calls this during
        // super.render()); this window paints its own opaque background.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int w = this.width;
        int h = this.height;
        computeLayout();

        graphics.fill(0, 0, w, h, BG);
        graphics.fill(0, 0, w, 26, PANEL_ALT);
        graphics.fill(0, 25, w, 26, BORDER);
        graphics.drawString(this.font, "CraftPy", 10, 9, ACCENT, false);
        graphics.drawString(this.font, "Projects", 10 + this.font.width("CraftPy") + 8, 9, MUTED, false);
        String hint = "Create or pick a project, then Open - the IDE is a separate window.";
        graphics.drawString(this.font, hint, w - this.font.width(hint) - 10, 9, MUTED, false);

        // Left: project list.
        graphics.fill(6, listTop - 4, leftW, listBottom, PANEL);
        graphics.drawString(this.font, "PROJECTS", 12, listTop - 12, ACCENT, false);
        renderProjectList(graphics, 8, listTop, leftW - 4, listBottom - listTop);

        // Right: details + actions.
        graphics.fill(rightX, listTop - 4, w - 6, listBottom, PANEL);
        ProjectSummary sel = selected();
        graphics.drawString(this.font, "DETAILS", rightX + 8, listTop - 12, ACCENT, false);
        if (sel != null) {
            graphics.drawString(this.font, sel.name.isEmpty() ? sel.id : sel.name, rightX + 8, listTop + 2, TEXT, false);
            graphics.drawString(this.font, sel.id, rightX + 8, listTop + 14, MUTED, false);
        } else {
            graphics.drawString(this.font, "No project selected", rightX + 8, listTop + 2, MUTED, false);
        }

        // Console log strip under the action buttons.
        int logTop = listBottom - 60;
        graphics.fill(rightX + 6, logTop - 12, w - 8, listBottom - 2, PANEL_ALT);
        graphics.drawString(this.font, "CONSOLE", rightX + 8, logTop - 10, ACCENT, false);
        List<String> log = ClientProjectStore.getInstance().log();
        int maxLines = Math.max(1, (listBottom - logTop) / 10);
        int from = Math.max(0, log.size() - maxLines);
        int ly = logTop + 1;
        for (int i = from; i < log.size(); i++) {
            graphics.drawString(this.font, log.get(i), rightX + 10, ly, TEXT, false);
            ly += 10;
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderProjectList(GuiGraphics graphics, int x, int y, int width, int height) {
        List<ProjectSummary> projects = ClientProjectStore.getInstance().projects();
        int rowH = 26;
        int maxScroll = Math.max(0, projects.size() * rowH - height);
        if (this.listScroll > maxScroll) {
            this.listScroll = maxScroll;
        }
        String selected = ClientProjectStore.getInstance().selectedId();
        for (int i = 0; i < projects.size(); i++) {
            int rowY = y + i * rowH - this.listScroll;
            if (rowY + rowH < y || rowY > y + height) {
                continue;
            }
            ProjectSummary project = projects.get(i);
            boolean isSelected = project.id.equals(selected);
            graphics.fill(x, rowY, x + width, rowY + rowH - 1, isSelected ? SELECTED : PANEL_ALT);
            graphics.drawString(this.font, project.name.isEmpty() ? project.id : project.name, x + 6, rowY + 3, TEXT,
                    false);
            String sub = project.id + (project.loaded ? "  \u2022 loaded" : "");
            graphics.drawString(this.font, sub, x + 6, rowY + 14, project.loaded ? ACCENT : MUTED, false);
        }
        if (projects.isEmpty()) {
            graphics.drawString(this.font, "No projects yet - type a name and press New.", x + 6, y + 4, MUTED, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled) {
            return true;
        }
        int pad = 8;
        if (mouseX >= pad && mouseX <= leftW && mouseY >= listTop && mouseY <= listBottom) {
            int index = (int) ((mouseY - listTop + listScroll) / 26.0);
            List<ProjectSummary> projects = ClientProjectStore.getInstance().projects();
            if (index >= 0 && index < projects.size()) {
                ProjectSummary project = projects.get(index);
                long now = System.currentTimeMillis();
                if (project.id.equals(lastClickId) && now - lastClickTime < 400) {
                    ClientProjectStore.getInstance().select(project.id);
                    openProject();
                    return true;
                }
                lastClickId = project.id;
                lastClickTime = now;
                confirmDeleteId = null;
                ClientProjectStore.getInstance().select(project.id);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        int pad = 8;
        if (mouseX >= pad && mouseX <= leftW && mouseY >= listTop && mouseY <= listBottom) {
            this.listScroll = (int) Math.max(0, this.listScroll - verticalAmount * 26);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
                && this.nameBox != null && this.nameBox.isFocused()) {
            if (selected() != null) {
                renameProject();
            } else {
                createProject();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        ClientProjectStore.getInstance().clearListener(storeListener);
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.closeContainer();
        }
        super.onClose();
    }

    @Override
    public void removed() {
        ClientProjectStore.getInstance().clearListener(storeListener);
        super.removed();
    }
}
