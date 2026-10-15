package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import icyllis.modernui.R;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * A floating list used by symbol search, jump history, reports and previews: a title, an optional filter
 * box, rows (text + muted detail, optionally clickable) and footer buttons. Rows can be replaced while the
 * dialog is open, because most reports arrive from the server a moment after it is shown. UI thread only.
 */
final class ListDialog {
    /**
     * @param onClick  null for plain information rows
     * @param color    a theme attribute name, or null
     * @param keepOpen the dialog stays open after the click (toggles)
     * @param custom   builds the row content instead of text/detail (recipe cards)
     */
    record Row(String text, String detail, @Nullable Runnable onClick, @Nullable String color, boolean keepOpen,
               @Nullable Function<Context, View> custom) {
        static Row info(String text) {
            return new Row(text, "", null, null, false, null);
        }

        static Row info(String text, String detail) {
            return new Row(text, detail, null, null, false, null);
        }

        static Row link(String text, String detail, Runnable onClick) {
            return new Row(text, detail, onClick, null, false, null);
        }

        static Row toggle(String text, String detail, Runnable onClick) {
            return new Row(text, detail, onClick, null, true, null);
        }

        /** Searchable by text/detail, drawn by build. */
        static Row card(String text, String detail, Function<Context, View> build) {
            return new Row(text, detail, null, null, false, build);
        }

        Row colored(String attr) {
            return new Row(text, detail, onClick, attr, keepOpen, custom);
        }
    }

    private final Context context;
    private final Overlays.Dialog dialog;
    private final LinearLayout list;
    private final LinearLayout footer;
    private final TextView message;
    private final EditText filter;
    private List<Row> rows = List.of();

    private ListDialog(View anchor, String titleText, boolean filterable, int widthDp, int heightDp) {
        context = anchor.getContext();
        dialog = Overlays.dialog(anchor, titleText, widthDp, 60);
        var content = dialog.body;

        message = label(context, "", R.attr.textAppearanceBodySmall);
        message.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        message.setVisibility(View.GONE);
        content.addView(message);

        if (filterable) {
            filter = new EditText(context, null, R.attr.editTextOutlinedStyle);
            filter.setSingleLine();
            filter.setHint("Type to filter (Enter = open the first)");
            filter.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    render();
                }
            });
            filter.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEY_ENTER || keyCode == KeyEvent.KEY_KP_ENTER)) {
                    for (Row row : visibleRows()) {
                        if (row.onClick() != null) {
                            if (!row.keepOpen()) {
                                dialog.dismiss();
                            }
                            row.onClick().run();
                            break;
                        }
                    }
                    return true;
                }
                return false;
            });
            var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            lp.topMargin = content.dp(8);
            content.addView(filter, lp);
        } else {
            filter = null;
        }

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        var scroll = new ScrollView(context);
        scroll.addView(list, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        int windowHeight = anchor.getRootView().getHeight();
        int listHeight = content.dp(heightDp);
        if (windowHeight > 0) {
            listHeight = Math.min(listHeight, Math.max(content.dp(80), windowHeight - content.dp(60 + 150)));
        }
        var scrollLp = new LinearLayout.LayoutParams(MATCH_PARENT, listHeight);
        scrollLp.topMargin = content.dp(6);
        content.addView(scroll, scrollLp);

        // Only actions live here (the title bar has the one close button); hidden until one is added.
        footer = row(context);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setVisibility(View.GONE);
        var footerLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        footerLp.topMargin = content.dp(6);
        content.addView(footer, footerLp);
    }

    /** Shows the dialog near the top of the window. */
    static ListDialog open(View anchor, String title, boolean filterable) {
        return open(anchor, title, filterable, 560, 300);
    }

    static ListDialog open(View anchor, String title, boolean filterable, int widthDp, int heightDp) {
        var dialog = new ListDialog(anchor, title, filterable, widthDp, heightDp);
        dialog.dialog.show();
        if (dialog.filter != null) {
            dialog.filter.requestFocus();
        }
        return dialog;
    }

    ListDialog setTitle(String text) {
        dialog.setTitle(text);
        return this;
    }

    /** A status line under the title ("Loading...", counts, hints). Empty hides it. */
    ListDialog setMessage(String text) {
        message.setText(text);
        message.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        return this;
    }

    /** Pre-fills the filter box (no effect on dialogs without one). */
    ListDialog setFilter(String text) {
        if (filter != null) {
            filter.setText(text);
            filter.setSelection(text.length());
        }
        return this;
    }

    ListDialog setRows(List<Row> next) {
        rows = List.copyOf(next);
        render();
        return this;
    }

    /** Adds a footer action button. */
    ListDialog addAction(String text, Runnable action) {
        footer.setVisibility(View.VISIBLE);
        footer.addView(button(context, text, R.attr.borderlessButtonStyle, v -> action.run()));
        return this;
    }

    void dismiss() {
        dialog.dismiss();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    private List<Row> visibleRows() {
        String query = filter == null ? "" : filter.getText().toString().toLowerCase(Locale.ROOT).trim();
        if (query.isEmpty()) {
            return rows;
        }
        List<Row> out = new ArrayList<>();
        for (Row row : rows) {
            if ((row.text() + " " + row.detail()).toLowerCase(Locale.ROOT).contains(query)) {
                out.add(row);
            }
        }
        return out;
    }

    private void render() {
        list.removeAllViews();
        List<Row> shown = visibleRows();
        int limit = Math.min(shown.size(), 200);
        for (int i = 0; i < limit; i++) {
            Row row = shown.get(i);
            var view = new LinearLayout(context);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(view.dp(8), view.dp(4), view.dp(8), view.dp(4));
            if (row.custom() != null) {
                view.addView(row.custom().apply(context));
            } else {
                var text = label(context, row.text(), R.attr.textAppearanceBodyMedium);
                if (row.color() != null) {
                    text.setTextColor(color(context, row.color()));
                }
                view.addView(text);
                if (!row.detail().isEmpty()) {
                    var detail = label(context, row.detail(), R.attr.textAppearanceBodySmall);
                    detail.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
                    view.addView(detail);
                }
            }
            if (row.onClick() != null) {
                view.setClickable(true);
                hoverBackground(view);
                Runnable action = row.onClick();
                boolean keepOpen = row.keepOpen();
                view.setOnClickListener(v -> {
                    if (!keepOpen) {
                        dialog.dismiss();
                    }
                    action.run();
                });
            }
            list.addView(view);
        }
        if (shown.size() > limit) {
            list.addView(label(context, "... " + (shown.size() - limit) + " more - keep typing to narrow it down",
                    R.attr.textAppearanceBodySmall));
        }
        if (shown.isEmpty() && !rows.isEmpty()) {
            list.addView(label(context, "Nothing matches.", R.attr.textAppearanceBodySmall));
        }
    }
}
