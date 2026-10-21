package net.xmilon.craftpy.client.mui;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.LinearLayout;
import net.minecraft.client.Minecraft;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.IdeHost;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The Editor tab: the way into the full editor (projects, code, UI maker, building blocks, docs). The editor opens as
 * its own screen; its rail has a first entry (and Esc at its root) that comes back here, and anything unsaved is kept by
 * the editor's own store or asked about before leaving.
 */
public final class HubEditorFragment extends Fragment {
    private LinearLayout page;
    private final Runnable storeListener = () -> {
        View view = page;
        if (view != null) {
            boolean allowed = allowed(), unlocked = ClientProjectStore.getInstance().ideKeyUnlocked();
            String project = ClientProjectStore.getInstance().selectedId();
            view.post(() -> {
                if (page == view) {
                    render(allowed, unlocked, project);
                }
            });
        }
    };

    /** The same rule as the editor key: a server admin or the owner of a singleplayer world (the server checks again). */
    private static boolean allowed() {
        if (!IdeHost.get().inGame()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && (mc.player.hasPermissions(2) || mc.hasSingleplayerServer());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        var context = requireContext();
        page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = page.dp(20);
        page.setPadding(pad, pad, pad, pad);
        render(false, false, null);
        store(s -> {
            s.setListener(storeListener);
            storeListener.run();
        });
        return page;
    }

    @Override
    public void onDestroyView() {
        store(s -> s.clearListener(storeListener));
        page = null;
        super.onDestroyView();
    }

    private void render(boolean allowed, boolean unlocked, @Nullable String project) {
        if (page == null) {
            return;
        }
        var context = page.getContext();
        page.removeAllViews();
        page.addView(label(context, "Editor", R.attr.textAppearanceHeadlineMedium));
        page.addView(HubFragment.muted(context, "The full editor: write Python for your world, draw windows in the UI maker, "
                + "insert building blocks, read the docs, and build modules. Server administrators also manage the server's "
                + "modules there."));

        boolean usable = allowed && unlocked;
        var open = button(context, project == null ? "Open editor" : "Open editor: " + project, R.attr.buttonTonalStyle,
                v -> launch(project == null ? PAGE_HOME : PAGE_EDITOR));
        var projects = button(context, "Projects and templates", R.attr.borderlessButtonStyle, v -> launch(PAGE_HOME));
        var docs = button(context, "Docs", R.attr.borderlessButtonStyle, v -> launch(PAGE_DOCS));
        var modules = button(context, "Administer modules", R.attr.borderlessButtonStyle, v -> launch(PAGE_MODULES));
        for (Button b : new Button[]{open, projects, docs, modules}) {
            b.setEnabled(usable);
        }
        var actions = row(context);
        actions.addView(open);
        actions.addView(projects);
        actions.addView(docs);
        actions.addView(modules);
        var actionsLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        actionsLp.topMargin = page.dp(14);
        page.addView(actions, actionsLp);

        if (!usable) {
            var why = label(context, !allowed
                    ? "The editor needs permission: server operators, or the owner of a singleplayer world."
                    : "Craft a CraftPy Computer to unlock the editor (the computer block opens it until then).",
                    R.attr.textAppearanceBodyMedium);
            why.setTextColor(IdeColors.WARNING);
            var whyLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            whyLp.topMargin = page.dp(10);
            page.addView(why, whyLp);
        }
        var note = HubFragment.muted(context, "Leaving the editor (Esc or the arrow at the top of its rail) brings you back to "
                + "this tab. Open files and their unsaved text are kept.");
        var noteLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        noteLp.topMargin = page.dp(18);
        page.addView(note, noteLp);
    }

    private void launch(int editorPage) {
        View anchor = page;
        if (anchor == null) {
            return;
        }
        // Modern UI runs this on its own thread; screens are changed on the game's.
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(CraftPyFragment.createScreen(editorPage, true)));
    }
}
