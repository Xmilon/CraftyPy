package net.xmilon.craftpy.client.mui;

import net.xmilon.craftpy.client.IdeHost;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.HorizontalScrollView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.SeekBar;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.PixelBuffer;
import net.xmilon.craftpy.client.assets.TextureCodec;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The texture editor tab of the asset dock: tools (pencil, eraser, fill with tolerance, picker, line, rectangle, ellipse,
 * lighten, darken, select), brush size, mirror drawing, 3x3 tile preview, colour mixer, palette, copy / cut / paste,
 * resize, undo, save. Drawing logic is {@link PixelBuffer}, mouse handling {@link PixelCanvas}; this class is layout and
 * wiring only. UI thread only; the project is written through the client thread.
 */
final class TextureEditorPanel implements DockPanel {
    private static final int[] PALETTE = {
            0xFF000000, 0xFFFFFFFF, 0xFF7F7F7F, 0xFFC3C3C3, 0xFFB97A57, 0xFF880015, 0xFFED1C24, 0xFFFF7F27,
            0xFFFFF200, 0xFF22B14C, 0xFF00A2E8, 0xFF3F48CC, 0xFFA349A4, 0xFFFFAEC9, 0xFFFFC90E, 0x00000000};
    private static final PixelCanvas.Tool[] TOOLS = PixelCanvas.Tool.values();
    private static final String[] TOOL_NAMES = {"Pencil", "Eraser", "Fill", "Pick", "Line", "Rect", "Box", "Ellipse",
            "Disc", "Lighten", "Darken", "Select"};
    private static final String[] TOOL_TIPS = {"Pencil (B)", "Eraser (E)", "Flood fill (G); tolerance below",
            "Colour picker (I)", "Line (L)", "Rectangle outline (R)", "Filled rectangle", "Ellipse outline (O)",
            "Filled ellipse", "Lighten pixels", "Darken pixels", "Select a box (S): copy, cut, delete, paste"};

    private final Context context;
    private final String projectId;
    private final LinearLayout root;
    private final PixelCanvas canvas;
    private final TextView status;
    private final EditText nameBox;
    private final TextView[] toolChips = new TextView[TOOLS.length];
    private final List<Integer> recent = new ArrayList<>();
    private final SeekBar[] channels = new SeekBar[4];
    private final EditText hexBox;
    private final View swatch;
    private final TextView undoButton;
    private final TextView redoButton;
    private final LinearLayout recentRow;
    private final Consumer<String> onSaved;

    private PixelBuffer buffer;
    private String path;
    private int savedVersion;
    private int color = 0xFFFFFFFF;
    private boolean updating;
    private PixelBuffer.Region clipboard;
    private int brushSize = 1;
    private boolean mirrorX;
    private boolean mirrorY;

    TextureEditorPanel(Context context, String projectId, String path, PixelBuffer buffer, Consumer<String> onSaved) {
        this.context = context;
        this.projectId = projectId;
        this.path = path;
        this.buffer = buffer;
        this.onSaved = onSaved;
        this.savedVersion = buffer.version();

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        var top = row(context);
        nameBox = new EditText(context, null, R.attr.editTextOutlinedStyle);
        nameBox.setSingleLine();
        nameBox.setText(path);
        nameBox.setHint("assets/textures/name.png");
        top.addView(nameBox, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        top.addView(button(context, "Save", R.attr.buttonStyle, v -> save()));
        root.addView(top);

        var tools = row(context);
        for (int i = 0; i < TOOLS.length; i++) {
            final PixelCanvas.Tool tool = TOOLS[i];
            toolChips[i] = chip(TOOL_NAMES[i], TOOL_TIPS[i], v -> selectTool(tool));
            tools.addView(toolChips[i]);
        }
        root.addView(scrolling(tools));

        var actions = row(context);
        undoButton = chip("Undo", "Undo (Ctrl+Z)", v -> undo());
        redoButton = chip("Redo", "Redo (Ctrl+Y)", v -> redo());
        actions.addView(undoButton);
        actions.addView(redoButton);
        actions.addView(chip("Flip H", "Mirror the picture left-right", v -> edit(() -> this.buffer.flipHorizontal())));
        actions.addView(chip("Flip V", "Mirror the picture top-bottom", v -> edit(() -> this.buffer.flipVertical())));
        actions.addView(chip("Rotate", "Rotate 90 degrees (square textures)", v -> {
            if (this.buffer.rotate()) {
                changed();
            } else {
                say("Rotate needs a square texture.");
            }
        }));
        actions.addView(chip("Shift", "Wrap the picture one pixel right", v -> edit(() -> this.buffer.shift(1, 0))));
        actions.addView(chip("Clear", "Make everything transparent", v -> edit(() -> this.buffer.clear(0))));
        actions.addView(chip("Resize", "Change the size of the texture", v -> resizeDialog()));
        root.addView(scrolling(actions));

        canvas = new PixelCanvas(context, buffer);
        var options = row(context);
        options.addView(chip("Brush -", "Smaller brush ([)", v -> brush(-1)));
        options.addView(chip("Brush +", "Bigger brush (])", v -> brush(1)));
        options.addView(toggle("Mirror X", "Draw mirrored left-right", on -> setMirror(on, null)));
        options.addView(toggle("Mirror Y", "Draw mirrored top-bottom", on -> setMirror(null, on)));
        options.addView(toggle("Tile", "Show the texture repeated 3x3 to check seams", canvas::setTiled));
        options.addView(toggle("Grid", "Pixel grid (when zoomed in)", canvas::setGrid));
        options.addView(chip("Fit", "Fit to the window", v -> canvas.fit()));
        options.addView(chip("Zoom +", "Zoom in (mouse wheel)", v -> canvas.zoomBy(1)));
        options.addView(chip("Zoom -", "Zoom out", v -> canvas.zoomBy(-1)));
        root.addView(scrolling(options));

        canvas.setFocusableInTouchMode(true); // a click on the picture gives it the keyboard
        canvas.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN && onKey(keyCode, event));
        canvas.setOnPick(c -> setColor(c, true));
        canvas.setOnChange(this::changed);
        canvas.setOnHover(this::updateStatus);
        canvas.setOnSelect(this::updateStatus);
        var frame = new FrameLayout(context);
        frame.setBackground(new ColorDrawable(0xFF202020));
        frame.addView(canvas, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        var frameLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
        frameLp.topMargin = root.dp(4);
        root.addView(frame, frameLp);

        var colours = row(context);
        swatch = new View(context);
        colours.addView(swatch, new LinearLayout.LayoutParams(root.dp(34), root.dp(34)));
        hexBox = new EditText(context, null, R.attr.editTextOutlinedStyle);
        hexBox.setSingleLine();
        hexBox.setHint("#RRGGBB / #AARRGGBB");
        hexBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!updating) {
                    Integer parsed = PixelBuffer.parseHex(s.toString());
                    if (parsed != null) {
                        setColor(parsed, false);
                        remember(parsed);
                    }
                }
            }
        });
        var hexLp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        hexLp.leftMargin = root.dp(6);
        colours.addView(hexBox, hexLp);
        colours.addView(chip("Copy palette", "Copy the colours of this picture as hex codes", v -> copyPalette()));
        colours.addView(chip("Paste palette", "Add hex codes from the clipboard to the recent colours", v -> pastePalette()));
        root.addView(colours);

        String[] names = {"R", "G", "B", "A"};
        var sliders = row(context);
        for (int i = 0; i < 4; i++) {
            final int channel = i;
            sliders.addView(label(context, names[i], R.attr.textAppearanceLabelSmall));
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
            sliders.addView(channels[i], new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        }
        root.addView(sliders);

        var tolerance = row(context);
        tolerance.addView(label(context, "Fill tolerance", R.attr.textAppearanceLabelSmall));
        var toleranceBar = new SeekBar(context);
        toleranceBar.setMax(128);
        toleranceBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                canvas.setTolerance(progress);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        tolerance.addView(toleranceBar, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        root.addView(tolerance);

        var palette = row(context);
        for (int argb : PALETTE) {
            palette.addView(swatchButton(argb));
        }
        root.addView(scrolling(palette));
        recentRow = row(context);
        root.addView(scrolling(recentRow));

        status = label(context, "", R.attr.textAppearanceLabelMedium);
        status.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        root.addView(status);

        setColor(color, false);
        selectTool(PixelCanvas.Tool.PENCIL);
        changed();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public boolean dirty() {
        return buffer.version() != savedVersion;
    }

    String path() {
        return path;
    }

    /** Esc: put a pasted picture away, then drop the selection. */
    @Override
    public boolean back() {
        if (canvas.isPasting()) {
            canvas.cancelPaste();
            return true;
        }
        if (canvas.selection() != null) {
            canvas.clearSelection();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ small widgets

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

    private View swatchButton(int argb) {
        var view = new View(context);
        view.setBackground(new ColorDrawable(argb == 0 ? 0xFF555555 : argb));
        view.setClickable(true);
        view.setTooltipText(PixelBuffer.hex(argb));
        view.setOnClickListener(v -> {
            setColor(argb, true);
            remember(argb);
        });
        var lp = new LinearLayout.LayoutParams(view.dp(22), view.dp(22));
        lp.setMargins(view.dp(1), view.dp(2), view.dp(1), view.dp(2));
        view.setLayoutParams(lp);
        return view;
    }

    // ------------------------------------------------------------------ state

    private void brush(int delta) {
        brushSize = Math.max(1, Math.min(32, brushSize + delta));
        canvas.setBrushSize(brushSize);
        say("Brush " + brushSize + "x" + brushSize);
    }

    private void setMirror(Boolean x, Boolean y) {
        mirrorX = x != null ? x : mirrorX;
        mirrorY = y != null ? y : mirrorY;
        canvas.setMirror(mirrorX, mirrorY);
    }

    private void selectTool(PixelCanvas.Tool tool) {
        canvas.setTool(tool);
        for (int i = 0; i < TOOLS.length; i++) {
            styleChip(toolChips[i], TOOLS[i] == tool);
        }
    }

    private void edit(Runnable change) {
        change.run();
        changed();
    }

    private void undo() {
        buffer.undo();
        changed();
    }

    private void redo() {
        buffer.redo();
        changed();
    }

    /** After any edit: repaint, refresh the undo buttons and the status line. */
    private void changed() {
        canvas.refresh();
        undoButton.setAlpha(buffer.canUndo() ? 1f : 0.4f);
        redoButton.setAlpha(buffer.canRedo() ? 1f : 0.4f);
        updateStatus();
    }

    private void updateStatus() {
        int x = canvas.hoverX();
        int y = canvas.hoverY();
        String where = buffer.inside(x, y) ? x + ", " + y + "  " + PixelBuffer.hex(buffer.get(x, y)) + "   " : "";
        int[] sel = canvas.selection();
        String selected = sel == null ? "" : "   selected " + (sel[2] - sel[0] + 1) + "x" + (sel[3] - sel[1] + 1);
        say(where + buffer.width() + "x" + buffer.height() + "   zoom " + canvas.scale() + "x"
                + selected + (dirty() ? "   (unsaved)" : ""));
    }

    private void say(String text) {
        status.setText(text);
    }

    private void setColor(int argb, boolean updateHex) {
        color = argb;
        canvas.setColor(argb);
        updating = true;
        int[] c = channelsOf(argb);
        for (int i = 0; i < 4; i++) {
            channels[i].setProgress(c[i]);
        }
        if (updateHex) {
            hexBox.setText(PixelBuffer.hex(argb));
        }
        swatch.setBackground(new ColorDrawable(argb));
        updating = false;
    }

    private static int[] channelsOf(int argb) {
        return new int[]{(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, argb >>> 24};
    }

    private void remember(int argb) {
        recent.remove((Integer) argb);
        recent.add(0, argb);
        while (recent.size() > 24) {
            recent.remove(recent.size() - 1);
        }
        recentRow.removeAllViews();
        for (int c : recent) {
            recentRow.addView(swatchButton(c));
        }
    }

    // ------------------------------------------------------------------ palette

    private void copyPalette() {
        StringBuilder text = new StringBuilder();
        for (int c : buffer.palette(64)) {
            text.append(PixelBuffer.hex(c)).append('\n');
        }
        String out = text.toString();
        IdeHost.get().execute(() -> IdeHost.get().setClipboard(out));
        say(out.isEmpty() ? "No colours to copy." : "Copied " + out.lines().count() + " colours.");
    }

    private void pastePalette() {
        IdeHost.get().execute(() -> {
            String text = IdeHost.get().clipboard();
            root.post(() -> {
                int added = 0;
                for (String token : text.split("[\\s,;]+")) {
                    Integer parsed = PixelBuffer.parseHex(token);
                    if (parsed != null && added < 64) {
                        remember(parsed);
                        added++;
                    }
                }
                say(added == 0 ? "The clipboard holds no hex colours." : "Added " + added + " colours.");
            });
        });
    }

    // ------------------------------------------------------------------ resize / save

    private void resizeDialog() {
        prompt(root, "New size (width x height, 1-" + TextureCodec.MAX_SIDE + ")",
                buffer.width() + "x" + buffer.height(), "Resize", value -> {
                    String[] parts = value.toLowerCase(Locale.ROOT).split("[x, ]+");
                    try {
                        int w = Integer.parseInt(parts[0].trim());
                        int h = Integer.parseInt(parts[1].trim());
                        PixelBuffer next = buffer.resized(w, h, 1, 1);
                        buffer = next;
                        canvas.setBuffer(next);
                        savedVersion = -1; // a different picture: it needs saving
                        changed();
                        say("Resized to " + w + "x" + h + " (the undo history starts over).");
                    } catch (RuntimeException e) {
                        say("Use a size like 32x32 (1 to " + TextureCodec.MAX_SIDE + ").");
                    }
                });
    }

    /** Valid texture target inside the project's assets folder. */
    static boolean validTarget(String target) {
        return net.xmilon.craftpy.client.assets.AssetDrop.validTarget(target, ".png");
    }

    void save() {
        String target = nameBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (!validTarget(target)) {
            say("Name must be like assets/textures/block/ruby.png (a-z, 0-9, _ - . /)");
            return;
        }
        try {
            byte[] png = TextureCodec.encode(buffer);
            final int version = buffer.version();
            say("Saving " + target + " ...");
            // "Saved" only once the server wrote it; a refused upload (size, type, no project) keeps the edits marked unsaved
            IdeHost.get().execute(() -> ClientProjectStore.getInstance().uploadAsset(projectId, target, png,
                    error -> root.post(() -> {
                        if (error != null) {
                            say("Not saved: " + error);
                            return;
                        }
                        path = target;
                        savedVersion = version;
                        say("Saved " + target + " (" + png.length + " bytes)");
                        onSaved.accept(target);
                    })));
        } catch (Exception e) {
            CraftPyLog.LOGGER.warn("Texture save failed", e);
            say("Save failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ keyboard

    private boolean onKey(int keyCode, KeyEvent event) {
        if (root.findFocus() instanceof EditText) {
            return false; // typing in a text box: never trigger tool keys
        }
        if (event.isCtrlPressed()) {
            switch (keyCode) {
                case KeyEvent.KEY_Z -> undo();
                case KeyEvent.KEY_Y -> redo();
                case KeyEvent.KEY_S -> save();
                case KeyEvent.KEY_A -> canvas.selectAll();
                case KeyEvent.KEY_C -> copySelection(false);
                case KeyEvent.KEY_X -> copySelection(true);
                case KeyEvent.KEY_V -> pasteClipboard();
                default -> {
                    return false;
                }
            }
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEY_DELETE -> deleteSelection();
            case KeyEvent.KEY_LEFT_BRACKET -> brush(-1);
            case KeyEvent.KEY_RIGHT_BRACKET -> brush(1);
            case KeyEvent.KEY_B -> selectTool(PixelCanvas.Tool.PENCIL);
            case KeyEvent.KEY_E -> selectTool(PixelCanvas.Tool.ERASER);
            case KeyEvent.KEY_G -> selectTool(PixelCanvas.Tool.FILL);
            case KeyEvent.KEY_I -> selectTool(PixelCanvas.Tool.PICKER);
            case KeyEvent.KEY_L -> selectTool(PixelCanvas.Tool.LINE);
            case KeyEvent.KEY_R -> selectTool(PixelCanvas.Tool.RECT);
            case KeyEvent.KEY_O -> selectTool(PixelCanvas.Tool.ELLIPSE);
            case KeyEvent.KEY_S -> selectTool(PixelCanvas.Tool.SELECT);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void copySelection(boolean cut) {
        int[] sel = canvas.selection();
        if (sel == null) {
            say("Select a box first (Select tool or Ctrl+A).");
            return;
        }
        clipboard = buffer.region(sel[0], sel[1], sel[2], sel[3]);
        if (cut && clipboard != null) {
            edit(() -> buffer.clearRegion(sel[0], sel[1], sel[2], sel[3]));
        }
        say(clipboard == null ? "Nothing selected." : (cut ? "Cut " : "Copied ") + clipboard.width() + "x" + clipboard.height()
                + ". Ctrl+V, then click where it goes.");
    }

    private void pasteClipboard() {
        if (clipboard == null) {
            say("Nothing copied yet.");
            return;
        }
        canvas.startPaste(clipboard);
        say("Click where the picture goes (Esc cancels).");
    }

    private void deleteSelection() {
        int[] sel = canvas.selection();
        if (sel != null) {
            edit(() -> buffer.clearRegion(sel[0], sel[1], sel[2], sel[3]));
        }
    }
}
