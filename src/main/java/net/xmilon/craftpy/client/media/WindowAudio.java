package net.xmilon.craftpy.client.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import net.minecraft.client.Minecraft;
import net.xmilon.craftpy.project.PyForgePaths;

/**
 * A sound that belongs to one widget of a window: played through Minecraft sound engine like the Jukebox (so the
 * Records / Jukebox volume and pausing the game apply), but independent of the Jukebox track, so a window can play
 * effects while music plays. The sound engine cannot seek or change speed, so a seek restarts the sound at the new
 * position and the speed is always 1. UI thread owns the object; the calls into Minecraft hop to its thread.
 */
public final class WindowAudio implements AutoCloseable {
    /** A sound file larger than this is refused. */
    public static final int MAX_BYTES = 32 * 1024 * 1024;

    private final Path file;
    private final MediaClock clock;
    private float volume = 1f;
    private boolean muted;
    private MediaSound sound;
    private int generation;
    private boolean closed;

    public WindowAudio(Path file, boolean loop) {
        this.file = file;
        double seconds = MediaDecoder.duration(file);
        this.clock = new MediaClock(seconds > 0 ? Math.round(seconds * 1000) : 0, loop);
    }

    /** A copy of the sound in the cache folder (the engine reads files, the pack gives bytes). */
    public static Path cache(MediaSource source, byte[] data) throws MediaException {
        if (data.length > MAX_BYTES) {
            throw new MediaException(source.id() + " is larger than " + (MAX_BYTES >> 20) + " MiB");
        }
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data), 0, 12);
            Path folder = PyForgePaths.cache().resolve("media");
            Files.createDirectories(folder);
            Path target = folder.resolve(hash + "." + source.extension());
            if (!Files.isRegularFile(target) || Files.size(target) != data.length) {
                Path temp = Files.createTempFile(folder, "part", ".tmp");
                Files.write(temp, data);
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new MediaException("Could not prepare " + source.id() + ": " + e.getMessage(), e);
        }
    }

    public MediaClock clock() {
        return clock;
    }

    public boolean decodable() {
        return MediaDecoder.supported(file.getFileName().toString());
    }

    public void setPlaying(boolean playing) {
        long now = System.nanoTime();
        if (playing == clock.playing()) {
            return;
        }
        clock.setPlaying(playing, now);
        if (playing) {
            start();
        } else {
            stop();
        }
    }

    public void seek(double ms) {
        clock.seek(ms, System.nanoTime());
        if (clock.playing()) {
            start();
        }
    }

    public void setLoop(boolean loop) {
        clock.setLoop(loop);
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0f, Math.min(1f, Float.isFinite(volume) ? volume : 1f));
        apply();
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        apply();
    }

    /** The error the sound engine reported (an unreadable file), or null. */
    public String error() {
        MediaSound current = sound;
        return current == null ? null : current.error;
    }

    /** Once per frame: advances the clock and restarts a looping sound. */
    public MediaClock.Event tick(long nowNanos) {
        MediaClock.Event event = clock.update(nowNanos);
        MediaSound current = sound;
        if (event == MediaClock.Event.NONE && clock.durationMs() == 0 && current != null && current.ended && clock.playing()) {
            // length unknown: the engine says when the file ended
            event = clock.loop() ? MediaClock.Event.LOOPED : MediaClock.Event.ENDED;
            clock.seek(0, nowNanos);
            if (event == MediaClock.Event.ENDED) {
                clock.setPlaying(false, nowNanos);
            }
        }
        if (event == MediaClock.Event.LOOPED) {
            start();
        } else if (event == MediaClock.Event.ENDED) {
            stop();
        }
        return event;
    }

    private float effective() {
        return muted ? 0f : volume;
    }

    private void apply() {
        MediaSound current = sound;
        if (current != null) {
            float value = effective();
            Minecraft.getInstance().execute(() -> current.setVolume(value));
        }
    }

    private void start() {
        stop();
        if (closed) {
            return;
        }
        int mine = ++generation;
        double from = clock.positionMs() / 1000.0;
        MediaSound next = new MediaSound(file, from, effective(), null);
        sound = next;
        Minecraft.getInstance().execute(() -> {
            if (mine == generation && !closed) {
                Minecraft.getInstance().getSoundManager().play(next);
            }
        });
    }

    private void stop() {
        MediaSound old = sound;
        sound = null;
        generation++;
        if (old != null) {
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().getSoundManager().stop(old));
        }
    }

    @Override
    public void close() {
        closed = true;
        stop();
    }
}
