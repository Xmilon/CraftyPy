package net.xmilon.craftpy.client.screen;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Live preview of a project's block or item (43): the real in-game item model of its slot, rotatable with
 * the mouse, a flat inventory icon, and the declared properties. It shows exactly what the resource pack
 * built from the last Run looks like; a purple/black cube means the texture or model is missing.
 */
public class ContentPreviewScreen extends Screen {
    private static final int BOX_X = 16;
    private static final int BOX_Y = 40;
    private static final int BOX_SIZE = 144;

    private final Screen parent;
    private final ItemStack stack;
    private final String heading;
    private final List<String> details;
    private float rotX = 25;
    private float rotY = -35;
    private float zoom = 1;
    private boolean rotating;

    public ContentPreviewScreen(Screen parent, ItemStack stack, String heading, List<String> details) {
        super(Component.literal("Preview"));
        this.parent = parent;
        this.stack = stack;
        this.heading = heading;
        this.details = details;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 102, height - 26, 90, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Reset view"), b -> {
            rotX = 25;
            rotY = -35;
            zoom = 1;
        }).bounds(width - 204, height - 26, 96, 18).build());
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, heading, 16, 16, 0xFFFFFF);
        graphics.fill(BOX_X, BOX_Y, BOX_X + BOX_SIZE, BOX_Y + BOX_SIZE, 0xFF202225);
        if (stack.isEmpty()) {
            graphics.drawString(font, "No item for this slot", BOX_X + 8, BOX_Y + 8, 0xFF8888);
        } else {
            renderModel(graphics);
            graphics.renderItem(stack, BOX_X + BOX_SIZE + 16, BOX_Y);
            graphics.drawString(font, "Inventory icon", BOX_X + BOX_SIZE + 38, BOX_Y + 4, 0xAAAAAA);
        }
        int y = BOX_Y + 24;
        for (String line : details) {
            graphics.drawString(font, line, BOX_X + BOX_SIZE + 16, y, 0xDDDDDD);
            y += 12;
        }
        graphics.drawString(font, "Drag to rotate, wheel to zoom.", BOX_X, BOX_Y + BOX_SIZE + 8, 0x888888);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderModel(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(BOX_X + BOX_SIZE / 2f, BOX_Y + BOX_SIZE / 2f, 150);
        float scale = BOX_SIZE * 0.55f * zoom;
        pose.scale(scale, -scale, scale);
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(rotX));
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rotY));
        pose.translate(-0.5f, -0.5f, -0.5f);
        var renderer = mc.getItemRenderer();
        var model = renderer.getModel(stack, mc.level, null, 0);
        com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
        renderer.render(stack, net.minecraft.world.item.ItemDisplayContext.NONE, false, pose, graphics.bufferSource(),
                0xF000F0, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, model);
        graphics.flush();
        pose.popPose();
    }

    private boolean insideBox(double mouseX, double mouseY) {
        return mouseX >= BOX_X && mouseX < BOX_X + BOX_SIZE && mouseY >= BOX_Y && mouseY < BOX_Y + BOX_SIZE;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!stack.isEmpty() && insideBox(mouseX, mouseY)) {
            rotating = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (rotating) {
            rotY += (float) dragX * 2;
            rotX = Math.max(-90, Math.min(90, rotX + (float) dragY * 2));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        rotating = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (insideBox(mouseX, mouseY)) {
            zoom = (float) Math.max(0.3, Math.min(3, zoom + scrollY * 0.1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
