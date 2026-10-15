package net.xmilon.craftpy.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A piece of audio as floating point samples (-1..1), interleaved when it has more than one channel, with the editing
 * operations of the sound editor. Every operation returns a new clip, so undo is just keeping the old one. No game or
 * UI classes, so it is unit-tested.
 */
public final class AudioClip {
    /** Longest clip the editor opens: one minute at CD quality. */
    public static final int MAX_FRAMES = 44_100 * 60;

    private final float[] samples;
    private final int channels;
    private final int sampleRate;

    public AudioClip(float[] samples, int channels, int sampleRate) {
        if (channels < 1 || channels > 2 || sampleRate < 1000 || sampleRate > 192_000 || samples.length % channels != 0) {
            throw new IllegalArgumentException("Unsupported audio: " + channels + " channel(s), " + sampleRate + " Hz");
        }
        if (samples.length / channels > MAX_FRAMES) {
            throw new IllegalArgumentException("The clip is longer than " + (MAX_FRAMES / 44_100) + " seconds");
        }
        this.samples = samples;
        this.channels = channels;
        this.sampleRate = sampleRate;
    }

    public int frames() {
        return samples.length / channels;
    }

    public int channels() {
        return channels;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public double seconds() {
        return frames() / (double) sampleRate;
    }

    public float sample(int frame, int channel) {
        return samples[frame * channels + channel];
    }

    private int clamp(int frame) {
        return Math.max(0, Math.min(frames(), frame));
    }

    // ------------------------------------------------------------------ editing

    /** Keeps the frames [from, to). */
    public AudioClip trim(int from, int to) {
        int a = clamp(Math.min(from, to));
        int b = clamp(Math.max(from, to));
        if (b - a < 1) {
            throw new IllegalArgumentException("Nothing is selected");
        }
        float[] out = new float[(b - a) * channels];
        System.arraycopy(samples, a * channels, out, 0, out.length);
        return new AudioClip(out, channels, sampleRate);
    }

    /** Removes the frames [from, to) and joins the rest. */
    public AudioClip cut(int from, int to) {
        int a = clamp(Math.min(from, to));
        int b = clamp(Math.max(from, to));
        if (b == a) {
            return this;
        }
        if (b - a >= frames()) {
            throw new IllegalArgumentException("That would remove everything");
        }
        float[] out = new float[samples.length - (b - a) * channels];
        System.arraycopy(samples, 0, out, 0, a * channels);
        System.arraycopy(samples, b * channels, out, a * channels, (frames() - b) * channels);
        return new AudioClip(out, channels, sampleRate);
    }

    /** Fades from silence to full volume over [from, to). */
    public AudioClip fadeIn(int from, int to) {
        return ramp(from, to, 0f, 1f);
    }

    /** Fades from full volume to silence over [from, to). */
    public AudioClip fadeOut(int from, int to) {
        return ramp(from, to, 1f, 0f);
    }

    private AudioClip ramp(int from, int to, float start, float end) {
        int a = clamp(Math.min(from, to));
        int b = clamp(Math.max(from, to));
        float[] out = samples.clone();
        for (int f = a; f < b; f++) {
            float t = b - a <= 1 ? 1f : (f - a) / (float) (b - a - 1);
            float g = start + (end - start) * t;
            for (int c = 0; c < channels; c++) {
                out[f * channels + c] *= g;
            }
        }
        return new AudioClip(out, channels, sampleRate);
    }

    /** Louder or quieter by {@code db} decibels over [from, to); samples are limited to the -1..1 range. */
    public AudioClip gain(int from, int to, double db) {
        float factor = (float) Math.pow(10, Math.max(-60, Math.min(60, db)) / 20);
        int a = clamp(Math.min(from, to));
        int b = clamp(Math.max(from, to));
        float[] out = samples.clone();
        for (int i = a * channels; i < b * channels; i++) {
            out[i] = Math.max(-1f, Math.min(1f, out[i] * factor));
        }
        return new AudioClip(out, channels, sampleRate);
    }

    /** Scales the whole clip so its loudest sample reaches {@code peak} (0.01 to 1). */
    public AudioClip normalize(double peak) {
        float loudest = peak();
        if (loudest < 1e-6f) {
            return this;
        }
        float factor = (float) (Math.max(0.01, Math.min(1.0, peak)) / loudest);
        float[] out = new float[samples.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = samples[i] * factor;
        }
        return new AudioClip(out, channels, sampleRate);
    }

    public AudioClip reverse(int from, int to) {
        int a = clamp(Math.min(from, to));
        int b = clamp(Math.max(from, to));
        float[] out = samples.clone();
        for (int i = 0; i < b - a; i++) {
            for (int c = 0; c < channels; c++) {
                out[(a + i) * channels + c] = samples[(b - 1 - i) * channels + c];
            }
        }
        return new AudioClip(out, channels, sampleRate);
    }

    /** Replaces [from, to) with silence. */
    public AudioClip silence(int from, int to) {
        int a = clamp(Math.min(from, to));
        int b = clamp(Math.max(from, to));
        float[] out = samples.clone();
        java.util.Arrays.fill(out, a * channels, b * channels, 0f);
        return new AudioClip(out, channels, sampleRate);
    }

    /** Inserts {@code frames} of silence at a position. */
    public AudioClip insertSilence(int at, int frames) {
        int where = clamp(at);
        int count = Math.max(0, Math.min(frames, MAX_FRAMES - frames()));
        float[] out = new float[samples.length + count * channels];
        System.arraycopy(samples, 0, out, 0, where * channels);
        System.arraycopy(samples, where * channels, out, (where + count) * channels, (frames() - where) * channels);
        return new AudioClip(out, channels, sampleRate);
    }

    public AudioClip toMono() {
        if (channels == 1) {
            return this;
        }
        float[] out = new float[frames()];
        for (int f = 0; f < out.length; f++) {
            out[f] = (samples[f * 2] + samples[f * 2 + 1]) / 2f;
        }
        return new AudioClip(out, 1, sampleRate);
    }

    /**
     * Plays the clip faster ({@code factor > 1}, higher pitch, shorter) or slower (lower, longer) by linear
     * interpolation; the sample rate stays, so the pitch changes with the speed like a record.
     */
    public AudioClip speed(double factor) {
        double f = Math.max(0.25, Math.min(4.0, factor));
        int frames = Math.max(1, (int) Math.floor(frames() / f));
        float[] out = new float[frames * channels];
        for (int i = 0; i < frames; i++) {
            double position = i * f;
            int base = (int) position;
            float frac = (float) (position - base);
            int next = Math.min(base + 1, frames() - 1);
            for (int c = 0; c < channels; c++) {
                float a = samples[Math.min(base, frames() - 1) * channels + c];
                float b = samples[next * channels + c];
                out[i * channels + c] = a + (b - a) * frac;
            }
        }
        return new AudioClip(out, channels, sampleRate);
    }

    // ------------------------------------------------------------------ analysis

    /** The largest absolute sample. */
    public float peak() {
        float loudest = 0;
        for (float s : samples) {
            loudest = Math.max(loudest, Math.abs(s));
        }
        return loudest;
    }

    /**
     * Waveform columns for drawing: for each of {@code columns} equal slices of [from, to) the lowest and highest
     * sample (all channels together), as {@code {min0, max0, min1, max1, ...}}.
     */
    public float[] peaks(int columns, int from, int to) {
        int a = clamp(Math.min(from, to));
        int b = clamp(Math.max(from, to));
        int n = Math.max(1, columns);
        float[] out = new float[n * 2];
        for (int col = 0; col < n; col++) {
            int start = a + (int) ((long) (b - a) * col / n);
            int end = Math.max(start + 1, a + (int) ((long) (b - a) * (col + 1) / n));
            float low = 0;
            float high = 0;
            for (int f = start; f < Math.min(end, frames()); f++) {
                for (int c = 0; c < channels; c++) {
                    float s = samples[f * channels + c];
                    low = Math.min(low, s);
                    high = Math.max(high, s);
                }
            }
            out[col * 2] = low;
            out[col * 2 + 1] = high;
        }
        return out;
    }

    // ------------------------------------------------------------------ PCM and WAV

    public static AudioClip fromPcm16(byte[] pcm, int channels, int sampleRate) {
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[pcm.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = buffer.getShort() / 32768f;
        }
        return new AudioClip(out, channels, sampleRate);
    }

    public byte[] toPcm16() {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples) {
            buffer.putShort((short) Math.max(-32768, Math.min(32767, Math.round(s * 32767f))));
        }
        return buffer.array();
    }

    /** A 16 bit PCM WAV file. */
    public byte[] toWav() {
        byte[] pcm = toPcm16();
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + pcm.length).put(new byte[]{'W', 'A', 'V', 'E'});
        wav.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        wav.put(new byte[]{'d', 'a', 't', 'a'}).putInt(pcm.length).put(pcm);
        return wav.array();
    }

    /** Reads a 16 bit PCM WAV (mono or stereo); throws {@link IllegalArgumentException} for anything else. */
    public static AudioClip fromWav(byte[] data) {
        try {
            ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (data.length < 44 || in.getInt(0) != 0x46464952 || in.getInt(8) != 0x45564157) {
                throw new IllegalArgumentException("Not a WAV file");
            }
            int position = 12;
            int channels = 0;
            int rate = 0;
            int bits = 0;
            while (position + 8 <= data.length) {
                int id = in.getInt(position);
                int size = in.getInt(position + 4);
                if (size < 0 || position + 8 + size > data.length + 1) {
                    break;
                }
                if (id == 0x20746d66) { // "fmt "
                    if (in.getShort(position + 8) != 1) {
                        throw new IllegalArgumentException("Only uncompressed PCM WAV files can be opened");
                    }
                    channels = in.getShort(position + 10);
                    rate = in.getInt(position + 12);
                    bits = in.getShort(position + 22);
                } else if (id == 0x61746164) { // "data"
                    if (bits != 16) {
                        throw new IllegalArgumentException("Only 16 bit WAV files can be opened");
                    }
                    int length = Math.min(size, data.length - position - 8);
                    byte[] pcm = new byte[length - length % (channels * 2)];
                    System.arraycopy(data, position + 8, pcm, 0, pcm.length);
                    return fromPcm16(pcm, channels, rate);
                }
                position += 8 + size + (size & 1);
            }
            throw new IllegalArgumentException("The WAV file has no audio data");
        } catch (java.nio.BufferUnderflowException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("The WAV file is damaged", e);
        }
    }
}
