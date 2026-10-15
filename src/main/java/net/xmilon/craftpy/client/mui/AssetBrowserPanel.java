package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.client.assets.AssetDrop;
import net.xmilon.craftpy.client.assets.AssetIndex;
import net.xmilon.craftpy.client.assets.SoundPreview;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The "Assets" tab of the dock: every texture, item, block, model and sound the game knows (vanilla, mods, your projects)
 * with a search box. {@code #tag} lists a tag, {@code @namespace} one namespace, a plain word searches the ids.
 * A click opens the asset in the dock (picture, texture editor, sound editor, 3D model); dragging it onto the code
 * inserts it, dragging it onto a folder of the explorer copies the file there. The index is read on the client thread,
 * the list is drawn on the UI thread.
 */
final class AssetBrowserPanel implements DockPanel {
    /** What the editor does with the asset the player clicked or dropped. */
    interface Actions {
        void inspect(AssetKind kind, String id);

        /** The pointer was released at a window position after a drag. */
        void drop(AssetKind kind, String id, float windowX, float windowY);
    }

    private static final int MAX_ROWS = 200;
    private static final AssetKind[] KINDS = {AssetKind.TEXTURE, AssetKind.ITEM, AssetKind.BLOCK, AssetKind.MODEL, AssetKind.SOUND};
    private static final String[] LABELS = {"Textures", "Items", "Blocks", "Models", "Sounds"};

    private final Context context;
    private final Actions actions;
    private final LinearLayout root;
    private final LinearLayout list;
    private final TextView status;
    private final EditText search;
    private final View[] chips = new View[KINDS.length];
    private AssetKind kind = AssetKind.TEXTURE;
    private int generation;
    private boolean started;

    AssetBrowserPanel(Context context, Actions actions) {
        this.context = context;
        this.actions = actions;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        var chipRow = row(context);
        for (int i = 0; i < KINDS.length; i++) {
            final AssetKind chipKind = KINDS[i];
            chips[i] = label(context, LABELS[i], R.attr.textAppearanceLabelLarge);
            chips[i].setPadding(chips[i].dp(8), chips[i].dp(5), chips[i].dp(8), chips[i].dp(5));
            chips[i].setClickable(true);
            chips[i].setOnClickListener(v -> {
                kind = chipKind;
                styleChips();
                refresh();
            });
            chipRow.addView(chips[i]);
        }
        var chipScroll = new icyllis.modernui.widget.HorizontalScrollView(context);
        chipScroll.addView(chipRow);
        root.addView(chipScroll);

        search = new EditText(context, null, R.attr.editTextOutlinedStyle);
        search.setSingleLine();
        search.setHint("Search ids   (#tag   @namespace)");
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
                openFirst();
                return true;
            }
            return false;
        });
        var searchLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        searchLp.topMargin = root.dp(4);
        root.addView(search, searchLp);

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        var scroll = new ScrollView(context);
        scroll.addView(list, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        var scrollLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
        scrollLp.topMargin = root.dp(4);
        root.addView(scroll, scrollLp);

        status = label(context, "", R.attr.textAppearanceLabelMedium);
        status.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        root.addView(status);
        styleChips();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public boolean dirty() {
        return false;
    }

    /** Esc clears the search first. */
    @Override
    public boolean back() {
        if (search.getText().length() > 0) {
            search.setText("");
            return true;
        }
        return false;
    }

    private void openFirst() {
        if (list.getChildCount() > 0 && list.getChildAt(0).getTag() instanceof String id) {
            actions.inspect(kind, id);
        }
    }

    /** The tab was opened: the first time, read the index. */
    void activate() {
        if (!started) {
            started = true;
            refresh();
        }
    }

    void focusSearch() {
        search.requestFocus();
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
            AssetIndex index = AssetIndex.get();
            index.ensureBuilt(); // starts a background build the first time
            List<String> found;
            if (query.startsWith("#")) {
                found = index.byTag(wanted, query.substring(1));
            } else if (query.startsWith("@")) {
                found = index.filter(wanted, "", query.substring(1).trim());
            } else {
                found = index.filter(wanted, query, "");
            }
            List<String> shown = new ArrayList<>(found.subList(0, Math.min(found.size(), MAX_ROWS)));
            int total = found.size();
            boolean building = !index.isBuilt();
            list.post(() -> {
                if (mine != generation) {
                    return;
                }
                render(wanted, shown, total, building);
                if (building) {
                    list.postDelayed(() -> {
                        if (mine == generation) {
                            refresh();
                        }
                    }, 500);
                }
            });
        });
    }

    private void render(AssetKind rowKind, List<String> ids, int total, boolean building) {
        list.removeAllViews();
        for (String id : ids) {
            var line = row(context);
            line.setTag(id);
            line.setClickable(true);
            line.setPadding(line.dp(8), line.dp(4), line.dp(8), line.dp(4));
            hoverBackground(line);
            line.setTooltipText("Click: open in the dock. Drag onto the code to insert it"
                    + (AssetDrop.isFile(rowKind) ? ", onto an explorer folder to copy the file." : "."));
            var name = label(context, id, R.attr.textAppearanceBodyMedium);
            name.setSingleLine();
            line.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            if (rowKind == AssetKind.SOUND) {
                var play = button(context, "▶", R.attr.borderlessButtonStyle, v -> play(id));
                line.addView(play);
            }
            line.setOnClickListener(v -> actions.inspect(rowKind, id));
            line.setOnTouchListener(new Drag(rowKind, id));
            list.addView(line);
        }
        status.setText(building ? "Building the asset index..." : total == 0 ? "Nothing found." : total > ids.size()
                ? "Showing the first " + ids.size() + " of " + total + " - type to narrow it down."
                : total + " found.");
    }

    private void play(String id) {
        Minecraft.getInstance().execute(() -> {
            try {
                if (!SoundPreview.play(id, 1f, 1f)) {
                    list.post(() -> status.setText("Cannot play " + id + ": not a valid sound id"));
                }
            } catch (RuntimeException e) {
                list.post(() -> status.setText("Could not play " + id + ": " + e.getMessage()));
            }
        });
    }

    /**
     * Press, move more than a few pixels, release: a drag. Until the pointer has moved the row behaves as a normal button
     * (the click opens the asset); once it moves, a chip with the id follows the pointer and the release is a drop.
     */
    private final class Drag implements View.OnTouchListener {
        private final AssetKind rowKind;
        private final String id;
        private float downX;
        private float downY;
        private boolean dragging;
        private boolean cancelled;
        private Overlays.Floating ghost;
        private Runnable removeBack = () -> { };

        Drag(AssetKind rowKind, String id) {
            this.rowKind = rowKind;
            this.id = id;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int[] at = new int[2];
            v.getLocationInWindow(at);
            float x = at[0] + event.getX();
            float y = at[1] + event.getY();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> {
                    downX = x;
                    downY = y;
                    dragging = false;
                    cancelled = false;
                    // the list scrolls with the wheel; a drag must not turn into a scroll of the list
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (!dragging) {
                        if (Math.hypot(x - downX, y - downY) < v.dp(8)) {
                            return false;
                        }
                        dragging = true;
                        v.setPressed(false); // the row never sees the release
                        ghost = Overlays.floating(v, ghostView());
                        removeBack = Overlays.onBack(v, () -> { // Esc drops the drag instead of the asset
                            cancelled = true;
                            endGhost();
                            return true;
                        });
                    }
                    if (ghost != null && !cancelled) {
                        ghost.showAt((int) x + v.dp(12), (int) y + v.dp(12), 0);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        return false;
                    }
                    dragging = false;
                    endGhost();
                    removeBack.run();
                    removeBack = () -> { };
                    if (event.getAction() == MotionEvent.ACTION_UP && !cancelled) {
                        actions.drop(rowKind, id, x, y);
                    }
                    return true;
                }
                default -> {
                    return dragging;
                }
            }
        }

        private void endGhost() {
            if (ghost != null) {
                ghost.dismiss();
                ghost = null;
            }
        }

        private View ghostView() {
            var chip = label(context, id, R.attr.textAppearanceBodyMedium);
            chip.setPadding(chip.dp(10), chip.dp(5), chip.dp(10), chip.dp(5));
            var bg = new ShapeDrawable();
            bg.setCornerRadius(chip.dp(8));
            bg.setColor(color(context, R.attr.colorSecondaryContainer));
            chip.setBackground(bg);
            return chip;
        }
    }
}
