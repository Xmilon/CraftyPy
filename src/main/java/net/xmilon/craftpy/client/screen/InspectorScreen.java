package net.xmilon.craftpy.client.screen;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.client.assets.AssetInfo;

/**
 * Read-only inspector for a single asset id: namespace, source pack, dimensions,
 * format, file size and (best-effort) which open project files reference it.
 */
public class InspectorScreen extends Screen {
    private final Screen parent;
    private final AssetKind kind;
    private final String assetId;

    public InspectorScreen(Screen parent, AssetKind kind, String assetId) {
        super(Component.literal("Inspector"));
        this.parent = parent;
        this.kind = kind;
        this.assetId = assetId;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width / 2 - 40, height - 26, 80, 18).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        AssetInfo.Result info = AssetInfo.describe(kind, assetId);
        int x = 20;
        int y = 30;
        int lh = 12;
        graphics.drawString(font, "Asset inspector", x, y, 0x4FC3F7, false);
        y += lh + 4;
        y = row(graphics, x, y, lh, "ID", info.id());
        y = row(graphics, x, y, lh, "Kind", info.kind().name().toLowerCase(java.util.Locale.ROOT));
        y = row(graphics, x, y, lh, "Namespace", info.namespace());
        y = row(graphics, x, y, lh, "Source pack", info.sourcePack());
        y = row(graphics, x, y, lh, "Dimensions", info.dimensions());
        y = row(graphics, x, y, lh, "Format", info.format());
        y = row(graphics, x, y, lh, "File size", info.size());
        y = row(graphics, x, y, lh, "Lookup", info.detail());

        y += 4;
        graphics.drawString(font, "Used by open files:", x, y, 0xD7E1EA, false);
        y += lh;
        List<String> used = AssetInfo.usedBy(assetId);
        if (used.isEmpty()) {
            graphics.drawString(font, "  (none of the open project files reference this id)", x, y, 0x7D8B9A,
                    false);
        } else {
            for (String file : used) {
                graphics.drawString(font, "  " + file, x, y, 0xD7E1EA, false);
                y += lh;
            }
        }
        graphics.drawString(font, "Note: usage scan covers files opened this session.", x, height - 40, 0x7D8B9A,
                false);
    }

    private int row(GuiGraphics graphics, int x, int y, int lh, String label, String value) {
        graphics.drawString(font, label + ":", x, y, 0x7D8B9A, false);
        graphics.drawString(font, value == null ? "-" : value, x + 90, y, 0xD7E1EA, false);
        return y + lh;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
