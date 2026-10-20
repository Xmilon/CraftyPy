package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.markflow.Markflow;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.ScreenCallback;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.CheckBox;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.xmilon.craftpy.client.ServerPack;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * Shown while joining a server whose modules ask for permissions (the web, files, commands, the Java bridge), before
 * anything of the server is loaded: which modules, what they can do, and their README when clicked. Continue joins
 * (optionally without asking again until the list changes), Leave disconnects. Escape does nothing: one of the two
 * must be chosen. Opened later with {@code /craftpy_client server_modules} it only has Close.
 */
public final class ServerModulesFragment extends Fragment implements ScreenCallback {
    private final JsonObject info;
    private final boolean joining;
    private boolean answered;
    @Nullable
    private TextView readme;
    @Nullable
    private Markflow markflow;
    private final List<View> rows = new ArrayList<>();

    private ServerModulesFragment(JsonObject info, boolean joining) {
        this.info = info;
        this.joining = joining;
    }

    public static Screen screen(JsonObject info) {
        return MuiModApi.get().createScreen(new ServerModulesFragment(info, ServerPack.joining()));
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        Context context = requireContext();
        var root = new FrameLayout(context);
        root.setBackground(new ColorDrawable(0xCC000000));
        var card = card(context);

        var title = label(context, "⚠ This server runs modules that ask for permissions", R.attr.textAppearanceTitleMedium);
        title.setTextColor(IdeColors.WARNING);
        card.addView(title);
        var intro = label(context, "These modules run on the server, not on your computer, but they can reach the web, "
                + "files or server commands. The CraftPy author is not responsible for what server owners or module "
                + "authors do with them. Join only servers, and trust only module authors, you trust. Click a module to "
                + "read its README.", R.attr.textAppearanceBodySmall);
        intro.setTextColor(IdeColors.TEXT_SECONDARY);
        var introLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        introLp.topMargin = card.dp(4);
        card.addView(intro, introLp);

        var body = row(context);
        body.setGravity(Gravity.TOP);
        var list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        var listScroll = new ScrollView(context);
        listScroll.addView(list, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        body.addView(listScroll, new LinearLayout.LayoutParams(card.dp(220), MATCH_PARENT));

        readme = label(context, "", R.attr.textAppearanceBodyMedium);
        markflow = HelpPanel.markflow(context);
        var readmeScroll = new ScrollView(context);
        readmeScroll.addView(readme, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        var readmeLp = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1);
        readmeLp.setMarginStart(card.dp(12));
        body.addView(readmeScroll, readmeLp);
        var bodyLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
        bodyLp.topMargin = card.dp(10);
        card.addView(body, bodyLp);

        List<JsonObject> modules = ServerPack.modules(info);
        for (JsonObject module : modules) {
            list.addView(moduleRow(context, module), new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
        if (!modules.isEmpty()) {
            select(0, modules.get(0));
        }

        var actions = row(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        var actionsLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        actionsLp.topMargin = card.dp(10);
        if (joining) {
            var remember = new CheckBox(context);
            remember.setText("Don't show again for this server (until its modules change)");
            actions.addView(remember, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            actions.addView(button(context, "Leave", R.attr.borderlessButtonStyle, v -> {
                answered = true;
                ServerPack.leave();
            }));
            actions.addView(button(context, "Continue", R.attr.buttonStyle, v -> {
                answered = true;
                // no world yet: an empty screen would show the title screen, so say what happens instead
                Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.GenericMessageScreen(
                        net.minecraft.network.chat.Component.literal("Joining...")));
                ServerPack.continueJoin(remember.isChecked());
            }));
        } else {
            actions.addView(new View(context), new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            actions.addView(button(context, "Close", R.attr.buttonStyle, v -> Minecraft.getInstance().setScreen(null)));
        }
        card.addView(actions, actionsLp);

        root.addView(card, new FrameLayout.LayoutParams(card.dp(640), card.dp(400), Gravity.CENTER));
        return root;
    }

    private View moduleRow(Context context, JsonObject module) {
        var row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = row.dp(6);
        row.setPadding(pad, pad, pad, pad);
        hoverBackground(row);
        String name = text(module, "name");
        row.addView(label(context, (name.isBlank() ? text(module, "id") : name) + "  " + text(module, "version"),
                R.attr.textAppearanceLabelLarge));
        List<TextView> chips = new ArrayList<>();
        if (module.has("can") && module.get("can").isJsonArray()) {
            for (JsonElement can : module.getAsJsonArray("can")) {
                String label = can.getAsString();
                boolean danger = label.startsWith("Java") || label.equals("commands") || label.startsWith("web");
                chips.add(tag(context, label, danger ? IdeColors.ERROR : IdeColors.WARNING));
            }
        }
        var chipRow = tags(context, chips);
        var chipLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        chipLp.topMargin = row.dp(2);
        row.addView(chipRow, chipLp);
        int index = rows.size();
        rows.add(row);
        row.setOnClickListener(v -> select(index, module));
        return row;
    }

    private void select(int index, JsonObject module) {
        for (int i = 0; i < rows.size(); i++) {
            styleChip(rows.get(i), i == index);
        }
        if (readme == null || markflow == null) {
            return;
        }
        String text = text(module, "readme");
        String warning = text(module, "warning");
        String markdown = (warning.isBlank() ? "" : "> **" + warning + "**\n\n")
                + (text.isBlank() ? "*This module has no README.*" : text);
        markflow.setMarkdown(readme, markdown);
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        readme = null;
        markflow = null;
        rows.clear();
        if (joining && !answered) {
            ServerPack.leave(); // closed some other way while joining: the safe answer
        }
    }

    @Override
    public boolean isBackKey(int keyCode, @NonNull KeyEvent event) {
        return !joining && keyCode == KeyEvent.KEY_ESCAPE;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldBlurBackground() {
        return false;
    }
}
