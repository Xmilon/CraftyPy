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
    private String chipToken;
    private boolean hovered;

    public Button(String label, Runnable action) {
        this.label = Component.literal(label);
        this.action = action;
    }

    /** Adds a small colored square left of the label (acts as an icon). */
    public Button chip(String colorToken) {
        this.chipToken = colorToken;
        return this;
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
        int chipW = chipToken == null ? 0 : 7;
        int total = chipW + tw;
        int startX = x + (width - total) / 2;
        if (chipToken != null) {
            graphics.fill(startX, y + (height - 4) / 2, startX + 4, y + (height - 4) / 2 + 4,
                    theme.color(chipToken));
            startX += 7;
        }
        graphics.drawString(font, label, startX, y + (height - 9) / 2, text, false);
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
