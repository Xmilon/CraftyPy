package net.xmilon.craftpy.client.assets;

import com.mojang.blaze3d.platform.NativeImage;

import net.xmilon.craftpy.client.PixelBuffer;

/** PNG bytes to and from the editor's {@link PixelBuffer} (ARGB). Client thread or any thread without GL: only NativeImage memory. */
public final class TextureCodec {
    public static final int MAX_SIDE = 1024;

    private TextureCodec() {
    }

    /** Decodes a PNG; throws {@link IllegalArgumentException} with a readable message when it is too large or broken. */
    public static PixelBuffer decode(byte[] png) {
        try (NativeImage image = NativeImage.read(png)) {
            int w = image.getWidth();
            int h = image.getHeight();
            if (w > MAX_SIDE || h > MAX_SIDE) {
                throw new IllegalArgumentException("The image is " + w + "x" + h + "; the editor opens textures up to "
                        + MAX_SIDE + "x" + MAX_SIDE + ".");
            }
            int[] argb = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    argb[y * w + x] = PixelBuffer.swapRedBlue(image.getPixelRGBA(x, y));
                }
            }
            return new PixelBuffer(w, h, argb);
        } catch (java.io.IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("Could not decode the image: " + e.getMessage(), e);
        }
    }

    public static byte[] encode(PixelBuffer buffer) throws java.io.IOException {
        try (NativeImage image = new NativeImage(buffer.width(), buffer.height(), false)) {
            for (int y = 0; y < buffer.height(); y++) {
                for (int x = 0; x < buffer.width(); x++) {
                    image.setPixelRGBA(x, y, PixelBuffer.swapRedBlue(buffer.get(x, y)));
                }
            }
            return image.asByteArray();
        }
    }
}
