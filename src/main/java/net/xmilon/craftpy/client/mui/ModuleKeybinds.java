package net.xmilon.craftpy.client.mui;

import icyllis.modernui.R;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.LinearLayout;
import java.util.List;
import net.xmilon.craftpy.client.IdeHost;
import net.xmilon.craftpy.client.ModuleKeys;
import net.xmilon.craftpy.keys.BindingStore;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/** Preferences include inactive modules and servers; no Python execution is needed to edit them. */
final class ModuleKeybinds {
    private ModuleKeybinds() { }

    static void open(View anchor) {
        var dialog = ListDialog.open(anchor, "Module Keybinds", true, 860, 440);
        dialog.setMessage("Use ctrl, shift and alt together, for example ctrl+shift+h. Empty means unbound. "
                + "Edit combinations with multiple modifiers here; Controls supports only one modifier and shows these combinations as unbound.");
        Runnable refresh = () -> IdeHost.get().execute(() -> {
            try {
                var entries = ModuleKeys.entries();
                anchor.post(() -> {
                    if (!dialog.isShowing()) return;
                    dialog.setRows(entries.stream().map(entry -> ListDialog.Row.card(entry.action().name(),
                            entry.id().owner() + " " + ModuleKeys.scopeLabel(entry.id().scope()) + " " + entry.action().category(), context -> {
                        var card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL);
                        card.addView(label(context, entry.action().name()
                                + (entry.active() ? "" : " · inactive"), R.attr.textAppearanceTitleSmall));
                        card.addView(label(context, entry.action().category() + " · " + ModuleKeys.scopeLabel(entry.id().scope()), R.attr.textAppearanceBodySmall));
                        var controls = row(context);
                        var field = field(context); field.setSingleLine(); field.setText(entry.assigned()); field.setHint("Unbound");
                        controls.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
                        var status = label(context, "", R.attr.textAppearanceBodySmall);
                        java.util.function.Consumer<String> apply = value -> IdeHost.get().execute(() -> {
                            try {
                                ModuleKeys.assign(entry.id(), value);
                                List<String> conflicts = ModuleKeys.conflicts(entry.id(), value);
                                field.post(() -> {
                                    field.setText(net.xmilon.craftpy.client.KeyCombo.normalize(value));
                                    status.setText(conflicts.isEmpty() ? "Saved" : "Conflict: " + String.join(", ", conflicts));
                                });
                            } catch (Exception error) { status.post(() -> status.setText(error.getMessage())); }
                        });
                        controls.addView(button(context, "Apply", R.attr.borderlessButtonStyle, v -> apply.accept(field.getText().toString())));
                        controls.addView(button(context, "Reset", R.attr.borderlessButtonStyle, v -> apply.accept(entry.action().combo())));
                        controls.addView(button(context, "Unbind", R.attr.borderlessButtonStyle, v -> apply.accept("")));
                        card.addView(controls); card.addView(status);
                        IdeHost.get().execute(() -> {
                            var conflicts = ModuleKeys.conflicts(entry.id(), entry.assigned());
                            if (!conflicts.isEmpty()) status.post(() -> status.setText("Conflict: " + String.join(", ", conflicts)));
                        });
                        return card;
                    })).toList());
                });
            } catch (RuntimeException error) { anchor.post(() -> dialog.setMessage(error.getMessage())); }
        });
        dialog.addAction("Refresh", refresh); refresh.run();
    }
}
