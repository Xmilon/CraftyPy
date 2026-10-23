package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.xmilon.craftpy.menu.MachineMenu;

/**
 * The Minecraft-style window of a machine block ({@code Screen(...)}): the grey container panel, the slots, an energy bar on
 * the right and a fluid gauge. Everything is drawn here with plain rectangles and the fluid's own texture, so a resource pack
 * that changes the fluid changes the gauge too. The server decides the contents; this only draws what it was sent.
 */
public class MachineScreen extends AbstractContainerScreen<MachineMenu> {
    private static final int BAR_X = 152, BAR_Y = 16, BAR_W = 16, BAR_H = 54;
    private static final int GAUGE_X = 86;

    public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = MachineMenu.WIDTH;
        imageHeight = MachineMenu.HEIGHT;
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        panel(graphics, x, y, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            if (slot.index < 2 && !menu.showSlots) {
                continue;
            }
            slotBox(graphics, x + slot.x - 1, y + slot.y - 1);
        }
        if (menu.showSlots) {
            // the little arrow between input and output
            int ax = x + MachineMenu.INPUT_X + 4, ay = y + MachineMenu.INPUT_Y + 20;
            graphics.fill(ax + 4, ay, ax + 6, ay + 12, 0xFF8B8B8B);
            graphics.fill(ax, ay + 8, ax + 10, ay + 10, 0xFF8B8B8B);
        }
        if (menu.showFluid) {
            gauge(graphics, x + GAUGE_X, y + BAR_Y);
        }
        if (menu.showEnergy) {
            energyBar(graphics, x + BAR_X, y + BAR_Y);
        }
    }

    private static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC6C6C6);
        g.fill(x + 1, y + 1, x + w - 2, y + 3, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 3, y + h - 2, 0xFFFFFFFF);
        g.fill(x + 2, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + w - 3, y + 2, x + w - 1, y + h - 1, 0xFF555555);
    }

    private static void slotBox(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, 0xFF373737);
        g.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    private void energyBar(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + BAR_W + 1, y + BAR_H + 1, 0xFF373737);
        g.fill(x, y, x + BAR_W, y + BAR_H, 0xFF1A1A1A);
        long capacity = Math.max(1, menu.energyCapacity());
        int filled = (int) Math.min(BAR_H, BAR_H * (long) menu.energy() / capacity);
        for (int row = 0; row < filled; row++) {
            int shade = 0xE0 - 0x40 * row / BAR_H;
            g.fill(x, y + BAR_H - 1 - row, x + BAR_W, y + BAR_H - row, 0xFF000000 | shade << 16 | (shade / 5) << 8);
        }
    }

    private void gauge(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + BAR_W + 1, y + BAR_H + 1, 0xFF373737);
        g.fill(x, y, x + BAR_W, y + BAR_H, 0xFF1A1A1A);
        var fluid = menu.fluid();
        long capacity = Math.max(1, menu.fluidCapacity());
        int filled = (int) Math.min(BAR_H, BAR_H * (long) menu.fluidAmount() / capacity);
        if (filled <= 0 || fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            return;
        }
        var extensions = IClientFluidTypeExtensions.of(fluid);
        TextureAtlasSprite sprite = minecraft.getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                .apply(extensions.getStillTexture());
        int tint = extensions.getTintColor();
        float r = (tint >> 16 & 255) / 255f, gr = (tint >> 8 & 255) / 255f, b = (tint & 255) / 255f;
        int drawn = 0;
        while (drawn < filled) {
            int piece = Math.min(16, filled - drawn);
            g.blit(x, y + BAR_H - drawn - piece, 0, BAR_W, piece, sprite, r, gr, b, 1f);
            drawn += piece;
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0x404040, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        List<Component> lines = new ArrayList<>();
        if (menu.showEnergy && inside(mouseX, mouseY, BAR_X, BAR_Y)) {
            lines.add(Component.literal("Energy"));
            lines.add(Component.literal(format(menu.energy()) + " / " + format(menu.energyCapacity()) + " FE"));
        } else if (menu.showFluid && inside(mouseX, mouseY, GAUGE_X, BAR_Y)) {
            var fluid = menu.fluid();
            lines.add(fluid == net.minecraft.world.level.material.Fluids.EMPTY ? Component.literal("Empty")
                    : fluid.getFluidType().getDescription());
            lines.add(Component.literal(format(menu.fluidAmount()) + " / " + format(menu.fluidCapacity()) + " mB"));
        }
        if (!lines.isEmpty()) {
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    private boolean inside(int mouseX, int mouseY, int x, int y) {
        return mouseX >= leftPos + x && mouseX < leftPos + x + BAR_W && mouseY >= topPos + y && mouseY < topPos + y + BAR_H;
    }

    private static String format(int value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }
}
