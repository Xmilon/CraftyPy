package net.xmilon.craftpy.client.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import net.xmilon.craftpy.client.AudioClip;

class OggEncoderTest {
    @Test
    void classifiesTheOutcomeOfThePythonRun() {
        assertNull(OggEncoder.classify(0, ""));
        assertEquals(OggEncoder.Status.MISSING_MODULE,
                OggEncoder.classify(1, "Traceback...\nModuleNotFoundError: No module named 'soundfile'").status());
        var failed = OggEncoder.classify(1, "Traceback\n\nRuntimeError: bad file\n");
        assertEquals(OggEncoder.Status.FAILED, failed.status());
        assertEquals("RuntimeError: bad file", failed.message());
    }

    @Test
    void commandPassesThePathsAsArguments() {
        var command = OggEncoder.command(Path.of("py"), Path.of("a.wav"), Path.of("b.ogg"));
        assertEquals(5, command.size());
        assertEquals("-c", command.get(1));
        assertEquals(OggEncoder.SCRIPT, command.get(2));
        assertTrue(command.get(3).endsWith("a.wav") && command.get(4).endsWith("b.ogg"));
    }

    @Test
    void withoutPythonItSaysSo() {
        assertEquals(OggEncoder.Status.NO_PYTHON, OggEncoder.encodeNow(null, new byte[0]).status());
    }

    /** The real round trip; skipped where Python or the soundfile package is missing. */
    @Test
    void encodesASineWhenSoundfileIsInstalled() {
        float[] samples = new float[4410];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) Math.sin(i * 0.1) * 0.5f;
        }
        var result = OggEncoder.encodeNow(Path.of("python"), new AudioClip(samples, 1, 44_100).toWav());
        assumeTrue(result.status() == OggEncoder.Status.OK, "needs python with the soundfile package: " + result.message());
        assertEquals('O', result.ogg()[0]);
        assertEquals('g', result.ogg()[1]);
        assertEquals('g', result.ogg()[2]);
        assertEquals('S', result.ogg()[3]);
    }
}
