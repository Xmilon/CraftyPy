package net.xmilon.craftpy.client.mui;

import java.util.List;
import java.util.function.IntConsumer;

import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.Layout;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import net.xmilon.craftpy.client.EditorTheme;
import net.xmilon.craftpy.client.TextOps;

/**
 * The code area: a Modern UI {@link EditText} that also paints IDE overlays under the text -
 * current-line highlight, the ruler, occurrences of the word under the cursor and find matches.
 * Ctrl + mouse wheel reports zoom steps.
 */
public class CodeView extends EditText {
    private static final int MAX_WORD_MATCHES = 500;

    private final Paint paint = new Paint();
    private EditorTheme theme = EditorTheme.DARK;
    private int rulerColumn = 100;
    private List<int[]> findMatches = List.of();
    private IntConsumer onZoom = step -> { };
    private IntConsumer onCaret = offset -> { };
    private IntConsumer onCtrlClick = offset -> { };
    private boolean ctrlGesture;
    private int linkStart = -1;
    private int linkEnd = -1;
    private int lastCaret = -2;
    private int focusStart = -1;
    private int focusEnd = -1;

    // Word-under-cursor cache, recomputed only when the text or the word changes.
    private int textVersion;
    private int cachedVersion = -1;
    private int cachedWordStart = -1;
    private int cachedWordEnd = -1;
    private List<int[]> wordMatches = List.of();

    // Indent guides: indentation (in spaces) of every logical line, recomputed when the text changes.
    private int guidesVersion = -1;
    private int[] lineStarts = new int[0];
    private int[] lineIndents = new int[0];
    private boolean indentGuides = true;
    // Bracket pair at the caret.
    private int bracketVersion = -1;
    private int bracketCaret = -1;
    private int[] bracketPair;

    public CodeView(Context context) {
        super(context);
        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                textVersion++;
            }
        });
    }

    /** Syntax colors for the text of {@code path} (spans replaced on every call). Shared by the main and split editors. */
    static void colorize(icyllis.modernui.text.Editable text, @icyllis.modernui.annotation.Nullable String path, EditorTheme theme) {
        for (var span : text.getSpans(0, text.length(), icyllis.modernui.text.style.ForegroundColorSpan.class)) {
            text.removeSpan(span);
        }
        String language = net.xmilon.craftpy.client.Syntax.language(path);
        String all = text.toString();
        int lineStart = 0;
        while (lineStart <= all.length()) {
            int lineEnd = all.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = all.length();
            }
            int pos = lineStart;
            for (var token : net.xmilon.craftpy.client.Syntax.tokenize(all.substring(lineStart, lineEnd), language)) {
                int end = pos + token.text().length();
                if (token.kind() != net.xmilon.craftpy.client.Syntax.Kind.TEXT && end > pos) {
                    text.setSpan(new icyllis.modernui.text.style.ForegroundColorSpan(theme.color(token.kind())), pos, end,
                            icyllis.modernui.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                pos = end;
            }
            if (lineEnd == all.length()) {
                break;
            }
            lineStart = lineEnd + 1;
        }
    }

    public void setIndentGuides(boolean show) {
        indentGuides = show;
        invalidate();
    }

    public void setTheme(EditorTheme theme) {
        this.theme = theme;
        setTextColor(theme.text);
        invalidate();
    }

    public void setRulerColumn(int column) {
        this.rulerColumn = column;
        invalidate();
    }

    public void setFindMatches(List<int[]> matches) {
        this.findMatches = matches == null ? List.of() : matches;
        invalidate();
    }

    /** Focus mode: text outside {@code [start, end)} is dimmed; {@code start < 0} switches it off. */
    public void setFocusRange(int start, int end) {
        if (start != focusStart || end != focusEnd) {
            focusStart = start;
            focusEnd = end;
            invalidate();
        }
    }

    /** Ctrl + click or Shift + click on a name (go to definition); gets the text offset under the mouse. */
    public void setOnCtrlClick(IntConsumer onCtrlClick) {
        this.onCtrlClick = onCtrlClick;
    }

    /** Text offset under a view-local mouse position, or -1 past the end of the line / outside the text. */
    public int offsetAt(float x, float y) {
        Layout layout = getLayout();
        if (layout == null || getText().length() == 0) {
            return -1;
        }
        int localY = (int) y - getExtendedPaddingTop();
        if (localY < 0 || localY > layout.getLineBottom(layout.getLineCount() - 1)) {
            return -1;
        }
        int line = layout.getLineForVertical(localY);
        float localX = x - getCompoundPaddingLeft();
        if (localX > layout.getLineMax(line) + charWidth()) {
            return -1;
        }
        return layout.getOffsetForHorizontal(line, localX);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && (event.isCtrlPressed() || event.isShiftPressed())) {
            int offset = offsetAt(event.getX(), event.getY());
            if (offset >= 0 && TextOps.wordAt(getText().toString(), offset) != null) {
                ctrlGesture = true;
                onCtrlClick.accept(offset);
                return true;
            }
        }
        if (ctrlGesture) {
            // the rest of a Ctrl+click must not move the caret or start a selection
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                ctrlGesture = false;
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onHoverEvent(@NonNull MotionEvent event) {
        int start = -1;
        int end = -1;
        if (event.isCtrlPressed() && event.getAction() != MotionEvent.ACTION_HOVER_EXIT) {
            int offset = offsetAt(event.getX(), event.getY());
            int[] word = offset < 0 ? null : TextOps.wordAt(getText().toString(), offset);
            if (word != null) {
                start = word[0];
                end = word[1];
            }
        }
        if (start != linkStart || end != linkEnd) {
            linkStart = start;
            linkEnd = end;
            invalidate();
        }
        return super.onHoverEvent(event);
    }

    public void setOnZoom(IntConsumer onZoom) {
        this.onZoom = onZoom;
    }

    /** Called (on the UI thread, after the frame) whenever the caret offset changed; used by the breadcrumbs. */
    public void setOnCaret(IntConsumer onCaret) {
        this.onCaret = onCaret;
    }

    /** Width of one monospace character, for the ruler. */
    public float charWidth() {
        return getPaint().measureTextRun("0", 0, 1, false, null);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        Layout layout = getLayout();
        if (layout != null) {
            int left = getCompoundPaddingLeft();
            int top = getExtendedPaddingTop();
            if (linkStart >= 0 && linkEnd <= getText().length()) {
                // Ctrl held over a name: underline it like a link
                int linkLine = layout.getLineForOffset(linkStart);
                paint.setColor(0xFF6AA9FF);
                canvas.drawRect(left + layout.getPrimaryHorizontal(linkStart), top + layout.getLineBottom(linkLine) - 2,
                        left + layout.getPrimaryHorizontal(linkEnd), top + layout.getLineBottom(linkLine), paint);
            }
            int selection = getSelectionStart();
            if (selection != lastCaret) {
                lastCaret = selection;
                final int caret = selection;
                post(() -> onCaret.accept(caret));
            }
            if (selection >= 0 && getSelectionEnd() == selection && isFocused()) {
                int line = layout.getLineForOffset(selection);
                paint.setColor(theme.currentLine);
                canvas.drawRect(getScrollX(), top + layout.getLineTop(line), getScrollX() + getWidth(),
                        top + layout.getLineBottom(line), paint);
            }
            if (rulerColumn > 0) {
                float x = left + rulerColumn * charWidth();
                paint.setColor(theme.ruler);
                canvas.drawRect(x, 0, x + Math.max(1, dp(1) / 2f), Math.max(getHeight(), layout.getHeight() + top), paint);
            }
            drawIndentGuides(canvas, layout, left, top);
            int[] pair = bracketPair(selection);
            if (pair != null) {
                paint.setColor(theme.wordMatch);
                for (int at : pair) {
                    drawRange(canvas, layout, left, top, at, at + 1, theme.wordMatch);
                }
            }
            for (int[] match : wordMatchesAt(selection)) {
                drawRange(canvas, layout, left, top, match[0], match[1], theme.wordMatch);
            }
            for (int[] match : findMatches) {
                drawRange(canvas, layout, left, top, match[0], match[1], theme.findMatch);
            }
        }
        super.onDraw(canvas);
        if (focusStart >= 0 && layout != null) {
            int length = getText().length();
            int startLine = layout.getLineForOffset(Math.min(focusStart, length));
            int endLine = layout.getLineForOffset(Math.min(focusEnd, length));
            int top = getExtendedPaddingTop();
            paint.setColor((theme.bg & 0x00FFFFFF) | 0xB0000000);
            if (startLine > 0) {
                canvas.drawRect(getScrollX(), top, getScrollX() + getWidth(), top + layout.getLineTop(startLine), paint);
            }
            if (endLine < layout.getLineCount() - 1) {
                canvas.drawRect(getScrollX(), top + layout.getLineBottom(endLine), getScrollX() + getWidth(),
                        top + layout.getHeight(), paint);
            }
        }
    }

    /** The ScrollView the editor scrolls in, for the visible range (null when there is none). */
    private icyllis.modernui.widget.ScrollView scroller() {
        for (var parent = getParent(); parent instanceof View view; parent = view.getParent()) {
            if (view instanceof icyllis.modernui.widget.ScrollView scroll) {
                return scroll;
            }
        }
        return null;
    }

    /**
     * Thin vertical lines at every indentation level of the visible lines (IntelliJ and VS Code draw them). Blank lines take
     * the indentation of the next line that has text, so a block's guide does not break at an empty line.
     */
    private void drawIndentGuides(Canvas canvas, Layout layout, int left, int top) {
        if (!indentGuides || getText().length() > 300_000) {
            return;
        }
        if (guidesVersion != textVersion) {
            computeIndents();
            guidesVersion = textVersion;
        }
        var scroll = scroller();
        int from = 0;
        int to = layout.getLineCount() - 1;
        if (scroll != null) {
            int offsetInScroll = 0;
            for (View v = this; v != null && v != scroll && v.getParent() instanceof View parent; v = parent) {
                offsetInScroll += v.getTop();
            }
            int y0 = scroll.getScrollY() - offsetInScroll - top;
            from = layout.getLineForVertical(Math.max(0, y0));
            to = layout.getLineForVertical(Math.max(0, y0 + scroll.getHeight()));
        }
        float unit = charWidth() * 4;
        if (unit <= 0) {
            return;
        }
        paint.setColor((theme.ruler & 0x00FFFFFF) | 0x80000000);
        float width = Math.max(1, dp(1) / 2f);
        for (int visual = from; visual <= to; visual++) {
            int logical = logicalLine(layout.getLineStart(visual));
            int indent = logical < lineIndents.length ? lineIndents[logical] : 0;
            float lineTop = top + layout.getLineTop(visual);
            float lineBottom = top + layout.getLineBottom(visual);
            for (int level = 1; level * 4 <= indent; level++) {
                float x = left + (level - 1) * unit;
                canvas.drawRect(x, lineTop, x + width, lineBottom, paint);
            }
        }
    }

    private void computeIndents() {
        String text = getText().toString();
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        int[] starts = new int[count];
        int[] indents = new int[count];
        boolean[] blank = new boolean[count];
        int line = 0;
        int start = 0;
        while (line < count) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            starts[line] = start;
            int spaces = 0;
            while (start + spaces < end && text.charAt(start + spaces) == ' ') {
                spaces++;
            }
            blank[line] = start + spaces == end;
            indents[line] = spaces;
            line++;
            start = end + 1;
        }
        int next = 0;
        for (int i = count - 1; i >= 0; i--) {
            if (blank[i]) {
                indents[i] = next;
            } else {
                next = indents[i];
            }
        }
        lineStarts = starts;
        lineIndents = indents;
    }

    private int logicalLine(int offset) {
        int index = java.util.Arrays.binarySearch(lineStarts, offset);
        return index >= 0 ? index : Math.max(0, -index - 2);
    }

    /** Offsets of the bracket next to the caret and its partner, or null; cached per text version and caret. */
    private int[] bracketPair(int caret) {
        if (caret != bracketCaret || bracketVersion != textVersion) {
            bracketCaret = caret;
            bracketVersion = textVersion;
            bracketPair = net.xmilon.craftpy.client.TextOps.matchingBracket(getText().toString(), caret);
        }
        return bracketPair;
    }

    private List<int[]> wordMatchesAt(int selection) {
        if (selection < 0 || getSelectionEnd() != selection) {
            return List.of();
        }
        CharSequence text = getText();
        int[] word = TextOps.wordAt(text.toString(), selection);
        if (word == null || word[1] - word[0] < 2) {
            return List.of();
        }
        if (cachedVersion != textVersion || cachedWordStart != word[0] || cachedWordEnd != word[1]) {
            cachedVersion = textVersion;
            cachedWordStart = word[0];
            cachedWordEnd = word[1];
            String all = text.toString();
            List<int[]> found = TextOps.findWord(all, all.substring(word[0], word[1]), MAX_WORD_MATCHES);
            // A word that appears once is not worth highlighting.
            wordMatches = found.size() > 1 ? found : List.of();
        }
        return wordMatches;
    }

    private void drawRange(Canvas canvas, Layout layout, int left, int top, int start, int end, int color) {
        int length = getText().length();
        start = Math.min(start, length);
        end = Math.min(end, length);
        if (end <= start) {
            return;
        }
        paint.setColor(color);
        int firstLine = layout.getLineForOffset(start);
        int lastLine = layout.getLineForOffset(end);
        for (int line = firstLine; line <= lastLine; line++) {
            float x0 = line == firstLine ? layout.getPrimaryHorizontal(start) : layout.getLineLeft(line);
            float x1 = line == lastLine ? layout.getPrimaryHorizontal(end) : layout.getLineRight(line);
            canvas.drawRect(left + x0, top + layout.getLineTop(line), left + Math.max(x1, x0 + 2),
                    top + layout.getLineBottom(line), paint);
        }
    }

    @Override
    public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL && event.isCtrlPressed()) {
            float delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (delta != 0) {
                onZoom.accept(delta > 0 ? 1 : -1);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }
}
