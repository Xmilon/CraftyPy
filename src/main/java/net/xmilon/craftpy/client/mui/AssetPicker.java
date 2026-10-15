package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.client.assets.AssetIndex;
import net.xmilon.craftpy.client.assets.SoundPreview;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The asset bank: every texture, item, block, model and sound the game knows (vanilla, mods, your projects), with
 * a search box. {@code #tag} lists a tag, {@code @namespace} one namespace, a plain word searches the ids.
 * Click a name to pick it; sounds have a play button. The index is read on the client thread, the list is drawn
 * on the UI thread.
 */
final class AssetPicker {
    private static final int MAX_ROWS = 200;
    private static final AssetKind[] KINDS = {AssetKind.TEXTURE, AssetKind.ITEM, AssetKind.BLOCK, AssetKind.MODEL, AssetKind.SOUND};
    private static final String[] LABELS = {"Textures", "Items", "Blocks", "Models", "Sounds"};

    private final Context context;
    private final Overlays.Dialog dialog;
    private LinearLayout list;
    private TextView status;
    private final EditText search;
    private final View[] chips = new View[KINDS.length];
    private final Consumer<String> onPick;
    private AssetKind kind;
    private int generation;

    private AssetPicker(View anchor, AssetKind first, Consumer<String> onPick) {
        this.context = anchor.getContext();
        this.onPick = onPick;
        this.kind = first;
        dialog = Overlays.dialog(anchor, "Asset bank", 640, 40);
        dialog.onDismiss(SoundPreview::stop);
        var body = dialog.body;

        var chipRow = row(context);
        for (int i = 0; i < KINDS.length; i++) {
            final AssetKind chipKind = KINDS[i];
            chips[i] = button(context, LABELS[i], R.attr.borderlessButtonStyle, v -> {
                kind = chipKind;
                styleChips();
                refresh();
            });
            chipRow.addView(chips[i]);
        }
        body.addView(chipRow);

        search = new EditText(context, null, R.attr.editTextOutlinedStyle);
        search.setSingleLine();
        search.setHint("Search ids   (#tag   @namespace)   Enter picks the first");
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refresh();
            }
        });
        search.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEY_ENTER || keyCode == KeyEvent.KEY_KP_ENTER)) {
                if (list.getChildCount() > 0 && list.getChildAt(0).getTag() instanceof String id) {
                    pick(id);
                }
                return true;
            }
            return false;
        });
        var searchLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        searchLp.topMargin = body.dp(6);
        body.addView(search, searchLp);

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        var scroll = new ScrollView(context);
        scroll.addView(list, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        int windowHeight = anchor.getRootView().getHeight();
        int height = windowHeight > 0 ? Math.max(body.dp(120), Math.min(body.dp(340), windowHeight - body.dp(250))) : body.dp(300);
        var scrollLp = new LinearLayout.LayoutParams(MATCH_PARENT, height);
        scrollLp.topMargin = body.dp(6);
        body.addView(scroll, scrollLp);

        status = label(context, "", R.attr.textAppearanceLabelMedium);
        status.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        body.addView(status);
        styleChips();
    }

    /** Opens the bank; {@code onPick} gets the chosen resource id (the dialog closes). */
    static void open(View anchor, AssetKind first, Consumer<String> onPick) {
        // The index is built from the resource manager: client thread first, then show.
        Minecraft.getInstance().execute(() -> {
            AssetIndex.get().ensureBuilt();
            anchor.post(() -> {
                var picker = new AssetPicker(anchor, first, onPick);
                picker.dialog.show();
                picker.search.requestFocus();
                picker.refresh();
            });
        });
    }

    private void styleChips() {
        for (int i = 0; i < KINDS.length; i++) {
            styleChip(chips[i], KINDS[i] == kind);
        }
    }

    private void refresh() {
        final int mine = ++generation;
        final AssetKind wanted = kind;
        final String query = search.getText().toString().trim();
        Minecraft.getInstance().execute(() -> {
            List<String> found;
            if (query.startsWith("#")) {
                found = AssetIndex.get().byTag(wanted, query.substring(1));
            } else if (query.startsWith("@")) {
                found = AssetIndex.get().filter(wanted, "", query.substring(1).trim());
            } else {
                found = AssetIndex.get().filter(wanted, query, "");
            }
            List<String> shown = new ArrayList<>(found.subList(0, Math.min(found.size(), MAX_ROWS)));
            int total = found.size();
            list.post(() -> {
                if (mine == generation) {
                    render(shown, total);
                }
            });
        });
    }

    private void render(List<String> ids, int total) {
        list.removeAllViews();
        for (String id : ids) {
            var line = row(context);
            line.setTag(id);
            line.setClickable(true);
            line.setPadding(line.dp(8), line.dp(4), line.dp(8), line.dp(4));
            hoverBackground(line);
            var name = label(context, id, R.attr.textAppearanceBodyMedium);
            name.setSingleLine();
            line.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            if (kind == AssetKind.SOUND) {
                var play = button(context, "▶", R.attr.borderlessButtonStyle, v -> play(id));
                line.addView(play);
            }
            line.setOnClickListener(v -> pick(id));
            list.addView(line);
        }
        status.setText(total == 0 ? "Nothing found." : total > ids.size()
                ? "Showing the first " + ids.size() + " of " + total + " - type to narrow it down."
                : total + " found. Click to pick.");
    }

    private void play(String id) {
        Minecraft.getInstance().execute(() -> {
            try {
                SoundPreview.play(id, 1f, 1f);
            } catch (RuntimeException e) {
                list.post(() -> status.setText("Could not play " + id + ": " + e.getMessage()));
            }
        });
    }

    private void pick(String id) {
        dialog.dismiss();
        onPick.accept(id);
    }
}
