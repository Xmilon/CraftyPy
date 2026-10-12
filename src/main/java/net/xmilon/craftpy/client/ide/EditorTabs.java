package net.xmilon.craftpy.client.ide;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public class EditorTabs extends AbstractWidget {
    public interface Delegate {
        List<TabInfo> tabs();

        int activeTab();

        void onSelect(int index);

        void onClose(int index);

        void onNewFile();
    }

    public record TabInfo(String title, boolean dirty, String path) {
    }

    private final Delegate delegate;
    private int tabScrollX;

    public EditorTabs(Delegate delegate) {
        super(0, 0, 1, 24, Component.literal("Editor tabs"));
        this.delegate = delegate;
    }

    private static int tabWidth(String title) {
        return Mth.clamp(title.length() * 6 + 30, 70, 200);
    }

    private int contentWidth() {
        int total = 0;
        for (TabInfo t : this.delegate.tabs()) {
            total += tabWidth(t.title()) + 2;
        }
        return total + 22;
    }

    private void clampScroll() {
        int overflow = this.contentWidth() - this.getWidth();
        this.tabScrollX = Mth.clamp(this.tabScrollX, -Math.max(0, overflow), 0);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.clampScroll();
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        graphics.fill(x, y, x + w, y + h, IdeColors.PANEL_ALT);
        graphics.fill(x, y + h - 1, x + w, y + h, IdeColors.BORDER);
        List<TabInfo> tabs = this.delegate.tabs();
        int cx = x + this.tabScrollX;
        boolean hasOverflow = this.contentWidth() > w;
        for (int i = 0; i < tabs.size(); i++) {
            TabInfo tab = tabs.get(i);
            int tw = tabWidth(tab.title());
            boolean active = i == this.delegate.activeTab();
            int bg = active ? IdeColors.PANEL : IdeColors.PANEL_ALT;
            graphics.fill(cx, y + 1, cx + tw, y + h - 1, bg);
            if (active) {
                graphics.fill(cx, y + h - 3, cx + tw, y + h - 2, IdeColors.ACCENT);
            }
            int textColor = active ? IdeColors.TEXT : IdeColors.MUTED;
            String title = Minecraft.getInstance().font.plainSubstrByWidth(tab.title(), tw - 26);
            graphics.drawString(Minecraft.getInstance().font, title, cx + 8, y + (h - 8) / 2, textColor, false);
            if (tab.dirty()) {
                graphics.fill(cx + tw - 17, y + (h - 4) / 2, cx + tw - 13, y + (h - 4) / 2 + 4, IdeColors.WARNING);
            }
            if (active) {
                graphics.fill(cx + tw - 15, y + (h - 4) / 2, cx + tw - 11, y + (h - 4) / 2 + 4, IdeColors.ERROR);
            }
            cx += tw + 2;
        }
        if (hasOverflow) {
            graphics.fill(x, y + 1, x + w, y + h - 1, 0x33000000);
        }
        boolean plusHover = hasOverflow && mouseX >= (double) (x + w - 22) && mouseY >= (double) (y + 3) && mouseY < (double) (y + h - 2);
        if (hasOverflow || plusHover) {
            int px = x + w - 13;
            int py = y + 4;
            if (plusHover) {
                graphics.fill(x + w - 24, y + 1, x + w, y + h - 1, IdeColors.PANEL_HOVER);
            }
            graphics.fill(px + 2, py, px + 3, py + 11, IdeColors.ACCENT);
            graphics.fill(px - 3, py + 5, px + 8, py + 6, IdeColors.ACCENT);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.visible || mouseY < (double) this.getY() || mouseY >= (double) (this.getY() + this.getHeight())) {
            return false;
        }
        if (mouseX < (double) this.getX() || mouseX >= (double) (this.getX() + this.getWidth())) {
            return false;
        }
        List<TabInfo> tabs = this.delegate.tabs();
        int cx = this.getX() + this.tabScrollX;
        for (int i = 0; i < tabs.size(); i++) {
            TabInfo tab = tabs.get(i);
            int tw = tabWidth(tab.title());
            if (mouseX >= (double) cx && mouseX < (double) (cx + tw)) {
                boolean insideClose = mouseX > (double) (cx + tw - 15);
                boolean rightClick = button == 2;
                if (insideClose || rightClick) {
                    this.delegate.onClose(i);
                } else if (button == 0) {
                    this.delegate.onSelect(i);
                }
                return true;
            }
            cx += tw + 2;
        }
        if (button == 0 && mouseX >= (double) (this.getX() + this.getWidth() - 24) && this.contentWidth() > this.getWidth()) {
            this.delegate.onNewFile();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY < (double) this.getY() || mouseY >= (double) (this.getY() + this.getHeight())) {
            return false;
        }
        this.tabScrollX += (int) (scrollY * 20.0);
        this.clampScroll();
        return this.contentWidth() > this.getWidth();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
        this.defaultButtonNarrationText(narrationOutput);
    }
}