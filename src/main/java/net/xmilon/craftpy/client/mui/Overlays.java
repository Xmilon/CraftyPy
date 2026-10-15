package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewParent;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;

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
        private boolean swallowKeyUp;

        Host(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEY_ESCAPE) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && back()) {
                    swallowKeyUp = true;
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_UP && swallowKeyUp) {
                    swallowKeyUp = false;
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }

        /** Esc: closes the topmost dialog, otherwise the newest page handler that wants it. */
        boolean back() {
            for (int i = layers.size() - 1; i >= 0; i--) {
                if (layers.get(i).modal) {
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

    /** One stacked layer. Dialog layers are modal (dim, swallow clicks); floating layers let clicks through. */
    private static final class Layer extends FrameLayout {
        final boolean modal;
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
    }

    @Nullable
    static Host host(View view) {
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
}
