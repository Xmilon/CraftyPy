package net.xmilon.craftpy.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A simple button widget.
 */
public class Button extends Widget {
    private Component label;
    private Runnable action;
    private boolean hovered;

    public Button(String label, Runnable action) {
        this.label = Component.literal(label);
        this.action = action;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        hovered = contains(mouseX, mouseY);
        Theme theme = Theme.current();
        int bg = hovered ? theme.color(Theme.SELECTED) : theme.color(Theme.PANEL_ALT);
        int text = hovered ? theme.color(Theme.ACCENT) : theme.color(Theme.TEXT);
        
        graphics.fill(x, y, x + width, y + height, bg);
        // Simple border
        graphics.fill(x, y, x + width, y + 1, theme.color(Theme.BORDER));
        graphics.fill(x, y + height - 1, x + width, y + height, theme.color(Theme.BORDER));
        graphics.fill(x, y, x + 1, y + height, theme.color(Theme.BORDER));
        graphics.fill(x + width - 1, y, x + width, y + height, theme.color(Theme.BORDER));

        Font font = Minecraft.getInstance().font;
        int tw = font.width(label);
        graphics.drawString(font, label, x + (width - tw) / 2, y + (height - 9) / 2, text, false);
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (button == 0 && action != null) {
            action.run();
            return true;
        }
        return false;
    }
}
