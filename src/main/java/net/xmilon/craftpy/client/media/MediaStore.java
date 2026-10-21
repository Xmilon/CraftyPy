package net.xmilon.craftpy.client.media;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Reads and decodes the pictures windows show, off every UI and render thread, and shares one decoded copy between
 * the widgets that use the same file. Counted: a copy stays while a widget uses it, a few unused ones stay a while so a
 * window that is opened again is instant, the rest is closed when the memory budget says so.
 */
public final class MediaStore {
    /** Reads the bytes of a source; called on a loader thread, may block. */
    public interface Reader {
        byte[] read(MediaSource source) throws MediaException;

        /** Distinguishes readers whose files may differ for the same id (the game packs and a project being edited). */
        String key();
    }

    /** Most bytes of a file read from disk or a pack: the video limit is the largest of the formats. */
    private static final int MAX_READ = AviFrames.MAX_FILE_BYTES;
    /** Decoded pictures that nobody uses are kept up to this much. */
    private static final long IDLE_BUDGET = 32L * 1024 * 1024;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CraftPy-Media-Load");
        thread.setDaemon(true);
        return thread;
    });

    private static final class Entry {
        final CompletableFuture<FrameSource> future;
        int users;

        Entry(CompletableFuture<FrameSource> future) {
            this.future = future;
        }
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>(16, 0.75f, true);

    private MediaStore() {
    }

    /** The decoded source, loading it first when needed. Pair every call with {@link #release}. */
    public static synchronized CompletableFuture<FrameSource> acquire(MediaSource source, Reader reader) {
        String key = reader.key() + "|" + source.id();
        Entry entry = ENTRIES.get(key);
        if (entry == null) {
            entry = new Entry(CompletableFuture.supplyAsync(() -> {
                try {
                    return decode(source, reader.read(source));
                } catch (MediaException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, LOADER));
            ENTRIES.put(key, entry);
        }
        entry.users++;
        return entry.future;
    }

    public static synchronized void release(MediaSource source, Reader reader) {
        String key = reader.key() + "|" + source.id();
        Entry entry = ENTRIES.get(key);
        if (entry == null) {
            return;
        }
        entry.users = Math.max(0, entry.users - 1);
        trim();
    }

    /** Closes every picture nobody uses (a resource reload or leaving the world). */
    public static synchronized void clearUnused() {
        List<String> drop = new ArrayList<>();
        ENTRIES.forEach((key, entry) -> {
            if (entry.users == 0) {
                drop.add(key);
            }
        });
        drop.forEach(MediaStore::remove);
    }

    private static void trim() {
        long idle = 0;
        List<String> idleKeys = new ArrayList<>();
        for (Map.Entry<String, Entry> e : ENTRIES.entrySet()) {
            Entry entry = e.getValue();
            if (entry.users == 0) {
                idleKeys.add(e.getKey());
                if (entry.future.isDone() && !entry.future.isCompletedExceptionally()) {
                    idle += entry.future.join().bytes();
                } else if (entry.future.isCompletedExceptionally()) {
                    remove(e.getKey()); // a failed load is never worth keeping
                }
            }
        }
        // oldest first: the map is in access order
        for (String key : idleKeys) {
            if (idle <= IDLE_BUDGET) {
                break;
            }
            Entry entry = ENTRIES.get(key);
            if (entry != null && entry.future.isDone() && !entry.future.isCompletedExceptionally()) {
                idle -= entry.future.join().bytes();
            }
            remove(key);
        }
    }

    private static void remove(String key) {
        Entry entry = ENTRIES.remove(key);
        if (entry != null) {
            entry.future.thenAccept(FrameSource::close);
        }
    }

    static FrameSource decode(MediaSource source, byte[] data) throws MediaException {
        return switch (source.kind()) {
            case GIF -> GifFrames.decode(data);
            case VIDEO -> AviFrames.decode(data);
            case STILL -> StillFrames.decode(data);
            case AUDIO -> throw new MediaException("A sound has no picture");
        };
    }

    /** The files of the resource packs the game has loaded: what a module ships in assets/media/. */
    public static Reader gameReader() {
        return new Reader() {
            @Override
            public byte[] read(MediaSource source) throws MediaException {
                ResourceLocation location = ResourceLocation.tryBuild(source.owner(), source.path());
                if (location == null) {
                    throw new MediaException("Not a valid resource name: " + source.id());
                }
                var resource = Minecraft.getInstance().getResourceManager().getResource(location);
                if (resource.isEmpty()) {
                    throw new MediaException("File not found: " + source.id() + ". Is it in the module's assets/media folder, "
                            + "and is the module loaded (RELOAD)?");
                }
                try (InputStream in = resource.get().open()) {
                    byte[] data = in.readNBytes(MAX_READ + 1);
                    if (data.length > MAX_READ) {
                        throw new MediaException(source.id() + " is larger than " + (MAX_READ >> 20) + " MiB");
                    }
                    return data;
                } catch (IOException e) {
                    throw new MediaException("Could not read " + source.id() + ": " + e.getMessage(), e);
                }
            }

            @Override
            public String key() {
                return "game";
            }
        };
    }
}
