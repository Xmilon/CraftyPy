package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.text.Typeface;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.Layout;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.widget.LinearLayout;

import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * IDE assists of the code area, backed by the Python worker (it knows the real API objects): the completion list
 * (typing, or Ctrl+Space) and the documentation card shown when the mouse rests on a name (or Ctrl+Q). Replies
 * carry a sequence number, so a slow answer for text that has changed since is dropped. UI thread only.
 */
final class CodeAssist {
    private static final int VISIBLE_ROWS = 8;
    private static final int TYPE_DELAY_MS = 120;
    private static final int HOVER_DELAY_MS = 550;
    /** The packet string is capped at 32767 characters, so very large files are sent as a window around the caret. */
    private static final int MAX_CHARS = 20000;

    private final CodeView code;
    private final BiConsumer<String, JsonObject> server; // op + args; the reply comes back through reply()
    private final IntFunction<String> problemAt;

    private int seq;
    private int completeSeq = -1;
    private int hoverSeq = -1;

    private Overlays.Floating listPopup;
    private LinearLayout listBox;
    private List<Item> items = List.of();
    private int selected;
    private int top;
    private int prefixLength;
    private int anchorOffset = -1;
    private boolean accepting;

    private Overlays.Floating docPopup;
    private int hoverOffset = -1;
    private float hoverX;
    private float hoverY;
    private final Runnable hoverTask = this::requestHover;
    private final Runnable typeTask = () -> requestComplete(false);

    private record Item(String name, String kind, String signature, String detail) {
    }

    CodeAssist(CodeView code, BiConsumer<String, JsonObject> server, IntFunction<String> problemAt) {
        this.code = code;
        this.server = server;
        this.problemAt = problemAt;
        code.setOnHoverListener((v, event) -> {
            onHover(event);
            return false;
        });
    }

    // ------------------------------------------------------------------
    // Completion
    // ------------------------------------------------------------------

    /** Called after the user typed or deleted; {@code added} is the number of inserted characters. */
    void onEdited(CharSequence text, int start, int added) {
        hideDoc();
        if (accepting) {
            return;
        }
        code.removeCallbacks(typeTask);
        if (added == 1 && start < text.length()) {
            char c = text.charAt(start);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || ((c == ':' || c == '/') && insideString(text, start + 1))) {
                code.postDelayed(typeTask, TYPE_DELAY_MS);
                return;
            }
        }
        if (added == 0 && isShowing()) {
            code.postDelayed(typeTask, TYPE_DELAY_MS); // backspace: refresh the list
            return;
        }
        hideList();
    }

    boolean isShowing() {
        return listPopup != null && listPopup.isShowing();
    }

    /** Returns true when the key was used by the open list. */
    boolean onKey(int keyCode, KeyEvent event) {
        if (event.isCtrlPressed() && keyCode == KeyEvent.KEY_SPACE) {
            requestComplete(true);
            return true;
        }
        if (event.isCtrlPressed() && keyCode == KeyEvent.KEY_Q) {
            hoverOffset = code.getSelectionStart();
            requestHover();
            return true;
        }
        if (!isShowing()) {
            return false;
        }
        switch (keyCode) {
            case KeyEvent.KEY_DOWN -> move(1);
            case KeyEvent.KEY_UP -> move(-1);
            case KeyEvent.KEY_ENTER, KeyEvent.KEY_KP_ENTER, KeyEvent.KEY_TAB -> accept(selected);
            case KeyEvent.KEY_ESCAPE -> hideList();
            default -> {
                return false;
            }
        }
        return true;
    }

    private void requestComplete(boolean manual) {
        Layout layout = code.getLayout();
        int caret = code.getSelectionStart();
        if (layout == null || caret < 0 || code.getSelectionEnd() != caret) {
            hideList();
            return;
        }
        String all = code.getText().toString();
        int[] slice = slice(all, caret);
        if (!manual && !wordBefore(all, caret)) {
            hideList();
            return;
        }
        anchorOffset = caret;
        completeSeq = ++seq;
        JsonObject args = new JsonObject();
        args.addProperty("code", all.substring(slice[0], slice[1]));
        args.addProperty("line", lineOf(all, caret) - lineOf(all, slice[0]));
        args.addProperty("column", caret - lineStart(all, caret));
        args.addProperty("seq", completeSeq);
        server.accept("complete", args);
    }

    /** Typing only opens the list after an identifier character or a dot. */
    private static boolean wordBefore(String text, int caret) {
        if (caret == 0) {
            return false;
        }
        char c = text.charAt(caret - 1);
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || ((c == ':' || c == '/') && insideString(text, caret));
    }

    /** True when an odd number of quotes precede the position on its line: it is probably inside a string. */
    private static boolean insideString(CharSequence text, int position) {
        int quotes = 0;
        for (int i = position - 1; i >= 0 && text.charAt(i) != '\n'; i--) {
            char c = text.charAt(i);
            if ((c == '"' || c == '\'') && (i == 0 || text.charAt(i - 1) != '\\')) {
                quotes++;
            }
        }
        return quotes % 2 == 1;
    }

    void onComplete(JsonObject reply) {
        if (reply.has("seq") && reply.get("seq").getAsInt() != completeSeq) {
            return;
        }
        if (code.getSelectionStart() != anchorOffset || !code.isAttachedToWindow()) {
            return;
        }
        List<Item> next = new ArrayList<>();
        if (reply.has("items") && reply.get("items").isJsonArray()) {
            for (JsonElement e : reply.getAsJsonArray("items")) {
                JsonObject o = e.getAsJsonObject();
                next.add(new Item(str(o, "n"), str(o, "k"), str(o, "s"), str(o, "d")));
            }
        }
        prefixLength = reply.has("prefix") ? reply.get("prefix").getAsString().length() : 0;
        if (next.isEmpty()) {
            hideList();
            return;
        }
        items = next;
        selected = 0;
        top = 0;
        showList();
    }

    private void showList() {
        Context context = code.getContext();
        if (listPopup == null) {
            listBox = new LinearLayout(context);
            listBox.setOrientation(LinearLayout.VERTICAL);
            int pad = listBox.dp(4);
            listBox.setPadding(pad, pad, pad, pad);
            var bg = new ShapeDrawable();
            bg.setCornerRadius(listBox.dp(8));
            bg.setColor(color(context, R.attr.colorSurfaceContainerHigh));
            listBox.setBackground(bg);
            listPopup = Overlays.floating(code, listBox);
            if (listPopup == null) {
                return;
            }
        }
        renderList();
        int[] at = caretScreen(anchorOffset);
        listPopup.showAt(at[0], at[1], code.getLineHeight());
    }

    private void renderList() {
        Context context = listBox.getContext();
        listBox.removeAllViews();
        int end = Math.min(items.size(), top + VISIBLE_ROWS);
        for (int i = top; i < end; i++) {
            Item item = items.get(i);
            var rowView = row(context);
            rowView.setPadding(rowView.dp(8), rowView.dp(2), rowView.dp(8), rowView.dp(2));
            var tag = label(context, tag(item.kind), R.attr.textAppearanceLabelSmall);
            tag.setTextColor(color(context, R.attr.colorPrimary));
            tag.setMinWidth(tag.dp(18));
            rowView.addView(tag);
            var name = label(context, item.name + item.signature, R.attr.textAppearanceBodyMedium);
            name.setTypeface(EditorFont.get());
            name.setSingleLine();
            rowView.addView(name);
            if (!item.detail.isEmpty() && i == selected) {
                var detail = label(context, "  " + item.detail, R.attr.textAppearanceBodySmall);
                detail.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
                detail.setSingleLine();
                rowView.addView(detail);
            }
            if (i == selected) {
                styleChip(rowView, true);
            }
            final int index = i;
            rowView.setClickable(true);
            rowView.setOnClickListener(v -> accept(index));
            listBox.addView(rowView, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        }
        if (items.size() > end || top > 0) {
            var more = label(context, (selected + 1) + " / " + items.size() + "  ↑↓ Enter/Tab", R.attr.textAppearanceLabelSmall);
            more.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
            more.setPadding(more.dp(8), 0, 0, 0);
            listBox.addView(more);
        }
    }

    private static String tag(String kind) {
        return switch (kind) {
            case "fn" -> "ƒ";
            case "class" -> "C";
            case "kw" -> "k";
            case "id" -> "◆";
            default -> "v";
        };
    }

    private void move(int delta) {
        selected = Math.floorMod(selected + delta, items.size());
        if (selected < top) {
            top = selected;
        } else if (selected >= top + VISIBLE_ROWS) {
            top = selected - VISIBLE_ROWS + 1;
        }
        renderList();
    }

    private void accept(int index) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        int caret = code.getSelectionStart();
        int from = caret - prefixLength;
        if (from < 0 || caret != anchorOffset) {
            hideList();
            return;
        }
        String name = items.get(index).name;
        hideList();
        accepting = true;
        try {
            Editable text = code.getText();
            text.replace(from, caret, name);
            code.setSelection(from + name.length());
        } finally {
            accepting = false;
        }
    }

    void hideList() {
        code.removeCallbacks(typeTask);
        completeSeq = -1;
        if (listPopup != null && listPopup.isShowing()) {
            listPopup.dismiss();
        }
    }

    // ------------------------------------------------------------------
    // Hover documentation
    // ------------------------------------------------------------------

    private void onHover(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_HOVER_EXIT -> {
                code.removeCallbacks(hoverTask);
                hideDoc();
            }
            case MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                Layout layout = code.getLayout();
                if (layout == null || code.getText().length() == 0) {
                    return;
                }
                int y = (int) event.getY() - code.getExtendedPaddingTop();
                int line = layout.getLineForVertical(Math.max(0, y));
                float x = event.getX() - code.getCompoundPaddingLeft();
                // Past the end of the line there is nothing to explain.
                boolean beyond = y < 0 || y > layout.getLineBottom(layout.getLineCount() - 1)
                        || x > layout.getLineMax(line) + code.charWidth();
                int offset = beyond ? -1 : layout.getOffsetForHorizontal(line, x);
                if (offset == hoverOffset && docPopup != null && docPopup.isShowing()) {
                    return;
                }
                hoverOffset = offset;
                hoverX = event.getX();
                hoverY = event.getY();
                code.removeCallbacks(hoverTask);
                hideDoc();
                if (offset >= 0) {
                    code.postDelayed(hoverTask, HOVER_DELAY_MS);
                }
            }
            default -> {
            }
        }
    }

    private void requestHover() {
        Layout layout = code.getLayout();
        if (layout == null || hoverOffset < 0 || hoverOffset > code.getText().length()) {
            return;
        }
        String all = code.getText().toString();
        int[] slice = slice(all, hoverOffset);
        int line = lineOf(all, hoverOffset);
        hoverSeq = ++seq;
        JsonObject args = new JsonObject();
        args.addProperty("code", all.substring(slice[0], slice[1]));
        args.addProperty("line", line - lineOf(all, slice[0]));
        args.addProperty("column", hoverOffset - lineStart(all, hoverOffset));
        args.addProperty("seq", hoverSeq);
        pendingLine = line;
        server.accept("hover", args);
    }

    private int pendingLine;

    void onHoverReply(JsonObject reply) {
        if (reply.has("seq") && reply.get("seq").getAsInt() != hoverSeq) {
            return;
        }
        String title = str(reply, "title");
        String doc = str(reply, "doc");
        String problem = problemAt.apply(pendingLine);
        if (title.isEmpty() && problem == null) {
            return;
        }
        Context context = code.getContext();
        var box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = box.dp(8);
        box.setPadding(pad, pad, pad, pad);
        var bg = new ShapeDrawable();
        bg.setCornerRadius(box.dp(8));
        bg.setColor(color(context, R.attr.colorSurfaceContainerHigh));
        box.setBackground(bg);
        if (problem != null) {
            var text = label(context, problem, R.attr.textAppearanceBodyMedium);
            text.setTextColor(color(context, R.attr.colorError));
            box.addView(text, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        }
        if (!title.isEmpty()) {
            var head = label(context, title, R.attr.textAppearanceBodyMedium);
            head.setTypeface(EditorFont.get());
            box.addView(head, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            if (!doc.isEmpty()) {
                var body = label(context, doc, R.attr.textAppearanceBodySmall);
                body.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
                body.setMaxWidth(box.dp(420));
                var lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
                lp.topMargin = box.dp(4);
                box.addView(body, lp);
            }
        }
        hideDoc();
        docPopup = Overlays.floating(code, box); // not clickable: the mouse stays with the code
        if (docPopup == null) {
            return;
        }
        int[] origin = new int[2];
        code.getLocationInWindow(origin);
        docPopup.showAt(origin[0] + (int) hoverX, origin[1] + (int) hoverY + code.getLineHeight(), code.getLineHeight());
    }

    /** Esc: closes the list and the documentation card; true when one was showing. */
    boolean dismissTransient() {
        boolean any = (listPopup != null && listPopup.isShowing()) || (docPopup != null && docPopup.isShowing());
        hideList();
        hideDoc();
        return any;
    }

    void hideDoc() {
        if (docPopup != null && docPopup.isShowing()) {
            docPopup.dismiss();
        }
    }

    /** Closes everything (focus lost, file switched, fragment destroyed). */
    void hideAll() {
        code.removeCallbacks(hoverTask);
        hideList();
        hideDoc();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int[] caretScreen(int offset) {
        Layout layout = code.getLayout();
        int[] origin = new int[2];
        code.getLocationInWindow(origin);
        int line = layout.getLineForOffset(offset);
        int x = origin[0] + code.getCompoundPaddingLeft() + (int) layout.getPrimaryHorizontal(offset);
        int y = origin[1] + code.getExtendedPaddingTop() + layout.getLineBottom(line);
        return new int[]{x, y};
    }

    /** The part of {@code text} sent to the worker: all of it, or whole lines around {@code offset}. */
    static int[] slice(String text, int offset) {
        if (text.length() <= MAX_CHARS) {
            return new int[]{0, text.length()};
        }
        int from = Math.max(0, offset - MAX_CHARS * 3 / 4);
        int to = Math.min(text.length(), from + MAX_CHARS);
        int lineFrom = text.lastIndexOf('\n', from);
        from = lineFrom < 0 ? 0 : lineFrom + 1;
        int lineTo = text.indexOf('\n', to);
        to = lineTo < 0 ? text.length() : lineTo;
        return new int[]{from, to};
    }

    /** Number of newlines before {@code offset}. */
    static int lineOf(String text, int offset) {
        int lines = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    static int lineStart(String text, int offset) {
        return text.lastIndexOf('\n', offset - 1) + 1;
    }

    private static String str(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
}
