package net.xmilon.craftpy.client.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

/**
 * Video as Motion JPEG in an AVI file: the one video format Java can decode without any extra library, because every
 * frame is a plain JPEG. Frames stay compressed in memory and are decoded on a background thread a few frames ahead of
 * playback, so a long clip costs about its file size, not its decoded size. Sound is not read from the AVI: a video
 * names a separate audio file ({@code audio}), see the guide.
 */
public final class AviFrames implements FrameSource {
    public static final int MAX_FILE_BYTES = 48 * 1024 * 1024;
    public static final int MAX_SIDE = 1280;
    public static final int MAX_FRAMES = 36_000;
    private static final int CACHE_FRAMES = 8;
    private static final int PREFETCH = 3;
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CraftPy-Video-Decode");
        thread.setDaemon(true);
        return thread;
    });

    private final byte[] file;
    private final int[] offsets;
    private final int[] sizes;
    private final double fps;
    private final int width;
    private final int height;
    private final Map<Integer, int[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, int[]> eldest) {
            return size() > CACHE_FRAMES;
        }
    };
    private final Set<Integer> pending = new HashSet<>();
    private volatile boolean closed;

    private AviFrames(byte[] file, int[] offsets, int[] sizes, double fps, int width, int height) {
        this.file = file;
        this.offsets = offsets;
        this.sizes = sizes;
        this.fps = fps;
        this.width = width;
        this.height = height;
    }

    /** Reads the headers and the first frame; the other frames are decoded while they play. */
    public static AviFrames decode(byte[] data) throws MediaException {
        if (data.length > MAX_FILE_BYTES) {
            throw new MediaException("The video file is " + (data.length >> 20) + " MiB: at most " + (MAX_FILE_BYTES >> 20) + " MiB");
        }
        Streams streams = new Streams();
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (data.length < 12 || !fourcc(buffer, 0).equals("RIFF") || !fourcc(buffer, 8).equals("AVI ")) {
                throw new MediaException("Not an AVI file. Videos must be Motion JPEG in an AVI (see the guide for the ffmpeg command)");
            }
            walk(buffer, 12, Math.min(data.length, 8 + buffer.getInt(4)), streams, 0);
        } catch (RuntimeException e) {
            throw new MediaException("The AVI file is damaged: " + e.getMessage(), e);
        }
        if (!streams.motionJpeg) {
            throw new MediaException("The video is not Motion JPEG (" + (streams.codec.isBlank() ? "unknown codec" : streams.codec)
                    + "). Convert it with: ffmpeg -i in.mp4 -an -c:v mjpeg -q:v 5 out.avi");
        }
        int frames = streams.offsets.size();
        if (frames == 0) {
            throw new MediaException("The AVI has no video frames");
        }
        if (frames > MAX_FRAMES) {
            throw new MediaException("The video has " + frames + " frames: at most " + MAX_FRAMES);
        }
        int[] offsets = streams.offsets.stream().mapToInt(Integer::intValue).toArray();
        int[] sizes = streams.sizes.stream().mapToInt(Integer::intValue).toArray();
        double fps = streams.scale > 0 && streams.rate > 0 ? (double) streams.rate / streams.scale
                : streams.microsPerFrame > 0 ? 1_000_000.0 / streams.microsPerFrame : 25;
        fps = Math.max(1, Math.min(120, fps));
        int[] first = jpeg(data, offsets[0], sizes[0]);
        int width = first[0], height = first[1];
        if (width > MAX_SIDE || height > MAX_SIDE) {
            throw new MediaException("The video is " + width + " x " + height + ": at most " + MAX_SIDE + " x " + MAX_SIDE);
        }
        AviFrames video = new AviFrames(data, offsets, sizes, fps, width, height);
        synchronized (video.cache) {
            video.cache.put(0, java.util.Arrays.copyOfRange(first, 2, first.length));
        }
        return video;
    }

    /** Collected while walking the chunks. */
    private static final class Streams {
        int streamIndex = -1;
        int videoStream = -1;
        boolean currentIsVideo;
        boolean motionJpeg;
        String codec = "";
        long scale;
        long rate;
        long microsPerFrame;
        final List<Integer> offsets = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();
    }

    private static void walk(ByteBuffer b, int start, int end, Streams s, int depth) {
        if (depth > 6) {
            throw new IllegalStateException("chunks nested too deep");
        }
        int position = start;
        while (position + 8 <= end) {
            String id = fourcc(b, position);
            int size = b.getInt(position + 4);
            int body = position + 8;
            if (size < 0 || body + size > b.limit()) {
                size = Math.max(0, Math.min(size < 0 ? Integer.MAX_VALUE : size, b.limit() - body));
            }
            switch (id) {
                case "LIST" -> {
                    String type = fourcc(b, body);
                    if (type.equals("strl")) {
                        s.streamIndex++;
                        s.currentIsVideo = false;
                    }
                    if (type.equals("hdrl") || type.equals("strl") || type.equals("movi") || type.equals("rec ")) {
                        walk(b, body + 4, body + size, s, depth + 1);
                    }
                }
                case "avih" -> {
                    if (size >= 4) {
                        s.microsPerFrame = b.getInt(body) & 0xFFFFFFFFL;
                    }
                }
                case "strh" -> {
                    if (size >= 28 && fourcc(b, body).equals("vids") && s.videoStream < 0) {
                        s.videoStream = s.streamIndex;
                        s.currentIsVideo = true;
                        s.codec = fourcc(b, body + 4);
                        s.scale = b.getInt(body + 20) & 0xFFFFFFFFL;
                        s.rate = b.getInt(body + 24) & 0xFFFFFFFFL;
                    }
                }
                case "strf" -> {
                    if (s.currentIsVideo && size >= 20) {
                        String compression = fourcc(b, body + 16);
                        if (!compression.isBlank()) {
                            s.codec = compression;
                        }
                    }
                    if (s.currentIsVideo) {
                        s.motionJpeg = isMotionJpeg(s.codec);
                    }
                }
                default -> {
                    // 00dc / 00db: a video frame of stream 00
                    if (id.length() == 4 && id.charAt(2) == 'd' && (id.charAt(3) == 'c' || id.charAt(3) == 'b')
                            && Character.isDigit(id.charAt(0)) && Character.isDigit(id.charAt(1)) && s.videoStream >= 0
                            && Integer.parseInt(id.substring(0, 2)) == s.videoStream && size > 0) {
                        s.offsets.add(body);
                        s.sizes.add(size);
                    }
                }
            }
            position = body + size + (size & 1);
        }
    }

    private static boolean isMotionJpeg(String codec) {
        return codec.equalsIgnoreCase("MJPG") || codec.equalsIgnoreCase("dmb1") || codec.equalsIgnoreCase("AVRn");
    }

    private static String fourcc(ByteBuffer b, int at) {
        byte[] code = new byte[4];
        for (int i = 0; i < 4; i++) {
            code[i] = b.get(at + i);
        }
        return new String(code, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** {@code [width, height, pixels...]} of one JPEG frame. */
    private static int[] jpeg(byte[] data, int offset, int size) throws MediaException {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data, offset, size));
            if (image == null) {
                throw new MediaException("A video frame is not a JPEG");
            }
            int[] pixels = StillFrames.argb(image);
            int[] out = new int[pixels.length + 2];
            out[0] = image.getWidth();
            out[1] = image.getHeight();
            System.arraycopy(pixels, 0, out, 2, pixels.length);
            return out;
        } catch (IOException | RuntimeException e) {
            throw new MediaException("A video frame could not be decoded: " + e.getMessage(), e);
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
        return offsets.length;
    }

    @Override
    public long durationMs() {
        return Math.round(offsets.length * 1000.0 / fps);
    }

    public double fps() {
        return fps;
    }

    @Override
    public int frameAt(long positionMs) {
        return (int) Math.max(0, Math.min(offsets.length - 1, positionMs * fps / 1000.0));
    }

    /** The decoded frame when it is ready; otherwise null, and it (with the next few) is decoded in the background. */
    @Override
    public int[] pixels(int index) {
        int frame = Math.max(0, Math.min(offsets.length - 1, index));
        int[] ready;
        synchronized (cache) {
            ready = cache.get(frame);
        }
        for (int i = 0; i <= PREFETCH && frame + i < offsets.length; i++) {
            schedule(frame + i);
        }
        return ready;
    }

    private void schedule(int frame) {
        synchronized (cache) {
            if (closed || cache.containsKey(frame) || !pending.add(frame)) {
                return;
            }
        }
        DECODER.execute(() -> {
            int[] decoded = null;
            try {
                if (!closed) {
                    int[] raw = jpeg(file, offsets[frame], sizes[frame]);
                    if (raw[0] == width && raw[1] == height) {
                        decoded = java.util.Arrays.copyOfRange(raw, 2, raw.length);
                    }
                }
            } catch (MediaException | RuntimeException ignored) {
                // one bad frame leaves the previous picture on screen
            }
            synchronized (cache) {
                pending.remove(frame);
                if (decoded != null && !closed) {
                    cache.put(frame, decoded);
                }
            }
        });
    }

    @Override
    public long bytes() {
        return file.length + (long) CACHE_FRAMES * width * height * 4;
    }

    @Override
    public void close() {
        closed = true;
        synchronized (cache) {
            cache.clear();
        }
    }
}
