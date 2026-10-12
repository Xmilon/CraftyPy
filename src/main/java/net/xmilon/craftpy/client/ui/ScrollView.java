package net.xmilon.craftpy.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A container that clips its children and supports vertical scrolling.
 */
public class ScrollView extends Widget {
    private int scrollY;
    private int contentHeight;

    @Override
    public void layout() {
        int cy = 0;
        int availableW = width - (contentHeight > height ? 4 : 0);
        for (Widget child : children) {
            child.layout();
            child.setBounds(x, y + cy - scrollY, availableW, child.height());
            cy += child.height();
        }
        this.contentHeight = cy;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;
        
        // Clip to our bounds
        graphics.enableScissor(x, y, x + width, y + height);
        
        renderWidget(graphics, mouseX, mouseY, partialTick);
        
        for (Widget child : children) {
            if (child.y() + child.height() > y && child.y() < y + height) {
                child.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        
        graphics.disableScissor();

        // Draw scrollbar
        if (contentHeight > height) {
            Theme theme = Theme.current();
            int sbW = 2;
            int sbH = (int) Math.max(10, ((float) height / contentHeight * height));
            int sbY = (int) ((float) scrollY / (contentHeight - height) * (height - sbH));
            graphics.fill(x + width - sbW - 1, y + sbY, x + width - 1, y + sbY + sbH, theme.color(Theme.MUTED));
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Transparent
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        
        if (contentHeight > height && contains(mouseX, mouseY)) {
            int oldScroll = scrollY;
            scrollY = (int) Math.max(0, Math.min(scrollY - verticalAmount * 16, contentHeight - height));
            if (scrollY != oldScroll) {
                layout();
                return true;
            }
        }
        return false;
    }
}
