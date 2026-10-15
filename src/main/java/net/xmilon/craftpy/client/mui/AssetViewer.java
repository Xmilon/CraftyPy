package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.NativeImage;
import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.SeekBar;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.PixelBuffer;
import net.xmilon.craftpy.client.assets.AssetPreview;
import net.xmilon.craftpy.client.assets.SoundPreview;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The asset viewer, drawn with Modern UI on top of the IDE: a pixel editor for textures (tools, colour mixer,
 * palette, undo, flips, zoom, save), a player for sounds and file facts for anything else. It replaces the old
 * vanilla viewer / texture editor / sound player screens. UI thread only.
 */
final class AssetViewer {
    private static final int[] PALETTE = {
            0xFF000000, 0xFFFFFFFF, 0xFF7F7F7F, 0xFFC3C3C3, 0xFFB97A57, 0xFF880015, 0xFFED1C24, 0xFFFF7F27,
            0xFFFFF200, 0xFF22B14C, 0xFF00A2E8, 0xFF3F48CC, 0xFFA349A4, 0xFFFFAEC9, 0xFFFFC90E, 0x00000000};
    private static final List<Integer> RECENT = new ArrayList<>();

    private final Context context;
    private final View anchor;
    private final String projectId;
    private String path;
    private Overlays.Dialog popup;
    private TextView status;

    // texture
    private PixelBuffer buffer;
    private PixelCanvas canvas;
    private int savedVersion;
    private boolean confirmDiscard;
    private int color = 0xFFFFFFFF;
    private boolean updating;
    private SeekBar[] channels;
    private EditText hexBox;
    private View swatch;
    private LinearLayout recentRow;
    private PixelCanvas.Tool[] toolOrder;
    private TextView[] toolChips;
    private TextView undoButton;
    private TextView redoButton;
    private EditText nameBox;

    private AssetViewer(View anchor, String projectId, String path) {
        this.context = anchor.getContext();
        this.anchor = anchor;
        this.projectId = projectId;
        this.path = path;
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    static void open(View anchor, String projectId, String path, byte[] data, String error) {
        var viewer = new AssetViewer(anchor, projectId, path);
        String lower = path.toLowerCase(Locale.ROOT);
        View body;
        if (error != null && !error.isEmpty()) {
            body = viewer.message("Could not load " + path + ": " + error);
        } else if (lower.endsWith(".png")) {
            body = viewer.textureBody(data);
        } else if (lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".mp3")) {
            body = viewer.soundBody(data == null ? 0 : data.length);
        } else {
            body = viewer.message((data == null ? 0 : data.length) + " bytes. This file type has no viewer.");
        }
        viewer.show(path, body);
    }

    static void newTexture(View anchor, String projectId, int size) {
        var viewer = new AssetViewer(anchor, projectId, "assets/textures/new_texture.png");
        viewer.buffer = new PixelBuffer(size, size);
        viewer.show("New " + size + "x" + size + " texture", viewer.textureLayout());
    }

    // ------------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------------

    private void show(String title, View body) {
        popup = Overlays.dialog(anchor, title, 0, -1);
        popup.beforeDismiss(this::mayClose).onDismiss(SoundPreview::stop);
        var root = popup.body;
        if (buffer != null) {
            var header = row(context);
            nameBox = new EditText(context, null, R.attr.editTextOutlinedStyle);
            nameBox.setSingleLine();
            nameBox.setText(path);
            nameBox.setHint("assets/textures/name.png");
            header.addView(nameBox, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            header.addView(button(context, "Save", R.attr.buttonStyle, v -> save()));
            root.addView(header);
        }
        root.addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        status = label(context, "", R.attr.textAppearanceLabelMedium);
        status.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        root.addView(status);

        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN && onKey(keyCode, event));
        popup.show();
        root.requestFocus();
    }

    /** False (and a hint) the first time the window is closed with unsaved pixels; the second close discards them. */
    private boolean mayClose() {
        if (buffer != null && buffer.version() != savedVersion && !confirmDiscard) {
            confirmDiscard = true;
            say("Unsaved changes - Save, or close again to discard them.");
            return false;
        }
        return true;
    }

    private void close(boolean force) {
        if (force) {
            popup.forceDismiss();
        } else {
            popup.dismiss();
        }
    }

    private void say(String text) {
        status.setText(text);
    }

    private View message(String text) {
        var label = label(context, text, R.attr.textAppearanceBodyMedium);
        label.setPadding(label.dp(8), label.dp(16), label.dp(8), label.dp(8));
        return label;
    }

    private boolean onKey(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEY_ESCAPE) {
            close(false);
            return true;
        }
        if (buffer == null || popup.body.findFocus() instanceof EditText) {
            return false; // typing in a text box: leave the keys alone
        }
        if (event.isCtrlPressed()) {
            switch (keyCode) {
                case KeyEvent.KEY_Z -> doUndo();
                case KeyEvent.KEY_Y -> doRedo();
                case KeyEvent.KEY_S -> save();
                default -> {
                    return false;
                }
            }
            return true;
        }
        PixelCanvas.Tool picked = switch (keyCode) {
            case KeyEvent.KEY_B -> PixelCanvas.Tool.PENCIL;
            case KeyEvent.KEY_E -> PixelCanvas.Tool.ERASER;
            case KeyEvent.KEY_G -> PixelCanvas.Tool.FILL;
            case KeyEvent.KEY_I -> PixelCanvas.Tool.PICKER;
            case KeyEvent.KEY_L -> PixelCanvas.Tool.LINE;
            case KeyEvent.KEY_R -> PixelCanvas.Tool.RECT;
            default -> null;
        };
        if (picked == null) {
            return false;
        }
        selectTool(picked);
        return true;
    }

    // ------------------------------------------------------------------
    // Textures
    // ------------------------------------------------------------------

    private View textureBody(byte[] data) {
        try (NativeImage image = NativeImage.read(data)) {
            int[] argb = new int[image.getWidth() * image.getHeight()];
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    argb[y * image.getWidth() + x] = PixelBuffer.swapRedBlue(image.getPixelRGBA(x, y));
                }
            }
            if (argb.length > 256 * 256) {
                return message("Image is " + image.getWidth() + "x" + image.getHeight()
                        + " - the editor handles textures up to 256x256.");
            }
            buffer = new PixelBuffer(image.getWidth(), image.getHeight(), argb);
        } catch (Exception e) {
            CraftPy.LOGGER.warn("Could not decode {}", path, e);
            return message("Could not decode the image: " + e.getMessage());
        }
        return textureLayout();
    }

    private View textureLayout() {
        savedVersion = buffer.version();
        canvas = new PixelCanvas(context, buffer);
        canvas.setOnPick(c -> setColor(c, true));
        canvas.setOnChange(this::changed);
        canvas.setOnHover(this::updateStatus);

        var body = row(context);
        body.addView(toolColumn(), new LinearLayout.LayoutParams(body.dp(150), MATCH_PARENT));
        var canvasFrame = new icyllis.modernui.widget.FrameLayout(context);
        canvasFrame.setBackground(new ColorDrawable(0xFF202020));
        canvasFrame.addView(canvas, new icyllis.modernui.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        var lp = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1);
        lp.leftMargin = body.dp(6);
        lp.rightMargin = body.dp(6);
        body.addView(canvasFrame, lp);
        body.addView(colorColumn(), new LinearLayout.LayoutParams(body.dp(210), MATCH_PARENT));
        setColor(color, false);
        selectTool(PixelCanvas.Tool.PENCIL);
        changed();
        return body;
    }

    private View toolColumn() {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        toolOrder = PixelCanvas.Tool.values();
        String[] names = {"Pencil (B)", "Eraser (E)", "Fill (G)", "Pick (I)", "Line (L)", "Rectangle (R)", "Filled box"};
        toolChips = new TextView[toolOrder.length];
        for (int i = 0; i < toolOrder.length; i++) {
            final PixelCanvas.Tool tool = toolOrder[i];
            toolChips[i] = chip(names[i], v -> selectTool(tool));
            column.addView(toolChips[i]);
        }
        column.addView(gap());
        undoButton = chip("Undo (Ctrl+Z)", v -> doUndo());
        redoButton = chip("Redo (Ctrl+Y)", v -> doRedo());
        column.addView(undoButton);
        column.addView(redoButton);
        column.addView(gap());
        column.addView(chip("Flip horizontal", v -> {
            buffer.flipHorizontal();
            changed();
        }));
        column.addView(chip("Flip vertical", v -> {
            buffer.flipVertical();
            changed();
        }));
        column.addView(chip("Rotate 90°", v -> {
            if (buffer.rotate()) {
                changed();
            } else {
                say("Rotate needs a square texture.");
            }
        }));
        column.addView(chip("Wrap shift →", v -> {
            buffer.shift(1, 0);
            changed();
        }));
        column.addView(chip("Wrap shift ↓", v -> {
            buffer.shift(0, 1);
            changed();
        }));
        column.addView(chip("Clear", v -> {
            buffer.clear(0);
            changed();
        }));
        column.addView(gap());
        column.addView(chip("Zoom +", v -> canvas.zoomBy(1)));
        column.addView(chip("Zoom -", v -> canvas.zoomBy(-1)));
        column.addView(chip("Fit", v -> canvas.fit()));
        column.addView(chip("Toggle grid", v -> canvas.setGrid(!canvas.grid())));
        var scroll = new ScrollView(context);
        scroll.addView(column, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        return scroll;
    }

    private View colorColumn() {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        swatch = new View(context);
        column.addView(swatch, new LinearLayout.LayoutParams(MATCH_PARENT, column.dp(36)));
        channels = new SeekBar[4];
        String[] names = {"Red", "Green", "Blue", "Alpha"};
        for (int i = 0; i < 4; i++) {
            final int channel = i;
            column.addView(label(context, names[i], R.attr.textAppearanceLabelSmall));
            channels[i] = new SeekBar(context);
            channels[i].setMax(255);
            channels[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && !updating) {
                        int[] c = channelsOf(color);
                        c[channel] = progress;
                        setColor((c[3] << 24) | (c[0] << 16) | (c[1] << 8) | c[2], false);
                    }
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    remember(color);
                }
            });
            column.addView(channels[i], new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
        hexBox = new EditText(context, null, R.attr.editTextOutlinedStyle);
        hexBox.setSingleLine();
        hexBox.setHint("#RRGGBB or #AARRGGBB");
        hexBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Integer parsed = updating ? null : PixelBuffer.parseHex(s.toString());
                if (parsed != null) {
                    setColor(parsed, false);
                    remember(parsed);
                }
            }
        });
        column.addView(hexBox, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        column.addView(label(context, "Palette", R.attr.textAppearanceLabelSmall));
        column.addView(swatchRow(0, 8));
        column.addView(swatchRow(8, 16));
        column.addView(label(context, "Recent", R.attr.textAppearanceLabelSmall));
        recentRow = row(context);
        column.addView(recentRow);
        renderRecent();
        var scroll = new ScrollView(context);
        scroll.addView(column, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        return scroll;
    }

    private View swatchRow(int from, int to) {
        var row = row(context);
        for (int i = from; i < to; i++) {
            row.addView(colorCell(PALETTE[i]));
        }
        return row;
    }

    private View colorCell(int argb) {
        var cell = new View(context);
        cell.setBackground(new ColorDrawable(argb == 0 ? 0xFF555555 : argb));
        cell.setClickable(true);
        cell.setTooltipText(argb == 0 ? "Transparent" : PixelBuffer.hex(argb));
        cell.setOnClickListener(v -> {
            setColor(argb, true);
            remember(argb);
        });
        var lp = new LinearLayout.LayoutParams(cell.dp(22), cell.dp(22));
        lp.rightMargin = cell.dp(2);
        lp.bottomMargin = cell.dp(2);
        cell.setLayoutParams(lp);
        return cell;
    }

    private void remember(int argb) {
        RECENT.remove((Integer) argb);
        RECENT.add(0, argb);
        while (RECENT.size() > 8) {
            RECENT.remove(RECENT.size() - 1);
        }
        renderRecent();
    }

    private void renderRecent() {
        if (recentRow == null) {
            return;
        }
        recentRow.removeAllViews();
        for (int argb : RECENT) {
            recentRow.addView(colorCell(argb));
        }
    }

    private static int[] channelsOf(int argb) {
        return new int[]{(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF};
    }

    private void setColor(int argb, boolean fromPick) {
        color = argb;
        canvas.setColor(argb);
        updating = true;
        try {
            int[] c = channelsOf(argb);
            for (int i = 0; i < 4; i++) {
                channels[i].setProgress(c[i]);
            }
            swatch.setBackground(new ColorDrawable(argb));
            if (!hexBox.hasFocus() || fromPick) {
                hexBox.setText(PixelBuffer.hex(argb));
            }
        } finally {
            updating = false;
        }
        if (fromPick) {
            remember(argb);
        }
    }

    private void selectTool(PixelCanvas.Tool tool) {
        canvas.setTool(tool);
        for (int i = 0; i < toolOrder.length; i++) {
            styleChip(toolChips[i], toolOrder[i] == tool);
        }
    }

    private void doUndo() {
        buffer.undo();
        changed();
    }

    private void doRedo() {
        buffer.redo();
        changed();
    }

    /** After any edit: repaint, refresh the undo buttons and the status line. */
    private void changed() {
        canvas.refresh();
        undoButton.setAlpha(buffer.canUndo() ? 1f : 0.4f);
        redoButton.setAlpha(buffer.canRedo() ? 1f : 0.4f);
        if (confirmDiscard && buffer.version() == savedVersion) {
            confirmDiscard = false;
        }
        updateStatus();
    }

    private void updateStatus() {
        int x = canvas.hoverX();
        int y = canvas.hoverY();
        String where = buffer.inside(x, y) ? x + ", " + y + "  " + PixelBuffer.hex(buffer.get(x, y)) + "   " : "";
        say(where + buffer.width() + "x" + buffer.height() + "   zoom " + canvas.scale() + "x"
                + (buffer.version() != savedVersion ? "   (unsaved)" : ""));
    }

    private void save() {
        String target = nameBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (!target.startsWith("assets/") || !target.endsWith(".png") || !target.matches("[a-z0-9_./-]+")
                || target.contains("..")) {
            say("Name must be like assets/textures/block/ruby.png (a-z, 0-9, _ - . /)");
            return;
        }
        try (NativeImage image = new NativeImage(buffer.width(), buffer.height(), false)) {
            for (int y = 0; y < buffer.height(); y++) {
                for (int x = 0; x < buffer.width(); x++) {
                    image.setPixelRGBA(x, y, PixelBuffer.swapRedBlue(buffer.get(x, y)));
                }
            }
            byte[] png = image.asByteArray();
            ClientProjectStore.getInstance().uploadAsset(projectId, target, png);
            path = target;
            savedVersion = buffer.version();
            confirmDiscard = false;
            say("Saved " + target + " (" + png.length + " bytes)");
        } catch (Exception e) {
            say("Save failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Sounds
    // ------------------------------------------------------------------

    private View soundBody(int size) {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(column.dp(8), column.dp(12), column.dp(8), column.dp(8));
        column.addView(label(context, path + "  -  " + (size / 1024) + " KB", R.attr.textAppearanceBodyMedium));

        String lower = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        String prefix = "assets/sounds/";
        String event = null;
        if (lower.startsWith(prefix) && lower.endsWith(".ogg")) {
            event = projectId + ":" + lower.substring(prefix.length(), lower.length() - ".ogg".length());
        }
        if (event == null || AssetPreview.soundFile(event) == null) {
            var hint = label(context, event == null
                    ? "Only .ogg files under assets/sounds/ can be played here."
                    : "The sound event '" + event + "' is not registered yet - declare it in assets/sounds.json, "
                    + "run the project and reopen this file.", R.attr.textAppearanceBodySmall);
            hint.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
            column.addView(hint);
            return column;
        }
        final String soundEvent = event;
        float[] settings = {1.0f, 1.0f}; // volume, pitch
        column.addView(label(context, "Volume", R.attr.textAppearanceLabelSmall));
        column.addView(slider(0, 100, 100, value -> settings[0] = value / 100f),
                new LinearLayout.LayoutParams(column.dp(300), WRAP_CONTENT));
        column.addView(label(context, "Pitch", R.attr.textAppearanceLabelSmall));
        column.addView(slider(50, 200, 100, value -> settings[1] = value / 100f),
                new LinearLayout.LayoutParams(column.dp(300), WRAP_CONTENT));
        var buttons = row(context);
        buttons.addView(button(context, "Play", R.attr.buttonStyle, v -> play(soundEvent, settings, false)));
        buttons.addView(button(context, "Loop", R.attr.buttonTonalStyle, v -> play(soundEvent, settings, true)));
        buttons.addView(button(context, "Stop", R.attr.buttonOutlinedStyle, v -> {
            SoundPreview.stop();
            say("Stopped");
        }));
        column.addView(buttons);
        return column;
    }

    private void play(String event, float[] settings, boolean loop) {
        SoundPreview.setPitchVolume(settings[1], settings[0]);
        boolean ok = loop ? SoundPreview.playLooping(event, settings[1], settings[0])
                : SoundPreview.play(event, settings[1], settings[0]);
        say(ok ? (loop ? "Looping " : "Playing ") + event : "Could not play " + event);
    }

    private SeekBar slider(int min, int max, int value, java.util.function.IntConsumer onChange) {
        var bar = new SeekBar(context);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    onChange.accept(progress + min);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        return bar;
    }

    // ------------------------------------------------------------------
    // Small widgets
    // ------------------------------------------------------------------

    private TextView chip(String text, View.OnClickListener onClick) {
        var chip = label(context, text, R.attr.textAppearanceLabelLarge);
        chip.setPadding(chip.dp(10), chip.dp(5), chip.dp(10), chip.dp(5));
        chip.setClickable(true);
        hoverBackground(chip);
        chip.setOnClickListener(onClick);
        return chip;
    }

    private View gap() {
        var gap = new View(context);
        gap.setLayoutParams(new LinearLayout.LayoutParams(1, gap.dp(8)));
        return gap;
    }
}
