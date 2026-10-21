package net.xmilon.craftpy.client.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** One picture: PNG, JPEG or BMP (what Java's own image readers decode). A GIF goes to {@link GifFrames}. */
public final class StillFrames implements FrameSource {
    /** A picture may not be larger than this on a side: 2048 x 2048 is 16 MiB decoded. */
    public static final int MAX_SIDE = 2048;
    private final int width;
    private final int height;
    private int[] pixels;

    private StillFrames(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    public static StillFrames decode(byte[] data) throws MediaException {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException e) {
            throw new MediaException("Not a readable image: " + e.getMessage(), e);
        }
        if (image == null) {
            throw new MediaException("Not a supported image (PNG, JPEG, GIF or BMP)");
        }
        if (image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE) {
            throw new MediaException("Image is " + image.getWidth() + " x " + image.getHeight() + ": at most " + MAX_SIDE
                    + " x " + MAX_SIDE);
        }
        return new StillFrames(image.getWidth(), image.getHeight(), argb(image));
    }

    /** ARGB pixels of any buffered image; also used by {@link AviFrames} for its JPEG frames. */
    static int[] argb(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int frameCount() {
        return 1;
    }

    @Override
    public long durationMs() {
        return 0;
    }

    @Override
    public int frameAt(long positionMs) {
        return 0;
    }

    @Override
    public int[] pixels(int index) {
        return pixels;
    }

    @Override
    public long bytes() {
        return (long) width * height * 4;
    }

    @Override
    public void close() {
        pixels = null;
    }
}
