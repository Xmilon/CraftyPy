package net.xmilon.craftpy.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A widget wrapper around vanilla Minecraft EditBox.
 */
public class EditBoxWidget extends Widget {
    private final EditBox editBox;

    public EditBoxWidget(Component label) {
        Font font = Minecraft.getInstance().font;
        this.editBox = new EditBox(font, 0, 0, 100, 20, label);
    }

    public EditBox editBox() {
        return editBox;
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        editBox.setX(x);
        editBox.setY(y);
        editBox.setWidth(width);
        // EditBox height is usually fixed by font
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        editBox.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        return editBox.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) return true;
        return editBox.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (super.charTyped(codePoint, modifiers)) return true;
        return editBox.charTyped(codePoint, modifiers);
    }
}
