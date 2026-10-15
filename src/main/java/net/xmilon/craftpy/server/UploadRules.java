package net.xmilon.craftpy.server;

import java.util.Locale;
import java.util.Set;

/**
 * What the server accepts for an asset upload before it allocates anything for it. Pure, so it is unit tested; the
 * request handler applies it to every chunk.
 */
public final class UploadRules {
    /** Characters of base64 per chunk; the client ({@code ClientProjectStore.uploadAsset}) splits with this size. */
    public static final int CHUNK_CHARS = 16000;

    private static final Set<String> EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".ogg", ".wav", ".json");

    private UploadRules() {
    }

    /** The most chunks a file of {@code maxBytes} can need. */
    public static int maxChunks(long maxBytes) {
        long chars = (maxBytes + 2) / 3 * 4;
        return (int) Math.min(Integer.MAX_VALUE, (chars + CHUNK_CHARS - 1) / CHUNK_CHARS);
    }

    /** Null when the chunk may be stored, otherwise the reason it is refused. */
    public static String check(String path, int chunk, int total, long maxBytes) {
        if (path == null || path.isBlank() || total <= 0 || chunk < 0 || chunk >= total) {
            return "Malformed upload request";
        }
        String lower = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (!lower.startsWith("assets/")) {
            return "Uploads go into assets/ (got " + path + ")";
        }
        if (lower.indexOf(':') >= 0) {
            return "Illegal path: " + path;
        }
        boolean allowed = false;
        for (String extension : EXTENSIONS) {
            allowed |= lower.endsWith(extension);
        }
        if (!allowed) {
            return "Unsupported file type (use .png, .jpg, .ogg, .wav or .json)";
        }
        if (total > maxChunks(maxBytes)) {
            return "File too large (limit " + maxBytes / 1024 + " KB)";
        }
        return null;
    }
}
