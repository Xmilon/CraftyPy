package net.xmilon.craftpy.client.ide;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public abstract class ScrollView extends AbstractScrollWidget {
    /**
     * A styled run of text inside a row. Markdown rendering produces many spans
     * per line so bold / italic / inline-code / links can be drawn with the
     * correct color and decoration while keeping the legacy single-color API.
     */
    public record Span(String text, int color, boolean bold, boolean italic, boolean underline) {
        public Span(String text, int color) {
            this(text, color, false, false, false);
        }
    }

    /** Visual role of a row, used by the renderer to add indentation/backgrounds. */
    public enum Style {
        NORMAL, HEADING1, HEADING2, HEADING3, HEADING4, CODE, BULLET, ORDERED, QUOTE, RULE, TABLE_HEADER,
        TABLE_CELL
    }

    public record Row(String text, int color, boolean highlight, List<Span> spans, Style style, int height) {
        /** Legacy constructor: a single-color, normal-styled row. */
        public Row(String text, int color, boolean highlight) {
            this(text, color, highlight, List.of(new Span(text, color, false, false, false)), Style.NORMAL, 0);
        }
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

    /** Effective pixel height of one row (a row may override the default). */
    public int rowHeight(Row row) {
        return row.height() > 0 ? row.height() : this.rowHeight();
    }

    private int rowsHeight() {
        int total = 0;
        for (Row row : this.rows()) {
            total += this.rowHeight(row);
        }
        return total;
    }

    @Override
    protected int getInnerHeight() {
        return this.rowsHeight() + this.pad() * 2;
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
        List<Row> rows = this.rows();
        double cursor = 0.0;
        for (int i = 0; i < rows.size(); i++) {
            double h = this.rowHeight(rows.get(i));
            if (shiftedY < cursor + h) {
                return i;
            }
            cursor += h;
        }
        return -1;
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
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        int pad = this.pad();
        int cursor = this.getY() + pad;
        int left = this.getX() + 6;
        int right = this.getX() + this.getWidth() - 6;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int rh = this.rowHeight(row);
            int rowTop = cursor;
            cursor += rh;
            if (!this.withinContentAreaTopBottom(rowTop, rowTop + rh)) {
                continue;
            }
            if (i == this.hoveredRow) {
                graphics.fill(this.getX() + 1, rowTop, this.getX() + this.getWidth() - 2, rowTop + rh, IdeColors.PANEL_HOVER);
            }
            if (row.highlight()) {
                graphics.fill(this.getX() + 1, rowTop, this.getX() + this.getWidth() - 2, rowTop + rh, IdeColors.SELECTED);
            }
            this.renderRow(graphics, font, row, left, rowTop, rh, right);
        }
    }

    /** Draws one row honoring its markdown style (headings, code, quotes, ...). */
    private void renderRow(GuiGraphics graphics, net.minecraft.client.gui.Font font, Row row, int left, int rowTop, int rh, int right) {
        switch (row.style()) {
            case RULE -> {
                int y = rowTop + rh / 2;
                graphics.fill(left, y, right, y + 1, IdeColors.BORDER);
            }
            case CODE -> {
                graphics.fill(this.getX() + 2, rowTop, this.getX() + this.getWidth() - 3, rowTop + rh, IdeColors.PANEL_ALT);
                this.drawSpans(graphics, font, row, left + 3, rowTop + 1);
            }
            case QUOTE -> {
                graphics.fill(left - 1, rowTop, left + 2, rowTop + rh, IdeColors.BORDER);
                this.drawSpans(graphics, font, row, left + 6, rowTop + 1);
            }
            case BULLET, ORDERED -> this.drawSpans(graphics, font, row, left + 6, rowTop + 1);
            case HEADING1 -> {
                this.drawSpans(graphics, font, row, left, rowTop + 2);
                int y = rowTop + rh - 3;
                graphics.fill(left, y, right, y + 1, IdeColors.BORDER);
            }
            case HEADING2, HEADING3, HEADING4 -> this.drawSpans(graphics, font, row, left, rowTop + 2);
            default -> this.drawSpans(graphics, font, row, left, rowTop + 1);
        }
    }

    private void drawSpans(GuiGraphics graphics, net.minecraft.client.gui.Font font, Row row, int x, int y) {
        int cx = x;
        for (Span span : row.spans()) {
            if (span.text().isEmpty()) {
                continue;
            }
            net.minecraft.network.chat.MutableComponent comp = Component.literal(span.text());
            if (span.bold()) {
                comp = comp.withStyle(net.minecraft.ChatFormatting.BOLD);
            }
            if (span.italic()) {
                comp = comp.withStyle(net.minecraft.ChatFormatting.ITALIC);
            }
            if (span.underline()) {
                comp = comp.withStyle(net.minecraft.ChatFormatting.UNDERLINE);
            }
            graphics.drawString(font, comp, cx, y, span.color(), false);
            cx += font.width(comp);
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