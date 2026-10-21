package net.xmilon.craftpy.client.mui;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.SeekBar;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.client.media.MediaSource;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.button;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.label;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.row;

/**
 * The optional control bar under a media widget: play and pause, a seek bar with the time, the speed of a picture
 * animation, and mute with a volume bar for anything that has sound. Only the controls that make sense for what was
 * loaded are shown. The bar is persistent like its {@link MediaView}, so a drag of the seek bar survives a refresh of
 * the window.
 */
final class MediaControls extends LinearLayout {
    private static final double[] SPEEDS = {0.5, 1, 1.5, 2};

    private final MediaView media;
    private final Button play;
    private final SeekBar seek;
    private final TextView time;
    private final Button speed;
    private final Button mute;
    private final SeekBar volume;
    private boolean dragging;
    private boolean updating;

    MediaControls(Context context, MediaView media) {
        super(context);
        this.media = media;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        play = button(context, "Play", R.attr.borderlessButtonStyle, v -> media.toggle());
        play.setTooltipText("Play or pause");
        addView(play);

        seek = new SeekBar(context);
        seek.setMax(1000);
        seek.setMinimumWidth(seek.dp(60));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && !updating) {
                    media.seek(progress / 1000.0 * media.durationMs());
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
            }
        });
        addView(seek, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));

        time = label(context, "0:00", R.attr.textAppearanceLabelSmall);
        time.setTextColor(IdeColors.TEXT_SECONDARY);
        time.setPadding(time.dp(6), 0, time.dp(6), 0);
        addView(time);

        speed = button(context, "1x", R.attr.borderlessButtonStyle, v -> {
            double now = media.speedNow();
            int next = 0;
            for (int i = 0; i < SPEEDS.length; i++) {
                if (SPEEDS[i] > now + 1e-9) {
                    next = i;
                    break;
                }
            }
            media.speed(SPEEDS[next]);
        });
        speed.setTooltipText("Playback speed (the sound is silent unless it is 1x)");
        addView(speed);

        mute = button(context, "Mute", R.attr.borderlessButtonStyle, v -> media.muted(!media.isMuted()));
        addView(mute);
        volume = new SeekBar(context);
        volume.setMax(100);
        volume.setMinimumWidth(volume.dp(48));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && !updating) {
                    media.volume(progress / 100f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        addView(volume, new LinearLayout.LayoutParams(volume.dp(72), WRAP_CONTENT));
        refresh();
    }

    /** Brings the bar in line with the media: called whenever it changes (and about ten times a second while playing). */
    void refresh() {
        updating = true;
        try {
            boolean ready = media.kind() != null && media.error() == null;
            long duration = media.durationMs();
            play.setEnabled(ready);
            play.setText(media.playing() ? "Pause" : media.ended() ? "Replay" : "Play");
            seek.setVisibility(duration > 0 ? VISIBLE : GONE);
            if (!dragging) {
                seek.setProgress(duration > 0 ? (int) Math.max(0, Math.min(1000, media.positionMs() / duration * 1000)) : 0);
            }
            time.setText(duration > 0 ? format(media.positionMs()) + " / " + format(duration) : format(media.positionMs()));
            time.setVisibility(ready ? VISIBLE : GONE);
            boolean visualClip = media.kind() == MediaSource.Kind.VIDEO || (media.kind() == MediaSource.Kind.GIF && media.animated());
            speed.setVisibility(visualClip ? VISIBLE : GONE);
            speed.setText(trim(media.speedNow()) + "x");
            boolean sound = media.hasSound();
            mute.setVisibility(sound ? VISIBLE : GONE);
            mute.setText(media.isMuted() ? "Unmute" : "Mute");
            volume.setVisibility(sound ? VISIBLE : GONE);
            volume.setProgress(Math.round(media.volumeLevel() * 100));
        } finally {
            updating = false;
        }
    }

    static String format(double ms) {
        long seconds = Math.max(0, Math.round(ms / 1000));
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** The bar and its media stacked: the media takes the height that is left. */
    static LinearLayout stack(Context context, MediaView media, MediaControls controls) {
        var box = new LinearLayout(context);
        box.setOrientation(VERTICAL);
        box.addView(media, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        box.addView(controls, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        return box;
    }
}
