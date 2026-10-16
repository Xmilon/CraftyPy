package net.xmilon.craftpy.client.mui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.markflow.Markflow;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.Switch;
import icyllis.modernui.widget.TextView;
import net.minecraft.Util;
import net.xmilon.craftpy.analysis.ModuleGraph;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.project.ModuleInfo;
import net.xmilon.craftpy.project.PyForgePaths;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;
import static net.xmilon.craftpy.client.mui.HomeFragment.humanSize;

/** Modules menu: every .cpm the server found, with enable toggles, details and README. */
public class ModulesFragment extends Fragment {

    private LinearLayout list;
    private LinearLayout details;
    private TextView readme;
    private Markflow markflow;
    private List<ModuleInfo> modules = List.of();
    private String filter = "";
    @Nullable
    private String selectedPath;

    /** Client thread: snapshot the store, render on the UI thread. */
    private final Runnable storeListener = () -> {
        List<ModuleInfo> snapshot = List.copyOf(ClientProjectStore.getInstance().modules());
        View view = list;
        if (view != null) {
            view.post(() -> {
                modules = snapshot;
                renderList();
                renderDetails();
            });
        }
    };

    /** Module dependency tree (45): roots first, missing / disabled / circular dependencies marked. */
    private void showDependencies(View anchor) {
        List<ModuleGraph.Node> nodes = new java.util.ArrayList<>();
        for (ModuleInfo module : modules) {
            if (module.primary) {
                nodes.add(new ModuleGraph.Node(module.id, module.enabled, module.loaded, module.dependencies));
            }
        }
        var dialog = ListDialog.open(anchor, "Module dependencies", false, 520, 300);
        if (nodes.isEmpty()) {
            dialog.setMessage("No modules found.");
            return;
        }
        List<ListDialog.Row> rows = new java.util.ArrayList<>();
        for (ModuleGraph.Row row : ModuleGraph.tree(nodes)) {
            String indent = row.depth() == 0 ? "" : "    ".repeat(row.depth() - 1) + "\u2514 ";
            String attr = switch (row.status()) {
                case OK -> R.attr.colorPrimary;
                case DISABLED, REPEAT -> R.attr.colorOnSurfaceVariant;
                case MISSING, CYCLE -> R.attr.colorError;
            };
            rows.add(ListDialog.Row.info(indent + row.id(), row.note()).colored(attr));
        }
        List<String> problems = ModuleGraph.problems(nodes);
        dialog.setMessage(problems.isEmpty() ? "All dependencies are installed and enabled."
                : problems.size() + " problem(s): " + String.join("; ", problems));
        dialog.setRows(rows);
    }

    @Override
    public void onCreate(@Nullable DataSet savedInstanceState) {
        super.onCreate(savedInstanceState);
        markflow = Markflow.create(requireContext());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        Context context = requireContext();
        var page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        int dp20 = page.dp(20);
        page.setPadding(dp20, dp20, dp20, dp20);

        {
            var header = row(context);
            header.addView(label(context, "Modules", R.attr.textAppearanceHeadlineMedium),
                    new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            header.addView(button(context, "Enable all", R.attr.borderlessButtonStyle,
                    v -> store(s -> s.setAllModulesEnabled(true))));
            header.addView(button(context, "Disable all", R.attr.borderlessButtonStyle,
                    v -> store(s -> s.setAllModulesEnabled(false))));
            header.addView(button(context, "Open folder", R.attr.borderlessButtonStyle,
                    v -> openFolder(PyForgePaths.modules().toString())));
            header.addView(button(context, "Reload all", R.attr.borderlessButtonStyle,
                    v -> store(s -> s.reloadModule("*"))));
            header.addView(button(context, "Dependencies", R.attr.borderlessButtonStyle, this::showDependencies));
            header.addView(button(context, "Refresh", R.attr.buttonTonalStyle,
                    v -> store(ClientProjectStore::refreshModules)));
            page.addView(header);
        }
        var subtitle = label(context, "Installed modules (.cpm) in pyforge/modules/: drop new ones there, built ones land there too. Toggle to enable or disable them.",
                R.attr.textAppearanceBodyMedium);
        subtitle.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        page.addView(subtitle);

        var body = row(context);
        body.setBaselineAligned(false);
        {
            var left = new LinearLayout(context);
            left.setOrientation(LinearLayout.VERTICAL);
            var search = CraftPyFragment.field(context);
            search.setSingleLine();
            search.setHint("Search modules");
            search.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    filter = s.toString().trim().toLowerCase(Locale.ROOT);
                    renderList();
                }
            });
            left.addView(search, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

            list = card(context);
            list.setPadding(0, page.dp(6), 0, page.dp(6));
            list.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
            list.setDividerDrawable(IdeColors.divider(list, false));
            var scroll = new ScrollView(context);
            scroll.addView(list, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            var lp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
            lp.topMargin = page.dp(8);
            left.addView(scroll, lp);

            var leftLp = new LinearLayout.LayoutParams(0, MATCH_PARENT, 2);
            leftLp.rightMargin = page.dp(12);
            body.addView(left, leftLp);
        }
        {
            details = card(context);
            readme = new PlainText(context);
            readme.setTextIsSelectable(true);
            var scroll = new ScrollView(context);
            scroll.addView(details, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            body.addView(scroll, new LinearLayout.LayoutParams(0, MATCH_PARENT, 3));
        }
        var bodyLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
        bodyLp.topMargin = page.dp(12);
        page.addView(body, bodyLp);

        renderList();
        renderDetails();
        store(s -> {
            s.setListener(storeListener);
            s.refreshModules();
        });
        return page;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        store(s -> s.clearListener(storeListener));
        list = null;
    }

    private void renderList() {
        if (list == null) {
            return;
        }
        list.removeAllViews();
        Context context = list.getContext();
        int shown = 0;
        for (ModuleInfo module : modules) {
            if (!filter.isEmpty() && !(module.id + " " + module.name).toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            list.addView(moduleRow(context, module));
            shown++;
        }
        if (shown == 0) {
            var empty = label(context, modules.isEmpty()
                    ? "No modules yet. Build one from a project in the Editor (Build)."
                    : "No module matches the search.", R.attr.textAppearanceBodyMedium);
            empty.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
            int dp12 = list.dp(12);
            empty.setPadding(dp12, dp12, dp12, dp12);
            list.addView(empty);
        }
    }

    private View moduleRow(Context context, ModuleInfo module) {
        var row = row(context);
        row.setPadding(row.dp(12), row.dp(8), row.dp(8), row.dp(8));
        row.setMinimumHeight(row.dp(52));
        hoverBackground(row);

        var text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        var name = label(context, displayName(module) + (module.version.isBlank() ? "" : "  " + module.version),
                R.attr.textAppearanceTitleSmall);
        if (module.path.equals(selectedPath)) {
            name.setTextColor(color(context, R.attr.colorPrimary));
        }
        text.addView(name);
        var status = label(context, statusText(module), R.attr.textAppearanceBodySmall);
        status.setTextColor(color(context, module.error != null && !module.error.isBlank()
                ? R.attr.colorError : R.attr.colorOnSurfaceVariant));
        text.addView(status);
        row.addView(text, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));

        var toggle = new Switch(context);
        toggle.setChecked(module.enabled);
        toggle.setOnCheckedChangeListener((v, checked) -> store(s -> s.setModuleEnabled(module.id, checked)));
        row.addView(toggle);

        row.setOnClickListener(v -> {
            selectedPath = module.path;
            renderList();
            renderDetails();
        });
        return row;
    }

    private void renderDetails() {
        if (details == null) {
            return;
        }
        details.removeAllViews();
        Context context = details.getContext();
        ModuleInfo module = null;
        for (ModuleInfo m : modules) {
            if (m.path.equals(selectedPath)) {
                module = m;
            }
        }
        if (module == null) {
            var hint = label(context, "Select a module to see its details.", R.attr.textAppearanceBodyMedium);
            hint.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
            details.addView(hint);
            return;
        }
        details.addView(label(context, displayName(module), R.attr.textAppearanceTitleLarge));
        if (!module.description.isBlank()) {
            details.addView(label(context, module.description, R.attr.textAppearanceBodyMedium));
        }
        if (module.error != null && !module.error.isBlank()) {
            var error = label(context, module.error, R.attr.textAppearanceBodyMedium);
            error.setTextColor(color(context, R.attr.colorError));
            details.addView(error);
        }
        var props = new LinearLayout(context);
        props.setOrientation(LinearLayout.VERTICAL);
        props.setPadding(0, details.dp(8), 0, details.dp(8));
        property(props, "Id", module.id);
        property(props, "Version", module.version);
        property(props, "Author", module.author);
        property(props, "Status", statusText(module));
        property(props, "Source", module.source);
        property(props, "Size", humanSize(module.sizeBytes));
        property(props, "Dependencies", module.dependencies.isEmpty() ? "none" : String.join(", ", module.dependencies));
        property(props, "File", module.path);
        details.addView(props);

        final ModuleInfo selected = module;
        if (module.permissionWarning != null && !module.permissionWarning.isEmpty()) {
            // forced: whoever wrote the module, its access to sites / files is said in red before anything else
            var warning = label(context, module.permissionWarning + (module.permissionsApproved ? "  (approved)"
                    : "  (not approved: it cannot use them until you approve)"), R.attr.textAppearanceBodyMedium);
            warning.setTextColor(IdeColors.ERROR);
            details.addView(warning);
            var approveRow = row(context);
            approveRow.addView(button(context, module.permissionsApproved ? "Revoke access" : "Approve access",
                    R.attr.buttonTonalStyle, v -> {
                        if (selected.permissionsApproved) {
                            store(s -> s.approveModule(selected.id, false));
                        } else {
                            prompt(v, selected.permissionWarning, null, "Approve", ignored -> store(s -> s.approveModule(selected.id, true)));
                        }
                    }));
            details.addView(approveRow);
        }
        var actions = row(context);
        actions.addView(button(context, "Reload", R.attr.buttonTonalStyle,
                v -> store(s -> s.reloadModule(selected.id))));
        actions.addView(button(context, "Open folder", R.attr.borderlessButtonStyle, v -> openFolder(selected.path)));
        actions.addView(button(context, "Use in project", R.attr.borderlessButtonStyle, v -> useInProject(v, selected.id)));
        actions.addView(button(context, "Open as project", R.attr.borderlessButtonStyle,
                v -> prompt(v, "Open " + Path.of(selected.path).getFileName() + " as a project to edit? A new project is made"
                                + " (nothing existing is replaced), the module is disabled, and nothing runs until you press Run."
                                + " Code from others: read it first.", null, "Open as project",
                        ignored -> store(s -> s.unpackModule(selected.path)))));
        actions.addView(button(context, "Delete", R.attr.borderlessButtonStyle,
                v -> prompt(v, "Delete " + Path.of(selected.path).getFileName() + " from disk?", null, "Delete",
                        ignored -> {
                            selectedPath = null;
                            store(s -> s.deleteModule(selected.path));
                        })));
        details.addView(actions);

        var title = HomeFragment.sectionTitle(context, "README");
        details.addView(title);
        readme.setText("Loading...");
        if (readme.getParent() instanceof ViewGroup parent) {
            parent.removeView(readme);
        }
        details.addView(readme, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        String path = selected.path;
        store(s -> s.requestModuleReadme(path, text -> readme.post(() -> {
            if (path.equals(selectedPath)) {
                if (text == null || text.isBlank()) {
                    readme.setText("This module has no README.");
                } else {
                    markflow.setMarkdown(readme, text);
                }
            }
        })));
    }

    private static void property(LinearLayout parent, String key, String value) {
        Context context = parent.getContext();
        var row = row(context);
        var k = label(context, key, R.attr.textAppearanceLabelLarge);
        k.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        row.addView(k, new LinearLayout.LayoutParams(parent.dp(110), WRAP_CONTENT));
        var v = label(context, value == null || value.isBlank() ? "-" : value, R.attr.textAppearanceBodyMedium);
        v.setTextIsSelectable(true);
        row.addView(v, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        parent.addView(row);
    }

    private static String displayName(ModuleInfo module) {
        return module.name == null || module.name.isBlank() ? module.id : module.name;
    }

    private static String statusText(ModuleInfo m) {
        StringBuilder sb = new StringBuilder(m.enabled ? "Enabled" : "Disabled");
        switch (net.xmilon.craftpy.project.ModuleSide.of(m.side)) {
            case CLIENT -> sb.append(" · Client only (resource pack of this computer)");
            case SERVER -> sb.append(" · Server only");
            default -> {
            }
        }
        if (m.permissionWarning != null && !m.permissionWarning.isEmpty()) {
            sb.append(m.permissionsApproved ? " · ⚠ web/files (approved)" : " · ⚠ wants web/files");
        }
        if (m.loaded) {
            sb.append(" · Loaded");
        }
        if (m.pendingRestart) {
            sb.append(" · Restart to unload");
        }
        if (m.duplicate) {
            sb.append(m.primary ? " · Duplicate id (wins)" : " · Duplicate id (ignored)");
        }
        if (m.error != null && !m.error.isBlank()) {
            sb.append(" · Error");
        }
        return sb.toString();
    }

    private static void openFolder(String path) {
        try {
            Path p = Path.of(path);
            Path dir = Files.isDirectory(p) ? p : p.getParent();
            if (dir != null) {
                Util.getPlatform().openFile(dir.toFile());
            }
        } catch (Exception e) {
            store(s -> s.append("Could not open folder: " + e.getMessage()));
        }
    }

    /** Adds the module to the open project's dependencies and copies the import line to use its code. */
    private void useInProject(View anchor, String moduleId) {
        var store = net.xmilon.craftpy.client.ClientProjectStore.getInstance();
        if (store.activeProjectId() == null) {
            prompt(anchor, "Open a project in the editor first: the module is added to that project.", null, "OK", ignored -> {
            });
            return;
        }
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("module", moduleId);
        store.tool("add_dependency", args, reply -> anchor.post(() -> {
            String line = "from modules." + moduleId + " import *";
            if (!reply.has("error")) {
                net.xmilon.craftpy.client.IdeHost.get().setClipboard(line);
            }
            prompt(anchor, reply.has("error") ? reply.get("error").getAsString()
                    : reply.get("message").getAsString() + "\n\nCopied to the clipboard: " + line, null, "OK", ignored -> {
            });
        }));
    }
}
