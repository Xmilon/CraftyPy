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
import net.xmilon.craftpy.client.EditorSettings;
import net.xmilon.craftpy.client.EditorTheme;

/**
 * The IDE's palette, laid out like IntelliJ's New UI (editor, slightly different tool windows and bars, thin
 * separators) with CraftPy's green as the accent. It follows the editor color scheme ({@link #apply}); Dark is the
 * original look. Every page asks for colors through {@link CraftPyFragment#color}, which answers from here, so the IDE
 * looks the same in the game (whatever Modern UI theme is set) and in the standalone window. Views read the colors when
 * they are built, so after {@link #apply} the open pages must be rebuilt.
 */
public final class IdeColors {
    public static int EDITOR, PANEL, RAISED, HOVER, SELECTED, SEPARATOR, BORDER, TEXT, TEXT_SECONDARY, TEXT_DISABLED;
    public static int ACCENT, ACCENT_HOVER, ACCENT_SOFT, FOCUS, ERROR, WARNING, SUCCESS, INFO;

    private static int lowest, accentPressed, tertiaryContainer, errorContainer, onErrorContainer;
    /** Material color roles the pages use, answered with the palette. Roles not listed fall back to the Modern UI theme. */
    private static volatile Map<String, Integer> roles = Map.of();

    static {
        EditorTheme theme = EditorTheme.DARK;
        try {
            theme = EditorSettings.get().theme();
        } catch (RuntimeException notReady) {
            // no config folder yet: the default look
        }
        apply(theme);
    }

    private IdeColors() {
    }

    /** {@code from} moved {@code percent} of the way to {@code to}. */
    private static int blend(int from, int to, int percent) {
        int out = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = from >> shift & 0xFF, b = to >> shift & 0xFF;
            out |= (a + (b - a) * percent / 100) << shift;
        }
        return out;
    }

    /** Derives the whole palette from an editor color scheme: surfaces step from its background toward its text color. */
    public static synchronized void apply(EditorTheme theme) {
        if (theme == EditorTheme.DARK) {
            EDITOR = 0xFF1E1F22; PANEL = 0xFF2B2D30; RAISED = 0xFF313438; HOVER = 0xFF393B40; SELECTED = 0xFF43454A;
            SEPARATOR = 0xFF1E1F22; BORDER = 0xFF4E5157; TEXT = 0xFFDFE1E5; TEXT_SECONDARY = 0xFF9DA0A8;
            TEXT_DISABLED = 0xFF6F737A;
            ACCENT = 0xFF36B37E; ACCENT_HOVER = 0xFF2E9C6D; ACCENT_SOFT = 0xFF1F4A37; FOCUS = 0xFF2E436E;
            ERROR = 0xFFF75464; WARNING = 0xFFF2C55C; SUCCESS = 0xFF5FB865; INFO = 0xFF548AF7;
            lowest = 0xFF19191C; accentPressed = accentPressed; tertiaryContainer = 0xFF253627; errorContainer = 0xFF5E2B30;
            onErrorContainer = 0xFFFFD9DC;
        } else {
            boolean light = theme == EditorTheme.LIGHT;
            int bg = theme.bg, fg = theme.text;
            EDITOR = bg;
            PANEL = blend(bg, fg, light ? 4 : 7);
            RAISED = blend(bg, fg, light ? 8 : 11);
            HOVER = blend(bg, fg, light ? 12 : 16);
            SELECTED = blend(bg, fg, light ? 18 : 22);
            SEPARATOR = blend(bg, fg, light ? 14 : 4);
            BORDER = blend(bg, fg, light ? 30 : 32);
            TEXT = fg;
            TEXT_SECONDARY = blend(fg, bg, 35);
            TEXT_DISABLED = blend(fg, bg, 58);
            ACCENT = light ? 0xFF1F9D6B : theme == EditorTheme.HIGH_CONTRAST ? 0xFF00E676 : 0xFF36B37E;
            ACCENT_HOVER = blend(ACCENT, bg, light ? 18 : 15);
            ACCENT_SOFT = blend(bg, ACCENT, light ? 22 : 28);
            FOCUS = blend(bg, 0xFF548AF7, 30);
            ERROR = light ? 0xFFD32F3F : 0xFFF75464;
            WARNING = light ? 0xFFB7791F : 0xFFF2C55C;
            SUCCESS = light ? 0xFF2E8B3E : 0xFF5FB865;
            INFO = light ? 0xFF2F6FDE : 0xFF548AF7;
            lowest = blend(bg, 0xFF000000, light ? 3 : 25);
            accentPressed = blend(ACCENT, bg, 40);
            tertiaryContainer = blend(bg, SUCCESS, 18);
            errorContainer = blend(bg, ERROR, light ? 18 : 35);
            onErrorContainer = light ? 0xFF5E1A22 : 0xFFFFD9DC;
        }
        roles = Map.ofEntries(
                Map.entry(R.attr.colorSurface, EDITOR),
                Map.entry(R.attr.colorSurfaceContainerLowest, lowest),
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
                Map.entry(R.attr.colorTertiary, WARNING), // warnings in the problem list
                Map.entry(R.attr.colorTertiaryContainer, tertiaryContainer),
                Map.entry(R.attr.colorOnTertiaryContainer, TEXT),
                Map.entry(R.attr.colorError, ERROR),
                Map.entry(R.attr.colorOnError, 0xFFFFFFFF),
                Map.entry(R.attr.colorErrorContainer, errorContainer),
                Map.entry(R.attr.colorOnErrorContainer, onErrorContainer),
                Map.entry(R.attr.colorOutline, BORDER),
                Map.entry(R.attr.colorOutlineVariant, HOVER));
    }

    /** The palette color of a Material role, or null when the palette has none. */
    static Integer role(String attr) {
        return roles.get(attr);
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
            button.setBackground(fading(button, 5, border, ACCENT_HOVER, ACCENT_HOVER, ACCENT, ACCENT, accentPressed));
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

    /** Colored initials of a project, like IntelliJ's project icons: the color follows the name. */
    static void avatar(icyllis.modernui.widget.TextView view, @icyllis.modernui.annotation.Nullable String name) {
        String clean = name == null ? "" : name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        String[] words = clean.split("[ _-]+");
        String initials = words.length > 1 && !words[0].isEmpty() && !words[1].isEmpty()
                ? "" + words[0].charAt(0) + words[1].charAt(0)
                : clean.length() >= 2 ? clean.substring(0, 2) : clean.isEmpty() ? "?" : clean;
        view.setText(initials.toUpperCase(java.util.Locale.ROOT));
        view.setGravity(Gravity.CENTER);
        view.setTextColor(0xFFFFFFFF);
        int[] colors = {0xFF3D8B62, 0xFF4A6FBF, 0xFF9C5DC9, 0xFFB5733C, 0xFF3C8E9E, 0xFFB0485E, 0xFF6B8E2F};
        var bg = new ShapeDrawable();
        bg.setCornerRadius(view.dp(5));
        bg.setColor(colors[Math.floorMod(clean.hashCode(), colors.length)]);
        view.setBackground(bg);
    }

    /** A raised card: panel color, thin border, rounded like IntelliJ's popups. */
    static ShapeDrawable card(icyllis.modernui.view.View view) {
        var bg = new ShapeDrawable();
        bg.setCornerRadius(view.dp(8));
        bg.setColor(PANEL);
        bg.setStroke(Math.max(1, view.dp(1)), HOVER);
        return bg;
    }

    /** A clickable tile (Hub apps): raised, a lighter fill on hover, the accent border while the keyboard points at it (selected). */
    static icyllis.modernui.graphics.drawable.Drawable tile(icyllis.modernui.view.View view) {
        var list = new icyllis.modernui.graphics.drawable.StateListDrawable();
        list.addState(new int[]{R.attr.state_pressed}, shape(view, 8, SELECTED, BORDER));
        list.addState(new int[]{R.attr.state_selected}, shape(view, 8, HOVER, ACCENT));
        list.addState(new int[]{R.attr.state_hovered}, shape(view, 8, HOVER, BORDER));
        list.addState(StateSet.WILD_CARD, shape(view, 8, RAISED, HOVER));
        list.setEnterFadeDuration(90);
        list.setExitFadeDuration(180);
        return list;
    }
}
