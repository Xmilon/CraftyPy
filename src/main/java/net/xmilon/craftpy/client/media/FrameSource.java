package net.xmilon.craftpy.client.media;

/**
 * The pictures of one image, GIF or video: what a window widget shows. Decoded pixels are ARGB ({@code 0xAARRGGBB}),
 * {@code width * height} ints. {@link #pixels} may answer null while a frame is still being decoded; the caller then
 * keeps showing the previous one.
 */
public interface FrameSource extends AutoCloseable {
    int width();

    int height();

    int frameCount();

    /** How long one pass lasts in milliseconds; 0 for a still picture. */
    long durationMs();

    /** The frame to show {@code positionMs} into the animation (clamped to the first and last). */
    int frameAt(long positionMs);

    /** The pixels of a frame, or null when they are not ready yet. Any thread. */
    int[] pixels(int index);

    /** Memory this source holds, for the cache budget. */
    long bytes();

    @Override
    void close();
}
