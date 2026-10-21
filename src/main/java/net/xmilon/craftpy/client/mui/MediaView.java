package net.xmilon.craftpy.client.mui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Bitmap;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.text.TextUtils;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.client.media.FrameSource;
import net.xmilon.craftpy.client.media.MediaClock;
import net.xmilon.craftpy.client.media.MediaException;
import net.xmilon.craftpy.client.media.MediaLayout;
import net.xmilon.craftpy.client.media.MediaSource;
import net.xmilon.craftpy.client.media.MediaStore;
import net.xmilon.craftpy.client.media.WindowAudio;

/**
 * One image, GIF, video or sound of a window ({@code image} with a {@code media/...} source, {@code video},
 * {@code audio}). It is an ordinary Modern UI view that draws the current frame in its own place in the layer order, so
 * widgets, other media and the background mix freely (unlike pictures drawn by Minecraft, which always lie on top).
 * <p>
 * It outlives the rebuilds of the window: the window hands it every new description with {@link #apply}, and only a
 * property whose value changed since the last description is acted on, so a window that is refreshed every second does
 * not restart the clip. UI thread only. Decoding and file reads are on loader threads ({@link MediaStore}); the
 * graphics memory is bounded; {@link #release} gives everything back.
 */
final class MediaView extends FrameLayout {
    /** Reports what happened; the window turns these into the Python callbacks. */
    interface Listener {
        void ended();

        void looped();

        void failed(String message);
    }

    /** Graphics memory one widget may hold for decoded frames. */
    private static final long GPU_BUDGET = 24L * 1024 * 1024;
    private static final Set<MediaSource.Kind> VISUAL = Set.of(MediaSource.Kind.STILL, MediaSource.Kind.GIF, MediaSource.Kind.VIDEO);

    private final String type;
    private final Listener listener;
    private final boolean preview;
    private final MediaStore.Reader reader;
    private final Paint paint = new Paint();
    private final TextView status;
    private final Map<Integer, Image> images = new LinkedHashMap<>(16, 0.75f, true);

    private JsonObject last = new JsonObject();
    private String owner = "";
    private MediaSource source;
    private String audioSource = "";
    private FrameSource frames;
    private MediaClock clock = new MediaClock(0, false);
    private WindowAudio audio;
    private MediaSource.Kind kind;
    private MediaLayout.Fit fit = MediaLayout.Fit.CONTAIN;
    private float[] crop;
    private float opacity = 1f;
    private float volume = 1f;
    private boolean muted;
    private boolean released;
    private boolean held;
    private int shown = -1;
    private int generation;
    private long lastChangeNanos;
    private String error;
    private boolean ended;

    private final Runnable frame = this::frame;
    private Runnable observer = () -> { };

    MediaView(Context context, String type, MediaStore.Reader reader, boolean preview, Listener listener) {
        super(context);
        this.type = type;
        this.reader = reader;
        this.preview = preview;
        this.listener = listener;
        setWillNotDraw(false);
        status = CraftPyFragment.label(context, "", icyllis.modernui.R.attr.textAppearanceBodySmall);
        status.setTextColor(IdeColors.TEXT_SECONDARY);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(3);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setVisibility(GONE);
        addView(status, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Called when the state changed in a way a control should show (the position at most ten times a second). */
    void observe(Runnable observer) {
        this.observer = observer;
    }

    // ------------------------------------------------------------------
    // Description -> state
    // ------------------------------------------------------------------

    /**
     * Applies a new description of the widget. {@code owner} is the module or project whose files {@code source} names
     * by default.
     */
    void apply(JsonObject node, String owner) {
        this.owner = owner;
        boolean first = last.size() == 0;
        fit = MediaLayout.Fit.of(str(node, "fit", ""));
        crop = crop(node);
        opacity = (float) Math.max(0, Math.min(1, num(node, "opacity", 1)));
        String nextSource = str(node, "source", "");
        String nextAudio = type.equals("video") ? str(node, "audio", "") : "";
        boolean reload = first || !nextSource.equals(str(last, "source", "")) || !nextAudio.equals(audioSource);
        if (reload) {
            audioSource = nextAudio;
            load(nextSource);
        }
        boolean wantsLoop = node.has("loop") ? bool(node, "loop", false) : kind == MediaSource.Kind.GIF;
        clock.setLoop(wantsLoop);
        if (audio != null) {
            audio.setLoop(wantsLoop);
        }
        changedValue(node, "speed", value -> speed(value.getAsDouble()), first);
        changedValue(node, "volume", value -> volume((float) value.getAsDouble()), first);
        changedValue(node, "muted", value -> muted(value.getAsBoolean()), first);
        changedValue(node, "position", value -> seek(value.getAsDouble() * 1000), first && node.has("position"));
        boolean autoplay = node.has("autoplay") ? bool(node, "autoplay", false) : kind == MediaSource.Kind.GIF;
        if (first || reload) {
            pendingPlay = node.has("playing") ? bool(node, "playing", false) : autoplay;
            tryStart();
        } else if (changed(node, "playing")) {
            play(bool(node, "playing", false));
        }
        if (!first && changed(node, "restart")) {
            seek(0);
            play(true);
        }
        last = node.deepCopy();
        invalidate();
        wake();
    }

    private boolean pendingPlay;

    private interface Setter {
        void set(JsonElement value);
    }

    private boolean changed(JsonObject node, String key) {
        JsonElement now = node.get(key), before = last.get(key);
        return now != null && !now.equals(before);
    }

    private void changedValue(JsonObject node, String key, Setter setter, boolean force) {
        if (node.has(key) && (force || changed(node, key))) {
            try {
                setter.set(node.get(key));
            } catch (RuntimeException ignored) {
                // a value of the wrong type keeps the old setting; the designer reports binding problems
            }
        }
    }

    private static float[] crop(JsonObject node) {
        if (node.has("crop") && node.get("crop").isJsonArray()) {
            JsonArray array = node.getAsJsonArray("crop");
            if (array.size() == 4) {
                try {
                    return new float[] {array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat(),
                            array.get(3).getAsFloat()};
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private void load(String raw) {
        dropSource();
        int mine = ++generation;
        error = null;
        ended = false;
        shown = -1;
        frames = null;
        audio = null;
        clock = new MediaClock(0, false);
        try {
            Set<MediaSource.Kind> allowed = type.equals("audio") ? Set.of(MediaSource.Kind.AUDIO) : VISUAL;
            source = MediaSource.parse(raw, owner, allowed);
            kind = source.kind();
        } catch (MediaException e) {
            source = null;
            kind = null;
            fail(e.getMessage());
            return;
        }
        showStatus("Loading…", false);
        if (kind == MediaSource.Kind.AUDIO) {
            loadAudio(source, mine);
            return;
        }
        held = true;
        MediaStore.acquire(source, reader).whenComplete((loaded, problem) -> post(() -> {
            if (mine != generation || released) {
                return;
            }
            if (problem != null) {
                Throwable cause = problem.getCause() != null ? problem.getCause() : problem;
                fail(cause.getMessage());
                return;
            }
            frames = loaded;
            clock = new MediaClock(loaded.durationMs(), last.has("loop") ? bool(last, "loop", false) : kind == MediaSource.Kind.GIF);
            status.setVisibility(GONE);
            applyPendingPlay();
            if (!audioSource.isEmpty()) {
                loadSidecar(mine);
            }
            requestLayout();
            invalidate();
            wake();
        }));
    }

    private void loadAudio(MediaSource sound, int mine) {
        if (preview) {
            // the designer preview never makes noise
            showStatus("♪ " + sound.path() + " (plays in the game)", false);
            return;
        }
        readAudio(sound, mine, file -> {
            audio = new WindowAudio(file, bool(last, "loop", false));
            clock = audio.clock();
            if (!audio.decodable()) {
                fail("The sound format is not supported: " + sound.extension());
                return;
            }
            status.setVisibility(GONE);
            audio.setVolume(volume);
            audio.setMuted(muted);
            applyPendingPlay();
            wake();
        });
    }

    /** The sound file next to a video: {@code audio} names it. */
    private void loadSidecar(int mine) {
        if (preview) {
            return;
        }
        try {
            MediaSource sound = MediaSource.parse(audioSource, owner, Set.of(MediaSource.Kind.AUDIO));
            readAudio(sound, mine, file -> {
                audio = new WindowAudio(file, false);
                audio.setVolume(volume);
                audio.setMuted(muted || clock.speed() != 1);
                if (clock.playing()) {
                    audio.seek(clock.positionMs());
                    audio.setPlaying(true);
                }
            });
        } catch (MediaException e) {
            fail("audio: " + e.getMessage());
        }
    }

    private interface FileConsumer {
        void accept(java.nio.file.Path file);
    }

    private void readAudio(MediaSource sound, int mine, FileConsumer then) {
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return WindowAudio.cache(sound, reader.read(sound));
            } catch (MediaException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, net.xmilon.craftpy.util.CraftPyExecutors.io()).whenComplete((file, problem) -> post(() -> {
            if (mine != generation || released) {
                return;
            }
            if (problem != null) {
                Throwable cause = problem.getCause() != null ? problem.getCause() : problem;
                fail(cause.getMessage());
            } else {
                then.accept(file);
            }
        }));
    }

    private void tryStart() {
        if (frames != null || audio != null) {
            applyPendingPlay();
        }
    }

    private void applyPendingPlay() {
        if (pendingPlay) {
            pendingPlay = false;
            play(true);
        }
    }

    private void dropSource() {
        if (held && source != null) {
            MediaStore.release(source, reader);
        }
        held = false;
        closeImages();
        if (audio != null) {
            audio.close();
            audio = null;
        }
    }

    // ------------------------------------------------------------------
    // Controls (from the window and from the control bar)
    // ------------------------------------------------------------------

    void play(boolean playing) {
        if (kind == null || (frames == null && audio == null)) {
            pendingPlay = playing;
            return;
        }
        ended = false;
        long now = System.nanoTime();
        if (kind == MediaSource.Kind.AUDIO) {
            audio.setPlaying(playing);
        } else if (frames != null) {
            clock.setPlaying(playing, now);
            if (audio != null) {
                if (playing) {
                    audio.seek(clock.positionMs());
                }
                audio.setPlaying(playing && clock.speed() == 1);
            }
        }
        changedNow();
        wake();
    }

    void toggle() {
        play(!playing());
    }

    boolean playing() {
        return kind == MediaSource.Kind.AUDIO ? audio != null && audio.clock().playing() : clock.playing();
    }

    void seek(double ms) {
        if (kind == null) {
            return;
        }
        if (kind == MediaSource.Kind.AUDIO) {
            if (audio != null) {
                audio.seek(ms);
            }
        } else if (frames != null) {
            clock.seek(ms, System.nanoTime());
            if (audio != null) {
                audio.seek(clock.positionMs());
            }
        }
        ended = false;
        changedNow();
        wake();
        invalidate();
    }

    void speed(double speed) {
        if (kind == null || kind == MediaSource.Kind.AUDIO) {
            return; // the sound engine plays at its own rate
        }
        clock.setSpeed(speed, System.nanoTime());
        if (audio != null) {
            // a sound cannot follow a different speed: it is silent until the video is back at normal speed
            audio.setMuted(muted || clock.speed() != 1);
            audio.setPlaying(clock.playing() && clock.speed() == 1);
            if (clock.speed() == 1 && clock.playing()) {
                audio.seek(clock.positionMs());
            }
        }
        changedNow();
    }

    void volume(float volume) {
        this.volume = Math.max(0, Math.min(1, volume));
        if (audio != null) {
            audio.setVolume(this.volume);
        }
        changedNow();
    }

    void muted(boolean muted) {
        this.muted = muted;
        if (audio != null) {
            audio.setMuted(muted || (kind != MediaSource.Kind.AUDIO && clock.speed() != 1));
        }
        changedNow();
    }

    boolean isMuted() {
        return muted;
    }

    float volumeLevel() {
        return volume;
    }

    double speedNow() {
        return clock.speed();
    }

    boolean hasSound() {
        return kind == MediaSource.Kind.AUDIO || !audioSource.isEmpty();
    }

    boolean animated() {
        return kind == MediaSource.Kind.VIDEO || (frames != null && frames.frameCount() > 1);
    }

    MediaSource.Kind kind() {
        return kind;
    }

    String error() {
        return error;
    }

    double positionMs() {
        return kind == MediaSource.Kind.AUDIO && audio != null ? audio.clock().positionMs() : clock.positionMs();
    }

    long durationMs() {
        return kind == MediaSource.Kind.AUDIO && audio != null ? audio.clock().durationMs() : clock.durationMs();
    }

    boolean ended() {
        return ended;
    }

    /** What the window sends back with its next event: where the clip is. */
    JsonObject snapshot() {
        JsonObject state = new JsonObject();
        state.addProperty("position", Math.round(positionMs()) / 1000.0);
        state.addProperty("duration", durationMs() / 1000.0);
        state.addProperty("playing", playing());
        state.addProperty("ended", ended);
        if (error != null) {
            state.addProperty("error", error);
        }
        return state;
    }

    // ------------------------------------------------------------------
    // Frame loop and drawing
    // ------------------------------------------------------------------

    private void wake() {
        removeCallbacks(frame);
        if (isAttachedToWindow() && !released) {
            postOnAnimation(frame);
        }
    }

    private void frame() {
        if (released || !isAttachedToWindow()) {
            return;
        }
        long now = System.nanoTime();
        MediaClock.Event event;
        if (kind == MediaSource.Kind.AUDIO) {
            event = audio == null ? MediaClock.Event.NONE : audio.tick(now);
            if (audio != null && audio.error() != null && error == null) {
                fail(audio.error());
            }
        } else {
            event = clock.update(now);
            if (audio != null) {
                MediaClock.Event sound = audio.tick(now);
                if (clock.playing() && Math.abs(audio.clock().positionMs() - clock.positionMs()) > 300 && clock.speed() == 1) {
                    audio.seek(clock.positionMs()); // keep the sound with the picture
                }
                if (sound == MediaClock.Event.ENDED && clock.playing()) {
                    audio.setPlaying(false);
                }
            }
        }
        if (event == MediaClock.Event.LOOPED) {
            if (audio != null && kind != MediaSource.Kind.AUDIO) {
                audio.seek(0);
            }
            listener.looped();
        } else if (event == MediaClock.Event.ENDED) {
            ended = true;
            if (audio != null && kind != MediaSource.Kind.AUDIO) {
                audio.setPlaying(false);
            }
            listener.ended();
            changedNow();
        }
        if (frames != null) {
            int index = frames.frameAt((long) clock.positionMs());
            if (index != shown) {
                invalidate();
            }
        }
        if (now - lastChangeNanos > 100_000_000L && playing()) {
            lastChangeNanos = now;
            observer.run();
        }
        if (playing() || (frames != null && shown != frames.frameAt((long) clock.positionMs()))) {
            postOnAnimation(frame);
        }
    }

    private void changedNow() {
        lastChangeNanos = System.nanoTime();
        observer.run();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        FrameSource source = frames;
        if (source == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        int index = source.frameAt((long) clock.positionMs());
        Image image = images.get(index);
        if (image == null) {
            int[] pixels = source.pixels(index);
            if (pixels != null) {
                image = createImage(source, index, pixels);
            }
        }
        if (image != null) {
            shown = index;
        } else if (shown >= 0) {
            image = images.get(shown); // the next frame is still being decoded: keep the last one
        }
        if (image == null) {
            return;
        }
        MediaLayout.Placement place = MediaLayout.place(source.width(), source.height(), getWidth(), getHeight(), fit, crop);
        paint.setAlpha((int) (opacity * 255));
        canvas.drawImage(image, place.src()[0], place.src()[1], place.src()[2], place.src()[3],
                place.dst()[0], place.dst()[1], place.dst()[2], place.dst()[3], paint);
    }

    private Image createImage(FrameSource source, int index, int[] pixels) {
        long frameBytes = (long) source.width() * source.height() * 4;
        while (!images.isEmpty() && (images.size() + 1) * frameBytes > GPU_BUDGET) {
            var eldest = images.entrySet().iterator().next();
            if (eldest.getKey() == shown && images.size() == 1) {
                break;
            }
            eldest.getValue().close();
            images.remove(eldest.getKey());
        }
        try (Bitmap bitmap = Bitmap.createBitmap(source.width(), source.height(), Bitmap.Format.RGBA_8888)) {
            bitmap.setPixels(pixels, 0, source.width(), 0, 0, source.width(), source.height());
            Image image = Image.createTextureFromBitmap(bitmap);
            if (image != null) {
                images.put(index, image);
            }
            return image;
        } catch (RuntimeException | OutOfMemoryError e) {
            CraftPyLog.LOGGER.warn("Could not upload a media frame: {}", e.toString());
            return null;
        }
    }

    private void closeImages() {
        images.values().forEach(Image::close);
        images.clear();
        shown = -1;
    }

    // ------------------------------------------------------------------
    // Status, failure, release
    // ------------------------------------------------------------------

    private void showStatus(String text, boolean problem) {
        status.setText(text);
        status.setTooltipText(text);
        status.setTextColor(problem ? IdeColors.ERROR : IdeColors.TEXT_SECONDARY);
        status.setVisibility(VISIBLE);
    }

    private void fail(String message) {
        error = message == null ? "unknown problem" : message;
        showStatus("⚠ " + error, true);
        listener.failed(error);
        changedNow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        wake();
    }

    /** Gives back the file copy, the graphics memory and the sound; the view is not used afterwards. */
    void release() {
        if (released) {
            return;
        }
        released = true;
        generation++;
        removeCallbacks(frame);
        dropSource();
        frames = null;
    }

    /** Releases every media view below {@code root} (the designer preview was replaced). */
    static void releaseAll(View root) {
        if (root instanceof MediaView media) {
            media.release();
        } else if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                releaseAll(group.getChildAt(i));
            }
        }
    }

    // ------------------------------------------------------------------

    private static String str(JsonObject node, String key, String fallback) {
        return WindowFragment.str(node, key, fallback);
    }

    private static double num(JsonObject node, String key, double fallback) {
        try {
            return node.has(key) ? node.get(key).getAsDouble() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject node, String key, boolean fallback) {
        try {
            return node.has(key) ? node.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
