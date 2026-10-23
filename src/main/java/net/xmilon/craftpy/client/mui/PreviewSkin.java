package net.xmilon.craftpy.client.mui;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import icyllis.modernui.graphics.Bitmap;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.Paint;
import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.client.LegacySkin;
import net.xmilon.craftpy.client.assets.GameResources;

/**
 * The window maker's preview draws Minecraft-style windows with the very art the game uses ({@link LegacySkin}): the textures
 * are read from the game's resources (in the standalone IDE, the vanilla assets on its classpath), and Mekanism's when the game
 * has them. A texture that cannot be read is left out, and the piece falls back to its drawn shape where it has one. Images are
 * kept for the whole session and drawn without smoothing, so pixels stay pixels at every zoom. UI thread only.
 */
final class PreviewSkin {
    private static final Map<ResourceLocation, Image> IMAGES = new HashMap<>();
    private static final java.util.Set<ResourceLocation> MISSING = new java.util.HashSet<>();
    private static final ResourceLocation MEKANISM_PROBE = ResourceLocation.fromNamespaceAndPath("mekanism", "gui/bar/base.png");

    private PreviewSkin() {
    }

    /** A target that draws GUI units on {@code canvas}: {@code unit} pixels per unit, {@code margin} pixels from the edge. */
    static LegacySkin.Target target(Canvas canvas, Paint paint, int unit, int margin) {
        return new LegacySkin.Target() {
            @Override
            public void fill(int x1, int y1, int x2, int y2, int argb) {
                paint.setStroke(false);
                paint.setColor(argb);
                canvas.drawRect(margin + x1 * unit, margin + y1 * unit, margin + x2 * unit, margin + y2 * unit, paint);
            }

            @Override
            public void blit(ResourceLocation texture, int x, int y, int width, int height, float u, float v, int uWidth, int vHeight,
                             int texW, int texH) {
                Image image = image(texture);
                if (image == null || width <= 0 || height <= 0) return;
                // the region is given in the texture's declared size; the file may be bigger (a resource pack)
                float sx = image.getWidth() / (float) texW, sy = image.getHeight() / (float) texH;
                paint.setColor(0xFFFFFFFF);
                paint.setFilter(false);
                canvas.drawImage(image, u * sx, v * sy, (u + uWidth) * sx, (v + vHeight) * sy,
                        margin + x * unit, margin + y * unit, margin + (x + width) * unit, margin + (y + height) * unit, paint);
            }

            @Override
            public void sprite(ResourceLocation sprite, int x, int y, int width, int height) {
                ResourceLocation file = sprite.withPath(path -> "textures/gui/sprites/" + path + ".png");
                Image image = image(file);
                if (image == null) return;
                if (sprite.getPath().startsWith("widget/button")) {
                    // the button is a nine-slice sprite (border 3 of 200 x 20), like the game draws it
                    nineSlice(this, file, x, y, width, height, 3, image.getWidth(), image.getHeight());
                } else {
                    blit(file, x, y, width, height, 0, 0, image.getWidth(), image.getHeight(), image.getWidth(), image.getHeight());
                }
            }

            @Override
            public boolean mekanism() {
                return image(MEKANISM_PROBE) != null;
            }
        };
    }

    private static void nineSlice(LegacySkin.Target target, ResourceLocation file, int x, int y, int w, int h, int b, int tw, int th) {
        int innerW = w - 2 * b, innerH = h - 2 * b;
        target.blit(file, x, y, b, b, 0, 0, b, b, tw, th);
        target.blit(file, x + w - b, y, b, b, tw - b, 0, b, b, tw, th);
        target.blit(file, x, y + h - b, b, b, 0, th - b, b, b, tw, th);
        target.blit(file, x + w - b, y + h - b, b, b, tw - b, th - b, b, b, tw, th);
        if (innerW > 0) {
            target.blit(file, x + b, y, innerW, b, b, 0, tw - 2 * b, b, tw, th);
            target.blit(file, x + b, y + h - b, innerW, b, b, th - b, tw - 2 * b, b, tw, th);
        }
        if (innerH > 0) {
            target.blit(file, x, y + b, b, innerH, 0, b, b, th - 2 * b, tw, th);
            target.blit(file, x + w - b, y + b, b, innerH, tw - b, b, b, th - 2 * b, tw, th);
        }
        if (innerW > 0 && innerH > 0) {
            target.blit(file, x + b, y + b, innerW, innerH, b, b, tw - 2 * b, th - 2 * b, tw, th);
        }
    }

    /** The texture as a GPU image, read once; null when the game has no such file. */
    static Image image(ResourceLocation texture) {
        Image cached = IMAGES.get(texture);
        if (cached != null || MISSING.contains(texture)) return cached;
        Image made = null;
        try {
            var manager = GameResources.manager();
            var resource = manager == null ? java.util.Optional.<net.minecraft.server.packs.resources.Resource>empty() : manager.getResource(texture);
            if (resource.isPresent()) {
                BufferedImage read;
                try (InputStream in = resource.get().open()) {
                    read = ImageIO.read(in);
                }
                if (read != null) {
                    int w = read.getWidth(), h = read.getHeight();
                    int[] argb = read.getRGB(0, 0, w, h, null, 0, w);
                    try (Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Format.RGBA_8888)) {
                        bitmap.setPixels(argb, 0, w, 0, 0, w, h);
                        made = Image.createTextureFromBitmap(bitmap);
                    }
                }
            }
        } catch (Exception | LinkageError e) {
            CraftPyLog.LOGGER.debug("Preview texture {} could not be read: {}", texture, e.toString());
        }
        if (made == null) {
            MISSING.add(texture);
        } else {
            IMAGES.put(texture, made);
        }
        return made;
    }
}
