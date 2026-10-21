package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextUtils;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.HorizontalScrollView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.HubApps;
import net.xmilon.craftpy.project.ModuleApp;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/** The Apps tab: what the enabled, loaded modules offer, as tiles you can search, filter and drive from the keyboard. */
public final class HubAppsFragment extends Fragment {
    private static final String ALL = "All";

    private List<HubApps.App> apps = List.of();
    private String filter = "";
    private String category = ALL;
    private TileGrid grid;
    private LinearLayout chips;
    private LinearLayout empty;
    private TextView count;
    private EditText search;
    private ScrollView scroll;
    /** Modern UI never leaves touch mode, so tiles cannot take focus: the grid does, and it tracks the tile it points at. */
    private String selectedKey = "";
    private List<HubApps.App> shown = List.of();

    /** Client thread: the apps of the moment (the store's module list is mutable, so only immutable values leave). */
    private final Runnable storeListener = () -> {
        List<HubApps.App> snapshot = HubApps.snapshot(ClientProjectStore.getInstance().modules());
        View view = grid;
        if (view != null) {
            view.post(() -> {
                if (grid == view) {
                    apps = snapshot;
                    render();
                }
            });
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        Context context = requireContext();
        var page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = page.dp(14);
        page.setPadding(pad, pad, pad, pad);

        search = CraftPyFragment.field(context);
        search.setSingleLine();
        search.setHint("Search apps by name, description or module");
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }
        });
        // Enter opens the first app that can be opened; Down moves into the tiles
        search.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEY_ENTER || keyCode == KeyEvent.KEY_KP_ENTER) {
                visible().stream().filter(HubApps.App::available).findFirst().ifPresent(this::open);
                return true;
            }
            if (keyCode == KeyEvent.KEY_DOWN && grid != null && grid.getChildCount() > 0) {
                grid.requestFocus();
                applySelection();
                return true;
            }
            return false;
        });
        page.addView(search, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        chips = row(context);
        var chipScroll = new HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips, new HorizontalScrollView.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        var chipLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        chipLp.topMargin = page.dp(8);
        page.addView(chipScroll, chipLp);

        grid = new TileGrid(context);
        grid.setFocusable(true);
        grid.setFocusableInTouchMode(true);
        grid.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN && gridKey(keyCode));
        grid.setOnFocusChangeListener((v, focused) -> applySelection());
        empty = new LinearLayout(context);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        var body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(grid, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        body.addView(empty, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        scroll = new ScrollView(context);
        scroll.addView(body, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        var scrollLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
        scrollLp.topMargin = page.dp(10);
        page.addView(scroll, scrollLp);

        var footer = row(context);
        count = HubFragment.muted(context, "");
        footer.addView(count, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        footer.addView(button(context, "Manage modules", R.attr.borderlessButtonStyle, v -> toModules()));
        page.addView(footer, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        render();
        grid.post(() -> {
            if (grid != null && search != null && !search.hasFocus()) {
                grid.requestFocus(); // the arrow keys and Enter work as soon as the Hub is open
            }
        });
        store(s -> {
            s.setListener(storeListener);
            storeListener.run();
            s.refreshModules();
        });
        return page;
    }

    @Override
    public void onDestroyView() {
        store(s -> s.clearListener(storeListener));
        grid = null;
        chips = null;
        empty = null;
        count = null;
        search = null;
        scroll = null;
        super.onDestroyView();
    }

    private void toModules() {
        HubFragment hub = HubFragment.of(this);
        if (hub != null) {
            hub.switchTab(HubFragment.TAB_MODULES);
        }
    }

    private void open(HubApps.App app) {
        if (app.available()) {
            store(s -> HubApps.open(app));
        } else {
            toModules(); // the reason says what to do there (RELOAD, an error, a missing mod)
        }
    }

    private List<HubApps.App> visible() {
        List<HubApps.App> out = new ArrayList<>();
        for (HubApps.App app : apps) {
            if (!category.equals(ALL) && !app.def().category.equals(category)) {
                continue;
            }
            String haystack = (app.def().name + " " + app.def().description + " " + app.ownerName() + " " + app.owner())
                    .toLowerCase(Locale.ROOT);
            if (filter.isEmpty() || haystack.contains(filter)) {
                out.add(app);
            }
        }
        return out;
    }

    private void render() {
        if (grid == null) {
            return;
        }
        Context context = grid.getContext();
        if (!category.equals(ALL) && apps.stream().noneMatch(a -> a.def().category.equals(category))) {
            category = ALL; // the last app of a category went away
        }
        renderChips(context);
        grid.removeAllViews();
        shown = visible();
        for (HubApps.App app : shown) {
            grid.addView(tile(context, app));
        }
        applySelection(); // a refresh keeps the tile the keyboard points at
        renderEmpty(context, shown.isEmpty());
        long available = shown.stream().filter(HubApps.App::available).count();
        count.setText(shown.size() + (shown.size() == 1 ? " app" : " apps")
                + (available == shown.size() ? "" : ", " + (shown.size() - available) + " unavailable"));
    }

    private void renderChips(Context context) {
        chips.removeAllViews();
        List<String> names = new ArrayList<>(List.of(ALL));
        for (String name : ModuleApp.CATEGORIES) {
            if (apps.stream().anyMatch(a -> a.def().category.equals(name))) {
                names.add(name);
            }
        }
        for (String name : names) {
            long n = name.equals(ALL) ? apps.size() : apps.stream().filter(a -> a.def().category.equals(name)).count();
            Button chip = button(context, name + "  " + n, R.attr.borderlessButtonStyle, v -> {
                category = name;
                render();
            });
            styleChip(chip, name.equals(category));
            chips.addView(chip);
        }
    }

    private void renderEmpty(Context context, boolean none) {
        empty.removeAllViews();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (!none) {
            return;
        }
        int pad = empty.dp(28);
        empty.setPadding(pad, pad, pad, pad);
        if (apps.isEmpty()) {
            empty.addView(label(context, "No apps yet", R.attr.textAppearanceTitleMedium));
            var hint = HubFragment.muted(context, "Enable a module that adds apps, then press RELOAD. A project adds its own "
                    + "with \"apps\" in project.json.");
            hint.setGravity(Gravity.CENTER);
            empty.addView(hint);
            empty.addView(button(context, "Open Modules", R.attr.buttonTonalStyle, v -> toModules()));
        } else {
            var text = HubFragment.muted(context, filter.isEmpty() ? "No apps in this category."
                    : "No app matches \"" + filter + "\".");
            text.setGravity(Gravity.CENTER);
            empty.addView(text);
        }
    }

    // ------------------------------------------------------------------
    // One tile
    // ------------------------------------------------------------------

    private View tile(Context context, HubApps.App app) {
        var tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setTag(app.key());
        tile.setBackground(IdeColors.tile(tile));
        tile.setPadding(tile.dp(12), tile.dp(10), tile.dp(12), tile.dp(10));
        tile.setClickable(true);
        tile.setAlpha(app.available() ? 1f : 0.6f);
        tile.setTooltipText(app.available() ? app.def().name + " (" + app.ownerName() + ")"
                : app.reason() + "\nClick to open Modules.");
        tile.setOnClickListener(v -> open(app));

        var top = row(context);
        top.addView(HubIcons.icon(context, app.owner(), app.def().name, app.icon(), 48));
        var names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        var name = label(context, app.def().name, R.attr.textAppearanceTitleSmall);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name);
        var owner = label(context, app.ownerName() + (app.server() ? " · server" : ""), R.attr.textAppearanceBodySmall);
        owner.setTextColor(IdeColors.TEXT_SECONDARY);
        owner.setSingleLine();
        owner.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(owner);
        var namesLp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        namesLp.setMarginStart(tile.dp(10));
        top.addView(names, namesLp);
        tile.addView(top, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var description = label(context, app.def().description.isEmpty() ? " " : app.def().description,
                R.attr.textAppearanceBodySmall);
        description.setTextColor(IdeColors.TEXT_SECONDARY);
        description.setMaxLines(2);
        description.setEllipsize(TextUtils.TruncateAt.END);
        var descriptionLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        descriptionLp.topMargin = tile.dp(8);
        tile.addView(description, descriptionLp);

        if (!app.available() || app.permissionPending()) {
            var badge = tag(context, app.available() ? "Permissions pending" : "Unavailable",
                    app.available() ? IdeColors.WARNING : IdeColors.ERROR);
            if (app.permissionPending()) {
                badge.setTooltipText("This module asks for permissions that are not approved yet. Click to review them in Modules.");
                badge.setClickable(true);
                badge.setOnClickListener(v -> toModules());
            }
            var badgeLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            badgeLp.topMargin = tile.dp(6);
            tile.addView(badge, badgeLp);
            if (!app.available()) {
                var reason = label(context, app.reason(), R.attr.textAppearanceBodySmall);
                reason.setTextColor(IdeColors.ERROR);
                reason.setMaxLines(2);
                reason.setEllipsize(TextUtils.TruncateAt.END);
                tile.addView(reason, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            }
        }
        return tile;
    }

    /** Marks the tile the keyboard points at (only while the grid has the focus); the first one when none was chosen. */
    private void applySelection() {
        if (grid == null) {
            return;
        }
        boolean focused = grid.hasFocus();
        int index = indexOf(selectedKey);
        if (focused && index < 0 && !shown.isEmpty()) {
            index = 0; // nothing chosen yet, or the chosen app left the list
            selectedKey = shown.get(0).key();
        }
        for (int i = 0; i < grid.getChildCount(); i++) {
            grid.getChildAt(i).setSelected(focused && i == index);
        }
    }

    private int indexOf(String key) {
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** Arrow keys move between tiles, Home and End jump, Enter and Space open the one pointed at. */
    private boolean gridKey(int keyCode) {
        if (grid == null || shown.isEmpty()) {
            return false;
        }
        int index = Math.max(0, indexOf(selectedKey)), columns = grid.columns(), last = shown.size() - 1;
        int target = switch (keyCode) {
            case KeyEvent.KEY_LEFT -> index - 1;
            case KeyEvent.KEY_RIGHT -> index + 1;
            case KeyEvent.KEY_UP -> index - columns;
            case KeyEvent.KEY_DOWN -> index + columns;
            case KeyEvent.KEY_HOME -> 0;
            case KeyEvent.KEY_END -> last;
            case KeyEvent.KEY_ENTER, KeyEvent.KEY_KP_ENTER, KeyEvent.KEY_SPACE -> {
                open(shown.get(index));
                yield index;
            }
            default -> -2;
        };
        if (target == -2) {
            return false;
        }
        if (target < 0 && keyCode == KeyEvent.KEY_UP && search != null) {
            search.requestFocus(); // up from the first row goes back to the search field
            applySelection();
            return true;
        }
        if (target >= 0 && target <= last && target != index) {
            selectedKey = shown.get(target).key();
            applySelection();
            grid.getChildAt(target).requestRectangleOnScreen(new icyllis.modernui.graphics.Rect(0, 0,
                    grid.getChildAt(target).getWidth(), grid.getChildAt(target).getHeight()), false);
        }
        return true;
    }

    /** Tiles of one width in rows, as many columns as fit; every tile of a row as high as the highest. */
    private static final class TileGrid extends FrameLayout {
        private static final int MIN_TILE = 200;
        private static final int GAP = 10;
        private int columns = 1;

        TileGrid(Context context) {
            super(context);
        }

        int columns() {
            return columns;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = Math.max(0, MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight());
            int gap = dp(GAP);
            columns = Math.max(1, (width + gap) / (dp(MIN_TILE) + gap));
            int tileWidth = Math.max(1, (width - (columns - 1) * gap) / columns);
            int total = 0;
            for (int start = 0; start < getChildCount(); start += columns) {
                int rowHeight = 0;
                int end = Math.min(getChildCount(), start + columns);
                for (int i = start; i < end; i++) {
                    View child = getChildAt(i);
                    child.measure(MeasureSpec.makeMeasureSpec(tileWidth, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
                }
                for (int i = start; i < end; i++) {
                    getChildAt(i).measure(MeasureSpec.makeMeasureSpec(tileWidth, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
                }
                total += rowHeight + (start + columns < getChildCount() ? gap : 0);
            }
            setMeasuredDimension(resolveSize(width + getPaddingLeft() + getPaddingRight(), widthMeasureSpec),
                    resolveSize(total + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int gap = dp(GAP);
            int y = getPaddingTop();
            for (int start = 0; start < getChildCount(); start += columns) {
                int rowHeight = 0;
                int x = getPaddingLeft();
                int end = Math.min(getChildCount(), start + columns);
                for (int i = start; i < end; i++) {
                    View child = getChildAt(i);
                    child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
                    x += child.getMeasuredWidth() + gap;
                    rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
                }
                y += rowHeight + gap;
            }
        }
    }
}
