package net.xmilon.craftpy.client.assets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.xmilon.craftpy.client.AudioClip;

/** Sound files to the editor's {@link AudioClip}: OGG Vorbis through the game's own decoder, 16 bit WAV directly. */
public final class SoundCodec {
    private SoundCodec() {
    }

    /** Decodes a sound; throws {@link IllegalArgumentException} with a readable message for anything it cannot open. */
    public static AudioClip decode(String path, byte[] data) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".wav")) {
            return AudioClip.fromWav(data);
        }
        if (!lower.endsWith(".ogg")) {
            throw new IllegalArgumentException("Only .ogg and .wav sounds can be opened.");
        }
        try (JOrbisAudioStream stream = new JOrbisAudioStream(new ByteArrayInputStream(data))) {
            int channels = stream.getFormat().getChannels();
            int rate = (int) stream.getFormat().getSampleRate();
            FloatArrayList samples = new FloatArrayList();
            int limit = AudioClip.MAX_FRAMES * channels;
            while (stream.readChunk(samples::add)) {
                if (samples.size() > limit) {
                    throw new IllegalArgumentException("The sound is longer than " + (AudioClip.MAX_FRAMES / 44_100) + " seconds.");
                }
            }
            return new AudioClip(samples.toFloatArray(), channels, rate);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("Could not decode the sound: " + e.getMessage(), e);
        }
    }
}
