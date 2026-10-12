package net.xmilon.craftpy.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import java.util.function.Supplier;

/**
 * Bottom status bar widget.
 */
public class StatusBar extends Widget {
    private final Supplier<String> statusSupplier;

    public StatusBar(Supplier<String> statusSupplier) {
        this.statusSupplier = statusSupplier;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Theme theme = Theme.current();
        Font font = Minecraft.getInstance().font;
        String status = statusSupplier.get();
        int tw = font.width(status);
        
        graphics.fill(x, y, x + width, y + height, theme.color(Theme.PANEL_ALT));
        graphics.fill(x, y, x + width, y + 1, theme.color(Theme.BORDER));
        
        graphics.drawString(font, status, x + width - tw - 4, y + (height - 9) / 2, theme.color(Theme.MUTED), false);
    }
}
