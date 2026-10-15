package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.LinearLayout;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;

/**
 * The narrow bar of tool window buttons along the left or right edge, like IntelliJ's New UI: icons only, a filled square
 * behind the ones whose window is open, a tooltip with the name and shortcut. Buttons added after {@link #addSpacer()} sit
 * at the bottom (IntelliJ puts the bottom tool windows there).
 */
final class ToolStripe extends LinearLayout {
    private record Entry(IdeIcon icon, BooleanSupplier open) {
    }

    private final List<Entry> entries = new ArrayList<>();

    ToolStripe(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setBackground(new ColorDrawable(IdeColors.PANEL));
        setPadding(0, dp(6), 0, dp(6));
    }

    /** @param open whether the tool window is showing (read again by {@link #refresh()}) */
    IdeIcon add(IdeIcon.Glyph glyph, String tooltip, BooleanSupplier open, View.OnClickListener onClick) {
        var icon = new IdeIcon(getContext(), glyph);
        int pad = dp(6);
        icon.setPadding(pad, pad, pad, pad);
        icon.setClickable(true);
        icon.setTooltipText(tooltip);
        icon.setOnClickListener(EditorFragment.hidingTooltip(tooltip, v -> {
            onClick.onClick(v);
            refresh();
        }));
        icon.setBackground(IdeColors.fading(icon, 6, 0, IdeColors.SELECTED, IdeColors.HOVER, IdeColors.SELECTED, 0, 0));
        var lp = new LayoutParams(dp(30), dp(30));
        lp.topMargin = dp(3);
        lp.bottomMargin = dp(3);
        addView(icon, lp);
        entries.add(new Entry(icon, open));
        return icon;
    }

    void addSpacer() {
        addView(new View(getContext()), new LayoutParams(MATCH_PARENT, 0, 1));
    }

    /** Marks the buttons of the open tool windows. */
    void refresh() {
        for (Entry entry : entries) {
            boolean open = entry.open().getAsBoolean();
            entry.icon().setSelected(open);
            entry.icon().setTint(open ? IdeColors.TEXT : IdeIcon.ownColor(glyphOf(entry.icon())));
        }
    }

    private static IdeIcon.Glyph glyphOf(IdeIcon icon) {
        return icon.glyph();
    }
}
