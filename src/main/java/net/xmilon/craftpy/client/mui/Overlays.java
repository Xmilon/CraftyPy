package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.view.MenuItem;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.PointerIcon;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.view.ViewParent;
import icyllis.modernui.view.menu.MenuBuilder;
import icyllis.modernui.view.menu.MenuItemImpl;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.client.MenuNav;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * Every dialog, quick-open palette, prompt and floating hint of the IDE. They are plain views stacked on top of the
 * page inside one {@link Host} instead of separate popup windows, so they can never outlive their screen, can never
 * swallow input when they are placed off screen, and all follow the same rules:
 * <ul>
 *   <li>A dialog has one title bar with one {@code ×}; footers only hold actions.</li>
 *   <li>A click on the dimmed area outside a dialog closes it.</li>
 *   <li>{@code Esc} closes the topmost dialog, then asks the page's back handlers (close search, cancel a rename,
 *       clear a selection...), newest first. Only when nothing used it does the screen itself go back.</li>
 *   <li>Closing a dialog returns the keyboard focus to where it was (the editor).</li>
 * </ul>
 */
final class Overlays {
    private Overlays() {
    }

    /** Root of the screen: the page plus the overlay layers on top of it. */
    static final class Host extends FrameLayout {
        private final List<Layer> layers = new ArrayList<>();
        private final List<BooleanSupplier> backHandlers = new ArrayList<>();
        /** Key code whose release must be swallowed because its press was handled here (Esc, menu keys). */
        private int swallowedUp = -1;

        Host(Context context) {
            super(context);
        }

    @Override
        public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
            int key = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_UP && key == swallowedUp) {
                swallowedUp = -1;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (key == KeyEvent.KEY_ESCAPE ? back() : menuKey(event)) {
                    swallowedUp = key;
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }

        /** An open menu gets the arrow keys, Enter and the like before the focused view (the code area) can move its caret. */
        private boolean menuKey(KeyEvent event) {
            for (int i = layers.size() - 1; i >= 0; i--) {
                Layer layer = layers.get(i);
                if (layer.modal || layer.escapes) {
                    return layer.keyHandler != null && layer.keyHandler.test(event);
                }
            }
            return false;
        }

        /** Esc: closes the topmost dialog, otherwise the newest page handler that wants it. */
        boolean back() {
            for (int i = layers.size() - 1; i >= 0; i--) {
                if (layers.get(i).modal || layers.get(i).escapes) {
                    layers.get(i).dismiss();
                    return true;
                }
            }
            for (int i = backHandlers.size() - 1; i >= 0; i--) {
                if (backHandlers.get(i).getAsBoolean()) {
                    return true;
                }
            }
            return false;
        }

        boolean hasDialog() {
            for (Layer layer : layers) {
                if (layer.modal) {
                    return true;
                }
            }
            return false;
        }

        private void attach(Layer layer) {
            layers.add(layer);
            addView(layer, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        }

        private void detach(Layer layer) {
            layers.remove(layer);
            removeView(layer);
        }
    }

    /**
     * One stacked layer. Dialog layers are modal (dim, swallow clicks); floating layers let clicks through; menu layers
     * are transparent but catch the click that dismisses them, Esc, and close when the pointer leaves the menu.
     */
    private static final class Layer extends FrameLayout {
        final boolean modal;
        /** Menu layers: Esc closes them like a dialog. */
        boolean escapes;
        /** Menu layers: asked with the pointer position in layer coordinates; false closes the menu. */
        @Nullable
        BiPredicate<Float, Float> pointerKeeps;
        /** Menu layers: offered every key press before the focused view; true means it was used. */
        @Nullable
        java.util.function.Predicate<KeyEvent> keyHandler;
        Runnable dismisser = () -> { };

        Layer(Context context, boolean modal) {
            super(context);
            this.modal = modal;
            if (modal) {
                setBackground(new ColorDrawable(0x66000000));
                setClickable(true);
                setOnClickListener(v -> dismiss());
            }
        }

        void dismiss() {
            dismisser.run();
        }

        @Override
        protected boolean dispatchHoverEvent(@NonNull MotionEvent event) {
            if (pointerKeeps != null && event.getAction() != MotionEvent.ACTION_HOVER_EXIT
                    && !pointerKeeps.test(event.getX(), event.getY())) {
                post(this::dismiss); // not now: this dispatch is still walking the layer's children
                return false;
            }
            return super.dispatchHoverEvent(event);
        }

        /**
         * A pointer icon is the first one any child answers, siblings underneath included, so the code area behind a
         * dialog kept the text cursor over the whole dialog. A dialog owns the pointer everywhere; a menu or floating
         * card owns it where it covers something.
         */
        @Override
        public PointerIcon onResolvePointerIcon(@NonNull MotionEvent event) {
            PointerIcon own = super.onResolvePointerIcon(event);
            if (own != null) {
                return own;
            }
            if (modal) {
                return PointerIcon.getSystemIcon(PointerIcon.TYPE_ARROW);
            }
            float x = event.getX();
            float y = event.getY();
            for (int i = getChildCount() - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (child.getVisibility() == VISIBLE && x >= child.getLeft() && x < child.getRight()
                        && y >= child.getTop() && y < child.getBottom()) {
                    return PointerIcon.getSystemIcon(PointerIcon.TYPE_ARROW);
                }
            }
            return null;
        }
    }

    @Nullable
    static Host host(View view) {
        if (view instanceof Host self) {
            return self; // the host itself is a fine anchor
        }
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent instanceof Host host) {
                return host;
            }
            parent = parent.getParent();
        }
        return null;
    }

    /**
     * Registers a page back handler for the Esc key; it returns true when it used the key. Returns the remover, call
     * it when the page goes away.
     */
    static Runnable onBack(View anchor, BooleanSupplier handler) {
        Host host = host(anchor);
        if (host == null) {
            return () -> { };
        }
        host.backHandlers.add(handler);
        return () -> host.backHandlers.remove(handler);
    }

    /** Esc pressed somewhere the host cannot see (a text field with its own key listener). */
    static boolean back(View anchor) {
        Host host = host(anchor);
        return host != null && host.back();
    }

    // ------------------------------------------------------------------
    // Dialog
    // ------------------------------------------------------------------

    /** A titled card in the middle of (or near the top of) the window. Fill {@link #body} then call {@link #show}. */
    static final class Dialog {
        private final Host host;
        private final Layer layer;
        final LinearLayout card;
        /** Vertical container under the title bar. */
        final LinearLayout body;
        private final TextView title;
        private Runnable onDismiss = () -> { };
        private BooleanSupplier guard = () -> true;
        private boolean showing;
        @Nullable
        private View previousFocus;

        private Dialog(Host host, Context context, String titleText, int widthDp, int topDp, boolean fill) {
            this.host = host;
            layer = new Layer(context, true);
            card = card(context);
            var bg = new ShapeDrawable();
            bg.setCornerRadius(card.dp(12));
            bg.setColor(color(context, R.attr.colorSurfaceContainerHigh));
            card.setBackground(bg);
            card.setClickable(true); // clicks on the card must not reach the dismissing scrim
            card.setFocusableInTouchMode(true);
            int pad = card.dp(12);
            card.setPadding(pad, pad, pad, pad);

            var header = row(context);
            title = label(context, titleText, R.attr.textAppearanceTitleMedium);
            header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            var close = button(context, "×", R.attr.borderlessButtonStyle, v -> dismiss());
            close.setTooltipText("Close (Esc)");
            header.addView(close);
            card.addView(header);

            body = new LinearLayout(context);
            body.setOrientation(LinearLayout.VERTICAL);
            card.addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, fill ? 0 : WRAP_CONTENT, fill ? 1 : 0));

            int margin = card.dp(16);
            var lp = new FrameLayout.LayoutParams(fill ? MATCH_PARENT : card.dp(widthDp), fill ? MATCH_PARENT : WRAP_CONTENT,
                    topDp < 0 ? Gravity.CENTER : Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.setMargins(margin, topDp < 0 ? margin : card.dp(topDp), margin, margin);
            layer.addView(card, lp);
            layer.dismisser = this::dismiss;
        }

        Dialog setTitle(String text) {
            title.setText(text);
            return this;
        }

        /** Called once when the dialog closes, whichever way. */
        Dialog onDismiss(Runnable action) {
            onDismiss = action;
            return this;
        }

        /** Asked before ×, Esc or a click outside closes the dialog; false keeps it open (unsaved work). */
        Dialog beforeDismiss(BooleanSupplier allowed) {
            guard = allowed;
            return this;
        }

        void show() {
            if (showing) {
                return;
            }
            showing = true;
            previousFocus = host.findFocus();
            host.attach(layer);
            // Typing must never reach the editor behind the dialog.
            card.requestFocus();
        }

        void dismiss() {
            if (showing && guard.getAsBoolean()) {
                forceDismiss();
            }
        }

        void forceDismiss() {
            if (!showing) {
                return;
            }
            showing = false;
            host.detach(layer);
            View back = previousFocus;
            previousFocus = null;
            if (back != null && back.isAttachedToWindow()) {
                back.requestFocus();
            }
            onDismiss.run();
        }

        boolean isShowing() {
            return showing;
        }
    }

    /**
     * Creates a dialog inside the screen of {@code anchor}. {@code topDp < 0} centres it, otherwise it sits that far
     * from the top; {@code widthDp <= 0} fills the window.
     */
    static Dialog dialog(View anchor, String title, int widthDp, int topDp) {
        Host host = host(anchor);
        if (host == null) {
            throw new IllegalStateException("Anchor is not inside a CraftPy screen");
        }
        return new Dialog(host, anchor.getContext(), title, Math.max(0, widthDp), topDp, widthDp <= 0);
    }

    // ------------------------------------------------------------------
    // Floating (completion list, documentation hover)
    // ------------------------------------------------------------------

    /** A view that floats next to a point in the window without taking the mouse or the focus. */
    static final class Floating {
        private final Host host;
        private final Layer layer;
        private final View content;
        private boolean showing;

        private Floating(Host host, View content) {
            this.host = host;
            this.content = content;
            layer = new Layer(content.getContext(), false);
        }

        /**
         * Shows or moves the view. {@code windowX/windowY} are window coordinates (as from getLocationInWindow) of
         * the point it hangs from, {@code lineHeight} how far above it is when there is no room below.
         */
        void showAt(int windowX, int windowY, int lineHeight) {
            if (!showing) {
                layer.addView(content, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
                host.attach(layer);
                showing = true;
            }
            int[] origin = new int[2];
            host.getLocationInWindow(origin);
            int width = Math.max(1, host.getWidth());
            int height = Math.max(1, host.getHeight());
            content.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            int w = content.getMeasuredWidth();
            int h = content.getMeasuredHeight();
            int x = Math.max(0, Math.min(windowX - origin[0], width - w));
            int y = windowY - origin[1];
            if (y + h > height) {
                y = Math.max(0, y - h - lineHeight);
            }
            var lp = (FrameLayout.LayoutParams) content.getLayoutParams();
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setMargins(x, y, 0, 0);
            content.setLayoutParams(lp);
        }

        void dismiss() {
            if (showing) {
                showing = false;
                layer.removeView(content);
                host.detach(layer);
            }
        }

        boolean isShowing() {
            return showing;
        }
    }

    @Nullable
    static Floating floating(View anchor, View content) {
        Host host = host(anchor);
        return host == null ? null : new Floating(host, content);
    }

    // ------------------------------------------------------------------
    // Menus (toolbar drop-downs, context menus)
    // ------------------------------------------------------------------

    /**
     * A drop-down or context menu drawn inside the screen. Modern UI's {@code PopupMenu} opens a separate popup window
     * that stayed open when the pointer left it; this one closes on a click outside, on Esc, when an item ran, and as
     * soon as the pointer leaves the menu, its open sub-menus and the view it hangs from. Fill {@link #getMenu()} like any
     * menu, then {@link #show()} (under the anchor) or {@link #showAt} (a context menu).
     */
    static final class MenuPopup {
        /** How far the pointer may stray from the menu before it closes, so a corner cut is not punished. */
        private static final int SLOP_DP = 6;

        @Nullable
        private final Host host;
        private final View anchor;
        private final Layer layer;
        private final MenuBuilder menu;
        /** The open cards, the root first; each lies directly in the layer. */
        private final List<View> cards = new ArrayList<>();
        /** The highlighted row of each open card (index into its rows), -1 for none; parallel to {@code cards}. */
        private final List<Integer> cursors = new ArrayList<>();
        /** Layer rectangles (left, top, right, bottom) of the cards, known as soon as {@link #place} ran: view bounds are 0 before layout. */
        private final java.util.Map<View, int[]> bounds = new java.util.IdentityHashMap<>();
        /** Layer coordinates of what the menu hangs from: left, top, right, bottom. */
        private final int[] anchorBox = new int[4];
        @Nullable
        private MenuItem.OnMenuItemClickListener onItem;
        private boolean showing;

        private MenuPopup(@Nullable Host host, View anchor) {
            this.host = host;
            this.anchor = anchor;
            menu = new MenuBuilder(anchor.getContext());
            layer = new Layer(anchor.getContext(), false);
            layer.escapes = true;
            layer.setClickable(true); // the click outside the menu lands here and closes it
            layer.setOnClickListener(v -> dismiss());
            layer.dismisser = this::dismiss;
            layer.pointerKeeps = this::keeps;
            layer.keyHandler = this::onKey;
        }

        MenuBuilder getMenu() {
            return menu;
        }

        /** Runs for every item that has no listener of its own. */
        void setOnMenuItemClickListener(MenuItem.OnMenuItemClickListener listener) {
            onItem = listener;
        }

        /** Opens the menu under the view it was made for. */
        void show() {
            int[] at = new int[2];
            anchor.getLocationInWindow(at);
            open(at[0], at[1], at[0] + anchor.getWidth(), at[1] + anchor.getHeight());
        }

        /** Opens the menu with its corner at a window point. */
        void showAt(float windowX, float windowY) {
            open((int) windowX, (int) windowY, (int) windowX, (int) windowY);
        }

        void dismiss() {
            if (showing && host != null) {
                showing = false;
                cards.clear();
                cursors.clear();
                bounds.clear();
                layer.removeAllViews();
                host.detach(layer);
            }
        }

        private void open(int left, int top, int right, int bottom) {
            if (host == null || showing || !menu.hasVisibleItems()) {
                return;
            }
            int[] origin = new int[2];
            host.getLocationInWindow(origin);
            anchorBox[0] = left - origin[0];
            anchorBox[1] = top - origin[1];
            anchorBox[2] = right - origin[0];
            anchorBox[3] = bottom - origin[1];
            showing = true;
            host.attach(layer);
            if (host.findFocus() == null) { // nothing to receive keys: the layer takes them for the arrow keys and Esc
                layer.setFocusableInTouchMode(true);
                layer.requestFocus();
            }
            View card = buildCard(menu, 0);
            cards.add(card);
            cursors.add(-1);
            // below the anchor, or above it when there is no room
            card.measure(MeasureSpec.makeMeasureSpec(Math.max(1, host.getWidth()), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(Math.max(1, host.getHeight()), MeasureSpec.AT_MOST));
            int y = anchorBox[3];
            if (y + card.getMeasuredHeight() > host.getHeight()) {
                y = anchorBox[1] - card.getMeasuredHeight();
            }
            place(card, anchorBox[0], y);
        }

        /** Puts a card at a layer position, moved back inside the window. */
        private void place(View card, int x, int y) {
            int width = Math.max(1, host.getWidth());
            int height = Math.max(1, host.getHeight());
            card.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            int w = card.getMeasuredWidth();
            int h = card.getMeasuredHeight();
            var lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
            int left = Math.max(0, Math.min(x, width - w));
            int top = Math.max(0, Math.min(y, height - h));
            lp.setMargins(left, top, 0, 0);
            bounds.put(card, new int[]{left, top, left + w, top + h});
            if (card.getParent() == null) {
                layer.addView(card, lp);
            } else {
                card.setLayoutParams(lp);
            }
        }

        private View buildCard(icyllis.modernui.view.Menu source, int depth) {
            Context context = anchor.getContext();
            var items = new LinearLayout(context);
            items.setOrientation(LinearLayout.VERTICAL);
            boolean checks = false;
            for (int i = 0; i < source.size(); i++) {
                MenuItem item = source.getItem(i);
                checks |= item.isVisible() && item.isCheckable();
            }
            for (int i = 0; i < source.size(); i++) {
                MenuItem item = source.getItem(i);
                if (item.isVisible()) {
                    items.addView(itemRow(item, depth, checks), new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                }
            }
            var scroll = new ScrollView(context);
            scroll.addView(items, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            var card = card(context);
            card.setClickable(true); // clicks inside must not reach the dismissing layer
            int pad = card.dp(4);
            card.setPadding(pad, pad, pad, pad);
            card.addView(scroll, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            return card;
        }

        private View itemRow(MenuItem item, int depth, boolean checks) {
            Context context = anchor.getContext();
            var line = row(context);
            line.setPadding(line.dp(10), line.dp(5), line.dp(10), line.dp(5));
            if (checks) {
                var check = label(context, item.isChecked() ? "✓" : "", R.attr.textAppearanceBodyMedium);
                check.setMinWidth(check.dp(18));
                line.addView(check);
            }
            var title = label(context, String.valueOf(item.getTitle()), R.attr.textAppearanceBodyMedium);
            line.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            boolean sub = item.hasSubMenu();
            if (sub) {
                var arrow = label(context, "›", R.attr.textAppearanceBodyMedium);
                arrow.setPadding(arrow.dp(16), 0, 0, 0);
                line.addView(arrow);
            }
            if (!item.isEnabled()) {
                line.setAlpha(0.5f);
                return line;
            }
            line.setTag(item); // the keyboard looks here for sub-menus
            hoverBackground(line);
            line.setClickable(true);
            line.setOnHoverListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                    setCursor(depth, line);
                    line.post(() -> { // not now: the hover dispatch is still walking the layer's children
                        if (showing) {
                            closeBelow(depth + 1);
                            if (sub) {
                                openSub(item.getSubMenu(), line, depth);
                            }
                        }
                    });
                }
                return false;
            });
            line.setOnClickListener(v -> {
                if (sub) {
                    closeBelow(depth + 1);
                    openSub(item.getSubMenu(), line, depth);
                    return;
                }
                dismiss(); // first: the action may open a dialog or another menu
                boolean handled = item instanceof MenuItemImpl impl && impl.invoke();
                if (!handled && onItem != null) {
                    onItem.onMenuItemClick(item);
                }
            });
            return line;
        }

        private void openSub(icyllis.modernui.view.Menu source, View row, int depth) {
            if (cards.size() > depth + 1) {
                return; // already open
            }
            View card = buildCard(source, depth + 1);
            cards.add(card);
            cursors.add(-1);
            View parent = cards.get(depth);
            int[] at = new int[2];
            row.getLocationInWindow(at);
            int[] origin = new int[2];
            host.getLocationInWindow(origin);
            card.measure(MeasureSpec.makeMeasureSpec(Math.max(1, host.getWidth()), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(Math.max(1, host.getHeight()), MeasureSpec.AT_MOST));
            int x = parent.getRight();
            if (x + card.getMeasuredWidth() > host.getWidth()) {
                x = parent.getLeft() - card.getMeasuredWidth();
            }
            place(card, x, at[1] - origin[1] - card.dp(4));
        }

        // ---- keyboard ----

        /** The rows of an open card. */
        private static ViewGroup rowsOf(View card) {
            return (ViewGroup) ((ViewGroup) ((ViewGroup) card).getChildAt(0)).getChildAt(0);
        }

        /** Highlights one row of a card (the one under the pointer or reached by the arrow keys). */
        private void setCursor(int depth, View row) {
            if (depth >= cards.size()) {
                return;
            }
            ViewGroup rows = rowsOf(cards.get(depth));
            int old = cursors.get(depth);
            if (old >= 0 && old < rows.getChildCount()) {
                styleChip(rows.getChildAt(old), false);
            }
            int index = rows.indexOfChild(row);
            cursors.set(depth, index);
            if (index >= 0) {
                styleChip(row, true);
                ((ScrollView) ((ViewGroup) cards.get(depth)).getChildAt(0)).scrollToDescendant(row);
            }
        }

        /** The next row that can be used from {@code from} in direction {@code dir} (wraps), -1 when there is none. */
        private static int step(ViewGroup rows, int from, int dir) {
            boolean[] usable = new boolean[rows.getChildCount()];
            for (int i = 0; i < usable.length; i++) {
                usable[i] = rows.getChildAt(i).isClickable();
            }
            return MenuNav.step(usable, from, dir);
        }

        private void select(int depth, int index) {
            if (index >= 0) {
                setCursor(depth, rowsOf(cards.get(depth)).getChildAt(index));
            }
        }

        /** Arrow keys, Home, End and Enter on the innermost open card; true when the key was used. */
        private boolean onKey(KeyEvent event) {
            if (!showing || cards.isEmpty()) {
                return false;
            }
            int depth = cards.size() - 1;
            ViewGroup rows = rowsOf(cards.get(depth));
            int cursor = cursors.get(depth);
            if (rows.getChildCount() == 0) {
                return false;
            }
            switch (event.getKeyCode()) {
                case KeyEvent.KEY_DOWN -> select(depth, step(rows, cursor, 1));
                case KeyEvent.KEY_UP -> select(depth, step(rows, cursor, -1));
                case KeyEvent.KEY_HOME -> select(depth, step(rows, -1, 1));
                case KeyEvent.KEY_END -> select(depth, step(rows, -1, -1));
                case KeyEvent.KEY_ENTER, KeyEvent.KEY_KP_ENTER -> {
                    if (cursor >= 0) {
                        rows.getChildAt(cursor).performClick();
                    }
                }
                case KeyEvent.KEY_RIGHT -> {
                    if (cursor >= 0 && rows.getChildAt(cursor).getTag() instanceof MenuItem item && item.hasSubMenu()) {
                        rows.getChildAt(cursor).performClick(); // opens the sub-menu
                        if (cards.size() == depth + 2) {
                            select(depth + 1, step(rowsOf(cards.get(depth + 1)), -1, 1));
                        }
                    }
                }
                case KeyEvent.KEY_LEFT -> {
                    if (depth > 0) {
                        closeBelow(depth);
                    }
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        private void closeBelow(int depth) {
            while (cards.size() > depth) {
                View gone = cards.remove(cards.size() - 1);
                cursors.remove(cursors.size() - 1);
                bounds.remove(gone);
                layer.removeView(gone);
            }
        }

        /** True while the pointer (layer coordinates) is over the anchor or a card, give or take a few dp. */
        private boolean keeps(float x, float y) {
            int slop = layer.dp(SLOP_DP);
            if (x >= anchorBox[0] - slop && x < anchorBox[2] + slop && y >= anchorBox[1] - slop && y < anchorBox[3] + slop) {
                return true;
            }
            for (int[] box : bounds.values()) {
                if (x >= box[0] - slop && x < box[2] + slop && y >= box[1] - slop && y < box[3] + slop) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A menu for {@code anchor}; it shows nothing when the anchor is not on a screen with a {@link Host}. */
    static MenuPopup menu(View anchor) {
        return new MenuPopup(host(anchor), anchor);
    }

    /**
     * Right click on {@code view} opens a menu filled by {@code fill} (not called again until the next right click).
     * Replaces {@code setOnCreateContextMenuListener}, whose popup window outlived the pointer.
     */
    static void contextMenu(View view, Consumer<icyllis.modernui.view.Menu> fill) {
        view.setOnTouchListener(contextMenuListener(fill));
    }

    /** The touch listener behind {@link #contextMenu}, for a view that needs to combine it with other touch handling. */
    static View.OnTouchListener contextMenuListener(Consumer<icyllis.modernui.view.Menu> fill) {
        return (v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_DOWN || (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) == 0) {
                return false;
            }
            MenuPopup popup = menu(v);
            fill.accept(popup.getMenu());
            int[] at = new int[2];
            v.getLocationInWindow(at);
            popup.showAt(at[0] + event.getX(), at[1] + event.getY());
            return true;
        };
    }
}
