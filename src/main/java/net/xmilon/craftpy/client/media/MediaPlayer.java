package net.xmilon.craftpy.client.media;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * What plays on this computer: your own track (a file of {@code pyforge/shared/...} or a sound event of a resource pack /
 * mod, such as a music disc) and the tracks nearby players share with you. It lives in Java, so a track keeps playing
 * when its window is closed. Client thread only, except {@link #state()}.
 */
public final class MediaPlayer {
    private static final MediaPlayer INSTANCE = new MediaPlayer();

    /** One track: where it is in seconds, counted while the game runs (a paused singleplayer game pauses it too). */
    static final class Track {
        final String name;
        final Path file;
        final ResourceLocation soundEvent;
        final double duration;
        final UUID follow;
        float volume;
        double start;
        double elapsed;
        boolean paused;
        boolean shared;
        SoundInstance sound;
        String error;
        boolean ended;
        long lastTick = System.nanoTime();
        int ticksPlayed;

        Track(String name, Path file, ResourceLocation soundEvent, double duration, UUID follow, float volume) {
            this.name = name;
            this.file = file;
            this.soundEvent = soundEvent;
            this.duration = duration;
            this.follow = follow;
            this.volume = volume;
        }

        double position() {
            return start + elapsed;
        }
    }

    private volatile Track own;
    private final Map<UUID, Track> others = new HashMap<>();
    private volatile JsonObject lastState = state(null);

    private MediaPlayer() {
    }

    public static MediaPlayer get() {
        return INSTANCE;
    }

    // -- your own track ---------------------------------------------------------------------------------------------------

    /** Plays a file from {@code seconds}; with {@code share} nearby players hear it too (when the server allows it). */
    public void playFile(String name, Path file, double seconds, float volume, boolean share) {
        stopOwn(true);
        Track track = new Track(name, file, null, MediaDecoder.duration(file), null, volume);
        track.shared = share;
        own = track;
        start(track, seconds);
        if (share) {
            MediaShare.startSharing(track.name, file, seconds, volume);
        }
    }

    /** Plays a sound event ({@code minecraft:music_disc.cat}, a music pack's sound): no seeking, pause starts it again. */
    public void playSound(ResourceLocation id, float volume) {
        stopOwn(true);
        Track track = new Track(id.toString(), null, id, -1, null, volume);
        own = track;
        start(track, 0);
    }

    public void pause() {
        Track track = own;
        if (track == null || track.paused || track.ended) {
            return;
        }
        track.start = track.position();
        track.elapsed = 0;
        track.paused = true;
        stopSound(track);
        if (track.shared) {
            MediaShare.control("pause", track.start, track.volume);
        }
    }

    public void resume() {
        Track track = own;
        if (track == null || !track.paused) {
            return;
        }
        track.paused = false;
        start(track, track.soundEvent != null ? 0 : track.start);
        if (track.shared) {
            MediaShare.control("resume", track.start, track.volume);
        }
    }

    public void seek(double seconds) {
        Track track = own;
        if (track == null || track.file == null) {
            return;
        }
        double to = track.duration > 0 ? Math.min(Math.max(0, seconds), track.duration) : Math.max(0, seconds);
        stopSound(track);
        track.ended = false;
        if (track.paused) {
            track.start = to;
            track.elapsed = 0;
        } else {
            start(track, to);
        }
        if (track.shared) {
            MediaShare.control(track.paused ? "pause" : "resume", to, track.volume);
        }
    }

    public void volume(float volume) {
        Track track = own;
        if (track == null) {
            return;
        }
        track.volume = Math.max(0f, Math.min(1f, volume));
        if (track.sound instanceof MediaSound media) {
            media.setVolume(track.volume);
        } else if (track.sound != null && !track.paused) {
            start(track, 0); // a sound event cannot change volume while playing
        }
        if (track.shared) {
            MediaShare.control(track.paused ? "pause" : "resume", track.position(), track.volume);
        }
    }

    public void stop() {
        stopOwn(true);
    }

    private void stopOwn(boolean tellOthers) {
        Track track = own;
        own = null;
        if (track != null) {
            stopSound(track);
            if (track.shared && tellOthers) {
                MediaShare.control("stop", 0, 0);
            }
        }
    }

    // -- tracks of other players --------------------------------------------------------------------------------------

    /** A nearby player shared a track (the file is already in the cache); {@code seconds} is where they are now. */
    public void playOther(UUID player, String name, Path file, double seconds, float volume, boolean paused) {
        stopOther(player);
        Track track = new Track(name, file, null, MediaDecoder.duration(file), player, volume);
        others.put(player, track);
        if (paused) {
            track.paused = true;
            track.start = seconds;
        } else {
            start(track, seconds);
        }
    }

    public void stopOther(UUID player) {
        Track track = others.remove(player);
        if (track != null) {
            stopSound(track);
        }
    }

    public void stopAll() {
        stopOwn(false);
        for (UUID player : others.keySet().toArray(new UUID[0])) {
            stopOther(player);
        }
    }

    // -- engine ---------------------------------------------------------------------------------------------------------

    private void start(Track track, double seconds) {
        stopSound(track);
        track.start = seconds;
        track.elapsed = 0;
        track.ended = false;
        track.ticksPlayed = 0;
        track.lastTick = System.nanoTime();
        if (track.soundEvent != null) {
            track.sound = new SimpleSoundInstance(track.soundEvent, SoundSource.RECORDS, track.volume, 1f,
                    RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
        } else {
            track.sound = new MediaSound(track.file, seconds, track.volume, track.follow);
        }
        if (track.follow == null) {
            Minecraft.getInstance().getMusicManager().stopPlaying(); // the game's background music would play over it
        }
        Minecraft.getInstance().getSoundManager().play(track.sound);
    }

    private static void stopSound(Track track) {
        if (track.sound != null) {
            Minecraft.getInstance().getSoundManager().stop(track.sound);
            track.sound = null;
        }
    }

    /** Every client tick: counts the time, notices the end. */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        tick(own, mc);
        others.values().forEach(track -> tick(track, mc));
        others.values().removeIf(track -> track.ended);
        lastState = state(own);
    }

    private static void tick(Track track, Minecraft mc) {
        if (track == null) {
            return;
        }
        long now = System.nanoTime();
        double dt = (now - track.lastTick) / 1e9;
        track.lastTick = now;
        if (track.paused || track.ended || track.sound == null) {
            return;
        }
        if (!mc.isPaused()) {
            track.elapsed += dt;
        }
        track.ticksPlayed++;
        if (track.sound instanceof MediaSound media && media.error != null) {
            track.error = media.error;
        }
        // the engine needs a few ticks to start a stream; after that, a sound that is not active any more has ended
        if (track.ticksPlayed > 10 && !mc.getSoundManager().isActive(track.sound)) {
            track.ended = true;
            track.sound = null;
            if (track.duration > 0) {
                track.elapsed = track.duration - track.start;
            }
        }
    }

    /** Your track as the Python API sees it; safe from any thread. */
    public JsonObject state() {
        return lastState.deepCopy();
    }

    private static JsonObject state(Track track) {
        JsonObject out = new JsonObject();
        out.addProperty("state", track == null ? "stopped" : track.ended ? "ended" : track.paused ? "paused" : "playing");
        out.addProperty("name", track == null ? "" : track.name);
        out.addProperty("kind", track == null ? "" : track.soundEvent != null ? "sound" : "file");
        out.addProperty("position", track == null ? 0 : Math.round(track.position() * 10) / 10.0);
        out.addProperty("duration", track == null ? -1 : Math.round(track.duration * 10) / 10.0);
        out.addProperty("volume", track == null ? 1 : track.volume);
        out.addProperty("shared", track != null && track.shared);
        out.addProperty("error", track == null || track.error == null ? "" : track.error);
        return out;
    }
}
