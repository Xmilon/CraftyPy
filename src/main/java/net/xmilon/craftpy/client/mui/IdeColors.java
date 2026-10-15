package net.xmilon.craftpy.client.mui;

import java.util.Map;

import icyllis.modernui.R;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.resources.ResourceId;
import icyllis.modernui.util.ColorStateList;
import icyllis.modernui.util.StateSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;

/**
 * The IDE's own dark palette, laid out like IntelliJ's New UI (dark editor, slightly lighter tool windows and bars, thin
 * separators) with CraftPy's green as the accent. Every page asks for colors through {@link CraftPyFragment#color}, which
 * answers from here, so the IDE looks the same in the game (whatever Modern UI theme is set) and in the standalone window.
 */
public final class IdeColors {
    public static final int EDITOR = 0xFF1E1F22;
    public static final int PANEL = 0xFF2B2D30;
    public static final int RAISED = 0xFF313438;
    public static final int HOVER = 0xFF393B40;
    public static final int SELECTED = 0xFF43454A;
    public static final int SEPARATOR = 0xFF1E1F22;
    public static final int BORDER = 0xFF4E5157;
    public static final int TEXT = 0xFFDFE1E5;
    public static final int TEXT_SECONDARY = 0xFF9DA0A8;
    public static final int TEXT_DISABLED = 0xFF6F737A;
    public static final int ACCENT = 0xFF36B37E;
    public static final int ACCENT_HOVER = 0xFF2E9C6D;
    public static final int ACCENT_SOFT = 0xFF1F4A37;
    public static final int FOCUS = 0xFF2E436E;
    public static final int ERROR = 0xFFF75464;
    public static final int WARNING = 0xFFF2C55C;
    public static final int SUCCESS = 0xFF5FB865;
    public static final int INFO = 0xFF548AF7;

    /** Material color roles the pages use, answered with the palette. Roles not listed fall back to the Modern UI theme. */
    private static final Map<String, Integer> ROLES = Map.ofEntries(
            Map.entry(R.attr.colorSurface, EDITOR),
            Map.entry(R.attr.colorSurfaceContainerLowest, 0xFF19191C),
            Map.entry(R.attr.colorSurfaceContainerLow, PANEL),
            Map.entry(R.attr.colorSurfaceContainer, PANEL),
            Map.entry(R.attr.colorSurfaceContainerHigh, RAISED),
            Map.entry(R.attr.colorSurfaceContainerHighest, HOVER),
            Map.entry(R.attr.colorSurfaceVariant, RAISED),
            Map.entry(R.attr.colorOnSurface, TEXT),
            Map.entry(R.attr.colorOnSurfaceVariant, TEXT_SECONDARY),
            Map.entry(R.attr.colorPrimary, ACCENT),
            Map.entry(R.attr.colorOnPrimary, 0xFFFFFFFF),
            Map.entry(R.attr.colorPrimaryContainer, ACCENT_SOFT),
            Map.entry(R.attr.colorOnPrimaryContainer, TEXT),
            Map.entry(R.attr.colorSecondary, TEXT_SECONDARY),
            Map.entry(R.attr.colorSecondaryContainer, SELECTED),
            Map.entry(R.attr.colorOnSecondaryContainer, TEXT),
            Map.entry(R.attr.colorTertiary, SUCCESS),
            Map.entry(R.attr.colorTertiaryContainer, 0xFF253627),
            Map.entry(R.attr.colorOnTertiaryContainer, TEXT),
            Map.entry(R.attr.colorError, ERROR),
            Map.entry(R.attr.colorOnError, 0xFFFFFFFF),
            Map.entry(R.attr.colorErrorContainer, 0xFF5E2B30),
            Map.entry(R.attr.colorOnErrorContainer, 0xFFFFD9DC),
            Map.entry(R.attr.colorOutline, BORDER),
            Map.entry(R.attr.colorOutlineVariant, HOVER));

    private IdeColors() {
    }

    /** The palette color of a Material role, or null when the palette has none. */
    static Integer role(String attr) {
        return ROLES.get(attr);
    }

    /**
     * Buttons the way IntelliJ draws them: the main action filled with the accent, other actions outlined, toolbar
     * actions flat with a hover background. Modern UI's own button colors come from its theme, which differs in game.
     */
    static void styleButton(Button button, ResourceId style) {
        boolean primary = R.attr.buttonStyle.equals(style);
        boolean flat = R.attr.borderlessButtonStyle.equals(style);
        int border = flat || primary ? 0 : BORDER;
        if (primary) {
            button.setBackground(fading(button, 5, border, ACCENT_HOVER, ACCENT_HOVER, ACCENT, ACCENT, 0xFF2A5C45));
        } else if (flat) {
            button.setBackground(fading(button, 5, border, SELECTED, HOVER, SELECTED, 0, 0));
        } else {
            button.setBackground(fading(button, 5, border, SELECTED, HOVER, SELECTED, PANEL, PANEL));
        }
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-R.attr.state_enabled}, StateSet.WILD_CARD},
                new int[]{TEXT_DISABLED, primary ? 0xFFFFFFFF : TEXT}));
        button.setTextSize(13);
        button.setMinHeight(button.dp(28));
        button.setMinimumHeight(button.dp(28));
        button.setPadding(button.dp(12), button.dp(4), button.dp(12), button.dp(4));
        button.setGravity(Gravity.CENTER);
    }

    /** A single-line or multi-line input with IntelliJ's flat field look (dark fill, border that turns accent on focus). */
    static void styleField(EditText field) {
        var bg = new ShapeDrawable();
        bg.setCornerRadius(field.dp(5));
        bg.setColor(EDITOR);
        bg.setStroke(field.dp(1), new ColorStateList(new int[][]{new int[]{R.attr.state_focused}, StateSet.WILD_CARD},
                new int[]{ACCENT, BORDER}));
        field.setBackground(bg);
        field.setTextColor(TEXT);
        field.setHintTextColor(TEXT_DISABLED);
        field.setPadding(field.dp(8), field.dp(5), field.dp(8), field.dp(5));
    }

    /** Hover and press background of a toolbar action, tab or list row (IntelliJ: rounded, no ripple). */
    static void hoverBackground(icyllis.modernui.view.View view, float radiusDp) {
        view.setBackground(fading(view, radiusDp, 0, SELECTED, HOVER, SELECTED, 0, 0));
    }

    /**
     * A background that fades between pressed, hovered, selected, normal and disabled (fast in, slower out, like
     * IntelliJ's and VS Code's lists), so the UI answers the pointer without flicker. A color of 0 draws nothing.
     */
    static icyllis.modernui.graphics.drawable.Drawable fading(icyllis.modernui.view.View view, float radiusDp, int border,
                                                              int pressed, int hovered, int selected, int normal,
                                                              int disabled) {
        var list = new icyllis.modernui.graphics.drawable.StateListDrawable();
        list.addState(new int[]{-R.attr.state_enabled}, shape(view, radiusDp, disabled, border));
        list.addState(new int[]{R.attr.state_pressed}, shape(view, radiusDp, pressed, border));
        list.addState(new int[]{R.attr.state_hovered}, shape(view, radiusDp, hovered, border));
        list.addState(new int[]{R.attr.state_selected}, shape(view, radiusDp, selected, border));
        list.addState(StateSet.WILD_CARD, shape(view, radiusDp, normal, border));
        list.setEnterFadeDuration(90);
        list.setExitFadeDuration(180);
        return list;
    }

    private static ShapeDrawable shape(icyllis.modernui.view.View view, float radiusDp, int color, int border) {
        var shape = new ShapeDrawable();
        shape.setCornerRadius(view.dp(radiusDp));
        shape.setColor(color);
        if (border != 0) {
            shape.setStroke(Math.max(1, view.dp(1)), border);
        }
        return shape;
    }

    /** A separator line for {@code LinearLayout.setDividerDrawable}: 1dp wide (vertical) or high. */
    static ShapeDrawable divider(icyllis.modernui.view.View view, boolean vertical) {
        var line = new ShapeDrawable();
        line.setShape(ShapeDrawable.RECTANGLE);
        line.setColor(SEPARATOR);
        int one = Math.max(1, view.dp(1));
        line.setSize(vertical ? one : -1, vertical ? -1 : one);
        return line;
    }

    /** A raised card: panel color, thin border, rounded like IntelliJ's popups. */
    static ShapeDrawable card(icyllis.modernui.view.View view) {
        var bg = new ShapeDrawable();
        bg.setCornerRadius(view.dp(8));
        bg.setColor(PANEL);
        bg.setStroke(Math.max(1, view.dp(1)), HOVER);
        return bg;
    }
}
