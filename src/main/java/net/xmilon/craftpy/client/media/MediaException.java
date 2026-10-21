package net.xmilon.craftpy.client.media;

/** A media file that cannot be used: the message is shown to the module's author in the window itself. */
public final class MediaException extends Exception {
    public MediaException(String message) {
        super(message);
    }

    public MediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
