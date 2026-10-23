package net.xmilon.craftpy.client.mui;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;

/** Children side by side, wrapping to a new line when the width runs out (the toolbox tiles). */
final class FlowLayout extends ViewGroup {
    FlowLayout(Context context) {
        super(context);
    }

    private int gap() {
        return dp(4);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int max = Math.max(1, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        int x = 0, y = 0, line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + child.getMeasuredWidth() > max) {
                x = 0;
                y += line + gap();
                line = 0;
            }
            x += child.getMeasuredWidth() + gap();
            line = Math.max(line, child.getMeasuredHeight());
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), y + line + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int max = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
            if (x > 0 && x + w > max) {
                x = 0;
                y += line + gap();
                line = 0;
            }
            child.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
            x += w + gap();
            line = Math.max(line, h);
        }
    }
}
