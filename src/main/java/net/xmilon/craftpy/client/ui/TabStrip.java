package net.xmilon.craftpy.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.xmilon.craftpy.client.ClientProjectStore;

import java.util.List;
import java.util.function.Consumer;

/**
 * A horizontal strip of tabs.
 */
public class TabStrip extends Widget {
    private final List<String> labels;
    private int activeIndex;
    private Consumer<Integer> onSelect;
    private Consumer<Integer> onClose;

    public TabStrip(List<String> labels, int activeIndex, Consumer<Integer> onSelect) {
        this.labels = labels;
        this.activeIndex = activeIndex;
        this.onSelect = onSelect;
    }

    public void setLabels(List<String> labels) {
        this.labels.clear();
        this.labels.addAll(labels);
    }

    public void setOnSelect(Consumer<Integer> onSelect) {
        this.onSelect = onSelect;
    }

    public void setOnClose(Consumer<Integer> onClose) {
        this.onClose = onClose;
    }

    public void setActiveIndex(int index) {
        this.activeIndex = index;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Theme theme = Theme.current();
        Font font = Minecraft.getInstance().font;
        
        int tx = x + 2;
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            int tw = font.width(label) + (onClose != null ? 20 : 12);
            boolean active = (i == activeIndex);
            
            if (active) {
                graphics.fill(tx, y, tx + tw, y + height, theme.color(Theme.SELECTED));
            }
            
            boolean hovered = mouseX >= tx && mouseX < tx + tw && mouseY >= y && mouseY < y + height;
            if (hovered && !active) {
                graphics.fill(tx, y, tx + tw, y + height, theme.color(Theme.PANEL_ALT));
            }

            int color = active ? theme.color(Theme.ACCENT) : theme.color(Theme.TEXT);
            graphics.drawString(font, label, tx + 6, y + (height - 9) / 2, color, false);
            
            if (onClose != null) {
                int cx = tx + tw - 12;
                boolean closeHover = mouseX >= cx && mouseX < cx + 10 && mouseY >= y && mouseY < y + height;
                graphics.drawString(font, "x", cx + 2, y + (height - 9) / 2, closeHover ? theme.color(Theme.ERROR) : theme.color(Theme.MUTED), false);
            }
            
            tx += tw + 2;
        }
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        Font font = Minecraft.getInstance().font;
        int tx = x + 2;
        for (int i = 0; i < labels.size(); i++) {
            int tw = font.width(labels.get(i)) + (onClose != null ? 20 : 12);
            if (mouseX >= tx && mouseX < tx + tw) {
                if (onClose != null && button == 0 && mouseX >= tx + tw - 12) {
                    onClose.accept(i);
                } else if (button == 0) {
                    onSelect.accept(i);
                }
                return true;
            }
            tx += tw + 2;
        }
        return false;
    }
}
