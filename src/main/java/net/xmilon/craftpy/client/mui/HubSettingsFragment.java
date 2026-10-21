package net.xmilon.craftpy.client.mui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.Switch;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.xmilon.craftpy.Config;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.client.EditorSettings;
import net.xmilon.craftpy.client.EditorTheme;
import net.xmilon.craftpy.client.IdeHost;
import net.xmilon.craftpy.python.PyApi;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The Settings tab. Two kinds, kept apart: what belongs to this player and this computer (changed here), and what the
 * server decides (shown, never editable here; on someone else's server its values are not even known to this client).
 */
public final class HubSettingsFragment extends Fragment {
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        Context context = requireContext();
        var page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = page.dp(20);
        page.setPadding(pad, pad, pad, pad);
        page.addView(label(context, "Settings", R.attr.textAppearanceHeadlineMedium));

        EditorSettings editor = EditorSettings.get();
        page.addView(section(context, "Yours", "Saved on this computer, for you only", IdeColors.ACCENT));
        page.addView(group(context, "Editor"));
        page.addView(stepper(context, "Color scheme", () -> editor.theme().label, step -> {
            EditorTheme[] all = EditorTheme.values();
            editor.theme = all[Math.floorMod(editor.theme().ordinal() + step, all.length)].name();
        }, editor));
        page.addView(stepper(context, "Font size", () -> Math.round(editor.fontSize) + "",
                step -> editor.fontSize = Math.clamp(editor.fontSize + step, 8, 32), editor));
        page.addView(toggle(context, "Minimap", () -> editor.minimap, v -> {
            editor.minimap = v;
            editor.save();
        }));
        page.addView(toggle(context, "Indent guides", () -> editor.indentGuides, v -> {
            editor.indentGuides = v;
            editor.save();
        }));
        page.addView(toggle(context, "Sticky scroll (pin the def you are in)", () -> editor.stickyScroll, v -> {
            editor.stickyScroll = v;
            editor.save();
        }));
        page.addView(toggle(context, "Trim trailing spaces on save", () -> editor.trimOnSave, v -> {
            editor.trimOnSave = v;
            editor.save();
        }));

        page.addView(group(context, "Keys"));
        var keys = row(context);
        keys.addView(button(context, "Module keybinds", R.attr.buttonTonalStyle, ModuleKeybinds::open));
        var keysNote = HubFragment.muted(context, "Every shortcut a module adds. They also appear in Minecraft's Controls "
                + "(one modifier key only there). The Hub opens with the key that used to open the IDE.");
        var noteLp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        noteLp.setMarginStart(page.dp(10));
        keys.addView(keysNote, noteLp);
        page.addView(keys, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        page.addView(group(context, "Modules and sound"));
        page.addView(configToggle(context, "Run client-side modules on this computer (from the next RELOAD)",
                Config.ENABLE_CLIENT_PYTHON));
        page.addView(configToggle(context, "Hear music that nearby players share", Config.MEDIA_HEAR));

        page.addView(section(context, "The server", serverNote(), IdeColors.TEXT_SECONDARY));
        for (View row : serverRows(context)) {
            page.addView(row);
        }

        var scroll = new ScrollView(context);
        scroll.addView(page, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        return scroll;
    }

    private static boolean ownServer() {
        return IdeHost.get().inGame() && Minecraft.getInstance().hasSingleplayerServer();
    }

    private static String serverNote() {
        return ownServer() ? "Your own world: read-only here. Edit config/craftpy-common.toml (Mods > CraftPy > Config)."
                : "Decided by the server's operators. This computer does not know the values.";
    }

    /** What the server decides; values only where this computer is the server. */
    private static java.util.List<View> serverRows(Context context) {
        var rows = new java.util.ArrayList<View>();
        var player = IdeHost.get().inGame() ? Minecraft.getInstance().player : null;
        rows.add(info(context, "Your access", player != null && (player.hasPermissions(2) || ownServer())
                ? "The editor and the server's modules" : "The Hub, your client modules and server apps"));
        if (!ownServer()) {
            return rows;
        }
        rows.add(info(context, "Scripting", Config.get(Config.ENABLE_SCRIPTING) ? "enabled" : "disabled"));
        rows.add(info(context, "Permission level for the editor", String.valueOf(Config.get(Config.PERMISSION_LEVEL))));
        rows.add(info(context, "Hot reload on save", Config.get(Config.HOT_RELOAD_ON_SAVE) ? "on" : "off"));
        rows.add(info(context, "Java bridge", Config.get(Config.JAVA_BRIDGE_MODE).toString().toLowerCase(java.util.Locale.ROOT)));
        rows.add(info(context, "Share music with nearby players", Config.get(Config.MEDIA_SHARE)
                ? "on, within " + Config.get(Config.MEDIA_RANGE) + " blocks, files up to " + Config.get(Config.MEDIA_MAX_MB) + " MB"
                : "off"));
        rows.add(info(context, "Largest asset a module may upload", Config.get(Config.MAX_ASSET_SIZE_KB) + " KB"));
        rows.add(info(context, "Python API", PyApi.version()));
        return rows;
    }

    // ---- rows

    private static View section(Context context, String title, String note, int color) {
        var box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, box.dp(18), 0, box.dp(4));
        var head = row(context);
        head.addView(tag(context, title.toUpperCase(java.util.Locale.ROOT), color));
        var text = HubFragment.muted(context, note);
        var lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        lp.setMarginStart(box.dp(8));
        head.addView(text, lp);
        box.addView(head);
        return box;
    }

    private static View group(Context context, String title) {
        var view = label(context, title, R.attr.textAppearanceTitleSmall);
        view.setPadding(0, view.dp(12), 0, view.dp(2));
        return view;
    }

    private static View info(Context context, String key, String value) {
        var row = row(context);
        row.setPadding(row.dp(4), row.dp(3), row.dp(4), row.dp(3));
        var name = label(context, key, R.attr.textAppearanceBodyMedium);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        var shown = label(context, value, R.attr.textAppearanceBodyMedium);
        shown.setTextColor(IdeColors.TEXT_SECONDARY);
        shown.setGravity(Gravity.END);
        row.addView(shown, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        return row;
    }

    private static View toggle(Context context, String text, BooleanSupplier get, Consumer<Boolean> set) {
        var row = row(context);
        row.setPadding(row.dp(4), row.dp(2), row.dp(4), row.dp(2));
        var name = label(context, text, R.attr.textAppearanceBodyMedium);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        var control = new Switch(context);
        control.setChecked(get.getAsBoolean());
        control.setOnCheckedChangeListener((v, checked) -> set.accept(checked));
        row.addView(control);
        return row;
    }

    private static View configToggle(Context context, String text, ModConfigSpec.BooleanValue value) {
        return toggle(context, text, () -> Config.get(value), checked -> {
            try {
                value.set(checked);
                Config.SPEC.save();
            } catch (RuntimeException notLoaded) {
                CraftPyLog.LOGGER.warn("Could not save the setting: {}", notLoaded.toString());
            }
        });
    }

    private static View stepper(Context context, String text, Supplier<String> get, IntConsumer step, EditorSettings editor) {
        var row = row(context);
        row.setPadding(row.dp(4), row.dp(2), row.dp(4), row.dp(2));
        var name = label(context, text, R.attr.textAppearanceBodyMedium);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        TextView value = label(context, get.get(), R.attr.textAppearanceLabelLarge);
        value.setGravity(Gravity.CENTER);
        row.addView(button(context, "−", R.attr.borderlessButtonStyle, v -> {
            step.accept(-1);
            editor.save();
            value.setText(get.get());
        }));
        row.addView(value, new LinearLayout.LayoutParams(row.dp(110), WRAP_CONTENT));
        row.addView(button(context, "+", R.attr.borderlessButtonStyle, v -> {
            step.accept(1);
            editor.save();
            value.setText(get.get());
        }));
        return row;
    }
}
