package net.xmilon.craftpy.client.mui;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.client.IdeHost;
import net.xmilon.craftpy.project.ModuleApp;

/**
 * The picture of an app or a module: an item drawn by Minecraft, a PNG of the module's own assets, or - when there is
 * none, it is missing or it cannot be shown outside the game - the coloured letters every project gets.
 */
final class HubIcons {
    private HubIcons() {
    }

    /** A square view of {@code sizeDp}; {@code owner} is the module or project id the PNG belongs to. */
    static View icon(Context context, String owner, String name, String icon, int sizeDp) {
        View picture = picture(context, owner, icon);
        View view = picture != null ? picture : letters(context, name, sizeDp);
        var holder = new FrameLayout(context);
        int size = holder.dp(sizeDp);
        holder.addView(view, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        holder.setMinimumWidth(size);
        holder.setMinimumHeight(size);
        return holder;
    }

    private static View picture(Context context, String owner, String icon) {
        if (icon == null || icon.isEmpty() || !IdeHost.get().inGame()) {
            return null;
        }
        if (ModuleApp.isItem(icon)) {
            return McPainters.stack(icon) == null ? null : new McCanvas(context, WindowFragment.itemPainter(icon));
        }
        ResourceLocation location = ResourceLocation.tryBuild(owner, icon);
        if (location == null || Minecraft.getInstance().getResourceManager().getResource(location).isEmpty()) {
            return null;
        }
        return new McCanvas(context, (g, w, h, mx, my, partial) -> g.blit(location, 0, 0, w, h, 0f, 0f, 1, 1, 1, 1));
    }

    private static TextView letters(Context context, String name, int sizeDp) {
        var view = new PlainText(context);
        IdeColors.avatar(view, name);
        view.setTextSize(Math.max(10, sizeDp / 3f));
        return view;
    }
}
