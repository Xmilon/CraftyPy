package net.xmilon.craftpy.client.mui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.CheckBox;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.client.BuildForm;
import net.xmilon.craftpy.project.ModuleInfo;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * "Build module": pack the project into a .cpm and either update the module of this project (replacing the installed
 * file, so there is never a duplicate) or create a new module with its own id and name.
 */
final class BuildDialog {
    private final Overlays.Dialog dialog;
    private final EditText version;
    private final EditText newId;
    private final EditText newName;
    private final CheckBox install;
    private final TextView problem;
    private final View updateChip;
    private final View newChip;
    private final LinearLayout newFields;
    private final Set<String> installedIds = new HashSet<>();
    private boolean asNew;

    private BuildDialog(View anchor, String projectId, String projectName, String projectVersion,
                        List<ModuleInfo> installed, Consumer<String> onBuild) {
        Context context = anchor.getContext();
        ModuleInfo own = null;
        for (ModuleInfo module : installed) {
            installedIds.add(module.id);
            if (module.id.equals(projectId)) {
                own = module;
            }
        }
        dialog = Overlays.dialog(anchor, "Build module", 460, -1);
        var body = dialog.body;

        var modes = row(context);
        updateChip = button(context, own == null ? "Install " + projectId : "Update " + projectId,
                R.attr.borderlessButtonStyle, v -> setMode(false));
        newChip = button(context, "New module", R.attr.borderlessButtonStyle, v -> setMode(true));
        modes.addView(updateChip);
        modes.addView(newChip);
        body.addView(modes);

        String what = own == null
                ? "No module " + projectId + " is installed yet; building installs it."
                : "Replaces the installed module " + projectId + " (version " + own.version
                        + ") in place. Unload the project first if it is loaded, or it runs twice.";
        var hint = label(context, what, R.attr.textAppearanceBodySmall);
        hint.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        body.addView(hint, spaced(body));

        version = field(context, "Version");
        version.setText(projectVersion);
        body.addView(version, spaced(body));

        newFields = new LinearLayout(context);
        newFields.setOrientation(LinearLayout.VERTICAL);
        newId = field(context, "Module id (letters, digits, _)");
        newId.setText(projectId + "_2");
        newName = field(context, "Display name");
        newName.setText(projectName + " (copy)");
        newFields.addView(newId, spaced(body));
        newFields.addView(newName, spaced(body));
        var note = label(context, "A new module is a separate mod: its blocks and items use its id as their namespace.",
                R.attr.textAppearanceBodySmall);
        note.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        newFields.addView(note, spaced(body));
        body.addView(newFields, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        install = new CheckBox(context);
        install.setText("Install into the game (modules folder); off = only export to exports/");
        install.setChecked(true);
        body.addView(install, spaced(body));

        problem = label(context, "", R.attr.textAppearanceLabelMedium);
        problem.setTextColor(color(context, R.attr.colorError));
        body.addView(problem, spaced(body));

        var buttons = row(context);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(button(context, "Build", R.attr.buttonTonalStyle, v -> submit(onBuild)));
        body.addView(buttons, spaced(body));

        TextWatcher recheck = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                validate();
            }
        };
        version.addTextChangedListener(recheck);
        newId.addTextChangedListener(recheck);
        setMode(false);
    }

    private static EditText field(Context context, String hint) {
        var field = new EditText(context, null, R.attr.editTextOutlinedStyle);
        field.setSingleLine();
        field.setHint(hint);
        return field;
    }

    private static LinearLayout.LayoutParams spaced(View reference) {
        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.topMargin = reference.dp(8);
        return lp;
    }

    private void setMode(boolean newModule) {
        asNew = newModule;
        styleChip(updateChip, !newModule);
        styleChip(newChip, newModule);
        newFields.setVisibility(newModule ? View.VISIBLE : View.GONE);
        validate();
    }

    private String error() {
        return BuildForm.error(asNew, version.getText().toString(), newId.getText().toString(), installedIds);
    }

    private void validate() {
        String message = error();
        problem.setText(message == null ? "" : message);
    }

    private void submit(Consumer<String> onBuild) {
        if (error() != null) {
            validate();
            return;
        }
        dialog.dismiss();
        onBuild.accept(BuildForm.request(asNew, version.getText().toString(), newId.getText().toString(),
                newName.getText().toString(), install.isChecked()));
    }

    static void open(View anchor, String projectId, String projectName, String projectVersion,
                     List<ModuleInfo> installed, Consumer<String> onBuild) {
        var built = new BuildDialog(anchor, projectId, projectName, projectVersion, installed, onBuild);
        built.dialog.show();
        built.version.requestFocus();
    }
}
