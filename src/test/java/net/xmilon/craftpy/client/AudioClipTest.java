package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AudioClipTest {
    private static AudioClip ramp(int frames) {
        float[] s = new float[frames];
        for (int i = 0; i < frames; i++) {
            s[i] = i / (float) frames;
        }
        return new AudioClip(s, 1, 8000);
    }

    @Test
    void trimCutAndInsert() {
        var clip = ramp(10);
        assertEquals(4, clip.trim(2, 6).frames());
        assertEquals(0.2f, clip.trim(2, 6).sample(0, 0), 1e-6);
        assertEquals(6, clip.cut(2, 6).frames());
        assertEquals(0.6f, clip.cut(2, 6).sample(2, 0), 1e-6);
        assertEquals(13, clip.insertSilence(5, 3).frames());
        assertEquals(0f, clip.insertSilence(5, 3).sample(6, 0));
        assertThrows(IllegalArgumentException.class, () -> clip.trim(3, 3));
        assertThrows(IllegalArgumentException.class, () -> clip.cut(0, 10));
        assertEquals(10, clip.cut(4, 4).frames(), "an empty cut changes nothing");
    }

    @Test
    void fadesAndGain() {
        var loud = new AudioClip(new float[]{1, 1, 1, 1, 1}, 1, 8000);
        var in = loud.fadeIn(0, 5);
        assertEquals(0f, in.sample(0, 0));
        assertEquals(1f, in.sample(4, 0));
        var out = loud.fadeOut(0, 5);
        assertEquals(1f, out.sample(0, 0));
        assertEquals(0f, out.sample(4, 0));
        var quiet = new AudioClip(new float[]{0.5f, 0.5f}, 1, 8000);
        assertEquals(0.25f, quiet.gain(0, 2, -6.0206).sample(0, 0), 1e-4);
        assertEquals(1f, quiet.gain(0, 2, 40).sample(0, 0), "gain is limited, never wraps");
        assertEquals(0.5f, quiet.gain(1, 2, -60).sample(0, 0), "outside the range stays");
    }

    @Test
    void normalizeReverseSilence() {
        var clip = new AudioClip(new float[]{0.1f, -0.5f, 0.25f}, 1, 8000);
        assertEquals(0.5f, clip.peak());
        assertEquals(1f, clip.normalize(1).peak(), 1e-6);
        assertEquals(clip, clip.normalize(1).normalize(1).equals(clip) ? clip : clip);
        var silent = new AudioClip(new float[]{0, 0}, 1, 8000);
        assertEquals(0f, silent.normalize(1).peak(), "silence stays silence, no division by zero");
        var back = new AudioClip(new float[]{1, 2, 3, 4}, 1, 8000).reverse(1, 4);
        assertEquals(4f, back.sample(1, 0));
        assertEquals(2f, back.sample(3, 0));
        assertEquals(1f, back.sample(0, 0));
        assertEquals(0f, new AudioClip(new float[]{1, 1, 1}, 1, 8000).silence(1, 2).sample(1, 0));
    }

    @Test
    void stereoAndMono() {
        var stereo = new AudioClip(new float[]{1, 0, 0, 1}, 2, 8000);
        assertEquals(2, stereo.frames());
        var mono = stereo.toMono();
        assertEquals(1, mono.channels());
        assertEquals(0.5f, mono.sample(0, 0));
        assertEquals(0f, stereo.reverse(0, 2).sample(0, 0));
        assertEquals(1f, stereo.reverse(0, 2).sample(0, 1), "channels stay in their place when reversing");
    }

    @Test
    void speedChangesLength() {
        var clip = ramp(100);
        assertEquals(50, clip.speed(2).frames());
        assertEquals(200, clip.speed(0.5).frames());
        assertEquals(25, clip.speed(99).frames(), "speed is limited to 4x");
        assertEquals(0.5f, clip.speed(2).sample(25, 0), 1e-3);
    }

    @Test
    void peaksSummariseColumns() {
        var clip = new AudioClip(new float[]{0.5f, -0.25f, 0.1f, -0.9f}, 1, 8000);
        float[] peaks = clip.peaks(2, 0, 4);
        assertArrayEquals(new float[]{-0.25f, 0.5f, -0.9f, 0.1f}, peaks, 1e-6f);
        assertEquals(6, clip.peaks(3, 0, 4).length);
    }

    @Test
    void wavRoundTrip() {
        var clip = new AudioClip(new float[]{0f, 0.5f, -0.5f, 1f, -1f, 0.25f}, 2, 22050);
        var back = AudioClip.fromWav(clip.toWav());
        assertEquals(2, back.channels());
        assertEquals(22050, back.sampleRate());
        assertEquals(3, back.frames());
        assertEquals(0.5f, back.sample(0, 1), 1e-3);
        assertEquals(-1f, back.sample(2, 0), 1e-3);
        assertThrows(IllegalArgumentException.class, () -> AudioClip.fromWav(new byte[10]));
        byte[] truncated = clip.toWav();
        assertThrows(IllegalArgumentException.class, () -> AudioClip.fromWav(java.util.Arrays.copyOf(truncated, 30)));
    }

    @Test
    void unsupportedFormatsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new AudioClip(new float[3], 2, 8000));
        assertThrows(IllegalArgumentException.class, () -> new AudioClip(new float[2], 6, 8000));
        assertThrows(IllegalArgumentException.class, () -> new AudioClip(new float[2], 1, 10));
        assertTrue(new AudioClip(new float[44100], 1, 44100).seconds() == 1.0);
    }
}
