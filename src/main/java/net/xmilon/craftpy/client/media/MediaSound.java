package net.xmilon.craftpy.client.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.lwjgl.BufferUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/**
 * One file played through Minecraft's sound engine (so the Jukebox/Note Blocks volume slider and pausing the game work):
 * the {@code craftpy:media} sound event stands in, and {@link #getStream} hands the engine the decoded file instead.
 * Your own media plays without distance; media of another player is heard around that player and follows them.
 */
final class MediaSound extends AbstractTickableSoundInstance {
    static final ResourceLocation EVENT = ResourceLocation.fromNamespaceAndPath("craftpy", "media");

    private final Path file;
    private final double startSeconds;
    private final UUID follow;
    /** The file ended (or could not be read); the engine stops the sound by itself. */
    volatile boolean ended;
    volatile String error;

    MediaSound(Path file, double startSeconds, float volume, UUID follow) {
        super(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(EVENT), SoundSource.RECORDS, RandomSource.create());
        this.file = file;
        this.startSeconds = Math.max(0, startSeconds);
        this.follow = follow;
        this.volume = volume;
        this.relative = follow == null;
        this.attenuation = follow == null ? SoundInstance.Attenuation.NONE : SoundInstance.Attenuation.LINEAR;
        this.looping = false;
        tick();
    }

    void setVolume(float volume) {
        this.volume = Math.max(0f, Math.min(1f, volume));
    }

    @Override
    public void tick() {
        if (follow == null) {
            return;
        }
        var level = Minecraft.getInstance().level;
        Entity owner = level == null ? null : level.getPlayerByUUID(follow);
        if (owner != null) {
            x = owner.getX();
            y = owner.getY();
            z = owner.getZ();
        }
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return (AudioStream) new Stream(MediaDecoder.open(file));
            } catch (IOException e) {
                error = e.getMessage();
                ended = true;
                throw new java.util.concurrent.CompletionException(e);
            }
        }, net.minecraft.Util.backgroundExecutor());
    }

    /** The decoded file as the engine reads it; anything a broken file throws ends the sound instead of the game. */
    private final class Stream implements AudioStream {
        private final MediaDecoder.Pcm pcm;
        private final javax.sound.sampled.AudioFormat format;
        private final int frameBytes;
        private byte[] chunk = new byte[0];

        Stream(MediaDecoder.Pcm pcm) throws IOException {
            this.pcm = pcm;
            format = new javax.sound.sampled.AudioFormat(pcm.sampleRate(), 16, pcm.channels(), true, false);
            frameBytes = pcm.channels() * 2;
            long skip = (long) (startSeconds * pcm.sampleRate()) * frameBytes;
            byte[] waste = new byte[64 * 1024 - (64 * 1024) % frameBytes];
            while (skip > 0) {
                int n = pcm.read(skip >= waste.length ? waste : new byte[(int) skip]);
                if (n <= 0) {
                    break;
                }
                skip -= n;
            }
        }

        @Override
        public javax.sound.sampled.AudioFormat getFormat() {
            return format;
        }

        @Override
        public ByteBuffer read(int size) throws IOException {
            int want = Math.max(frameBytes, size - size % frameBytes);
            if (chunk.length != want) {
                chunk = new byte[want];
            }
            int n;
            try {
                n = pcm.read(chunk);
            } catch (IOException | RuntimeException | LinkageError e) {
                error = "The file stopped playing: " + e;
                n = -1;
            }
            ByteBuffer out = BufferUtils.createByteBuffer(Math.max(n, 0));
            if (n <= 0) {
                ended = true;
                return out.flip();
            }
            return out.put(chunk, 0, n).flip();
        }

        @Override
        public void close() throws IOException {
            pcm.close();
        }
    }
}
