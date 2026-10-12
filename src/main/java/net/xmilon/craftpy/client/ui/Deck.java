package net.xmilon.craftpy.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A container that only renders one of its children at a time.
 */
public class Deck extends Widget {
    private int activeIndex = -1;

    public void setActiveIndex(int index) {
        if (index >= 0 && index < children.size()) {
            this.activeIndex = index;
            layout();
        } else {
            this.activeIndex = -1;
        }
    }

    @Override
    public void layout() {
        for (int i = 0; i < children.size(); i++) {
            Widget child = children.get(i);
            child.setBounds(x, y, width, height);
            child.visible = (i == activeIndex);
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Deck is transparent
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activeIndex >= 0 && activeIndex < children.size()) {
            return children.get(activeIndex).mouseClicked(mouseX, mouseY, button);
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (activeIndex >= 0 && activeIndex < children.size()) {
            return children.get(activeIndex).mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        return false;
    }
}
