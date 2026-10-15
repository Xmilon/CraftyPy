package net.xmilon.craftpy.client.mui;

import net.xmilon.craftpy.client.IdeHost;
import java.util.function.Consumer;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.fragment.FragmentContainerView;
import icyllis.modernui.fragment.FragmentTransaction;
import icyllis.modernui.graphics.drawable.RippleDrawable;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.ScreenCallback;
import icyllis.modernui.mc.ui.ThemeControl;
import icyllis.modernui.resources.ResourceId;
import icyllis.modernui.resources.TypedValue;
import icyllis.modernui.util.ColorStateList;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.util.StateSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.RadioButton;
import icyllis.modernui.widget.RadioGroup;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.gui.screens.Screen;
import net.xmilon.craftpy.client.ClientProjectStore;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * Root of every CraftPy screen: a navigation rail (Home / Modules / Editor) next to the
 * active page, laid out like Modern UI's own center screen.
 * <p>
 * Threading: Modern UI views live on the UI thread, while {@link ClientProjectStore} is
 * owned by the Minecraft client thread. Pages read store state only inside a store
 * listener (client thread), copy what they need and {@link View#post} it to the UI thread.
 * Every store call made from a click goes through {@link #store(Consumer)}.
 */
public class CraftPyFragment extends Fragment implements ScreenCallback {

    public static final int PAGE_HOME = 1001;
    public static final int PAGE_MODULES = 1002;
    public static final int PAGE_EDITOR = 1003;
    public static final int PAGE_DOCS = 1004;
    private static final int ID_CONTAINER = 0x2101;

    private final int initialPage;
    private RadioGroup nav;

    /** Client thread: jumps into the editor when the server asked for a project ({@code /craftpy ide project}). */
    private final Runnable pendingProjectListener = () -> {
        ClientProjectStore store = ClientProjectStore.getInstance();
        String pending = store.pendingIdeProject();
        if (pending == null || pending.isBlank() || store.find(pending) == null) {
            return;
        }
        store.setPendingIdeProject(null);
        store.select(pending);
        RadioGroup view = nav;
        if (view != null) {
            view.post(() -> navigate(PAGE_EDITOR));
        }
    };

    public CraftPyFragment() {
        this(PAGE_HOME);
    }

    public CraftPyFragment(int initialPage) {
        this.initialPage = initialPage;
    }

    /** Creates the standalone (keybind) screen. */
    public static Screen createScreen(int page) {
        return MuiModApi.get().createScreen(new CraftPyFragment(page));
    }

    @Override
    public void onCreate(@Nullable DataSet savedInstanceState) {
        super.onCreate(savedInstanceState);
        getChildFragmentManager().beginTransaction()
                .replace(ID_CONTAINER, pageFragment(initialPage), null)
                .setReorderingAllowed(true)
                .commit();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        Context context = requireContext();
        var base = new LinearLayout(context);
        base.setOrientation(LinearLayout.HORIZONTAL);
        base.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        base.setDividerDrawable(ThemeControl.makeDivider(base, true));
        base.setBackground(new icyllis.modernui.graphics.drawable.ColorDrawable(
                color(context, R.attr.colorSurface)));

        nav = new RadioGroup(context);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setGravity(Gravity.CENTER_HORIZONTAL);
        nav.setBackground(new icyllis.modernui.graphics.drawable.ColorDrawable(
                color(context, R.attr.colorSurfaceContainer)));
        int dp12 = base.dp(12);
        nav.setPadding(0, dp12, 0, dp12);
        {
            var logo = new TextView(context);
            logo.setText("CraftPy");
            logo.setTextAppearance(R.attr.textAppearanceTitleMedium);
            logo.setTextColor(color(context, R.attr.colorPrimary));
            logo.setGravity(Gravity.CENTER);
            var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            lp.bottomMargin = base.dp(16);
            nav.addView(logo, lp);
        }
        nav.addView(navButton(context, PAGE_HOME, "Home"));
        nav.addView(navButton(context, PAGE_MODULES, "Modules"));
        nav.addView(navButton(context, PAGE_EDITOR, "Editor"));
        nav.addView(navButton(context, PAGE_DOCS, "Docs"));
        nav.check(initialPage);
        nav.setOnCheckedChangeListener((group, checkedId) -> getChildFragmentManager().beginTransaction()
                .replace(ID_CONTAINER, pageFragment(checkedId), null)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .setReorderingAllowed(true)
                .commit());
        base.addView(nav, new LinearLayout.LayoutParams(base.dp(104), MATCH_PARENT));

        var pages = new FragmentContainerView(context);
        pages.setId(ID_CONTAINER);
        base.addView(pages, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));

        // Dialogs and floating hints are stacked on the page inside this host (see Overlays).
        var host = new Overlays.Host(context);
        host.addView(base, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        host.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        store(s -> s.setListener(pendingProjectListener));
        return host;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        store(s -> s.clearListener(pendingProjectListener));
        nav = null;
    }

    /** Switches the visible page (UI thread). */
    public void navigate(int page) {
        if (nav != null) {
            nav.check(page);
        }
    }

    /** Navigates from a page fragment to another page. */
    static void navigate(Fragment page, int target) {
        if (page.getParentFragment() instanceof CraftPyFragment root) {
            root.navigate(target);
        }
    }

    /** Shows or hides the navigation rail (zen mode). */
    static void setNavVisible(Fragment page, boolean visible) {
        if (page.getParentFragment() instanceof CraftPyFragment root && root.nav != null) {
            root.nav.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private static Fragment pageFragment(int page) {
        return switch (page) {
            case PAGE_MODULES -> new ModulesFragment();
            case PAGE_EDITOR -> new EditorFragment();
            case PAGE_DOCS -> new DocsFragment();
            default -> new HomeFragment();
        };
    }

    private RadioButton navButton(Context context, int id, String text) {
        var button = new RadioButton(context, null, null, null);
        button.setId(id);
        button.setText(text);
        button.setClickable(true);
        button.setOnClickListener(v -> navigate(id));
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{R.attr.state_checked}, StateSet.WILD_CARD},
                new int[]{color(context, R.attr.colorOnSecondaryContainer), color(context, R.attr.colorOnSurfaceVariant)}));
        // Same pill indicator + ripple as Modern UI's navigation rail, with the label inside the pill.
        var indicator = new ShapeDrawable();
        indicator.setCornerRadius(1000);
        indicator.setColor(new ColorStateList(
                new int[][]{new int[]{R.attr.state_checked}, StateSet.WILD_CARD},
                new int[]{color(context, R.attr.colorSecondaryContainer), 0}));
        int onContainer = color(context, R.attr.colorOnSecondaryContainer);
        button.setBackground(new RippleDrawable(new ColorStateList(
                new int[][]{new int[]{R.attr.state_pressed}, new int[]{R.attr.state_hovered}, StateSet.WILD_CARD},
                new int[]{ColorStateList.modulateColor(onContainer, 0.12f),
                        ColorStateList.modulateColor(onContainer, 0.08f),
                        ColorStateList.modulateColor(onContainer, 0.08f)}),
                indicator, null));
        int dp8 = button.dp(8);
        var lp = new LinearLayout.LayoutParams(button.dp(84), button.dp(36));
        lp.setMargins(0, dp8 / 2, 0, dp8 / 2);
        button.setLayoutParams(lp);
        return button;
    }

    // ---- ScreenCallback ----

    @Override
    public boolean isBackKey(int keyCode, @NonNull KeyEvent event) {
        // Only Escape: the inventory key must stay typeable inside the editor.
        return keyCode == KeyEvent.KEY_ESCAPE;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldBlurBackground() {
        return false;
    }

    // ---- shared helpers for the pages ----

    /** Runs a store action on the client thread. */
    static void store(Consumer<ClientProjectStore> action) {
        IdeHost.get().execute(() -> action.accept(ClientProjectStore.getInstance()));
    }

    static int color(Context context, String attr) {
        var value = new TypedValue();
        context.getTheme().resolveAttribute(R.ns, attr, value, true);
        return value.data;
    }

    static Button button(Context context, String text, ResourceId style, View.OnClickListener onClick) {
        var button = new Button(context, null, style);
        button.setText(text);
        button.setOnClickListener(onClick);
        return button;
    }

    static TextView label(Context context, String text, ResourceId appearance) {
        var tv = new TextView(context);
        tv.setText(text);
        tv.setTextAppearance(appearance);
        return tv;
    }

    /** An outlined, rounded card like the ones in Modern UI's preferences. */
    static LinearLayout card(Context context) {
        var card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        ThemeControl.makeOutlinedCard(context, card, new TypedValue());
        return card;
    }

    /** Hover/press highlight for clickable rows (rounded, theme tinted). */
    static void hoverBackground(View view) {
        int onSurface = color(view.getContext(), R.attr.colorOnSurface);
        var mask = new ShapeDrawable();
        mask.setCornerRadius(view.dp(8));
        mask.setColor(0xFFFFFFFF);
        view.setBackground(new RippleDrawable(new ColorStateList(
                new int[][]{new int[]{R.attr.state_pressed}, StateSet.WILD_CARD},
                new int[]{ColorStateList.modulateColor(onSurface, 0.12f),
                        ColorStateList.modulateColor(onSurface, 0.08f)}),
                null, mask));
    }

    /** Rounded pill background for the selected tab/chip; hover highlight otherwise. */
    static void styleChip(View view, boolean selected) {
        if (selected) {
            var bg = new ShapeDrawable();
            bg.setCornerRadius(view.dp(8));
            bg.setColor(color(view.getContext(), R.attr.colorSecondaryContainer));
            view.setBackground(bg);
        } else {
            hoverBackground(view);
        }
    }

    static LinearLayout row(Context context) {
        var row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    /**
     * Small anchored dialog. With {@code initial != null} it shows a text field and passes the
     * typed value to {@code onConfirm}; otherwise it is a plain confirmation.
     */
    static void prompt(View anchor, String title, @Nullable String initial, String confirm,
                       Consumer<String> onConfirm) {
        Context context = anchor.getContext();
        var dialog = Overlays.dialog(anchor, title, 320, -1);
        EditText input = null;
        if (initial != null) {
            input = new EditText(context, null, R.attr.editTextOutlinedStyle);
            input.setSingleLine();
            input.setText(initial);
            input.selectAll();
            var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            lp.topMargin = dialog.card.dp(8);
            dialog.body.addView(input, lp);
        }
        final EditText field = input;
        Runnable accept = () -> {
            String value = field == null ? "" : field.getText().toString().trim();
            if (field != null && value.isEmpty()) {
                return;
            }
            dialog.dismiss();
            onConfirm.accept(value);
        };
        var buttons = row(context);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(button(context, confirm, R.attr.buttonTonalStyle, v -> accept.run()));
        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.topMargin = dialog.card.dp(8);
        dialog.body.addView(buttons, lp);
        if (field != null) {
            field.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEY_ENTER || keyCode == KeyEvent.KEY_KP_ENTER)) {
                    accept.run();
                    return true;
                }
                return false;
            });
        }
        dialog.show();
        if (field != null) {
            field.requestFocus();
        }
    }

    private static ShapeDrawable filledBackground(View view) {
        var bg = new ShapeDrawable();
        bg.setCornerRadius(view.dp(12));
        bg.setColor(color(view.getContext(), R.attr.colorSurfaceContainerHigh));
        return bg;
    }
}
