package net.xmilon.craftpy.client.ide;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public abstract class ScrollView extends AbstractScrollWidget {
    public record Row(String text, int color, boolean highlight) {
    }

    public static Row row(String text, int color) {
        return new Row(text, color, false);
    }

    private int hoveredRow = -1;
    private boolean draggingScroll;

    public ScrollView(Component message) {
        super(0, 0, 1, 1, message);
    }

    protected abstract List<Row> rows();

    public int rowHeight() {
        return 11;
    }

    protected int pad() {
        return 4;
    }

    protected void onRowClicked(int index, int button, double x, double y) {
    }

    protected void onRowRightClicked(int index, double x, double y) {
    }

    public void refresh() {
    }

    @Override
    protected int getInnerHeight() {
        return this.rows().size() * this.rowHeight() + this.pad() * 2;
    }

    @Override
    protected double scrollRate() {
        return 9.0;
    }

    public void scrollTo(double amount) {
        this.setScrollAmount(amount);
    }

    public void scrollToEnd() {
        this.setScrollAmount(this.getMaxScrollAmount());
    }

    public double scroll() {
        return this.scrollAmount();
    }

    public boolean atEnd() {
        return this.getMaxScrollAmount() <= 0 || this.scrollAmount() >= (double) (this.getMaxScrollAmount() - 1);
    }

    public int rowAt(double mouseX, double mouseY) {
        if (!this.withinContentAreaPoint(mouseX, mouseY)) {
            return -1;
        }
        double shiftedY = mouseY + this.scrollAmount() - (double) (this.getY() + this.pad());
        if (shiftedY < 0.0) {
            return -1;
        }
        int index = (int) (shiftedY / (double) this.rowHeight());
        if (index < 0 || index >= this.rows().size()) {
            return -1;
        }
        return index;
    }

    @Override
    protected void renderBackground(GuiGraphics graphics) {
        graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), IdeColors.PANEL);
        graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + 1, IdeColors.BORDER);
        graphics.fill(this.getX(), this.getY() + this.getHeight() - 1, this.getX() + this.getWidth(), this.getY() + this.getHeight(), IdeColors.BORDER);
        graphics.fill(this.getX(), this.getY(), this.getX() + 1, this.getY() + this.getHeight(), IdeColors.BORDER);
        graphics.fill(this.getX() + this.getWidth() - 1, this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), IdeColors.BORDER);
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        List<Row> rows = this.rows();
        int pad = this.pad();
        int rh = this.rowHeight();
        for (int i = 0; i < rows.size(); i++) {
            int rowTop = this.getY() + pad + i * rh;
            if (!this.withinContentAreaTopBottom(rowTop, rowTop + rh)) {
                continue;
            }
            Row row = rows.get(i);
            if (i == this.hoveredRow) {
                graphics.fill(this.getX() + 1, rowTop, this.getX() + this.getWidth() - 2, rowTop + rh, IdeColors.PANEL_HOVER);
            }
            if (row.highlight()) {
                graphics.fill(this.getX() + 1, rowTop, this.getX() + this.getWidth() - 2, rowTop + rh, IdeColors.SELECTED);
            }
            graphics.drawString(Minecraft.getInstance().font, row.text(), this.getX() + 6, rowTop + 1, row.color(), false);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
        this.defaultButtonNarrationText(narrationOutput);
    }

    @Override
    protected void renderDecorations(GuiGraphics graphics) {
        if (this.scrollbarVisible()) {
            int x = this.getX() + this.getWidth() - 7;
            int max = this.getMaxScrollAmount();
            int thumb = Mth.clamp(this.getHeight() * this.getHeight() / Math.max(1, this.getInnerHeight()), 24, this.getHeight() - 8);
            int track = this.getHeight() - thumb;
            int y = this.getY() + (max <= 0 ? 0 : (int) (this.scrollAmount() / (double) max * (double) track));
            graphics.fill(this.getX() + this.getWidth() - 8, this.getY() + 2, this.getX() + this.getWidth() - 1, this.getY() + this.getHeight() - 2, IdeColors.PANEL_ALT);
            graphics.fill(x, y, x + 5, y + thumb, IdeColors.MUTED);
        }
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        this.hoveredRow = this.rowAt(mouseX, mouseY);
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.visible) {
            return false;
        }
        if (mouseX >= (double) (this.getX() + this.getWidth() - 8)
            && mouseX <= (double) (this.getX() + this.getWidth())
            && mouseY >= (double) this.getY()
            && mouseY < (double) (this.getY() + this.getHeight())) {
            if (button == 0) {
                this.draggingScroll = true;
                this.applyScrollTo(mouseY);
            }
            return true;
        }
        if (!this.withinContentAreaPoint(mouseX, mouseY)) {
            return false;
        }
        int index = this.rowAt(mouseX, mouseY);
        if (button == 2) {
            return true;
        }
        if (button == 1) {
            this.onRowRightClicked(index, mouseX, mouseY);
            return true;
        }
        if (button == 0) {
            this.onRowClicked(index, button, mouseX, mouseY);
            return true;
        }
        return false;
    }

    private void applyScrollTo(double mouseY) {
        int thumb = Mth.clamp(this.getHeight() * this.getHeight() / Math.max(1, this.getInnerHeight()), 24, this.getHeight() - 8);
        int track = this.getHeight() - thumb - 4;
        int max = this.getMaxScrollAmount();
        if (track <= 0 || max <= 0) {
            this.scrollTo(0);
            return;
        }
        double t = Mth.clamp((mouseY - (double) (this.getY() + 2)) / (double) track, 0.0, 1.0);
        this.scrollTo(t * (double) max);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScroll && button == 0) {
            this.applyScrollTo(mouseY);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            this.draggingScroll = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!this.visible || !this.withinContentAreaPoint(mouseX, mouseY)) {
            return false;
        }
        double before = this.scrollAmount();
        this.setScrollAmount(this.scrollAmount() - scrollY * this.scrollRate());
        return before != this.scrollAmount();
    }
}