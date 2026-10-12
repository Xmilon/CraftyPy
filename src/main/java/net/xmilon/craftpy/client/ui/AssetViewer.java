package net.xmilon.craftpy.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.assets.AssetInfo;
import net.xmilon.craftpy.client.assets.SoundPreview;

/**
 * Widget for viewing assets (textures, sounds).
 */
public class AssetViewer extends Widget {
    private String assetPath;
    private AssetKind kind;
    private String assetId;
    private byte[] data;
    private boolean loading;
    private String error = "";

    public void setAsset(String path) {
        if (path != null && path.equals(this.assetPath)) return;
        
        this.assetPath = path;
        this.data = null;
        this.loading = false;
        this.error = "";
        
        if (path == null) {
            this.kind = null;
            this.assetId = null;
            return;
        }
        
        if (path.startsWith("assets/textures/")) {
            kind = AssetKind.TEXTURE;
            assetId = path.substring("assets/textures/".length());
            if (assetId.endsWith(".png")) assetId = assetId.substring(0, assetId.length() - 4);
        } else if (path.startsWith("assets/sounds/")) {
            kind = AssetKind.SOUND;
            assetId = path.substring("assets/sounds/".length());
            if (assetId.endsWith(".ogg")) assetId = assetId.substring(0, assetId.length() - 4);
        } else if (path.startsWith("assets/models/")) {
            kind = AssetKind.MODEL;
            assetId = path.substring("assets/models/".length());
            if (assetId.endsWith(".json")) assetId = assetId.substring(0, assetId.length() - 5);
        } else {
            kind = null;
            assetId = null;
        }
        
        String projectId = ClientProjectStore.getInstance().activeProjectId();
        if (projectId != null && kind != null) {
            loading = true;
            final String currentPath = path;
            ClientProjectStore.getInstance().requestAsset(projectId, path, bytes -> {
                if (currentPath.equals(this.assetPath)) {
                    this.data = bytes.data();
                    this.error = bytes.error();
                    this.loading = false;
                }
            });
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Theme theme = Theme.current();
        Font font = Minecraft.getInstance().font;
        
        if (assetPath == null) {
            graphics.drawString(font, "No asset selected", x + 10, y + 10, theme.color(Theme.MUTED), false);
            return;
        }

        graphics.drawString(font, "ASSET INSPECTOR: " + assetPath, x + 10, y + 10, theme.color(Theme.ACCENT), false);
        
        if (loading) {
            graphics.drawString(font, "Loading asset data...", x + 10, y + 30, theme.color(Theme.MUTED), false);
            return;
        }
        
        if (!error.isEmpty()) {
            graphics.drawString(font, "Error: " + error, x + 10, y + 30, theme.color(Theme.ERROR), false);
            return;
        }

        if (kind == null) {
             graphics.drawString(font, "Unknown asset type or not a previewable asset.", x + 10, y + 30, theme.color(Theme.MUTED), false);
             return;
        }

        AssetInfo.Result info = AssetInfo.describe(kind, assetId);
        int ly = y + 30;
        int lh = 12;
        
        ly = row(graphics, font, theme, x + 10, ly, lh, "ID", info.id());
        ly = row(graphics, font, theme, x + 10, ly, lh, "Kind", info.kind().name());
        ly = row(graphics, font, theme, x + 10, ly, lh, "Namespace", info.namespace());
        ly = row(graphics, font, theme, x + 10, ly, lh, "Dimensions", info.dimensions());
        ly = row(graphics, font, theme, x + 10, ly, lh, "File size", info.size());
        
        if (kind == AssetKind.TEXTURE && data != null) {
             // Preview texture
             graphics.fill(x + 150, y + 30, x + 150 + 64, y + 30 + 64, 0xFF000000);
             graphics.drawString(font, "[Texture Preview Available]", x + 155, y + 55, theme.color(Theme.SUCCESS), false);
             graphics.drawString(font, "(" + data.length + " bytes)", x + 155, y + 67, theme.color(Theme.MUTED), false);
        } else if (kind == AssetKind.SOUND && data != null) {
             graphics.drawString(font, "Sound data loaded (" + data.length + " bytes).", x + 10, ly + 4, theme.color(Theme.SUCCESS), false);
             graphics.drawString(font, "Click to play (Shift-Click to stop)", x + 10, ly + 16, theme.color(Theme.MUTED), false);
        }
    }
    
    private int row(GuiGraphics graphics, Font font, Theme theme, int rx, int ry, int lh, String label, String value) {
        graphics.drawString(font, label + ":", rx, ry, theme.color(Theme.MUTED), false);
        graphics.drawString(font, value == null ? "-" : value, rx + 80, ry, theme.color(Theme.TEXT), false);
        return ry + lh;
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        if (kind == AssetKind.SOUND && data != null) {
            if (Screen.hasShiftDown()) {
                SoundPreview.stop();
            } else {
                // We use the assetId (e.g. "block.stone.break")
                SoundPreview.play(assetId, 1.0f, 1.0f);
            }
            return true;
        }
        return false;
    }
}
