package net.xmilon.craftpy.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A horizontal layout container.
 */
public class HBox extends Widget {
    private int gap = 2;
    private int padding = 2;

    public HBox gap(int gap) { this.gap = gap; return this; }
    public HBox padding(int padding) { this.padding = padding; return this; }
    public int gap() { return gap; }
    public int padding() { return padding; }

    @Override
    public void layout() {
        int cx = x + padding;
        int availableH = height - 2 * padding;
        for (Widget child : children) {
            child.layout();
            child.setBounds(cx, y + padding, child.width(), availableH);
            cx += child.width() + gap;
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Transparent container
    }
}
