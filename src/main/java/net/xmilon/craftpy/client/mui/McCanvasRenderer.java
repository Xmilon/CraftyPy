package net.xmilon.craftpy.client.mui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.xmilon.craftpy.CraftPyLog;

/**
 * Paints every visible {@link McCanvas} after the screen has drawn (Modern UI draws inside {@code Screen.render}, so
 * this comes on top of it). Render thread. A painter that throws is switched off, so one broken mod renderer
 * cannot break the IDE every frame.
 */
@EventBusSubscriber(modid = CraftPyLog.MODID, value = Dist.CLIENT)
final class McCanvasRenderer {
    private McCanvasRenderer() {
    }

    @SubscribeEvent
    static void afterScreen(ScreenEvent.Render.Post event) {
        if (McCanvas.LIVE.isEmpty()) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        for (McCanvas canvas : McCanvas.LIVE) {
            McCanvas.Snap s = canvas.snap;
            McCanvas.Painter painter = canvas.painter();
            if (!s.visible() || painter == null) {
                continue;
            }
            int x = (int) Math.round(s.x() / scale);
            int y = (int) Math.round(s.y() / scale);
            int w = (int) Math.round(s.width() / scale);
            int h = (int) Math.round(s.height() / scale);
            int mx = event.getMouseX() - x;
            int my = event.getMouseY() - y;
            boolean inside = mx >= 0 && my >= 0 && mx < w && my < h
                    && event.getMouseX() * scale >= s.clipLeft() && event.getMouseX() * scale < s.clipRight()
                    && event.getMouseY() * scale >= s.clipTop() && event.getMouseY() * scale < s.clipBottom();
            g.enableScissor((int) Math.floor(s.clipLeft() / scale), (int) Math.floor(s.clipTop() / scale),
                    (int) Math.ceil(s.clipRight() / scale), (int) Math.ceil(s.clipBottom() / scale));
            g.pose().pushPose();
            try {
                RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
                g.pose().translate(x, y, 0);
                painter.paint(g, w, h, inside ? mx : -1, inside ? my : -1, event.getPartialTick());
                g.flush();
            } catch (Throwable e) { // a mod renderer that throws must not break every frame of the IDE
                CraftPyLog.LOGGER.warn("Preview painter failed; switched off", e);
                canvas.setPainter(null);
            } finally {
                g.pose().popPose();
                g.disableScissor();
            }
        }
    }
}
