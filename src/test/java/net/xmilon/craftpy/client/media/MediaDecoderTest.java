package net.xmilon.craftpy.client.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.xmilon.craftpy.security.ModulePermissions;
import net.xmilon.craftpy.security.PermissionStore;

class MediaDecoderTest {
    /** One second of a 24 bit stereo WAV at 8000 Hz. */
    private static Path wav(Path dir, String name) throws IOException {
        AudioFormat format = new AudioFormat(8000, 24, 2, true, false);
        byte[] data = new byte[8000 * 6];
        for (int i = 0; i < data.length; i += 3) {
            data[i + 2] = (byte) ((i / 6) % 100); // high byte only
        }
        Path file = dir.resolve(name);
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(data), format, 8000), AudioFileFormat.Type.WAVE, file.toFile());
        return file;
    }

    @Test
    void wavIsDecodedTo16BitWithItsLength(@TempDir Path dir) throws IOException {
        Path file = wav(dir, "tone.wav");
        assertEquals(1.0, MediaDecoder.duration(file), 0.01);
        try (MediaDecoder.Pcm pcm = MediaDecoder.open(file)) {
            assertEquals(8000, pcm.sampleRate());
            assertEquals(2, pcm.channels());
            long total = 0;
            byte[] buffer = new byte[4096];
            for (int n; (n = pcm.read(buffer)) > 0; ) {
                total += n;
            }
            assertEquals(8000 * 4, total, "16 bit stereo: 4 bytes per frame");
        }
    }

    @Test
    void conversionsAndRefusals(@TempDir Path dir) throws IOException {
        assertArrayEquals(new byte[]{0x22, 0x33}, MediaDecoder.to16(new byte[]{0x11, 0x22, 0x33}, 3, 3));
        assertArrayEquals(new byte[]{0, 0x7f}, MediaDecoder.to16(new byte[]{0x7f}, 1, 1));
        Path opus = Files.writeString(dir.resolve("song.opus"), "x");
        assertThrows(IOException.class, () -> MediaDecoder.open(opus));
        Path broken = Files.writeString(dir.resolve("song.mp3"), "not an mp3 at all");
        assertThrows(IOException.class, () -> MediaDecoder.open(broken));
        assertTrue(MediaDecoder.supported("A.FLAC") && !MediaDecoder.supported("a.m4a"));
    }

    @Test
    void libraryListsAudioAndRefusesPathsOutside(@TempDir Path dir) throws IOException {
        Path music = Files.createDirectories(dir.resolve("music/album"));
        wav(music, "b.wav");
        Files.writeString(dir.resolve("music/notes.txt"), "x");
        Files.writeString(dir.resolve("secret.mp3"), "x");
        Path base = dir.resolve("music");
        assertEquals(List.of("album/b.wav"), MediaLibrary.list(base));
        assertEquals(base.toRealPath().resolve("album/b.wav"), MediaLibrary.resolve(base, "album/b.wav"));
        for (String bad : new String[]{"../secret.mp3", "/etc/passwd", "C:/x.mp3", "notes.txt", "album\\b.wav", "missing.ogg"}) {
            assertThrows(IOException.class, () -> MediaLibrary.resolve(base, bad), bad);
        }
        assertTrue(MediaLibrary.validFolder("music") && !MediaLibrary.validFolder("../music") && !MediaLibrary.validFolder("Music"));
    }

    @Test
    void sharedPoolListsEveryFileAndKnowsWhatNeedsOgg(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("music"));
        Files.writeString(dir.resolve("music/Song.MP3"), "x");
        Files.writeString(dir.resolve("ruby.png"), "x");
        assertEquals(List.of("music/Song.MP3", "ruby.png"), net.xmilon.craftpy.client.assets.SharedAssets.list(dir, ""));
        assertEquals(List.of("ruby.png"), net.xmilon.craftpy.client.assets.SharedAssets.list(dir, "RUBY"));
    }

    @Test
    void sharedFoldersMustBeDeclaredAndApproved() {
        PermissionStore.forget();
        ModulePermissions asked = new ModulePermissions();
        asked.shared = List.of("music", "../etc", "Bad");
        PermissionStore.declare("module:mp", asked);
        assertEquals(List.of("music"), PermissionStore.declared("module:mp").shared, "only plain folder names stay");
        assertTrue(!PermissionStore.sharedAllowed("module:mp", "music"));
        PermissionStore.approve("module:mp", PermissionStore.declared("module:mp"));
        assertTrue(PermissionStore.sharedAllowed("module:mp", "music") && !PermissionStore.sharedAllowed("module:mp", "videos"));
        PermissionStore.revoke("module:mp");
        PermissionStore.forget();
    }
}
