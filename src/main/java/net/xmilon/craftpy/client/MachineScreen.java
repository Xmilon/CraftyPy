package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.xmilon.craftpy.menu.LegacyShapes;
import net.xmilon.craftpy.menu.MachineMenu;
import net.xmilon.craftpy.menu.ScreenLayout;

/**
 * The Minecraft-style window of a machine block ({@code Screen(...)}), drawn from its {@link ScreenLayout}: the grey panel,
 * slots, bars, arrows, flames, labels and item icons at the positions the project's window gives. The shapes are the shared
 * {@link LegacyShapes}, so this matches the UI maker's preview. The liquid uses its own texture, so a resource pack that
 * changes the liquid changes the gauge. The server decides the contents; this only draws what it was sent.
 */
public class MachineScreen extends AbstractContainerScreen<MachineMenu> {
    public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = menu.layout.width;
        imageHeight = menu.layout.height;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        LegacyShapes.Surface surface = (x1, y1, x2, y2, argb) -> graphics.fill(leftPos + x1, topPos + y1, leftPos + x2, topPos + y2, argb);
        var layout = menu.layout;
        if (!layout.has("lg_panel")) {
            LegacyShapes.panel(surface, 0, 0, imageWidth, imageHeight);
        }
        for (ScreenLayout.Piece piece : layout.pieces) {
            switch (piece.type()) {
                case "lg_panel" -> LegacyShapes.panel(surface, piece.x(), piece.y(), piece.width(), piece.height());
                case "lg_slot" -> LegacyShapes.slotBox(surface, piece.x(), piece.y());
                case "lg_slots" -> {
                    for (int row = 0; row < piece.rows(); row++) {
                        for (int column = 0; column < piece.columns(); column++) {
                            LegacyShapes.slotBox(surface, piece.x() + column * 18, piece.y() + row * 18);
                        }
                    }
                }
                case "lg_player" -> LegacyShapes.playerSlots(surface, piece.x(), piece.y());
                case "lg_energy" -> LegacyShapes.energy(surface, piece.x(), piece.y(), piece.width(), piece.height(),
                        menu.fraction(piece.bind()));
                case "lg_fluid" -> fluid(graphics, surface, piece);
                case "lg_arrow" -> LegacyShapes.arrow(surface, piece.x(), piece.y(), piece.width(), piece.height(),
                        menu.fraction(piece.bind()));
                case "lg_flame" -> LegacyShapes.flame(surface, piece.x(), piece.y(), piece.width(), piece.height(),
                        menu.fraction(piece.bind()));
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

    private void fluid(GuiGraphics graphics, LegacyShapes.Surface surface, ScreenLayout.Piece piece) {
        LegacyShapes.well(surface, piece.x(), piece.y(), piece.width(), piece.height());
        var fluid = menu.fluid();
        int filled = (int) Math.round(piece.height() * menu.fraction(piece.bind()));
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
                String text = switch (piece.text()) {
                    case "{title}" -> menu.title;
                    case "{inventory}" -> playerInventoryTitle.getString();
                    default -> piece.text();
                };
                graphics.drawString(font, text, piece.x(), piece.y(), 0xFF000000 | piece.color(), false);
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        List<Component> lines = new ArrayList<>();
        for (ScreenLayout.Piece piece : menu.layout.pieces) {
            boolean inside = mouseX >= leftPos + piece.x() && mouseX < leftPos + piece.x() + piece.width()
                    && mouseY >= topPos + piece.y() && mouseY < topPos + piece.y() + piece.height();
            if (!inside) {
                continue;
            }
            if (piece.type().equals("lg_energy")) {
                lines.add(Component.literal("Energy"));
                lines.add(Component.literal(format(menu.energy()) + " / " + format(menu.energyCapacity()) + " FE"));
            } else if (piece.type().equals("lg_fluid")) {
                var fluid = menu.fluid();
                lines.add(fluid == net.minecraft.world.level.material.Fluids.EMPTY ? Component.literal("Empty")
                        : fluid.getFluidType().getDescription());
                lines.add(Component.literal(format(menu.fluidAmount()) + " / " + format(menu.fluidCapacity()) + " mB"));
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
