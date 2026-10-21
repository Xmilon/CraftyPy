package net.xmilon.craftpy.client.media;

/**
 * Where an animation or sound is and whether it is running. One pure state machine for everything a window plays, so
 * play, pause, seek, speed, looping and the end are decided in one place. Times are milliseconds; a duration of 0 or
 * less means unknown (it runs until told to stop). Not thread-safe: the UI thread owns it.
 */
public final class MediaClock {
    public static final double MIN_SPEED = 0.25;
    public static final double MAX_SPEED = 4;

    /** What happened during an {@link #update}. */
    public enum Event {
        NONE, LOOPED, ENDED
    }

    private final long durationMs;
    private boolean playing;
    private boolean loop;
    private double speed = 1;
    private double positionMs;
    private long lastNanos;
    private boolean ended;

    public MediaClock(long durationMs, boolean loop) {
        this.durationMs = Math.max(0, durationMs);
        this.loop = loop;
    }

    public long durationMs() {
        return durationMs;
    }

    public boolean playing() {
        return playing;
    }

    public boolean loop() {
        return loop;
    }

    public boolean ended() {
        return ended;
    }

    public double speed() {
        return speed;
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    /** Playing again after the end starts from the beginning. */
    public void setPlaying(boolean playing, long nowNanos) {
        if (playing == this.playing) {
            return;
        }
        update(nowNanos);
        if (playing && ended) {
            positionMs = 0;
            ended = false;
        }
        this.playing = playing;
        this.lastNanos = nowNanos;
    }

    public void setSpeed(double speed, long nowNanos) {
        update(nowNanos);
        this.speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, Double.isFinite(speed) ? speed : 1));
    }

    public void seek(double ms, long nowNanos) {
        update(nowNanos);
        double max = durationMs > 0 ? durationMs : Double.MAX_VALUE;
        positionMs = Math.max(0, Math.min(max, Double.isFinite(ms) ? ms : 0));
        ended = false;
        lastNanos = nowNanos;
    }

    public double positionMs() {
        return positionMs;
    }

    /** Moves the position by the time that passed (scaled by the speed) and reports a loop or the end. */
    public Event update(long nowNanos) {
        long passed = nowNanos - lastNanos;
        lastNanos = nowNanos;
        if (!playing || passed <= 0) {
            return Event.NONE;
        }
        positionMs += passed / 1_000_000.0 * speed;
        if (durationMs > 0 && positionMs >= durationMs) {
            if (loop) {
                positionMs %= durationMs;
                return Event.LOOPED;
            }
            positionMs = durationMs;
            playing = false;
            ended = true;
            return Event.ENDED;
        }
        return Event.NONE;
    }
}
