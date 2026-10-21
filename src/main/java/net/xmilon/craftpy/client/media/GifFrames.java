package net.xmilon.craftpy.client.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NodeList;

/**
 * An animated (or still) GIF decoded with Java's own reader and composed frame by frame the way browsers do: each
 * frame disposal method (leave, clear to transparent, restore the previous picture) and transparency are applied, so
 * every stored frame is a complete picture. Transparent pixels stay transparent.
 */
public final class GifFrames implements FrameSource {
    public static final int MAX_FRAMES = 512;
    public static final int MAX_SIDE = 1024;
    /** All frames together, decoded; a bigger GIF is refused instead of eating the memory of the game. */
    public static final long MAX_BYTES = 96L * 1024 * 1024;
    private static final int MIN_DELAY_MS = 20;
    private static final int DEFAULT_DELAY_MS = 100;

    private final int width;
    private final int height;
    private int[][] frames;
    private final long[] ends;

    private GifFrames(int width, int height, int[][] frames, long[] ends) {
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.ends = ends;
    }

    public static GifFrames decode(byte[] data) throws MediaException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(input, false, false);
            int count = reader.getNumImages(true);
            if (count < 1) {
                throw new MediaException("The GIF has no frames");
            }
            if (count > MAX_FRAMES) {
                throw new MediaException("The GIF has " + count + " frames: at most " + MAX_FRAMES);
            }
            int[] size = logicalSize(reader, count);
            int width = size[0], height = size[1];
            if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
                throw new MediaException("The GIF is " + width + " x " + height + ": at most " + MAX_SIDE + " x " + MAX_SIDE);
            }
            long total = (long) count * width * height * 4;
            if (total > MAX_BYTES) {
                throw new MediaException("The GIF would need " + (total >> 20) + " MiB decoded (" + count + " frames of "
                        + width + " x " + height + "): at most " + (MAX_BYTES >> 20) + " MiB. Make it smaller or shorter.");
            }
            int[][] frames = new int[count][];
            long[] ends = new long[count];
            int[] canvas = new int[width * height];
            int[] before = null;
            String lastDisposal = "none";
            int[] lastRect = {0, 0, 0, 0};
            long time = 0;
            for (int i = 0; i < count; i++) {
                BufferedImage piece = reader.read(i);
                IIOMetadata meta = reader.getImageMetadata(i);
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree("javax_imageio_gif_image_1.0");
                int[] rect = rect(root, piece);
                String disposal = "none";
                int delay = DEFAULT_DELAY_MS;
                NodeList controls = root.getElementsByTagName("GraphicControlExtension");
                if (controls.getLength() > 0) {
                    IIOMetadataNode control = (IIOMetadataNode) controls.item(0);
                    disposal = control.getAttribute("disposalMethod");
                    int centi = parse(control.getAttribute("delayTime"), 0);
                    delay = centi <= 1 ? DEFAULT_DELAY_MS : Math.max(MIN_DELAY_MS, centi * 10);
                }
                // what the previous frame asked for happens before this one is drawn
                if (i > 0) {
                    if ("restoreToBackgroundColor".equals(lastDisposal)) {
                        clear(canvas, width, height, lastRect);
                    } else if ("restoreToPrevious".equals(lastDisposal) && before != null) {
                        System.arraycopy(before, 0, canvas, 0, canvas.length);
                    }
                }
                before = "restoreToPrevious".equals(disposal) ? canvas.clone() : null;
                draw(canvas, width, height, piece, rect);
                frames[i] = canvas.clone();
                time += delay;
                ends[i] = time;
                lastDisposal = disposal;
                lastRect = rect;
            }
            return new GifFrames(width, height, frames, ends);
        } catch (IOException | RuntimeException e) {
            throw new MediaException("Not a readable GIF: " + e.getMessage(), e);
        } finally {
            reader.dispose();
        }
    }

    private static int[] logicalSize(ImageReader reader, int count) throws IOException {
        int width = 0, height = 0;
        try {
            IIOMetadata stream = reader.getStreamMetadata();
            if (stream != null) {
                IIOMetadataNode root = (IIOMetadataNode) stream.getAsTree("javax_imageio_gif_stream_1.0");
                NodeList screen = root.getElementsByTagName("LogicalScreenDescriptor");
                if (screen.getLength() > 0) {
                    IIOMetadataNode node = (IIOMetadataNode) screen.item(0);
                    width = parse(node.getAttribute("logicalScreenWidth"), 0);
                    height = parse(node.getAttribute("logicalScreenHeight"), 0);
                }
            }
        } catch (RuntimeException ignored) {
            // fall back to the bounds of the frames below
        }
        if (width < 1 || height < 1) {
            for (int i = 0; i < count; i++) {
                IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                BufferedImage piece = reader.read(i);
                int[] rect = rect(root, piece);
                width = Math.max(width, rect[0] + rect[2]);
                height = Math.max(height, rect[1] + rect[3]);
            }
        }
        return new int[] {width, height};
    }

    private static int[] rect(IIOMetadataNode root, BufferedImage piece) {
        NodeList descriptors = root.getElementsByTagName("ImageDescriptor");
        if (descriptors.getLength() == 0) {
            return new int[] {0, 0, piece.getWidth(), piece.getHeight()};
        }
        IIOMetadataNode node = (IIOMetadataNode) descriptors.item(0);
        return new int[] {parse(node.getAttribute("imageLeftPosition"), 0), parse(node.getAttribute("imageTopPosition"), 0),
                piece.getWidth(), piece.getHeight()};
    }

    private static void draw(int[] canvas, int width, int height, BufferedImage piece, int[] rect) {
        int[] pixels = piece.getRGB(0, 0, piece.getWidth(), piece.getHeight(), null, 0, piece.getWidth());
        for (int y = 0; y < piece.getHeight(); y++) {
            int cy = rect[1] + y;
            if (cy < 0 || cy >= height) {
                continue;
            }
            for (int x = 0; x < piece.getWidth(); x++) {
                int cx = rect[0] + x;
                int argb = pixels[y * piece.getWidth() + x];
                if (cx >= 0 && cx < width && (argb >>> 24) != 0) {
                    canvas[cy * width + cx] = argb;
                }
            }
        }
    }

    private static void clear(int[] canvas, int width, int height, int[] rect) {
        for (int y = Math.max(0, rect[1]); y < Math.min(height, rect[1] + rect[3]); y++) {
            Arrays.fill(canvas, y * width + Math.max(0, rect[0]), y * width + Math.min(width, rect[0] + rect[2]), 0);
        }
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
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
        return ends.length;
    }

    @Override
    public long durationMs() {
        return ends.length == 1 ? 0 : ends[ends.length - 1];
    }

    @Override
    public int frameAt(long positionMs) {
        int low = 0, high = ends.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (ends[mid] <= positionMs) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    @Override
    public int[] pixels(int index) {
        int[][] current = frames;
        return current == null ? null : current[Math.max(0, Math.min(current.length - 1, index))];
    }

    @Override
    public long bytes() {
        return (long) ends.length * width * height * 4;
    }

    @Override
    public void close() {
        frames = null;
    }
}
