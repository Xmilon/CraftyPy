package net.xmilon.craftpy.client.mui;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.PointerIcon;
import icyllis.modernui.widget.TextView;

/**
 * Text you can select and copy but not type into (labels, console output, docs, README, values in the debugger) shows the
 * normal arrow. Modern UI shows the typing cursor over any selectable text, which looked as if those places were fields.
 * Fields (EditText) keep the typing cursor.
 */
public class PlainText extends TextView {
    public PlainText(Context context) {
        super(context);
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event) {
        if (isTextSelectable()) { // a PlainText is never a field
            return PointerIcon.getSystemIcon(PointerIcon.TYPE_ARROW);
        }
        return super.onResolvePointerIcon(event);
    }
}
