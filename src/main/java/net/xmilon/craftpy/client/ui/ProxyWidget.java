package net.xmilon.craftpy.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A widget that delegates rendering to a functional interface.
 * Useful for bridging legacy code or special rendering needs.
 */
public class ProxyWidget extends Widget {
    public interface Renderer {
        void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, int x, int y, int width, int height);
    }

    private final Renderer renderer;

    public ProxyWidget(Renderer renderer) {
        this.renderer = renderer;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderer.render(graphics, mouseX, mouseY, partialTick, x, y, width, height);
    }
}
