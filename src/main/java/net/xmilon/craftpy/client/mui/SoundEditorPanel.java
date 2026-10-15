package net.xmilon.craftpy.client.mui;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.HorizontalScrollView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.client.AudioClip;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.assets.OggEncoder;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The sound editor tab of the asset dock: a waveform you can select in, playback of the sound or the selection (also
 * looped), and the edits trim, cut, silence, fade in / out, gain, normalize, reverse, speed, mono and insert silence, each
 * undoable. Playback uses the JDK's audio output, independent of the game's sound engine.
 * <p>
 * Minecraft can only play Ogg Vorbis and Java cannot write it, so Save hands the sound to a short-lived Python with the
 * soundfile package ({@link OggEncoder}); when the package is missing the panel offers to install it, or to save a 16 bit
 * WAV instead.
 */
final class SoundEditorPanel implements DockPanel {
    private static final int HISTORY = 12;
    /** Samples are floats: a long stereo clip is ~20 MB, so the undo list is also capped by size. */
    private static final long HISTORY_BYTES = 96L * 1024 * 1024;

    private final Context context;
    private final String projectId;
    private final LinearLayout root;
    private Waveform waveform;
    private final TextView status;
    private final EditText nameBox;
    private final TextView undoButton;
    private final TextView redoButton;
    private final Consumer<String> onSaved;
    private final LinearLayout fallbackRow;
    private final TextView installButton;
    private final ArrayDeque<AudioClip> undo = new ArrayDeque<>();
    private final ArrayDeque<AudioClip> redo = new ArrayDeque<>();

    private AudioClip clip;
    private boolean modified;
    private Clip playing;
    private boolean loop;
    private int playFrom;

    SoundEditorPanel(Context context, String projectId, String path, AudioClip clip, Consumer<String> onSaved) {
        this.context = context;
        this.projectId = projectId;
        this.clip = clip;
        this.onSaved = onSaved;

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        var top = row(context);
        nameBox = new EditText(context, null, R.attr.editTextOutlinedStyle);
        nameBox.setSingleLine();
        nameBox.setText(oggName(path));
        nameBox.setHint("assets/sounds/name.ogg");
        top.addView(nameBox, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        top.addView(button(context, "Save", R.attr.buttonStyle, v -> save()));
        root.addView(top);

        var transport = row(context);
        transport.addView(chip("Play", "Play from the start or the cursor (Space)", v -> play(false)));
        transport.addView(chip("Selection", "Play only the selection", v -> play(true)));
        transport.addView(toggle("Loop", "Repeat while playing", on -> loop = on));
        transport.addView(chip("Stop", "Stop playing", v -> stop()));
        undoButton = chip("Undo", "Undo (Ctrl+Z)", v -> undo());
        redoButton = chip("Redo", "Redo (Ctrl+Y)", v -> redo());
        transport.addView(undoButton);
        transport.addView(redoButton);
        root.addView(scrolling(transport));

        var edits = row(context);
        edits.addView(chip("Select all", "Select the whole sound (Ctrl+A)", v -> waveform.selectAll()));
        edits.addView(chip("Trim", "Keep only the selection", v -> apply("Trimmed", c -> c.trim(selFrom(), selTo()))));
        edits.addView(chip("Cut", "Remove the selection (Delete)", v -> apply("Cut", c -> c.cut(selFrom(), selTo()))));
        edits.addView(chip("Silence", "Replace the selection with silence", v -> apply("Silenced", c -> c.silence(selFrom(), selTo()))));
        edits.addView(chip("Fade in", "Fade in over the selection (or the first second)",
                v -> apply("Fade in", c -> c.fadeIn(rangeFrom(), rangeToOrSecond(true)))));
        edits.addView(chip("Fade out", "Fade out over the selection (or the last second)",
                v -> apply("Fade out", c -> c.fadeOut(rangeFromOrLastSecond(), rangeTo()))));
        edits.addView(chip("+3 dB", "Louder by 3 dB (selection or all)", v -> apply("Louder", c -> c.gain(rangeFrom(), rangeTo(), 3))));
        edits.addView(chip("-3 dB", "Quieter by 3 dB (selection or all)", v -> apply("Quieter", c -> c.gain(rangeFrom(), rangeTo(), -3))));
        edits.addView(chip("Normalize", "Make the loudest point full volume", v -> apply("Normalized", c -> c.normalize(1.0))));
        edits.addView(chip("Reverse", "Play the selection (or all) backwards", v -> apply("Reversed", c -> c.reverse(rangeFrom(), rangeTo()))));
        edits.addView(chip("Faster", "Twice as fast, an octave higher", v -> apply("Faster", c -> c.speed(2.0))));
        edits.addView(chip("Slower", "Half as fast, an octave lower", v -> apply("Slower", c -> c.speed(0.5))));
        edits.addView(chip("Mono", "Mix stereo down to one channel", v -> apply("Mono", AudioClip::toMono)));
        edits.addView(chip("+0.5 s", "Insert half a second of silence at the cursor",
                v -> apply("Silence inserted", c -> c.insertSilence(waveform.cursor, c.sampleRate() / 2))));
        root.addView(scrolling(edits));

        waveform = new Waveform(context);
        waveform.setFocusableInTouchMode(true); // a click on the waveform gives it the keyboard
        waveform.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN && onKey(keyCode, event));
        var frame = new FrameLayout(context);
        frame.addView(waveform, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        var frameLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
        frameLp.topMargin = root.dp(4);
        root.addView(frame, frameLp);

        status = label(context, "", R.attr.textAppearanceLabelMedium);
        status.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        root.addView(status);
        fallbackRow = row(context);
        installButton = chip("Install encoder", "Runs: python -m pip install --user soundfile", v -> installEncoder());
        fallbackRow.addView(installButton);
        fallbackRow.addView(chip("Save as .wav", "Save a 16 bit WAV instead (Minecraft cannot play it)", v -> saveWav()));
        fallbackRow.setVisibility(View.GONE);
        root.addView(fallbackRow);
        var hint = label(context, "Saves Ogg Vorbis (the only format Minecraft plays). That needs Python with the soundfile package; the editor offers to install it.",
                R.attr.textAppearanceBodySmall);
        hint.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        root.addView(hint);
        refresh();
    }

    private static String oggName(String path) {
        int dot = path.lastIndexOf('.');
        return (dot > 0 ? path.substring(0, dot) : path) + ".ogg";
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public boolean dirty() {
        return modified;
    }

    /** Esc: stop playing, then drop the selection. */
    @Override
    public boolean back() {
        if (playing != null) {
            stop();
            return true;
        }
        if (waveform.hasSelection()) {
            waveform.clearSelection();
            refresh();
            return true;
        }
        return false;
    }

    /** Called when the dock lets go of this panel. */
    void dispose() {
        stop();
    }

    // ------------------------------------------------------------------ widgets

    private TextView chip(String text, String tip, View.OnClickListener onClick) {
        var chip = label(context, text, R.attr.textAppearanceLabelMedium);
        chip.setPadding(chip.dp(8), chip.dp(4), chip.dp(8), chip.dp(4));
        chip.setClickable(true);
        chip.setTooltipText(tip);
        hoverBackground(chip);
        chip.setOnClickListener(EditorFragment.hidingTooltip(tip, onClick));
        return chip;
    }

    private TextView toggle(String text, String tip, Consumer<Boolean> onChange) {
        boolean[] on = {false};
        var chip = chip(text, tip, v -> { });
        chip.setOnClickListener(EditorFragment.hidingTooltip(tip, v -> {
            on[0] = !on[0];
            styleChip(chip, on[0]);
            onChange.accept(on[0]);
        }));
        return chip;
    }

    private View scrolling(LinearLayout content) {
        var scroll = new HorizontalScrollView(context);
        scroll.addView(content);
        return scroll;
    }

    // ------------------------------------------------------------------ editing

    private int selFrom() {
        return waveform.selFrom();
    }

    private int selTo() {
        return waveform.selTo();
    }

    /** The selection, or the whole clip when nothing is selected. */
    private int rangeFrom() {
        return waveform.hasSelection() ? waveform.selFrom() : 0;
    }

    private int rangeTo() {
        return waveform.hasSelection() ? waveform.selTo() : clip.frames();
    }

    private int rangeToOrSecond(boolean start) {
        return waveform.hasSelection() ? waveform.selTo() : Math.min(clip.frames(), clip.sampleRate());
    }

    private int rangeFromOrLastSecond() {
        return waveform.hasSelection() ? waveform.selFrom() : Math.max(0, clip.frames() - clip.sampleRate());
    }

    private long undoBytes() {
        long total = 0;
        for (AudioClip old : undo) {
            total += (long) old.frames() * old.channels() * Float.BYTES;
        }
        return total;
    }

    private void apply(String what, UnaryOperator<AudioClip> edit) {
        try {
            AudioClip next = edit.apply(clip);
            if (next == clip) {
                say("Nothing to change.");
                return;
            }
            stop();
            undo.push(clip);
            while (undo.size() > HISTORY || undoBytes() > HISTORY_BYTES) {
                undo.removeLast();
            }
            redo.clear();
            clip = next;
            modified = true;
            waveform.clampTo(clip.frames());
            refresh();
            say(what + ".");
        } catch (IllegalArgumentException e) {
            say(e.getMessage());
        }
    }

    private void undo() {
        if (!undo.isEmpty()) {
            stop();
            redo.push(clip);
            clip = undo.pop();
            modified = true;
            waveform.clampTo(clip.frames());
            refresh();
        }
    }

    private void redo() {
        if (!redo.isEmpty()) {
            stop();
            undo.push(clip);
            clip = redo.pop();
            modified = true;
            waveform.clampTo(clip.frames());
            refresh();
        }
    }

    private void refresh() {
        undoButton.setAlpha(undo.isEmpty() ? 0.4f : 1f);
        redoButton.setAlpha(redo.isEmpty() ? 0.4f : 1f);
        waveform.setClip(clip);
        float peak = clip.peak();
        String db = peak < 1e-6f ? "silent" : String.format(Locale.ROOT, "peak %.1f dB", 20 * Math.log10(peak));
        String selection = waveform.hasSelection()
                ? String.format(Locale.ROOT, "   selected %.2f s", (selTo() - selFrom()) / (double) clip.sampleRate()) : "";
        say(String.format(Locale.ROOT, "%.2f s, %d Hz, %s, %s%s%s", clip.seconds(), clip.sampleRate(),
                clip.channels() == 1 ? "mono" : "stereo", db, selection, modified ? "   (unsaved)" : ""));
    }

    private void say(String text) {
        status.setText(text);
    }

    // ------------------------------------------------------------------ playback

    private void play(boolean selectionOnly) {
        stop();
        int from = selectionOnly && waveform.hasSelection() ? selFrom() : waveform.cursor;
        int to = selectionOnly && waveform.hasSelection() ? selTo() : clip.frames();
        if (to - from < 1) {
            from = 0;
            to = clip.frames();
        }
        try {
            AudioClip part = clip.trim(from, to);
            AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, part.sampleRate(), 16, part.channels(),
                    part.channels() * 2, part.sampleRate(), false);
            byte[] pcm = part.toPcm16();
            AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, part.frames());
            Clip line = AudioSystem.getClip();
            line.open(stream);
            line.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP && line == playing && !line.isRunning()
                        && line.getFramePosition() >= line.getFrameLength() - 1) {
                    root.post(() -> {
                        if (line == playing) {
                            finishPlaying();
                        }
                    });
                }
            });
            playing = line;
            playFrom = from;
            if (loop) {
                line.loop(Clip.LOOP_CONTINUOUSLY);
            } else {
                line.start();
            }
            waveform.playhead = from;
            tickPlayhead(line, part.frames());
        } catch (Exception e) {
            CraftPy.LOGGER.warn("Sound playback failed", e);
            say("Could not play: " + e.getMessage());
            playing = null;
        }
    }

    private void tickPlayhead(Clip line, int frames) {
        if (line != playing) {
            return;
        }
        int position = line.getFramePosition();
        waveform.playhead = Math.min(clip.frames(), playFrom + (frames == 0 ? 0 : position % Math.max(1, frames)));
        waveform.invalidate();
        root.postDelayed(() -> tickPlayhead(line, frames), 40);
    }

    private void finishPlaying() {
        stop();
    }

    void stop() {
        Clip line = playing;
        playing = null;
        waveform.playhead = -1;
        waveform.invalidate();
        if (line != null) {
            try {
                line.stop();
                line.close();
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
    }

    // ------------------------------------------------------------------ saving

    /** Save: Ogg Vorbis through the Python encoder, or a WAV when the name says so. */
    void save() {
        String target = targetName();
        if (target == null) {
            return;
        }
        fallbackRow.setVisibility(View.GONE);
        if (target.endsWith(".wav")) {
            upload(target, clip.toWav(), clip);
            return;
        }
        final AudioClip saved = clip;
        say("Encoding Ogg Vorbis ...");
        OggEncoder.encode(saved.toWav()).thenAccept(result -> root.post(() -> {
            switch (result.status()) {
                case OK -> upload(target, result.ogg(), saved);
                case MISSING_MODULE -> {
                    say("Saving .ogg needs the Python package soundfile.");
                    installButton.setVisibility(View.VISIBLE);
                    fallbackRow.setVisibility(View.VISIBLE);
                }
                default -> {
                    say("Could not make the .ogg: " + result.message());
                    installButton.setVisibility(View.GONE);
                    fallbackRow.setVisibility(View.VISIBLE);
                }
            }
        }));
    }

    /** The fallback button: the same sound as a 16 bit WAV next to the .ogg name. */
    private void saveWav() {
        String target = targetName();
        if (target != null) {
            fallbackRow.setVisibility(View.GONE);
            String wav = target.endsWith(".ogg") ? target.substring(0, target.length() - 4) + ".wav" : target;
            nameBox.setText(wav);
            upload(wav, clip.toWav(), clip);
        }
    }

    private void installEncoder() {
        say("Installing soundfile with pip ...");
        installButton.setVisibility(View.GONE);
        OggEncoder.install().thenAccept(result -> root.post(() -> {
            if (result.status() == OggEncoder.Status.OK) {
                fallbackRow.setVisibility(View.GONE);
                say("soundfile installed. Press Save again.");
            } else {
                installButton.setVisibility(View.VISIBLE);
                say("Install failed: " + result.message());
            }
        }));
    }

    /** The checked file name of the name box, or null (after telling the player). */
    private String targetName() {
        String target = nameBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (!net.xmilon.craftpy.client.assets.AssetDrop.validTarget(target, ".ogg", ".wav")) {
            say("Name must be like assets/sounds/ruby_chime.ogg (a-z, 0-9, _ - . /)");
            return null;
        }
        return target;
    }

    /** Uploads the bytes; the sound counts as saved only once the server wrote the file. */
    private void upload(String target, byte[] bytes, AudioClip saved) {
        say("Saving " + target + " ...");
        Minecraft.getInstance().execute(() -> ClientProjectStore.getInstance().uploadAsset(projectId, target, bytes,
                error -> root.post(() -> {
                    if (error != null) {
                        say("Not saved: " + error);
                        return;
                    }
                    if (clip == saved) {
                        modified = false;
                        refresh();
                    }
                    say("Saved " + target + " (" + Math.max(1, bytes.length / 1024) + " KB)");
                    onSaved.accept(target);
                })));
    }

    private boolean onKey(int keyCode, KeyEvent event) {
        if (root.findFocus() instanceof EditText) {
            return false;
        }
        if (event.isCtrlPressed()) {
            switch (keyCode) {
                case KeyEvent.KEY_Z -> undo();
                case KeyEvent.KEY_Y -> redo();
                case KeyEvent.KEY_S -> save();
                case KeyEvent.KEY_A -> waveform.selectAll();
                default -> {
                    return false;
                }
            }
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEY_SPACE -> {
                if (playing != null) {
                    stop();
                } else {
                    play(false);
                }
            }
            case KeyEvent.KEY_DELETE -> apply("Cut", c -> c.cut(selFrom(), selTo()));
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ the waveform

    private final class Waveform extends View {
        private final Paint paint = new Paint();
        private AudioClip shown;
        private float[] peaks;
        private int peaksWidth;
        private int from;
        private int to;
        private int anchor = -1;
        private int selA = -1;
        private int selB = -1;
        private boolean dragged;
        int cursor;
        int playhead = -1;

        Waveform(Context context) {
            super(context);
        }

        void setClip(AudioClip next) {
            if (next != shown) {
                shown = next;
                peaks = null;
                from = 0;
                to = next.frames();
            }
            invalidate();
        }

        boolean hasSelection() {
            return selA >= 0 && selB >= 0 && selA != selB;
        }

        int selFrom() {
            return Math.min(selA, selB);
        }

        int selTo() {
            return Math.max(selA, selB);
        }

        void selectAll() {
            selA = 0;
            selB = shown.frames();
            refresh();
            invalidate();
        }

        void clearSelection() {
            selA = -1;
            selB = -1;
            invalidate();
        }

        void clampTo(int frames) {
            cursor = Math.min(cursor, Math.max(0, frames - 1));
            if (selA > frames || selB > frames) {
                clearSelection();
            }
        }

        private int frameAt(float x) {
            int width = Math.max(1, getWidth());
            long frame = from + (long) ((to - from) * Math.max(0f, Math.min(1f, x / width)));
            return (int) Math.max(0, Math.min(shown.frames(), frame));
        }

        private float xOf(int frame) {
            return (frame - from) / (float) Math.max(1, to - from) * getWidth();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            paint.setColor(0xFF1E1E1E);
            canvas.drawRect(0, 0, w, h, paint);
            if (shown == null || w < 2) {
                return;
            }
            if (peaks == null || peaksWidth != w || peaksFrom != from || peaksTo != to) {
                peaks = shown.peaks(w, from, to);
                peaksWidth = w;
                peaksFrom = from;
                peaksTo = to;
            }
            float mid = h / 2f;
            paint.setColor(0xFF2A2A2A);
            canvas.drawRect(0, mid, w, mid + 1, paint);
            if (hasSelection()) {
                paint.setColor(0x44FFFFFF);
                canvas.drawRect(Math.max(0, xOf(selFrom())), 0, Math.min(w, xOf(selTo())), h, paint);
            }
            paint.setColor(0xFF4FC3F7);
            for (int x = 0; x < w; x++) {
                float low = peaks[x * 2];
                float high = peaks[x * 2 + 1];
                canvas.drawRect(x, mid - high * mid * 0.95f, x + 1, Math.max(mid - high * mid * 0.95f + 1, mid - low * mid * 0.95f), paint);
            }
            paint.setColor(0xFFFFFFFF);
            canvas.drawRect(xOf(cursor), 0, xOf(cursor) + 1, h, paint);
            if (playhead >= 0) {
                paint.setColor(0xFFFFC107);
                canvas.drawRect(xOf(playhead), 0, xOf(playhead) + 2, h, paint);
            }
        }

        private int peaksFrom = -1;
        private int peaksTo = -1;

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            if (shown == null) {
                return true;
            }
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> {
                    anchor = frameAt(event.getX());
                    dragged = false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    int now = frameAt(event.getX());
                    if (Math.abs(now - anchor) > Math.max(1, (to - from) / Math.max(1, getWidth())) * 3) {
                        dragged = true;
                        selA = anchor;
                        selB = now;
                        invalidate();
                    }
                }
                case MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        cursor = anchor;
                        clearSelection();
                    }
                    refresh();
                    invalidate();
                }
                default -> {
                }
            }
            return true;
        }

        @Override
        public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_SCROLL && shown != null) {
                float delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (delta != 0) {
                    int focus = frameAt(event.getX());
                    double factor = delta > 0 ? 0.8 : 1.25;
                    int span = (int) Math.max(Math.min(256, shown.frames()), Math.min(shown.frames(), (to - from) * factor));
                    int start = Math.max(0, Math.min(shown.frames() - span, focus - (int) ((focus - from) * (span / (double) Math.max(1, to - from)))));
                    from = start;
                    to = start + span;
                    invalidate();
                    return true;
                }
            }
            return super.onGenericMotionEvent(event);
        }
    }
}
