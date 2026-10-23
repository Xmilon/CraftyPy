package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.xmilon.craftpy.menu.MachineMenu;
import net.xmilon.craftpy.menu.ScreenLayout;

/**
 * The Minecraft-style window of a machine block ({@code Screen(...)}) or of a window Python opened ({@code ui.open_screen}),
 * drawn from its {@link ScreenLayout} with the game's own art and Mekanism's when it is installed ({@link LegacySkin}). The
 * liquid uses its own texture, so a resource pack that changes the liquid changes the gauge. A button sends its number to the
 * server, which tells the project's Python. The server decides the contents; this only draws what it was sent.
 */
public class MachineScreen extends AbstractContainerScreen<MachineMenu> {
    public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = menu.layout.width;
        imageHeight = menu.layout.height;
    }

    private boolean over(ScreenLayout.Piece piece, double mouseX, double mouseY) {
        return mouseX >= leftPos + piece.x() && mouseX < leftPos + piece.x() + piece.width()
                && mouseY >= topPos + piece.y() && mouseY < topPos + piece.y() + piece.height();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        var layout = menu.layout;
        LegacySkin skin = new LegacySkin(LegacySkin.screen(graphics), leftPos, topPos, layout.style);
        if (!layout.has("lg_panel")) {
            skin.panel(0, 0, imageWidth, imageHeight);
        }
        for (ScreenLayout.Piece piece : layout.pieces) {
            switch (piece.type()) {
                case "lg_panel" -> skin.panel(piece.x(), piece.y(), piece.width(), piece.height());
                case "lg_slot" -> skin.slot(piece.x(), piece.y(), group(piece.slot()));
                case "lg_slots" -> {
                    for (int row = 0; row < piece.rows(); row++) {
                        for (int column = 0; column < piece.columns(); column++) {
                            skin.slot(piece.x() + column * 18, piece.y() + row * 18, piece.slot());
                        }
                    }
                }
                case "lg_player" -> {
                    for (int row = 0; row < 3; row++) {
                        for (int column = 0; column < 9; column++) {
                            skin.slot(piece.x() + column * 18, piece.y() + row * 18, "");
                        }
                    }
                    for (int column = 0; column < 9; column++) {
                        skin.slot(piece.x() + column * 18, piece.y() + 58, "");
                    }
                }
                case "lg_energy" -> skin.energy(piece.x(), piece.y(), piece.width(), piece.height(), menu.fraction(piece.bind(), piece.max()));
                case "lg_fluid" -> skin.gauge(piece.x(), piece.y(), piece.width(), piece.height(), () -> liquid(graphics, piece));
                case "lg_arrow" -> skin.arrow(piece.x(), piece.y(), piece.width(), piece.height(), menu.fraction(piece.bind(), piece.max()));
                case "lg_flame" -> skin.flame(piece.x(), piece.y(), piece.width(), piece.height(), menu.fraction(piece.bind(), piece.max()));
                case "lg_bar" -> skin.bar(piece.x(), piece.y(), piece.width(), piece.height(), menu.fraction(piece.bind(), piece.max()), piece.color());
                case "lg_image" -> skin.image(piece.x(), piece.y(), piece.width(), piece.height(), piece.text());
                case "lg_button" -> skin.button(piece.x(), piece.y(), piece.width(), piece.height(), over(piece, mouseX, mouseY));
                case "lg_icon" -> {
                    var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(piece.text()) == null
                            ? ResourceLocation.withDefaultNamespace("air") : ResourceLocation.tryParse(piece.text()));
                    graphics.renderItem(new ItemStack(item), leftPos + piece.x(), topPos + piece.y());
                }
                default -> {
                }
            }
        }
    }

    /** The group of {@code input.0}: Mekanism colors input and output slots. */
    private static String group(String slot) {
        int dot = slot.indexOf('.');
        return dot < 0 ? slot : slot.substring(0, dot);
    }

    /** The liquid of the tank a gauge is bound to, tiled with its own texture and tint, filling from the bottom. */
    private void liquid(GuiGraphics graphics, ScreenLayout.Piece piece) {
        int tank = MachineMenu.tankOf(piece.bind());
        var fluid = menu.fluid(tank);
        int filled = (int) Math.round(piece.height() * menu.fraction(piece.bind(), piece.max()));
        if (filled <= 0 || fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            return;
        }
        var extensions = IClientFluidTypeExtensions.of(fluid);
        TextureAtlasSprite sprite = minecraft.getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                .apply(extensions.getStillTexture());
        int tint = extensions.getTintColor();
        float r = (tint >> 16 & 255) / 255f, g = (tint >> 8 & 255) / 255f, b = (tint & 255) / 255f;
        int x = leftPos + piece.x(), bottom = topPos + piece.y() + piece.height();
        for (int drawn = 0; drawn < filled; ) {
            int rows = Math.min(16, filled - drawn);
            for (int across = 0; across < piece.width(); across += 16) {
                graphics.blit(x + across, bottom - drawn - rows, 0, Math.min(16, piece.width() - across), rows, sprite, r, g, b, 1f);
            }
            drawn += rows;
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        for (ScreenLayout.Piece piece : menu.layout.pieces) {
            if (piece.type().equals("lg_label")) {
                String text = menu.label(piece.text(), playerInventoryTitle.getString());
                graphics.drawString(font, text, piece.x(), piece.y(), 0xFF000000 | piece.color(), false);
            } else if (piece.type().equals("lg_button")) {
                String text = menu.label(piece.text(), playerInventoryTitle.getString());
                int width = font.width(text);
                // a caption that does not fit is cut, like vanilla buttons do
                if (width > piece.width() - 4) {
                    text = font.plainSubstrByWidth(text, piece.width() - 8) + "...";
                    width = font.width(text);
                }
                graphics.drawString(font, text, piece.x() + (piece.width() - width) / 2, piece.y() + (piece.height() - 8) / 2,
                        0xFFFFFFFF, true);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            List<ScreenLayout.Piece> buttons = menu.layout.buttons();
            for (int i = 0; i < buttons.size(); i++) {
                if (over(buttons.get(i), mouseX, mouseY) && minecraft != null && minecraft.gameMode != null) {
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, i);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        List<Component> lines = new ArrayList<>();
        for (ScreenLayout.Piece piece : menu.layout.pieces) {
            if (!over(piece, mouseX, mouseY)) {
                continue;
            }
            if (piece.type().equals("lg_energy")) {
                lines.add(Component.literal("Energy"));
                lines.add(Component.literal(format(menu.energy()) + " / " + format(menu.energyCapacity()) + " FE"));
            } else if (piece.type().equals("lg_fluid")) {
                int tank = MachineMenu.tankOf(piece.bind());
                var fluid = menu.fluid(tank);
                lines.add(fluid == net.minecraft.world.level.material.Fluids.EMPTY ? Component.literal("Empty")
                        : fluid.getFluidType().getDescription());
                lines.add(Component.literal(format(menu.fluidAmount(tank)) + " / " + format(menu.fluidCapacity(tank)) + " mB"));
            }
            if (!lines.isEmpty()) {
                break;
            }
        }
        if (!lines.isEmpty()) {
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    private static String format(int value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }
}
