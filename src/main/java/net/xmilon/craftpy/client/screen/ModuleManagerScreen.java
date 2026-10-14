package net.xmilon.craftpy.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.ide.IdeColors;
import net.xmilon.craftpy.client.ide.MarkdownRenderer;
import net.xmilon.craftpy.client.ide.ScrollView;
import net.xmilon.craftpy.client.ide.ScrollView.Row;
import net.xmilon.craftpy.client.ide.ScrollView.Span;
import net.xmilon.craftpy.client.ide.ScrollView.Style;
import net.xmilon.craftpy.menu.ComputerMenu;
import net.xmilon.craftpy.project.ModuleInfo;
import net.xmilon.craftpy.project.PyForgePaths;

/**
 * The Modules front page: lists every {@code .cpm} found anywhere under
 * {@code pyforge/} with a radio toggle to enable or disable each one, plus a
 * search box, source grouping, duplicate detection and a markdown details pane
 * that renders the module's bundled README as its extended description.
 */
public class ModuleManagerScreen extends Screen {
    private static final int BG = 0xFF10141A;
    private static final int PANEL = 0xFF1B222C;
    private static final int PANEL_ALT = 0xFF232C38;
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int TEXT = 0xFFD7E1EA;
    private static final int MUTED = 0xFF7D8B9A;
    private static final int BORDER = 0xFF2E3A48;
    private static final int ERROR = 0xFFFF6B6B;
    private static final int OK = 0xFF8BC34A;
    private static final int WARN = 0xFFFFB74D;

    private final ClientProjectStore store = ClientProjectStore.getInstance();
    private final ComputerMenu menu;
    private final Inventory inventory;

    private EditBox searchBox;
    private ModuleListView listView;
    private DetailView detailView;

    private String selectedPath;
    private boolean grouped = true;
    private String confirmDeletePath;

    // Layout cached for input hit-testing.
    private int listLeft;
    private int listRight;
    private int bodyTop;
    private int bodyBottom;

    public ModuleManagerScreen(ComputerMenu menu, Inventory inventory) {
        super(Component.literal("CraftPy Modules"));
        this.menu = menu;
        this.inventory = inventory;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        this.store.refreshModules();

        this.searchBox = new EditBox(this.font, 10, 30, 200, 16, Component.literal("search modules"));
        this.searchBox.setHint(Component.literal("Filter by id, name, author or folder..."));
        this.searchBox.setMaxLength(80);
        addRenderableWidget(this.searchBox);

        int by = 52;
        int bw = 76; // narrower buttons
        int gap = 4;
        int x = 10;
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> this.store.refreshModules())
                .bounds(x, by, bw, 16).build());
        x += bw + gap;
        addRenderableWidget(Button.builder(Component.literal("Enable all"), b -> this.store.setAllModulesEnabled(true))
                .bounds(x, by, bw, 16).build());
        x += bw + gap;
        addRenderableWidget(Button.builder(Component.literal("Disable all"), b -> this.store.setAllModulesEnabled(false))
                .bounds(x, by, bw, 16).build());
        x += bw + gap;
        addRenderableWidget(Button.builder(Component.literal("Group: on"), b -> {
            this.grouped = !this.grouped;
            b.setMessage(Component.literal("Group: " + (this.grouped ? "on" : "off")));
            if (this.listView != null) {
                this.listView.scrollTo(0);
            }
        }).bounds(x, by, bw, 16).build());
        x += bw + gap;
        addRenderableWidget(Button.builder(Component.literal("Open folder"), b -> openSelectedFolder())
                .bounds(x, by, bw, 16).build());
        x += bw + gap;
        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> deleteSelected())
                .bounds(x, by, bw, 16).build());
        x += bw + gap;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> back())
                .bounds(x, by, bw, 16).build());

        this.listView = new ModuleListView();
        addRenderableWidget(this.listView);
        this.detailView = new DetailView();
        addRenderableWidget(this.detailView);

        layout();
    }

    private void layout() {
        int titleH = 26;
        int toolbarBottom = 74;
        int pad = 8;
        this.listLeft = pad;
        this.listRight = Math.max(pad + 120, (int) (this.width * 0.55));
        this.bodyTop = toolbarBottom;
        this.bodyBottom = this.height - pad;

        if (this.searchBox != null) {
            this.searchBox.setPosition(10, titleH + 2);
        }
        if (this.listView != null) {
            this.listView.setPosition(this.listLeft, this.bodyTop);
            this.listView.setSize(this.listRight - this.listLeft - 6, Math.max(20, this.bodyBottom - this.bodyTop));
        }
        if (this.detailView != null) {
            int dx = this.listRight + 2;
            this.detailView.setPosition(dx, this.bodyTop);
            this.detailView.setSize(Math.max(40, this.width - dx - pad), Math.max(20, this.bodyBottom - this.bodyTop));
        }
    }

    // ------------------------------------------------------------------
    // Data helpers
    // ------------------------------------------------------------------

    private List<ModuleInfo> filtered() {
        String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        List<ModuleInfo> out = new ArrayList<>();
        for (ModuleInfo module : this.store.modules()) {
            if (query.isEmpty() || matches(module, query)) {
                out.add(module);
            }
        }
        if (this.grouped) {
            out.sort(Comparator.comparing((ModuleInfo m) -> sourceKey(m))
                    .thenComparing(m -> m.enabled ? 0 : 1)
                    .thenComparing(m -> m.id, String.CASE_INSENSITIVE_ORDER));
        } else {
            out.sort(Comparator.comparing((ModuleInfo m) -> m.enabled ? 0 : 1)
                    .thenComparing(m -> sourceKey(m))
                    .thenComparing(m -> m.id, String.CASE_INSENSITIVE_ORDER));
        }
        return out;
    }

    private static boolean matches(ModuleInfo m, String query) {
        return contains(m.id, query) || contains(m.name, query) || contains(m.author, query)
                || contains(m.source, query) || contains(m.description, query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private static String sourceKey(ModuleInfo m) {
        return m.source == null || m.source.isEmpty() ? "(root)" : m.source;
    }

    private ModuleInfo selected() {
        return this.store.findModule(this.selectedPath);
    }

    private void select(String path) {
        if (path == null) {
            return;
        }
        boolean changed = !path.equals(this.selectedPath);
        this.selectedPath = path;
        if (changed) {
            refreshDetail();
        }
    }

    private void refreshDetail() {
        ModuleInfo module = selected();
        if (this.detailView == null) {
            return;
        }
        if (module == null) {
            this.detailView.setContent(null, "");
            return;
        }
        this.detailView.setContent(module, "(loading README...)");
        this.store.requestModuleReadme(module.path, text -> {
            if (module.path.equals(this.selectedPath)) {
                this.detailView.setContent(module, text);
            }
        });
    }

    private void moveSelection(int delta) {
        List<ModuleInfo> modules = filtered();
        if (modules.isEmpty()) {
            return;
        }
        int index = -1;
        for (int i = 0; i < modules.size(); i++) {
            if (modules.get(i).path.equals(this.selectedPath)) {
                index = i;
                break;
            }
        }
        index = Math.max(0, Math.min(modules.size() - 1, index < 0 ? 0 : index + delta));
        ModuleInfo module = modules.get(index);
        select(module.path);
        this.listView.ensureVisible(module);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void toggleSelected() {
        ModuleInfo module = selected();
        if (module != null) {
            this.store.setModuleEnabled(module.id, !module.enabled);
        }
    }

    private void openSelectedFolder() {
        ModuleInfo module = selected();
        if (module == null || module.path == null || module.path.isBlank()) {
            openFolder(PyForgePaths.root().toString());
            return;
        }
        openFolder(module.path);
    }

    private void openFolder(String path) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(path);
            java.nio.file.Path dir = java.nio.file.Files.isDirectory(p) ? p : p.getParent();
            if (dir != null) {
                net.minecraft.Util.getPlatform().openFile(dir.toFile());
            }
        } catch (Exception e) {
            this.store.append("Could not open folder: " + e.getMessage());
        }
    }

    private void deleteSelected() {
        ModuleInfo module = selected();
        if (module == null) {
            return;
        }
        if (module.path.equals(this.confirmDeletePath)) {
            this.confirmDeletePath = null;
            this.store.deleteModule(module.path);
            this.selectedPath = null;
        } else {
            this.confirmDeletePath = module.path;
            this.store.append("Press Delete again to remove " + module.path);
        }
    }

    private void back() {
        if (this.minecraft == null) {
            return;
        }
        if (this.menu != null && this.inventory != null) {
            this.minecraft.setScreen(
                    new ProjectChooserScreen(this.menu, this.inventory, Component.literal("CraftPy Projects")));
        } else {
            this.minecraft.setScreen(null);
        }
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Opaque custom background; skip the vanilla blur layer.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        layout();

        graphics.fill(0, 0, this.width, this.height, BG);
        graphics.fill(0, 0, this.width, 26, PANEL_ALT);
        graphics.fill(0, 25, this.width, 26, BORDER);
        graphics.drawString(this.font, "CraftPy", 10, 9, ACCENT, false);
        graphics.drawString(this.font, "Modules", 10 + this.font.width("CraftPy") + 8, 9, MUTED, false);
        int total = this.store.modules().size();
        int enabled = 0;
        for (ModuleInfo m : this.store.modules()) {
            if (m.enabled) {
                enabled++;
            }
        }
        String hint = enabled + " / " + total + " enabled  \u2022  scanned from all of pyforge/";
        graphics.drawString(this.font, hint, this.width - this.font.width(hint) - 10, 9, MUTED, false);

        // Panel backgrounds behind the scroll views.
        graphics.fill(this.listLeft - 2, this.bodyTop - 2, this.listRight, this.bodyBottom, PANEL);
        graphics.fill(this.listRight + 4, this.bodyTop - 2, this.width - 6, this.bodyBottom, PANEL);
        graphics.drawString(this.font, "MODULES", this.listLeft, this.bodyTop - 12, ACCENT, false);
        graphics.drawString(this.font, "DETAILS", this.listRight + 6, this.bodyTop - 12, ACCENT, false);

        super.render(graphics, mouseX, mouseY, partialTick);

        drawHoverTooltip(graphics, mouseX, mouseY);

        if (this.confirmDeletePath != null) {
            graphics.drawString(this.font, "Press Delete again to confirm removal", 10, this.height - 12, WARN, false);
        }
    }

    private void drawHoverTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.listView == null) {
            return;
        }
        ModuleInfo module = this.listView.moduleAtRow(this.listView.rowAt(mouseX, mouseY));
        if (module == null) {
            return;
        }
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[] { module.name.isEmpty() ? module.id : module.name, "head" });
        lines.add(new String[] { "id: " + module.id, "norm" });
        if (module.version != null && !module.version.isEmpty()) {
            lines.add(new String[] { "version: " + module.version, "norm" });
        }
        if (module.author != null && !module.author.isEmpty()) {
            lines.add(new String[] { "author: " + module.author, "norm" });
        }
        lines.add(new String[] { "source: " + sourceKey(module), "norm" });
        lines.add(new String[] { "file: " + module.path, "norm" });
        lines.add(new String[] { "size: " + humanSize(module.sizeBytes), "norm" });
        lines.add(new String[] { "status: " + statusText(module), module.enabled ? "ok" : "muted" });
        if (module.duplicate) {
            lines.add(new String[] { "duplicate id - another file wins the load", "warn" });
        }
        if (module.error != null && !module.error.isBlank()) {
            lines.add(new String[] { "error: " + module.error, "error" });
        }

        int width = 0;
        for (String[] line : lines) {
            width = Math.max(width, this.font.width(line[0]));
        }
        int pad = 4;
        int lineH = 10;
        int boxW = width + pad * 2;
        int boxH = lines.size() * lineH + pad * 2;
        int bx = mouseX + 12;
        int by = mouseY + 12;
        if (bx + boxW > this.width) {
            bx = Math.max(2, mouseX - boxW - 8);
        }
        if (by + boxH > this.height) {
            by = Math.max(2, this.height - boxH - 2);
        }
        graphics.fill(bx - 1, by - 1, bx + boxW + 1, by + boxH + 1, BORDER);
        graphics.fill(bx, by, bx + boxW, by + boxH, PANEL_ALT);
        int ty = by + pad;
        for (String[] line : lines) {
            int color = switch (line[1]) {
                case "head" -> ACCENT;
                case "ok" -> OK;
                case "warn" -> WARN;
                case "error" -> ERROR;
                case "muted" -> MUTED;
                default -> TEXT;
            };
            graphics.drawString(this.font, line[0], bx + pad, ty, color, false);
            ty += lineH;
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private static String statusText(ModuleInfo m) {
        StringBuilder sb = new StringBuilder(m.enabled ? "enabled" : "disabled");
        if (m.loaded) {
            sb.append(", loaded");
        }
        if (m.pendingRestart) {
            sb.append(", restart to unload");
        }
        if (m.duplicate) {
            sb.append(", duplicate");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> {
                back();
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> {
                moveSelection(1);
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> {
                moveSelection(-1);
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,
                    org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER -> {
                if (this.searchBox != null && this.searchBox.isFocused()) {
                    break;
                }
                toggleSelected();
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE -> {
                deleteSelected();
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_F5 -> {
                this.store.refreshModules();
                return true;
            }
            default -> {
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        back();
    }

    // ------------------------------------------------------------------
    // List view
    // ------------------------------------------------------------------

    private final class ModuleListView extends ScrollView {
        private ModuleListView() {
            super(Component.literal("Modules"));
        }

        private List<PlanRow> plan() {
            List<PlanRow> rows = new ArrayList<>();
            String lastSource = null;
            for (ModuleInfo module : filtered()) {
                if (ModuleManagerScreen.this.grouped) {
                    String source = sourceKey(module);
                    if (!source.equals(lastSource)) {
                        rows.add(PlanRow.header(source));
                        lastSource = source;
                    }
                }
                boolean isSelected = module.path.equals(ModuleManagerScreen.this.selectedPath);
                rows.add(PlanRow.module(module, isSelected));
            }
            return rows;
        }

        ModuleInfo moduleAtRow(int index) {
            List<PlanRow> plan = plan();
            if (index < 0 || index >= plan.size()) {
                return null;
            }
            return plan.get(index).module();
        }

        void ensureVisible(ModuleInfo module) {
            List<PlanRow> plan = plan();
            int rowIndex = -1;
            for (int i = 0; i < plan.size(); i++) {
                ModuleInfo m = plan.get(i).module();
                if (m != null && m.path.equals(module.path)) {
                    rowIndex = i;
                    break;
                }
            }
            if (rowIndex < 0) {
                return;
            }
            int offset = 0;
            for (int i = 0; i < rowIndex; i++) {
                offset += rowHeight(plan.get(i).row());
            }
            int rowH = rowHeight(plan.get(rowIndex).row());
            int viewTop = (int) scroll();
            int viewHeight = getHeight() - pad() * 2;
            if (offset < viewTop) {
                scrollTo(offset);
            } else if (offset + rowH > viewTop + viewHeight) {
                scrollTo(offset - viewHeight + rowH);
            }
        }

        @Override
        protected List<Row> rows() {
            List<PlanRow> plan = plan();
            List<Row> out = new ArrayList<>(plan.size());
            for (PlanRow pr : plan) {
                out.add(pr.row());
            }
            return out;
        }

        @Override
        protected void onRowClicked(int index, int button, double x, double y) {
            ModuleInfo module = moduleAtRow(index);
            if (module == null) {
                return;
            }
            boolean onRadio = x <= getX() + 20;
            if (onRadio) {
                store.setModuleEnabled(module.id, !module.enabled);
            } else {
                select(module.path);
            }
        }

        @Override
        public int rowHeight() {
            return 12;
        }
    }

    private record PlanRow(Row row, ModuleInfo module) {
        static PlanRow header(String source) {
            String upper = source.toUpperCase(Locale.ROOT);
            return new PlanRow(new Row(upper, ACCENT, false,
                    List.of(new Span(upper, ACCENT, true, false, false)), Style.HEADING3, 13), null);
        }

        static PlanRow module(ModuleInfo m, boolean selected) {
            List<Span> spans = new ArrayList<>();
            int radioColor = m.enabled ? ACCENT : MUTED;
            spans.add(new Span(m.enabled ? "(\u25CF) " : "( ) ", radioColor, true, false, false));
            int nameColor = m.duplicate ? WARN : (m.enabled ? TEXT : MUTED);
            spans.add(new Span(m.name.isEmpty() ? m.id : m.name, nameColor, false, false, false));
            spans.add(new Span("  " + m.id, MUTED, false, false, false));
            if (m.version != null && !m.version.isEmpty()) {
                spans.add(new Span("  v" + m.version, MUTED, false, false, false));
            }
            if (m.loaded) {
                spans.add(new Span("  [loaded]", OK, false, false, false));
            }
            if (!m.enabled) {
                spans.add(new Span("  [disabled]", MUTED, false, false, false));
            }
            if (m.pendingRestart) {
                spans.add(new Span("  [restart]", WARN, false, false, false));
            }
            if (m.duplicate) {
                spans.add(new Span("  [dup]", WARN, false, false, false));
            }
            if (m.error != null && !m.error.isBlank()) {
                spans.add(new Span("  [error]", ERROR, false, false, false));
            }
            return new PlanRow(new Row(m.id, nameColor, selected, spans, Style.NORMAL, 12), m);
        }
    }

    // ------------------------------------------------------------------
    // Details view
    // ------------------------------------------------------------------

    private final class DetailView extends ScrollView {
        private List<Row> cached = List.of(new Row("Select a module to see its README", MUTED, false));

        private DetailView() {
            super(Component.literal("Details"));
        }

        void setContent(ModuleInfo module, String readme) {
            if (module == null) {
                this.cached = List.of(new Row("Select a module to see its README", MUTED, false));
                refresh();
                scrollTo(0);
                return;
            }
            List<Row> rows = new ArrayList<>();
            rows.add(new Row(module.name.isEmpty() ? module.id : module.name, ACCENT, false));
            StringBuilder meta = new StringBuilder("id ").append(module.id);
            if (module.version != null && !module.version.isEmpty()) {
                meta.append("  \u2022  v").append(module.version);
            }
            if (module.author != null && !module.author.isEmpty()) {
                meta.append("  \u2022  by ").append(module.author);
            }
            rows.add(new Row(meta.toString(), MUTED, false));
            rows.add(new Row(" ", TEXT, false, List.of(), Style.NORMAL, 5));
            if (readme != null && !readme.isBlank()) {
                rows.addAll(MarkdownRenderer.render(readme));
            } else {
                if (module.description != null && !module.description.isBlank()) {
                    rows.add(new Row(module.description, TEXT, false));
                }
                rows.add(new Row("This module has no bundled README.md.", MUTED, false));
                rows.add(new Row("Add README.md to the project and rebuild to show it here.", MUTED, false));
            }
            this.cached = rows;
            refresh();
            scrollTo(0);
        }

        @Override
        protected List<Row> rows() {
            return this.cached;
        }
    }
}
