package net.xmilon.craftpy.client.screen;

import java.util.Locale;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.client.assets.AssetPreview;
import net.xmilon.craftpy.client.assets.SoundPreview;

/**
 * Viewer for a single project asset file opened from the explorer. Textures are
 * decoded and rendered on a checkerboard backdrop (with an "Edit" shortcut into
 * the pixel editor); sounds resolve their matching sound event for best-effort
 * playback; anything else shows file info. Mirrors the IntelliJ "click a file
 * to preview it" behavior.
 */
public class AssetFileScreen extends Screen {
    private static final int MAX_ZOOM = 16;

    private final Screen parent;
    private final String projectId;
    private final String path;
    private final byte[] data;
    private final String error;

    private NativeImage image;
    private ResourceLocation texture;
    private int zoom = 1;
    private String status = "";
    private String soundEvent;
    private ResourceLocation soundFile;
    private float volume = 1.0f;
    private float pitch = 1.0f;
    private boolean looping;

    public AssetFileScreen(Screen parent, String projectId, String path, byte[] data, String error) {
        super(Component.literal("Asset viewer"));
        this.parent = parent;
        this.projectId = projectId;
        this.path = path;
        this.data = data == null ? new byte[0] : data;
        this.error = error == null ? "" : error;
    }

    @Override
    protected void init() {
        if (isImage() && image == null && error.isEmpty()) {
            decodeImage();
        }
        if (isSound() && soundEvent == null) {
            resolveSound();
        }
        int y = height - 26;
        int bx = 12;
        if (isImage() && image != null) {
            addRenderableWidget(Button.builder(Component.literal("Edit..."), b -> editImage())
                    .bounds(bx, y, 70, 18).build());
            bx += 74;
            addRenderableWidget(Button.builder(Component.literal("Zoom+"), b -> zoom = Math.min(MAX_ZOOM, zoom + 1))
                    .bounds(bx, y, 60, 18).build());
            bx += 64;
            addRenderableWidget(Button.builder(Component.literal("Zoom-"), b -> zoom = Math.max(1, zoom - 1))
                    .bounds(bx, y, 60, 18).build());
            bx += 64;
        }
        if (isSound() && soundEvent != null && soundFile != null) {
            addRenderableWidget(Button.builder(Component.literal("Play"), b -> play(false)).bounds(bx, y, 54, 18).build());
            bx += 58;
            addRenderableWidget(Button.builder(Component.literal("Loop"), b -> play(true)).bounds(bx, y, 54, 18).build());
            bx += 58;
            addRenderableWidget(Button.builder(Component.literal("Stop"), b -> {
                SoundPreview.stop();
                status = "Stopped";
            }).bounds(bx, y, 54, 18).build());
            bx += 58;
        }
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 86, y, 74, 18).build());
    }

    private void decodeImage() {
        try {
            NativeImage decoded = NativeImage.read(data);
            // The DynamicTexture takes ownership of the pixels; removed() releases it.
            texture = minecraft.getTextureManager().register("craftpy/asset_preview", new DynamicTexture(decoded));
            image = decoded;
        } catch (Exception e) {
            CraftPy.LOGGER.warn("Failed to decode asset preview for {}", path, e);
            image = null;
            status = "Could not decode image: " + e.getMessage();
        }
    }

    /** Maps "assets/sounds/a/b.ogg" to the sound event "<project>:a/b", or null. */
    private void resolveSound() {
        String lower = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        String prefix = "assets/sounds/";
        if (!lower.startsWith(prefix) || !lower.endsWith(".ogg")) {
            soundEvent = "";
            return;
        }
        String rel = lower.substring(prefix.length(), lower.length() - ".ogg".length());
        soundEvent = projectId + ":" + rel;
        soundFile = AssetPreview.soundFile(soundEvent);
    }

    private void play(boolean loop) {
        SoundPreview.setPitchVolume(pitch, volume);
        boolean ok = loop ? SoundPreview.playLooping(soundEvent, pitch, volume)
                : SoundPreview.play(soundEvent, pitch, volume);
        looping = ok && loop;
        status = ok ? (loop ? "Looping" : "Playing")
                : "Sound event '" + soundEvent + "' is not registered (needs assets/sounds.json)";
    }

    private void editImage() {
        if (minecraft == null || image == null) {
            return;
        }
        SoundPreview.stop();
        String name = path;
        if (name.startsWith("assets/textures/")) {
            name = name.substring("assets/textures/".length());
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".png")) {
            name = name.substring(0, name.length() - 4);
        }
        minecraft.setScreen(TextureEditorScreen.fromImage(this, projectId, image, name));
    }

    private boolean isImage() {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    private boolean isSound() {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".mp3");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, "Asset viewer", 12, 12, 0x4FC3F7, false);
        graphics.drawString(font, path, 12, 26, 0xFFFFFF, false);
        graphics.drawString(font, data.length + " bytes", 12, 38, 0x7D8B9A, false);

        int y = 52;
        if (!error.isEmpty()) {
            graphics.drawString(font, error, 12, y, 0xFF6B6B, false);
        } else if (isImage() && image != null) {
            drawImage(graphics, y);
        } else if (isSound()) {
            graphics.drawString(font, "Sound event: " + (soundEvent.isEmpty() ? "-" : soundEvent), 12, y, 0xD7E1EA,
                    false);
            graphics.drawString(font, "Resolved file: " + (soundFile == null ? "unresolved" : soundFile.toString()),
                    12, y + 12, soundFile == null ? 0xFFB86C : 0xD7E1EA, false);
            graphics.drawString(font, "State: " + (SoundPreview.isPlaying() ? (looping ? "looping" : "playing") : "stopped"),
                    12, y + 24, 0xD7E1EA, false);
            if (soundEvent.isEmpty()) {
                graphics.drawString(font, "Sounds outside assets/sounds/ cannot be played.", 12, y + 40, 0xFFB86C,
                        false);
            }
        } else {
            graphics.drawString(font, "No preview available for this file type.", 12, y, 0xFFB86C, false);
        }
        if (!status.isEmpty()) {
            graphics.drawString(font, status, 12, height - 44, 0xFFB86C, false);
        }
    }

    private void drawImage(GuiGraphics graphics, int y) {
        int imgW = image.getWidth();
        int imgH = image.getHeight();
        int drawW = imgW * zoom;
        int drawH = imgH * zoom;
        int boxX = 12;
        int boxY = y;
        int boxW = Math.min(drawW + 16, width - 24);
        int boxH = Math.min(drawH + 16, height - boxY - 34);
        graphics.fill(boxX - 1, boxY - 1, boxX + boxW + 1, boxY + boxH + 1, 0xFF000000);
        // checkerboard backdrop so transparent pixels are visible
        for (int cy = 0; cy < boxH; cy += 8) {
            for (int cx = 0; cx < boxW; cx += 8) {
                boolean light = (((cx / 8) + (cy / 8)) & 1) == 0;
                graphics.fill(boxX + cx, boxY + cy, boxX + Math.min(cx + 8, boxW), boxY + Math.min(cy + 8, boxH),
                        light ? 0xFF3A3A3A : 0xFF2A2A2A);
            }
        }
        int dx = boxX + Math.max(0, (boxW - drawW) / 2);
        int dy = boxY + Math.max(0, (boxH - drawH) / 2);
        graphics.enableScissor(boxX, boxY, boxX + boxW, boxY + boxH);
        graphics.blit(texture, dx, dy, drawW, drawH, 0f, 0f, imgW, imgH, imgW, imgH);
        graphics.disableScissor();
        graphics.drawString(font, imgW + "x" + imgH + "  zoom " + zoom + "x", boxX + boxW + 12, boxY + 4, 0xD7E1EA,
                false);
        graphics.drawString(font, "scroll to zoom", boxX + boxW + 12, boxY + 16, 0x7D8B9A, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isImage() && image != null) {
            zoom = Math.max(1, Math.min(MAX_ZOOM, zoom + (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void tick() {
        super.tick();
        SoundPreview.tick();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        SoundPreview.stop();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void removed() {
        super.removed();
        if (texture != null && minecraft != null) {
            // release() closes the DynamicTexture, which owns the decoded pixels.
            minecraft.getTextureManager().release(texture);
            texture = null;
            image = null;
        } else if (image != null) {
            image.close();
            image = null;
        }
    }
}
