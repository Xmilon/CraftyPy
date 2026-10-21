package net.xmilon.craftpy.client.mui;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.fragment.FragmentContainerView;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.ScreenCallback;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.HorizontalScrollView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.xmilon.craftpy.client.IdeHost;
import net.minecraft.client.gui.screens.Screen;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The Hub: CraftPy's front door. One card over the game with five tabs (Settings, Editor, Modules, Apps, Marketplace).
 * <p>
 * Back, Esc and the close button follow one rule: Esc closes the topmost popup first (see {@link Overlays}), then goes
 * back one level (any tab other than Apps returns to Apps; a page may have a level of its own, such as a selected
 * module), and closes the Hub only from Apps. The close button always closes the Hub. Leaving the Modules tab, by any
 * of these, asks about pending module changes first ({@link ModulesFragment#requestLeave}).
 */
public final class HubFragment extends Fragment implements ScreenCallback {
    public static final int TAB_SETTINGS = 1;
    public static final int TAB_EDITOR = 2;
    public static final int TAB_MODULES = 3;
    public static final int TAB_APPS = 4;
    public static final int TAB_MARKETPLACE = 5;
    private static final int[] TABS = {TAB_SETTINGS, TAB_EDITOR, TAB_MODULES, TAB_APPS, TAB_MARKETPLACE};
    private static final int ID_PAGES = 0x2201;
    /** The card never grows past this, and leaves this margin around itself on a small screen (dp). */
    private static final int MAX_WIDTH = 960;
    private static final int MAX_HEIGHT = 620;
    private static final int MARGIN = 12;

    private final int initialTab;
    private int tab;
    private Button back;
    private final java.util.Map<Integer, Button> tabButtons = new java.util.HashMap<>();
    private Runnable removeBack = () -> { };

    public HubFragment() {
        this(TAB_APPS);
    }

    public HubFragment(int tab) {
        this.initialTab = tab;
        this.tab = tab;
    }

    public static Screen createScreen(int tab) {
        return MuiModApi.get().createScreen(new HubFragment(tab));
    }

    /** Client thread. */
    public static void open(int tab) {
        Minecraft.getInstance().setScreen(createScreen(tab));
    }

    /** The Hub a page belongs to, or null (a page shown on its own, as in tests). */
    @Nullable
    static HubFragment of(Fragment page) {
        return page.getParentFragment() instanceof HubFragment hub ? hub : null;
    }

    static String tabName(int tab) {
        return switch (tab) {
            case TAB_SETTINGS -> "Settings";
            case TAB_EDITOR -> "Editor";
            case TAB_MODULES -> "Modules";
            case TAB_MARKETPLACE -> "Marketplace";
            default -> "Apps";
        };
    }

    private static Fragment pageFragment(int tab) {
        return switch (tab) {
            case TAB_SETTINGS -> new HubSettingsFragment();
            case TAB_EDITOR -> new HubEditorFragment();
            case TAB_MODULES -> new ModulesFragment(true);
            case TAB_MARKETPLACE -> new HubMarketplaceFragment();
            default -> new HubAppsFragment();
        };
    }

    @Override
    public void onCreate(@Nullable DataSet savedInstanceState) {
        super.onCreate(savedInstanceState);
        getChildFragmentManager().beginTransaction()
                .replace(ID_PAGES, pageFragment(initialTab), null)
                .setReorderingAllowed(true)
                .commit();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        Context context = requireContext();
        var card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(IdeColors.card(card));
        card.setClickable(true); // a click on the card must not reach the backdrop behind it
        card.setFocusableInTouchMode(true);

        var header = row(context);
        header.setPadding(card.dp(10), card.dp(8), card.dp(8), card.dp(8));
        back = button(context, "‹ Back", R.attr.borderlessButtonStyle, v -> goBack());
        back.setTooltipText("Back (Esc)");
        header.addView(back);
        var title = label(context, "CraftPy Hub", R.attr.textAppearanceTitleMedium);
        var titleLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        titleLp.setMarginStart(card.dp(6));
        titleLp.setMarginEnd(card.dp(6));
        header.addView(title, titleLp);
        var beta = tag(context, "BETA", IdeColors.WARNING);
        beta.setTooltipText("The Hub is new: tell us what is missing");
        header.addView(beta);

        var tabRow = row(context);
        for (int id : TABS) {
            Button button = button(context, tabName(id), R.attr.borderlessButtonStyle, v -> switchTab(id));
            tabButtons.put(id, button);
            tabRow.addView(button);
        }
        // a narrow screen scrolls the tabs instead of squeezing them
        var tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabRow, new HorizontalScrollView.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        var tabLp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        tabLp.setMarginStart(card.dp(12));
        header.addView(tabScroll, tabLp);

        var close = button(context, "×", R.attr.borderlessButtonStyle, v -> closeHub());
        close.setTooltipText("Close");
        header.addView(close);
        card.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        card.addView(new View(context) {
            { setBackground(IdeColors.divider(this, false)); }
        }, new LinearLayout.LayoutParams(MATCH_PARENT, Math.max(1, card.dp(1))));

        var pages = new FragmentContainerView(context);
        pages.setId(ID_PAGES);
        card.addView(pages, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        var stage = new Stage(context);
        stage.addView(card, new FrameLayout.LayoutParams(card.dp(MAX_WIDTH), card.dp(MAX_HEIGHT), Gravity.CENTER));
        stage.setBackground(new ColorDrawable(0x99000000));
        stage.setOnClickListener(v -> closeHub()); // a click outside the card, like a dialog's scrim

        var host = new Overlays.Host(context);
        host.addView(stage, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        host.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        updateChrome();
        host.post(() -> removeBack = Overlays.onBack(host, this::onBackAtRoot));
        return host;
    }

    @Override
    public void onDestroyView() {
        removeBack.run();
        removeBack = () -> { };
        tabButtons.clear();
        back = null;
        super.onDestroyView();
    }

    /** Sizes the card to the window: its maximum, or the window less a margin. */
    private static final class Stage extends FrameLayout {
        Stage(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            View card = getChildAt(0);
            if (card != null && card.getLayoutParams() instanceof FrameLayout.LayoutParams lp) {
                int w = MeasureSpec.getSize(widthMeasureSpec) - 2 * dp(MARGIN);
                int h = MeasureSpec.getSize(heightMeasureSpec) - 2 * dp(MARGIN);
                lp.width = Math.max(dp(240), Math.min(dp(MAX_WIDTH), w));
                lp.height = Math.max(dp(200), Math.min(dp(MAX_HEIGHT), h));
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    // ------------------------------------------------------------------
    // Navigation (UI thread)
    // ------------------------------------------------------------------

    int currentTab() {
        return tab;
    }

    private void updateChrome() {
        if (back != null) {
            back.setVisibility(tab == TAB_APPS ? View.GONE : View.VISIBLE);
        }
        tabButtons.forEach((id, button) -> styleChip(button, id == tab));
    }

    /** Opens another tab; leaving Modules first asks about pending changes. */
    void switchTab(int target) {
        if (target == tab || back == null) {
            return;
        }
        Runnable go = () -> showTab(target);
        if (tab == TAB_MODULES && getChildFragmentManager().findFragmentById(ID_PAGES) instanceof ModulesFragment modules) {
            modules.requestLeave(go);
        } else {
            go.run();
        }
    }

    private void showTab(int target) {
        if (back == null || target == tab) {
            return;
        }
        tab = target;
        updateChrome();
        getChildFragmentManager().beginTransaction()
                .replace(ID_PAGES, pageFragment(target), null)
                .setReorderingAllowed(true)
                .commit();
    }

    /** Back button: one level up; from Apps (the root) it closes. */
    void goBack() {
        if (!onBackAtRoot()) {
            closeHub();
        }
    }

    /** Esc after every popup and page handler declined: any tab but Apps returns to Apps. False at the root. */
    private boolean onBackAtRoot() {
        if (tab == TAB_APPS) {
            return false;
        }
        switchTab(TAB_APPS);
        return true;
    }

    /** The close button and the backdrop: leave the Hub from anywhere, after the pending-changes question. */
    void closeHub() {
        Runnable close = () -> IdeHost.get().execute(() -> IdeHost.get().closeScreen());
        if (tab == TAB_MODULES && getChildFragmentManager().findFragmentById(ID_PAGES) instanceof ModulesFragment modules) {
            modules.requestLeave(close);
        } else {
            close.run();
        }
    }

    // ------------------------------------------------------------------
    // ScreenCallback
    // ------------------------------------------------------------------

    @Override
    public boolean isBackKey(int keyCode, @NonNull KeyEvent event) {
        // Only Escape: the inventory key must stay typeable in the search fields.
        return keyCode == KeyEvent.KEY_ESCAPE;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean hasDefaultBackground() {
        return false;
    }

    @Override
    public boolean shouldBlurBackground() {
        return false;
    }

    /** Shows a short message under the Hub title area of a page: used for "nothing here" states. */
    static TextView muted(Context context, String text) {
        var view = label(context, text, R.attr.textAppearanceBodyMedium);
        view.setTextColor(IdeColors.TEXT_SECONDARY);
        return view;
    }
}
