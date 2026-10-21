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
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.ScreenCallback;
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
    /** Not a page: the rail entry that returns to the Hub (only when the Hub opened this screen). */
    private static final int PAGE_HUB = 1000;
    private static final int ID_CONTAINER = 0x2101;

    private final int initialPage;
    /** The Hub opened this screen: Esc at its root and a rail entry lead back to the Hub. */
    private final boolean fromHub;
    private Runnable removeBack = () -> { };
    private LinearLayout nav;
    private int current;

    /** Client thread: jumps into the editor when the server asked for a project ({@code /craftpy ide project}). */
    private final Runnable pendingProjectListener = () -> {
        ClientProjectStore store = ClientProjectStore.getInstance();
        String pending = store.pendingIdeProject();
        if (pending == null || pending.isBlank() || store.find(pending) == null) {
            return;
        }
        store.setPendingIdeProject(null);
        store.select(pending);
        LinearLayout view = nav;
        if (view != null) {
            view.post(() -> navigate(PAGE_EDITOR));
        }
    };

    public CraftPyFragment() {
        this(PAGE_HOME);
    }

    public CraftPyFragment(int initialPage) {
        this(initialPage, false);
    }

    public CraftPyFragment(int initialPage, boolean fromHub) {
        this.initialPage = initialPage;
        this.fromHub = fromHub;
    }

    /** Creates the standalone (keybind) screen. */
    /** The server said to open the IDE (computer block, /craftpy ide): a plain screen unless one is open already. */
    public static void openFromServer(boolean editor) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (!(minecraft.screen instanceof icyllis.modernui.mc.MuiScreen mui && mui.getFragment() instanceof CraftPyFragment)) {
            minecraft.setScreen(createScreen(editor ? PAGE_EDITOR : PAGE_HOME));
        }
    }

    public static Screen createScreen(int page) {
        return MuiModApi.get().createScreen(new CraftPyFragment(page));
    }

    /** The editor screens the Hub opens: they return to the Hub's Editor tab. */
    public static Screen createScreen(int page, boolean fromHub) {
        return MuiModApi.get().createScreen(new CraftPyFragment(page, fromHub));
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
        base.setDividerDrawable(IdeColors.divider(base, true));
        base.setBackground(new icyllis.modernui.graphics.drawable.ColorDrawable(IdeColors.EDITOR));

        // VS Code's activity bar: one icon per page, the open one marked by an accent bar on its left edge
        nav = new LinearLayout(context);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setGravity(Gravity.CENTER_HORIZONTAL);
        nav.setBackground(new icyllis.modernui.graphics.drawable.ColorDrawable(IdeColors.PANEL));
        nav.setPadding(0, base.dp(6), 0, base.dp(6));
        if (fromHub) {
            nav.addView(navItem(context, PAGE_HUB, IdeIcon.Glyph.BACK, "Back to the Hub (Esc)"));
        }
        nav.addView(navItem(context, PAGE_HOME, IdeIcon.Glyph.HOME, "Home: projects and templates"));
        nav.addView(navItem(context, PAGE_EDITOR, IdeIcon.Glyph.CODE, "Editor"));
        nav.addView(navItem(context, PAGE_MODULES, IdeIcon.Glyph.MODULES, "Modules: installed .cpm files"));
        nav.addView(navItem(context, PAGE_DOCS, IdeIcon.Glyph.BOOK, "Docs: the Python API and guides"));
        current = initialPage;
        updateNav();
        base.addView(nav, new LinearLayout.LayoutParams(base.dp(44), MATCH_PARENT));

        var pages = new FragmentContainerView(context);
        pages.setId(ID_CONTAINER);
        base.addView(pages, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));

        // Dialogs and floating hints are stacked on the page inside this host (see Overlays).
        var host = new Overlays.Host(context);
        host.addView(base, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        host.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        store(s -> s.setListener(pendingProjectListener));
        // Registered first, so asked last: Esc that nothing in the page wanted goes back to the Hub
        host.post(() -> removeBack = Overlays.onBack(host, () -> {
            if (!fromHub) {
                return false;
            }
            leaveGuarded();
            return true;
        }));
        return host;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        removeBack.run();
        removeBack = () -> { };
        store(s -> s.clearListener(pendingProjectListener));
        nav = null;
    }

    /** Switches the visible page (UI thread). */
    public void navigate(int page) {
        if (page == PAGE_HUB) {
            leaveGuarded();
            return;
        }
        if (nav == null || page == current) {
            return;
        }
        if (current == PAGE_MODULES && getChildFragmentManager().findFragmentById(ID_CONTAINER) instanceof ModulesFragment modules) {
            modules.requestLeave(() -> navigateConfirmed(page));
            return;
        }
        navigateConfirmed(page);
    }

    /** Back to the Hub from the page that is open, after that page's own question (pending module changes, unsaved dock edits). */
    private void leaveGuarded() {
        var page = getChildFragmentManager().findFragmentById(ID_CONTAINER);
        if (page instanceof ModulesFragment modules) {
            modules.requestLeave(this::leaveToHub);
        } else if (page instanceof EditorFragment editor) {
            editor.leaveGuarded(this::leaveToHub);
        } else {
            leaveToHub();
        }
    }

    private void leaveToHub() {
        IdeHost.get().execute(() -> {
            if (fromHub) {
                HubFragment.open(HubFragment.TAB_EDITOR);
            } else {
                IdeHost.get().closeScreen();
            }
        });
    }

    /** A page of this screen is done: the Hub when it came from there, otherwise the screen just closes. */
    static void leave(Fragment page) {
        if (page.getParentFragment() instanceof CraftPyFragment root) {
            root.leaveToHub();
        } else {
            IdeHost.get().execute(() -> IdeHost.get().closeScreen());
        }
    }

    private void navigateConfirmed(int page) {
        if (nav == null || page == current) {
            return;
        }
        current = page;
        updateNav();
        getChildFragmentManager().beginTransaction()
                .replace(ID_CONTAINER, pageFragment(page), null)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .setReorderingAllowed(true)
                .commit();
    }

    private void updateNav() {
        for (int i = 0; i < nav.getChildCount(); i++) {
            View item = nav.getChildAt(i);
            boolean selected = item.getId() == current;
            item.setSelected(selected);
            if (item instanceof ViewGroup group && group.getChildAt(0) instanceof IdeIcon icon) {
                icon.setTint(selected ? IdeColors.TEXT : IdeColors.TEXT_SECONDARY);
            }
            item.invalidate();
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

    /** One activity bar entry: the icon, a hover square, and the accent bar while its page is open. */
    private View navItem(Context context, int id, IdeIcon.Glyph glyph, String tooltip) {
        var item = new FrameLayout(context) {
            private final icyllis.modernui.graphics.Paint bar = new icyllis.modernui.graphics.Paint();

            @Override
            protected void dispatchDraw(@NonNull icyllis.modernui.graphics.Canvas canvas) {
                super.dispatchDraw(canvas);
                if (isSelected()) {
                    bar.setColor(IdeColors.ACCENT);
                    canvas.drawRect(0, dp(8), dp(2), getHeight() - dp(8), bar);
                }
            }
        };
        item.setId(id);
        item.setClickable(true);
        item.setTooltipText(tooltip);
        item.setOnClickListener(EditorFragment.hidingTooltip(tooltip, v -> navigate(id)));
        var icon = new IdeIcon(context, glyph);
        icon.setDuplicateParentStateEnabled(true);
        icon.setBackground(IdeColors.fading(item, 6, 0, IdeColors.SELECTED, IdeColors.HOVER, IdeColors.SELECTED, 0, 0));
        item.addView(icon, new FrameLayout.LayoutParams(item.dp(32), item.dp(32), Gravity.CENTER));
        item.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, item.dp(40)));
        return item;
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
        Integer palette = IdeColors.role(attr);
        if (palette != null) {
            return palette;
        }
        var value = new TypedValue();
        context.getTheme().resolveAttribute(R.ns, attr, value, true);
        return value.data;
    }

    static Button button(Context context, String text, ResourceId style, View.OnClickListener onClick) {
        var button = new Button(context, null, style);
        button.setText(text);
        button.setOnClickListener(onClick);
        IdeColors.styleButton(button, style);
        return button;
    }

    /** A text field in the IDE's look (Modern UI's outlined field, restyled). */
    static EditText field(Context context) {
        var field = new EditText(context, null, R.attr.editTextOutlinedStyle);
        IdeColors.styleField(field);
        return field;
    }

    static TextView label(Context context, String text, ResourceId appearance) {
        var tv = new PlainText(context);
        tv.setText(text);
        tv.setTextAppearance(appearance);
        return tv;
    }

    /** An outlined, rounded card like the ones in Modern UI's preferences. */
    static LinearLayout card(Context context) {
        var card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(IdeColors.card(card));
        int pad = card.dp(12);
        card.setPadding(pad, pad, pad, pad);
        return card;
    }

    /** Hover/press highlight for clickable rows (rounded, theme tinted). */
    static void hoverBackground(View view) {
        IdeColors.hoverBackground(view, 5);
    }

    /** A small outlined tag ("Changed", "web", "FULL TRUST") in {@code color}. */
    static TextView tag(Context context, String text, int color) {
        var tag = new TextView(context);
        tag.setText(text);
        tag.setTextSize(11);
        tag.setTextColor(color);
        tag.setPadding(tag.dp(6), tag.dp(1), tag.dp(6), tag.dp(1));
        var bg = new ShapeDrawable();
        bg.setCornerRadius(tag.dp(4));
        bg.setColor((color & 0x00FFFFFF) | 0x22000000);
        bg.setStroke(Math.max(1, tag.dp(1)), color);
        tag.setBackground(bg);
        return tag;
    }

    /** {@link #tag}s in a row, with a little space between them. */
    static LinearLayout tags(Context context, java.util.List<TextView> tags) {
        var row = row(context);
        for (TextView tag : tags) {
            var params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(row.dp(4));
            row.addView(tag, params);
        }
        return row;
    }

    /** Rounded pill background for the selected tab/chip; hover highlight otherwise. */
    static void styleChip(View view, boolean selected) {
        if (selected) {
            var bg = new ShapeDrawable();
            bg.setCornerRadius(view.dp(5));
            bg.setColor(IdeColors.SELECTED);
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
            input = field(context);
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
