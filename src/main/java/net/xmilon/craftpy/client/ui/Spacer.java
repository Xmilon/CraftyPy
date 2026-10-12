package net.xmilon.craftpy.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A widget that takes up space but draws nothing.
 * Used for padding or expanding in layouts.
 */
public class Spacer extends Widget {
    private int fixedWidth = -1;
    private int fixedHeight = -1;

    public Spacer() {}

    public Spacer(int w, int h) {
        this.fixedWidth = w;
        this.fixedHeight = h;
    }

    @Override
    public void layout() {
        if (fixedWidth >= 0) this.width = fixedWidth;
        if (fixedHeight >= 0) this.height = fixedHeight;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Nothing to draw
    }
}
