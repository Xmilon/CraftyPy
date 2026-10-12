package net.xmilon.craftpy.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A simple text widget.
 */
public class Label extends Widget {
    private Component text;
    private String colorToken = Theme.TEXT;
    private boolean shadow = false;

    public Label(String text) {
        this.text = Component.literal(text);
    }

    public Label(Component text) {
        this.text = text;
    }

    public Label color(String token) {
        this.colorToken = token;
        return this;
    }

    public Label withShadow() {
        this.shadow = true;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, text, x, y, Theme.current().color(colorToken), shadow);
    }

    @Override
    public void layout() {
        Font font = Minecraft.getInstance().font;
        this.width = font.width(text);
        this.height = font.lineHeight;
    }
}
