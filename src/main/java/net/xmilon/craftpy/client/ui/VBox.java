package net.xmilon.craftpy.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A vertical layout container.
 */
public class VBox extends Widget {
    private int gap = 2;
    private int padding = 2;

    public VBox gap(int gap) { this.gap = gap; return this; }
    public VBox padding(int padding) { this.padding = padding; return this; }
    public int gap() { return gap; }
    public int padding() { return padding; }

    @Override
    public void layout() {
        int cy = y + padding;
        int availableW = width - 2 * padding;
        for (Widget child : children) {
            child.layout();
            child.setBounds(x + padding, cy, availableW, child.height());
            cy += child.height() + gap;
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Transparent container
    }
}
