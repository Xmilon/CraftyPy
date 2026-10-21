package net.xmilon.craftpy.client.media;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where a window widget gets its picture or sound: a file a module (or project) ships under {@code assets/media/}.
 * Written {@code media/intro.gif} (the module that owns the window) or {@code othermod:media/intro.gif}. Nothing
 * else is accepted: no web addresses, no paths outside {@code media/}, no {@code ..}.
 */
public record MediaSource(String owner, String path, Kind kind) {
    public enum Kind {
        /** PNG, JPEG, BMP. */
        STILL,
        /** GIF, animated or not. */
        GIF,
        /** Motion JPEG in an AVI. */
        VIDEO,
        /** A sound the media player of CraftPy can decode. */
        AUDIO
    }

    public static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "bmp", "gif");
    public static final Set<String> VIDEO_EXTENSIONS = Set.of("avi");
    public static final Set<String> AUDIO_EXTENSIONS = MediaDecoder.EXTENSIONS;
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern PATH = Pattern.compile("media/[a-z0-9_./-]{1,120}");

    /** Resource id the file has in the resource pack ({@code owner:media/...}). */
    public String id() {
        return owner + ":" + path;
    }

    public String extension() {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    /**
     * @param raw          what the widget says ({@code source})
     * @param defaultOwner the module or project the window belongs to, when {@code raw} names none
     * @param allowed      the kinds this widget can show
     * @throws MediaException with a message that tells the author what to change
     */
    public static MediaSource parse(String raw, String defaultOwner, Set<Kind> allowed) throws MediaException {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            throw new MediaException("No source: name a file such as media/clip.gif");
        }
        if (text.contains("://") || text.startsWith("//") || text.contains("\\")) {
            throw new MediaException("Only files bundled in the module are supported, not web addresses: put the file in "
                    + "assets/media/ and use media/<name>");
        }
        String owner = defaultOwner == null ? "" : defaultOwner;
        String path = text;
        int colon = text.indexOf(':');
        if (colon >= 0) {
            owner = text.substring(0, colon);
            path = text.substring(colon + 1);
        }
        if (!NAMESPACE.matcher(owner).matches()) {
            throw new MediaException("The window has no module to find '" + text + "' in: write it as <module>:media/<file>");
        }
        if (path.contains("..") || !PATH.matcher(path).matches()) {
            throw new MediaException("'" + text + "' must be a file under media/ with a lowercase name (a-z, 0-9, _ . - /)");
        }
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!path.contains(".") || extension.contains("/")) {
            throw new MediaException("'" + text + "' has no file extension");
        }
        Kind kind;
        if (extension.equals("gif")) {
            kind = Kind.GIF;
        } else if (IMAGE_EXTENSIONS.contains(extension)) {
            kind = Kind.STILL;
        } else if (VIDEO_EXTENSIONS.contains(extension)) {
            kind = Kind.VIDEO;
        } else if (AUDIO_EXTENSIONS.contains(extension)) {
            kind = Kind.AUDIO;
        } else {
            throw new MediaException("." + extension + " is not supported. Images: png, jpg, bmp, gif. Video: avi (Motion JPEG). "
                    + "Audio: " + String.join(", ", new java.util.TreeSet<>(AUDIO_EXTENSIONS)));
        }
        if (!allowed.contains(kind)) {
            throw new MediaException("A " + kind.name().toLowerCase(Locale.ROOT) + " file (." + extension + ") cannot be used here");
        }
        return new MediaSource(owner, path, kind);
    }
}
