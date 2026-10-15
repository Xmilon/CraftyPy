package net.xmilon.craftpy.client.mui;

import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.ScrollView;
import net.xmilon.craftpy.client.EditorTheme;
import net.xmilon.craftpy.client.Syntax.Kind;

/**
 * A narrow overview of the whole file next to the code: one thin bar per line (its length is the line's length),
 * the visible part is outlined, click or drag to scroll. Long files are sampled so it always fits.
 */
final class MiniMap extends View {
    private final Paint paint = new Paint();
    private final ScrollView scroll;
    private EditorTheme theme = EditorTheme.DARK;
    private int[] lengths = new int[0];
    private boolean[] comments = new boolean[0];
    /** line -> 2 error, 1 warning (drawn as marks on the right edge, like IntelliJ's error stripe). */
    private java.util.Map<Integer, Integer> marks = java.util.Map.of();

    void setMarks(java.util.Map<Integer, Integer> marks) {
        this.marks = marks;
        invalidate();
    }

    MiniMap(Context context, ScrollView scroll) {
        super(context);
        this.scroll = scroll;
    }

    void setTheme(EditorTheme theme) {
        this.theme = theme;
        invalidate();
    }

    void setText(String text) {
        String[] lines = text.split("\n", -1);
        lengths = new int[lines.length];
        comments = new boolean[lines.length];
        for (int i = 0; i < lines.length; i++) {
            lengths[i] = lines[i].stripTrailing().length();
            comments[i] = lines[i].stripLeading().startsWith("#");
        }
        invalidate();
    }

    private float contentHeight() {
        return Math.max(1, scroll.getChildCount() == 0 ? 1 : scroll.getChildAt(0).getHeight());
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        paint.setColor(theme.gutterBg);
        canvas.drawRect(0, 0, width, height, paint);
        int unit = Math.max(1, dp(1));
        int slots = Math.max(1, height / unit);
        int stride = Math.max(1, (int) Math.ceil(lengths.length / (double) slots));
        int maxChars = 120;
        for (int line = 0, row = 0; line < lengths.length; line += stride, row++) {
            if (lengths[line] == 0) {
                continue;
            }
            paint.setColor((theme.color(comments[line] ? Kind.COMMENT : Kind.TEXT)) & 0x00FFFFFF | 0x99000000);
            float barWidth = Math.max(unit, Math.min(width - 2f, lengths[line] / (float) maxChars * width));
            canvas.drawRect(1, row * unit, 1 + barWidth, row * unit + unit, paint);
        }
        for (var mark : marks.entrySet()) {
            float y = Math.min(height - unit * 2f, mark.getKey() / (float) stride * unit);
            paint.setColor(mark.getValue() >= 2 ? 0xFFF75464 : 0xFFF2C55C);
            canvas.drawRect(width - unit * 4f, y, width, y + unit * 2f, paint);
        }
        float total = contentHeight();
        float top = scroll.getScrollY() / total * Math.min(height, (lengths.length / stride) * unit);
        float visible = scroll.getHeight() / total * Math.min(height, (lengths.length / stride) * unit);
        paint.setColor(0x33FFFFFF);
        canvas.drawRect(0, top, width, top + Math.max(unit * 4, visible), paint);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            int unit = Math.max(1, dp(1));
            int slots = Math.max(1, getHeight() / unit);
            int stride = Math.max(1, (int) Math.ceil(lengths.length / (double) slots));
            float span = Math.max(1, Math.min(getHeight(), (lengths.length / stride) * unit));
            float ratio = Math.max(0, Math.min(1, event.getY() / span));
            scroll.scrollTo(0, (int) (ratio * contentHeight() - scroll.getHeight() / 2f));
            invalidate();
        }
        return true;
    }
}
