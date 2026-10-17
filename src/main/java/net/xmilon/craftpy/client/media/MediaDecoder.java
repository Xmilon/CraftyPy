package net.xmilon.craftpy.client.media;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Audio files to 16 bit PCM (at most two channels, what OpenAL plays): OGG Vorbis with Minecraft's own decoder, WAV / AIFF
 * / AU with Java's, MP3 with JLayer and FLAC with jFLAC. Opus, M4A and AAC have no small pure Java decoder and are
 * refused with a message. Files from other players go through here too, so every failure is an {@link IOException}.
 */
public final class MediaDecoder {
    /** File endings the player can open. */
    public static final Set<String> EXTENSIONS = Set.of("ogg", "oga", "wav", "aiff", "aif", "au", "mp3", "flac");

    private MediaDecoder() {
    }

    /** Decoded audio: 16 bit signed little-endian, interleaved. */
    public interface Pcm extends Closeable {
        int sampleRate();

        int channels();

        /** Fills {@code out} from the start; returns the number of bytes written, -1 at the end. */
        int read(byte[] out) throws IOException;
    }

    public static boolean supported(String fileName) {
        return EXTENSIONS.contains(extension(fileName));
    }

    static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static Pcm open(Path file) throws IOException {
        String ext = extension(file.getFileName().toString());
        InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        try {
            Pcm raw = switch (ext) {
                case "ogg", "oga" -> new Ogg(in);
                case "mp3" -> new Mp3(in);
                case "flac" -> new Flac(in);
                case "wav", "aiff", "aif", "au" -> new JavaSound(in);
                default -> throw new IOException("'" + ext + "' files cannot be played (OGG, WAV, AIFF, MP3 or FLAC)");
            };
            return raw.channels() <= 2 ? raw : new Downmix(raw);
        } catch (IOException e) {
            in.close();
            throw e;
        } catch (RuntimeException | LinkageError e) {
            in.close();
            throw new IOException("This file could not be read: " + e, e);
        }
    }

    /** Length in seconds, or -1 when it cannot be found cheaply. */
    public static double duration(Path file) {
        try {
            return switch (extension(file.getFileName().toString())) {
                case "ogg", "oga" -> oggDuration(file);
                case "mp3" -> mp3Duration(file);
                case "flac" -> {
                    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                        var info = new org.jflac.FLACDecoder(in).readStreamInfo();
                        yield info == null || info.getSampleRate() <= 0 ? -1 : (double) info.getTotalSamples() / info.getSampleRate();
                    }
                }
                default -> {
                    try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
                        long frames = in.getFrameLength();
                        yield frames <= 0 ? -1 : frames / (double) in.getFormat().getFrameRate();
                    }
                }
            };
        } catch (IOException | RuntimeException | LinkageError | UnsupportedAudioFileException e) {
            return -1;
        }
    }

    /** The granule position of the last Ogg page over the sample rate of the identification header. */
    static double oggDuration(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] head = new byte[Math.min(4096, (int) raf.length())];
            raf.readFully(head);
            int rate = -1;
            for (int i = 0; i + 16 < head.length; i++) {
                if (head[i] == 1 && head[i + 1] == 'v' && head[i + 2] == 'o' && head[i + 3] == 'r') {
                    rate = (head[i + 12] & 0xff) | (head[i + 13] & 0xff) << 8 | (head[i + 14] & 0xff) << 16 | (head[i + 15] & 0xff) << 24;
                    break;
                }
            }
            long size = raf.length();
            int tailSize = (int) Math.min(size, 65536);
            byte[] tail = new byte[tailSize];
            raf.seek(size - tailSize);
            raf.readFully(tail);
            for (int i = tailSize - 14; i >= 0; i--) {
                if (tail[i] == 'O' && tail[i + 1] == 'g' && tail[i + 2] == 'g' && tail[i + 3] == 'S') {
                    long granule = 0;
                    for (int b = 7; b >= 0; b--) {
                        granule = granule << 8 | (tail[i + 6 + b] & 0xff);
                    }
                    return rate > 0 && granule > 0 ? granule / (double) rate : -1;
                }
            }
            return -1;
        }
    }

    static double mp3Duration(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            var stream = new javazoom.jl.decoder.Bitstream(in);
            double ms = 0;
            try {
                for (int frames = 0; frames < 2_000_000; frames++) {
                    var header = stream.readFrame();
                    if (header == null) {
                        break;
                    }
                    ms += header.ms_per_frame();
                    stream.closeFrame();
                }
            } catch (javazoom.jl.decoder.BitstreamException e) {
                // a broken tail: what was read counts
            }
            return ms > 0 ? ms / 1000.0 : -1;
        }
    }

    // -- the formats ------------------------------------------------------------------------------------------------------

    /** Minecraft's own Vorbis decoder, which already gives 16 bit samples. */
    private static final class Ogg implements Pcm {
        private final net.minecraft.client.sounds.JOrbisAudioStream stream;
        private java.nio.ByteBuffer pending;

        Ogg(InputStream in) throws IOException {
            stream = new net.minecraft.client.sounds.JOrbisAudioStream(in);
        }

        @Override
        public int sampleRate() {
            return (int) stream.getFormat().getSampleRate();
        }

        @Override
        public int channels() {
            return stream.getFormat().getChannels();
        }

        @Override
        public int read(byte[] out) throws IOException {
            if (pending == null || !pending.hasRemaining()) {
                pending = stream.read(out.length);
                if (pending == null || !pending.hasRemaining()) {
                    return -1;
                }
            }
            int n = Math.min(out.length, pending.remaining());
            pending.get(out, 0, n);
            return n;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    /** WAV, AIFF and AU through Java Sound, converted to 16 bit little-endian. */
    private static final class JavaSound implements Pcm {
        private final AudioInputStream stream;

        JavaSound(InputStream in) throws IOException {
            try {
                AudioInputStream source = AudioSystem.getAudioInputStream(in);
                AudioFormat from = source.getFormat();
                AudioFormat to = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, from.getSampleRate(), 16,
                        from.getChannels(), from.getChannels() * 2, from.getSampleRate(), false);
                stream = AudioSystem.getAudioInputStream(to, source);
            } catch (UnsupportedAudioFileException | IllegalArgumentException e) {
                throw new IOException("This WAV/AIFF encoding cannot be played (use 8, 16 or 24 bit PCM): " + e.getMessage());
            }
        }

        @Override
        public int sampleRate() {
            return (int) stream.getFormat().getSampleRate();
        }

        @Override
        public int channels() {
            return stream.getFormat().getChannels();
        }

        @Override
        public int read(byte[] out) throws IOException {
            int frame = channels() * 2;
            int n = stream.read(out, 0, out.length - out.length % frame);
            return n <= 0 ? -1 : n;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    /** MP3 frame by frame with JLayer. */
    private static final class Mp3 implements Pcm {
        private final InputStream in;
        private final javazoom.jl.decoder.Bitstream bits;
        private final javazoom.jl.decoder.Decoder decoder = new javazoom.jl.decoder.Decoder();
        private final int sampleRate;
        private final int channels;
        private byte[] pending = new byte[0];
        private int offset;
        private javazoom.jl.decoder.Header first;

        Mp3(InputStream in) throws IOException {
            this.in = in;
            bits = new javazoom.jl.decoder.Bitstream(in);
            try {
                first = bits.readFrame();
            } catch (javazoom.jl.decoder.BitstreamException e) {
                throw new IOException("Not an MP3 file: " + e.getMessage());
            }
            if (first == null) {
                throw new IOException("This MP3 file has no audio");
            }
            sampleRate = first.frequency();
            channels = first.mode() == javazoom.jl.decoder.Header.SINGLE_CHANNEL ? 1 : 2;
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        @Override
        public int channels() {
            return channels;
        }

        private boolean nextFrame() throws IOException {
            try {
                javazoom.jl.decoder.Header header = first != null ? first : bits.readFrame();
                first = null;
                if (header == null) {
                    return false;
                }
                var buffer = (javazoom.jl.decoder.SampleBuffer) decoder.decodeFrame(header, bits);
                bits.closeFrame();
                short[] samples = buffer.getBuffer();
                int count = buffer.getBufferLength();
                if (buffer.getChannelCount() != channels) {
                    count = 0; // a frame that switches mono/stereo mid-file is skipped rather than played at the wrong speed
                }
                pending = new byte[count * 2];
                for (int i = 0; i < count; i++) {
                    pending[2 * i] = (byte) samples[i];
                    pending[2 * i + 1] = (byte) (samples[i] >> 8);
                }
                offset = 0;
                return true;
            } catch (javazoom.jl.decoder.JavaLayerException e) {
                return false; // damaged end of file: stop here
            }
        }

        @Override
        public int read(byte[] out) throws IOException {
            int written = 0;
            while (written < out.length) {
                if (offset >= pending.length && !nextFrame()) {
                    break;
                }
                int n = Math.min(out.length - written, pending.length - offset);
                System.arraycopy(pending, offset, out, written, n);
                offset += n;
                written += n;
            }
            return written == 0 ? -1 : written;
        }

        @Override
        public void close() throws IOException {
            try {
                bits.close();
            } catch (javazoom.jl.decoder.BitstreamException ignored) {
                // closing anyway
            }
            in.close();
        }
    }

    /** FLAC frame by frame with jFLAC; 8 and 24 bit samples become 16 bit. */
    private static final class Flac implements Pcm {
        private final InputStream in;
        private final org.jflac.FLACDecoder decoder;
        private final int sampleRate;
        private final int channels;
        private final int bytesPerSample;
        private byte[] pending = new byte[0];
        private int offset;
        private org.jflac.util.ByteData data;

        Flac(InputStream in) throws IOException {
            this.in = in;
            decoder = new org.jflac.FLACDecoder(in);
            var info = decoder.readStreamInfo();
            if (info == null) {
                throw new IOException("Not a FLAC file");
            }
            decoder.readMetadata(info);
            sampleRate = info.getSampleRate();
            channels = info.getChannels();
            bytesPerSample = (info.getBitsPerSample() + 7) / 8;
            if (bytesPerSample < 1 || bytesPerSample > 3) {
                throw new IOException("FLAC with " + info.getBitsPerSample() + " bit samples cannot be played");
            }
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        @Override
        public int channels() {
            return channels;
        }

        private boolean nextFrame() throws IOException {
            if (decoder.isEOF()) {
                return false;
            }
            var frame = decoder.readNextFrame();
            if (frame == null) {
                return false;
            }
            data = decoder.decodeFrame(frame, data);
            pending = to16(data.getData(), data.getLen(), bytesPerSample);
            offset = 0;
            return true;
        }

        @Override
        public int read(byte[] out) throws IOException {
            int written = 0;
            while (written < out.length) {
                if (offset >= pending.length && !nextFrame()) {
                    break;
                }
                int n = Math.min(out.length - written, pending.length - offset);
                System.arraycopy(pending, offset, out, written, n);
                offset += n;
                written += n;
            }
            return written == 0 ? -1 : written;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Little-endian samples of 1, 2 or 3 bytes to 16 bit (8 bit FLAC/WAV samples are signed here). */
    static byte[] to16(byte[] raw, int length, int bytesPerSample) {
        if (bytesPerSample == 2) {
            return java.util.Arrays.copyOf(raw, length);
        }
        int samples = length / bytesPerSample;
        byte[] out = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int at = i * bytesPerSample;
            if (bytesPerSample == 1) {
                out[2 * i] = 0;
                out[2 * i + 1] = raw[at];
            } else { // 24 bit: keep the two high bytes
                out[2 * i] = raw[at + 1];
                out[2 * i + 1] = raw[at + 2];
            }
        }
        return out;
    }

    /** More than two channels (5.1 FLAC) to stereo: even channels left, odd ones right. */
    static final class Downmix implements Pcm {
        private final Pcm source;
        private byte[] wide = new byte[0];

        Downmix(Pcm source) {
            this.source = source;
        }

        @Override
        public int sampleRate() {
            return source.sampleRate();
        }

        @Override
        public int channels() {
            return 2;
        }

        @Override
        public int read(byte[] out) throws IOException {
            int inChannels = source.channels();
            int frames = out.length / 4;
            if (wide.length != frames * inChannels * 2) {
                wide = new byte[frames * inChannels * 2];
            }
            int n = source.read(wide);
            if (n <= 0) {
                return -1;
            }
            int got = n / (inChannels * 2);
            for (int f = 0; f < got; f++) {
                int left = 0;
                int right = 0;
                for (int c = 0; c < inChannels; c++) {
                    int at = (f * inChannels + c) * 2;
                    int sample = (short) ((wide[at] & 0xff) | wide[at + 1] << 8);
                    if (c % 2 == 0) {
                        left += sample;
                    } else {
                        right += sample;
                    }
                }
                int perSide = (inChannels + 1) / 2;
                short l = (short) (left / perSide);
                short r = (short) (right / Math.max(1, inChannels / 2));
                out[4 * f] = (byte) l;
                out[4 * f + 1] = (byte) (l >> 8);
                out[4 * f + 2] = (byte) r;
                out[4 * f + 3] = (byte) (r >> 8);
            }
            return got * 4;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
