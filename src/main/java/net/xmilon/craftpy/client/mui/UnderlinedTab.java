package net.xmilon.craftpy.client.mui;

import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.widget.TextView;

/** A tab label marked by an accent line under it while selected (IntelliJ's tool window and editor tabs). */
class UnderlinedTab extends TextView {
    private final Paint line = new Paint();

    UnderlinedTab(Context context) {
        super(context);
        setGravity(icyllis.modernui.view.Gravity.CENTER_VERTICAL);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (isSelected()) {
            line.setColor(IdeColors.ACCENT);
            canvas.drawRect(dp(4), getHeight() - dp(2), getWidth() - dp(4), getHeight(), line);
        }
    }

    /** A row (icon, name, close button) with the same accent line: the editor tabs. */
    static class Row extends icyllis.modernui.widget.LinearLayout {
        private final Paint line = new Paint();

        Row(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(icyllis.modernui.view.Gravity.CENTER_VERTICAL);
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            super.dispatchDraw(canvas);
            if (isSelected()) {
                line.setColor(IdeColors.ACCENT);
                canvas.drawRect(0, getHeight() - dp(2), getWidth(), getHeight(), line);
            }
        }
    }
}
