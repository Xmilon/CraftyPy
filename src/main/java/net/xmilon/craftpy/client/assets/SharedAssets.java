package net.xmilon.craftpy.client.assets;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

import net.xmilon.craftpy.client.AudioClip;
import net.xmilon.craftpy.client.media.MediaDecoder;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.util.CraftPyExecutors;

/**
 * The shared pool ({@code pyforge/shared/}) in the IDE: every file in it can be imported into the open project like a
 * dropped file. Audio Minecraft cannot play as a sound asset (MP3, FLAC, WAV, AIFF) is turned into OGG on the way, when
 * the converter is installed (the sound editor offers it); otherwise the WAV is imported and the message says so.
 */
public final class SharedAssets {
    public static final int MAX_FILES = 500;

    private SharedAssets() {
    }

    /** Files of the pool relative to it ({@code music/song.mp3}), sorted, matching {@code query} (part of the name). */
    public static List<String> list(Path base, String query) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            return out;
        }
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Path real = base.toRealPath();
        try (Stream<Path> files = Files.walk(real, 8)) {
            files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> real.relativize(p).toString().replace('\\', '/'))
                    .filter(name -> name.toLowerCase(Locale.ROOT).contains(q))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .limit(MAX_FILES)
                    .forEach(out::add);
        }
        return out;
    }

    public static Path pool() {
        return PyForgePaths.shared();
    }

    /** True for audio that has to become OGG to be a Minecraft sound. */
    static boolean needsOgg(String name) {
        return MediaDecoder.supported(name) && !name.toLowerCase(Locale.ROOT).matches(".*\\.(ogg|oga)$");
    }

    /** Copies {@code name} of the pool into the project (textures, models, sounds... by its ending). */
    public static void importInto(String projectId, String name, Consumer<String> status) {
        Path file;
        try {
            file = pool().toRealPath().resolve(name).normalize();
            if (!file.startsWith(pool().toRealPath()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                status.accept("Not a file of pyforge/shared: " + name);
                return;
            }
        } catch (IOException e) {
            status.accept("Could not read " + name + ": " + e.getMessage());
            return;
        }
        if (!needsOgg(name)) {
            AssetUploader.uploadAll(projectId, List.of(file), null);
            status.accept("Imported " + name + " into " + projectId);
            return;
        }
        CraftPyExecutors.runAsync(() -> {
            byte[] wav;
            try (MediaDecoder.Pcm pcm = MediaDecoder.open(file)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = pcm.read(buffer)) > 0; ) {
                    bytes.write(buffer, 0, n);
                    if (bytes.size() > 200 * 1024 * 1024) {
                        throw new IOException("too long to import as a sound (keep it under about 15 minutes)");
                    }
                }
                wav = AudioClip.fromPcm16(bytes.toByteArray(), pcm.channels(), pcm.sampleRate()).toWav();
            } catch (IOException | RuntimeException e) {
                status.accept("Could not import " + name + ": " + e.getMessage());
                return;
            }
            String base = file.getFileName().toString().replaceAll("\\.[^.]+$", "");
            OggEncoder.encode(wav).thenAccept(result -> {
                try {
                    Path temp = Files.createTempDirectory("craftpy-import");
                    if (result.status() == OggEncoder.Status.OK) {
                        Path ogg = Files.write(temp.resolve(base + ".ogg"), result.ogg());
                        AssetUploader.uploadAll(projectId, List.of(ogg), null);
                        status.accept("Imported " + name + " as sounds/" + base.toLowerCase(Locale.ROOT) + ".ogg");
                    } else {
                        Path out = Files.write(temp.resolve(base + ".wav"), wav);
                        AssetUploader.uploadAll(projectId, List.of(out), null);
                        status.accept("Imported as WAV (" + result.message() + "). Minecraft plays only OGG: open it in the"
                                + " sound editor and save it as OGG.");
                    }
                } catch (IOException e) {
                    status.accept("Could not import " + name + ": " + e.getMessage());
                }
            });
        });
    }
}
