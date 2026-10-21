package net.xmilon.craftpy.client.mui;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.LinearLayout;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/** Marketplace: a placeholder. Nothing is browsed, downloaded or installed from here yet. */
public final class HubMarketplaceFragment extends Fragment {
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        var context = requireContext();
        var page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        int pad = page.dp(24);
        page.setPadding(pad, pad, pad, pad);
        page.addView(tag(context, "COMING SOON", IdeColors.WARNING));
        var title = label(context, "Marketplace", R.attr.textAppearanceHeadlineMedium);
        title.setGravity(Gravity.CENTER);
        var titleLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        titleLp.topMargin = page.dp(10);
        page.addView(title, titleLp);
        var text = HubFragment.muted(context, "A place to find and share modules is planned. For now, install a module by "
                + "putting its .cpm file into pyforge/modules (Modules > Open folder), and read what it asks for before "
                + "you allow it.");
        text.setGravity(Gravity.CENTER);
        text.setMaxWidth(page.dp(480));
        var textLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        textLp.topMargin = page.dp(8);
        page.addView(text, textLp);
        var go = button(context, "Open Modules", R.attr.buttonTonalStyle, v -> {
            HubFragment hub = HubFragment.of(this);
            if (hub != null) {
                hub.switchTab(HubFragment.TAB_MODULES);
            }
        });
        var goLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        goLp.topMargin = page.dp(14);
        page.addView(go, goLp);
        return page;
    }
}
