package net.xmilon.craftpy.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A widget that draws a background and can contain other widgets.
 */
public class Panel extends Widget {
    private String colorToken = Theme.PANEL;
    private boolean drawBorder = false;

    public Panel() {}

    public Panel(String colorToken) {
        this.colorToken = colorToken;
    }

    public Panel withBorder() {
        this.drawBorder = true;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Theme theme = Theme.current();
        graphics.fill(x, y, x + width, y + height, theme.color(colorToken));
        if (drawBorder) {
            // Simple 1px border
            graphics.fill(x, y, x + width, y + 1, theme.color(Theme.BORDER));
            graphics.fill(x, y + height - 1, x + width, y + height, theme.color(Theme.BORDER));
            graphics.fill(x, y, x + 1, y + height, theme.color(Theme.BORDER));
            graphics.fill(x + width - 1, y, x + width, y + height, theme.color(Theme.BORDER));
        }
    }
}
