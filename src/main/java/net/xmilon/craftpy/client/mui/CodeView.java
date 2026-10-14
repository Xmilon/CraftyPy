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

    // Word-under-cursor cache, recomputed only when the text or the word changes.
    private int textVersion;
    private int cachedVersion = -1;
    private int cachedWordStart = -1;
    private int cachedWordEnd = -1;
    private List<int[]> wordMatches = List.of();

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

    public void setOnZoom(IntConsumer onZoom) {
        this.onZoom = onZoom;
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
            int selection = getSelectionStart();
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
            for (int[] match : wordMatchesAt(selection)) {
                drawRange(canvas, layout, left, top, match[0], match[1], theme.wordMatch);
            }
            for (int[] match : findMatches) {
                drawRange(canvas, layout, left, top, match[0], match[1], theme.findMatch);
            }
        }
        super.onDraw(canvas);
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
