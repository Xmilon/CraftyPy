package net.xmilon.craftpy.client.mui;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.PixelBuffer;
import net.xmilon.craftpy.client.assets.SoundCodec;
import net.xmilon.craftpy.client.assets.TextureCodec;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The docked panel at the right of the editor with three tabs: resource preview, texture editor and sound editor. It
 * replaces the old dialogs: the files stay visible next to your code. Each tab keeps its state while you switch;
 * opening another file in a tab with unsaved work asks first. UI thread only; files are read on the client thread and
 * handed over when ready (a counter drops answers for a file that was replaced meanwhile).
 */
final class AssetDock {
    static final int PREVIEW = 0;
    static final int TEXTURE = 1;
    static final int SOUND = 2;
    private static final String[] NAMES = {"Preview", "Texture", "Sound"};

    private final Context context;
    private final LinearLayout column;
    private final FrameLayout content;
    private final TextView[] tabs = new TextView[3];
    private final DockPanel[] panels = new DockPanel[3];
    private final Supplier<String> projectId;
    private final Consumer<String> toast;
    private final Runnable onFilesChanged;
    private final Consumer<Boolean> onVisible;
    private final ResourcePreviewPanel preview;
    private int current = -1;
    private int ticket;

    AssetDock(Context context, Supplier<String> projectId, Consumer<String> toast, Runnable onFilesChanged, Consumer<Boolean> onVisible) {
        this.context = context;
        this.projectId = projectId;
        this.toast = toast;
        this.onFilesChanged = onFilesChanged;
        this.onVisible = onVisible;
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackground(new ColorDrawable(color(context, R.attr.colorSurfaceContainerLow)));
        column.setPadding(column.dp(6), column.dp(4), column.dp(6), column.dp(4));

        var strip = row(context);
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i] = label(context, NAMES[i], R.attr.textAppearanceLabelLarge);
            tabs[i].setPadding(tabs[i].dp(12), tabs[i].dp(6), tabs[i].dp(12), tabs[i].dp(6));
            tabs[i].setClickable(true);
            tabs[i].setOnClickListener(v -> show(index));
            strip.addView(tabs[i]);
        }
        var spacer = new View(context);
        strip.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        var close = label(context, "×", R.attr.textAppearanceTitleSmall);
        close.setPadding(close.dp(10), close.dp(2), close.dp(10), close.dp(2));
        close.setClickable(true);
        close.setTooltipText("Close the dock (your edits stay until you open another file)");
        hoverBackground(close);
        close.setOnClickListener(v -> setVisible(false));
        strip.addView(close);
        column.addView(strip);

        content = new FrameLayout(context);
        column.addView(content, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        preview = new ResourcePreviewPanel(context);
        panels[PREVIEW] = preview;
        show(PREVIEW);
        setVisible(false);
    }

    View view() {
        return column;
    }

    boolean visible() {
        return column.getVisibility() == View.VISIBLE;
    }

    void setVisible(boolean visible) {
        column.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible && panels[SOUND] instanceof SoundEditorPanel sound) {
            sound.stop(); // a closed dock must not keep playing
        }
        onVisible.accept(visible);
    }

    int tab() {
        return current;
    }

    void show(int tab) {
        if (tab < 0 || tab > 2) {
            return;
        }
        current = tab;
        content.removeAllViews();
        DockPanel panel = panels[tab];
        if (panel != null) {
            content.addView(panel.view(), new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        } else {
            var empty = label(context, tab == TEXTURE
                    ? "No texture open.\nDouble click a .png in the explorer, or use Tools > New texture."
                    : "No sound open.\nDouble click an .ogg or .wav in the explorer.", R.attr.textAppearanceBodyMedium);
            empty.setPadding(empty.dp(12), empty.dp(16), empty.dp(12), empty.dp(8));
            empty.setGravity(Gravity.START);
            content.addView(empty, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
        for (int i = 0; i < tabs.length; i++) {
            styleChip(tabs[i], i == tab);
        }
        setVisible(true);
    }

    /** Esc: lets the open panel use it first. */
    boolean back() {
        DockPanel panel = current >= 0 ? panels[current] : null;
        return visible() && panel != null && panel.back();
    }

    /** True when any tab holds unsaved work. */
    boolean dirty() {
        for (DockPanel panel : panels) {
            if (panel != null && panel.dirty()) {
                return true;
            }
        }
        return false;
    }

    /** Stops playback and drops the panels (the editor is closing). */
    void dispose() {
        ticket++;
        if (panels[SOUND] instanceof SoundEditorPanel sound) {
            sound.dispose();
        }
    }

    // ------------------------------------------------------------------ opening things

    /** Opens a project file in the right tab; text files that are models or block states go to the preview. */
    void open(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            openBytes(path, TEXTURE);
        } else if (lower.endsWith(".ogg") || lower.endsWith(".wav")) {
            openBytes(path, SOUND);
        } else {
            toast.accept("Nothing to show for " + path + " here");
        }
    }

    void newTexture(int size) {
        guard(TEXTURE, () -> {
            String id = projectId.get();
            if (id == null) {
                toast.accept("Open a project first");
                return;
            }
            setPanel(TEXTURE, new TextureEditorPanel(context, id, "assets/textures/new_texture.png", new PixelBuffer(size, size),
                    saved -> onFilesChanged.run()));
        });
    }

    /** Shows the JSON text of a model, block state or item as a picture. */
    void previewModel(String name, String json) {
        preview.showModel(name, json);
        if (current != PREVIEW || !visible()) {
            show(PREVIEW);
        }
    }

    private void openBytes(String path, int tab) {
        String id = projectId.get();
        if (id == null) {
            toast.accept("Open a project first");
            return;
        }
        guard(tab, () -> {
            final int mine = ++ticket;
            ClientProjectStore.getInstance(); // fail early outside the game
            Minecraft.getInstance().execute(() -> ClientProjectStore.getInstance().requestAsset(id, path, data -> {
                // runs on the client thread: decode here, hand the result to the UI thread
                Runnable result;
                if (data.error() != null && !data.error().isEmpty()) {
                    result = () -> fail(path, data.error());
                } else if (tab == TEXTURE) {
                    try {
                        PixelBuffer buffer = TextureCodec.decode(data.data());
                        result = () -> {
                            setPanel(TEXTURE, new TextureEditorPanel(context, id, path, buffer, saved -> onFilesChanged.run()));
                            preview.showTexture(path, new PixelBuffer(buffer.width(), buffer.height(), buffer.copy()));
                        };
                    } catch (RuntimeException e) {
                        result = () -> fail(path, e.getMessage());
                    }
                } else {
                    try {
                        var clip = SoundCodec.decode(path, data.data());
                        result = () -> setPanel(SOUND, new SoundEditorPanel(context, id, path, clip, saved -> onFilesChanged.run()));
                    } catch (RuntimeException e) {
                        result = () -> fail(path, e.getMessage());
                    }
                }
                final Runnable run = result;
                column.post(() -> {
                    if (mine == ticket) {
                        run.run();
                    }
                });
            }));
        });
    }

    private void fail(String path, String message) {
        preview.showMessage(path, "Could not open " + path + ": " + message);
        show(PREVIEW);
    }

    private void setPanel(int tab, DockPanel panel) {
        if (panels[tab] instanceof SoundEditorPanel old) {
            old.dispose();
        }
        panels[tab] = panel;
        show(tab);
    }

    /** Runs {@code proceed} at once, or after the player agreed to drop unsaved work in that tab. */
    private void guard(int tab, Runnable proceed) {
        DockPanel panel = panels[tab];
        if (panel == null || !panel.dirty()) {
            proceed.run();
            return;
        }
        show(tab);
        var dialog = Overlays.dialog(column, "Unsaved changes", 380, -1);
        dialog.body.addView(label(context, "The " + NAMES[tab].toLowerCase(Locale.ROOT)
                + " you are editing has changes that are not saved. Open the other file and lose them?", R.attr.textAppearanceBodyMedium));
        var buttons = row(context);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(button(context, "Keep editing", R.attr.borderlessButtonStyle, v -> dialog.dismiss()));
        buttons.addView(button(context, "Discard and open", R.attr.buttonTonalStyle, v -> {
            dialog.dismiss();
            proceed.run();
        }));
        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.topMargin = column.dp(10);
        dialog.body.addView(buttons, lp);
        dialog.show();
    }

    static boolean isAsset(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".ogg") || lower.endsWith(".wav");
    }
}
