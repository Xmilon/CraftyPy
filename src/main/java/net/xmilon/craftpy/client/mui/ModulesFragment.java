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
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.Switch;
import icyllis.modernui.widget.TextView;
import net.minecraft.Util;
import net.xmilon.craftpy.analysis.ModuleGraph;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.IdeHost;
import net.xmilon.craftpy.project.ModuleInfo;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.util.Json;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;
import static net.xmilon.craftpy.client.mui.HomeFragment.humanSize;

/** Modules menu: every .cpm the server found, with enable toggles, details and README. */
public class ModulesFragment extends Fragment {

    /** In the Hub: client and server modules apart, server modules read-only, no administration. */
    private final boolean hub;
    private boolean showServer;
    private LinearLayout left;
    private LinearLayout bodyRow;
    private View detailsPane;
    private Button clientTab;
    private Button serverTab;
    private LinearLayout list;
    private LinearLayout details;
    private TextView readme;
    private Markflow markflow;
    private Button reload;
    private TextView reloadSummary;
    private boolean pendingChanges;
    private boolean reloading;
    private String summary = "";
    private boolean checkingLeave;
    private Overlays.Dialog leaveDialog;
    private Button leaveReload;
    private boolean applyingChanges;
    private Runnable removeBack = () -> { };
    private List<ModuleInfo> modules = List.of();
    private String filter = "";
    @Nullable
    private String selectedPath;

    public ModulesFragment() {
        this(false);
    }

    public ModulesFragment(boolean hub) {
        this.hub = hub;
    }

    private static boolean isClient(ModuleInfo module) {
        return net.xmilon.craftpy.project.ModuleSide.of(module.side) == net.xmilon.craftpy.project.ModuleSide.CLIENT;
    }

    /** Hub only: the modules of this computer can be changed here, the server's cannot. */
    private boolean editable(ModuleInfo module) {
        return !hub || isClient(module);
    }

    /** Client thread: snapshot the store, render on the UI thread. */
    private final Runnable storeListener = () -> {
        var store = ClientProjectStore.getInstance();
        // ModuleInfo is mutable: the UI must not retain the store's objects.
        List<ModuleInfo> snapshot = store.modules().stream()
                .map(m -> Json.COMPACT.fromJson(Json.COMPACT.toJson(m), ModuleInfo.class)).toList();
        boolean pending = store.hasPendingModuleChanges();
        boolean busy = store.moduleReloading();
        String reloadText = store.moduleReloadSummary();
        View view = list;
        if (view != null) {
            view.post(() -> {
                if (list != view) {
                    return;
                }
                modules = snapshot;
                pendingChanges = pending;
                reloading = busy;
                summary = reloadText;
                renderReload();
                renderTabs();
                renderList();
                renderDetails();
                applyPanes();
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
            if (!hub) {
                header.addView(button(context, "Enable all", R.attr.borderlessButtonStyle,
                        v -> store(s -> s.setAllModulesEnabled(true))));
                header.addView(button(context, "Disable all", R.attr.borderlessButtonStyle,
                        v -> store(s -> s.setAllModulesEnabled(false))));
            }
            header.addView(button(context, "Open folder", R.attr.borderlessButtonStyle,
                    v -> openFolder(PyForgePaths.modules().toString())));
            reload = button(context, "RELOAD", R.attr.buttonTonalStyle,
                    v -> store(s -> s.applyModuleChanges(ignored -> { })));
            reload.setVisibility(View.GONE);
            header.addView(reload);
            header.addView(button(context, "Dependencies", R.attr.borderlessButtonStyle, this::showDependencies));
            if (!hub) {
                header.addView(button(context, "Module Keybinds", R.attr.borderlessButtonStyle, ModuleKeybinds::open));
            }
            header.addView(button(context, "Refresh", R.attr.buttonTonalStyle,
                    v -> store(ClientProjectStore::refreshModules)));
            page.addView(header);
        }
        var subtitle = label(context, hub ? "Modules on this computer can be switched on and off here. The server's modules are shown read-only: admins manage them in the Editor. Toggles are staged; press RELOAD to apply."
                : "Installed modules (.cpm) in pyforge/modules/: drop new ones there, built ones land there too. Toggles are staged; press RELOAD to apply changes.",
                R.attr.textAppearanceBodyMedium);
        subtitle.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        page.addView(subtitle);
        if (hub) {
            var tabs = row(context);
            clientTab = button(context, "Client", R.attr.borderlessButtonStyle, v -> selectSide(false));
            serverTab = button(context, "Server", R.attr.borderlessButtonStyle, v -> selectSide(true));
            tabs.addView(clientTab);
            tabs.addView(serverTab);
            var tabsLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            tabsLp.topMargin = page.dp(6);
            page.addView(tabs, tabsLp);
        }
        reloadSummary = label(context, "", R.attr.textAppearanceBodyMedium);
        reloadSummary.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        page.addView(reloadSummary);

        var body = row(context);
        bodyRow = body;
        body.setBaselineAligned(false);
        {
            left = new LinearLayout(context);
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
            detailsPane = scroll;
            body.addView(scroll, new LinearLayout.LayoutParams(0, MATCH_PARENT, 3));
        }
        if (hub) {
            // a narrow Hub shows the list, or - once a module is picked - its details
            body.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol) applyPanes();
            });
        }
        var bodyLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
        bodyLp.topMargin = page.dp(12);
        page.addView(body, bodyLp);

        renderTabs();
        renderList();
        renderDetails();
        store(s -> {
            s.setListener(storeListener);
            storeListener.run();
            s.refreshModules();
        });
        return page;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable DataSet savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        removeBack = Overlays.onBack(view, () -> {
            if (hub) {
                // a picked module is one level; without one the Hub goes back to Apps (and asks about pending changes)
                if (selectedPath == null) {
                    return false;
                }
                selectedPath = null;
                renderList();
                renderDetails();
                applyPanes();
                return true;
            }
            requestLeave(() -> CraftPyFragment.leave(this));
            return true;
        });
    }

    @Override
    public void onDestroyView() {
        removeBack.run();
        removeBack = () -> { };
        if (leaveDialog != null) {
            leaveDialog.dismiss();
        }
        checkingLeave = false;
        store(s -> s.clearListener(storeListener));
        left = null;
        bodyRow = null;
        detailsPane = null;
        clientTab = null;
        serverTab = null;
        list = null;
        details = null;
        readme = null;
        reload = null;
        reloadSummary = null;
        super.onDestroyView();
    }

    private void selectSide(boolean server) {
        showServer = server;
        selectedPath = null;
        renderTabs();
        renderList();
        renderDetails();
        applyPanes();
    }

    private void renderTabs() {
        if (clientTab == null) {
            return;
        }
        long clients = modules.stream().filter(ModulesFragment::isClient).count();
        clientTab.setText("Client (" + clients + ")");
        serverTab.setText("Server (" + (modules.size() - clients) + ")");
        styleChip(clientTab, !showServer);
        styleChip(serverTab, showServer);
    }

    private boolean narrow() {
        return bodyRow != null && bodyRow.getWidth() > 0 && bodyRow.getWidth() < bodyRow.dp(560);
    }

    /** Hub on a narrow screen: the list, or the picked module's details, one at a time. */
    private void applyPanes() {
        if (!hub || left == null || detailsPane == null) {
            return;
        }
        boolean narrow = narrow();
        left.setVisibility(narrow && selectedPath != null ? View.GONE : View.VISIBLE);
        detailsPane.setVisibility(narrow && selectedPath == null ? View.GONE : View.VISIBLE);
    }

    private void renderReload() {
        reload.setVisibility(pendingChanges || reloading ? View.VISIBLE : View.GONE);
        reload.setEnabled(!reloading);
        reloadSummary.setText(summary);
        if (leaveReload != null) {
            leaveReload.setEnabled(!reloading && !applyingChanges);
        }
    }

    /** Every destination, including closing the screen, is deferred until the user's choice. */
    void requestLeave(Runnable destination) {
        View anchor = list;
        if (anchor == null || checkingLeave || leaveDialog != null) {
            return;
        }
        checkingLeave = true;
        // Read after queued toggle actions, rather than trusting the last rendered snapshot.
        store(s -> {
            boolean pending = s.hasPendingModuleChanges();
            boolean busy = s.moduleReloading();
            String text = s.moduleReloadSummary();
            anchor.post(() -> {
                if (list != anchor) {
                    return;
                }
                checkingLeave = false;
                if (!pending && !busy) {
                    destination.run();
                    return;
                }
                showLeaveDialog(anchor, text, busy, destination);
            });
        });
    }

    private void showLeaveDialog(View anchor, String summary, boolean busy, Runnable destination) {
        var dialog = Overlays.dialog(anchor, "Pending module changes", 480, -1);
        leaveDialog = dialog;
        dialog.onDismiss(() -> {
            leaveDialog = null;
            leaveReload = null;
            applyingChanges = false;
        });
        var message = label(anchor.getContext(), summary, R.attr.textAppearanceBodyMedium);
        dialog.body.addView(message);
        var actions = row(anchor.getContext());
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        var apply = button(anchor.getContext(), "Reload now", R.attr.buttonTonalStyle, v -> { });
        var later = button(anchor.getContext(), "Leave for later", R.attr.borderlessButtonStyle, v -> {
            dialog.dismiss();
            destination.run();
        });
        leaveReload = apply;
        apply.setEnabled(!busy);
        apply.setOnClickListener(v -> {
            applyingChanges = true;
            apply.setEnabled(false);
            later.setEnabled(false);
            message.setText("Reloading modules…");
            store(s -> {
                // Another client action may have started a reload since this button was rendered.
                if (s.moduleReloading()) {
                    anchor.post(() -> {
                        if (list == anchor && leaveDialog == dialog) {
                            applyingChanges = false;
                            message.setText("A module reload is already in progress. Wait for it to finish, or leave for later.");
                            later.setEnabled(true);
                        }
                    });
                    return;
                }
                s.applyModuleChanges(success -> anchor.post(() -> {
                    if (list != anchor || leaveDialog != dialog) {
                        return;
                    }
                    if (Boolean.TRUE.equals(success)) {
                        dialog.dismiss();
                        destination.run();
                    } else {
                        applyingChanges = false;
                        message.setText("Module reload failed. Stay here to review the changes and try again.");
                        apply.setEnabled(true);
                        later.setEnabled(true);
                    }
                }));
            });
        });
        actions.addView(apply);
        actions.addView(later);
        actions.addView(button(anchor.getContext(), "Stay", R.attr.borderlessButtonStyle, v -> dialog.dismiss()));
        dialog.body.addView(actions);
        dialog.show();
    }

    private void renderList() {
        if (list == null) {
            return;
        }
        list.removeAllViews();
        Context context = list.getContext();
        int shown = 0;
        for (ModuleInfo module : modules) {
            if (hub && isClient(module) == showServer) {
                continue;
            }
            if (!filter.isEmpty() && !(module.id + " " + module.name).toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            list.addView(moduleRow(context, module));
            shown++;
        }
        if (shown == 0) {
            var empty = label(context, hub && modules.stream().noneMatch(m -> isClient(m) != showServer)
                    ? (showServer ? "No server modules to show. Admins add them in the Editor."
                    : "No client modules installed. Drop a .cpm into pyforge/modules/ (Open folder).")
                    : modules.isEmpty() ? "No modules yet. Build one from a project in the Editor (Build)."
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
        var flags = flagTags(context, module);
        if (!flags.isEmpty()) {
            var tagRow = tags(context, flags);
            tagRow.setPadding(0, row.dp(3), 0, 0);
            text.addView(tagRow);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));

        var toggle = new Switch(context);
        toggle.setChecked(module.enabled);
        toggle.setEnabled(!reloading && editable(module) && (module.enabled || module.error == null || !module.error.startsWith("Unavailable:")));
        if (!editable(module)) {
            toggle.setTooltipText("Controlled by the server. Admins change it in the Editor.");
        }
        toggle.setOnCheckedChangeListener((v, checked) -> store(s -> s.setModuleEnabled(module.id, checked)));
        row.addView(toggle);

        row.setOnClickListener(v -> {
            selectedPath = module.path;
            renderList();
            renderDetails();
            applyPanes();
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
        if (hub && narrow()) {
            details.addView(button(context, "‹ Modules", R.attr.borderlessButtonStyle, v -> {
                selectedPath = null;
                renderList();
                renderDetails();
                applyPanes();
            }));
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
        if (!module.apps.isEmpty()) {
            property(props, "Apps", module.apps.stream().map(a -> a.name).collect(java.util.stream.Collectors.joining(", ")));
        }
        if (module.decompiledTo != null) {
            property(props, "Decompiled", "to project " + module.decompiledTo + (module.changedSinceDecompile
                    ? " - CHANGED since: this file is not the one that was decompiled (rebuilt or replaced)"
                    : " - unchanged since"));
        }
        if (module.changedSinceApproval) {
            property(props, "Approval", "given to another version of this file: its access is off until you approve again");
        }
        property(props, "File", module.path);
        details.addView(props);
        for (String problem : module.appProblems) {
            var line = label(context, "Apps: " + problem, R.attr.textAppearanceBodySmall);
            line.setTextColor(IdeColors.ERROR);
            details.addView(line);
        }
        var flags = flagTags(context, module);
        if (!flags.isEmpty()) {
            details.addView(tags(context, flags));
        }

        final ModuleInfo selected = module;
        if (module.permissionWarning != null && !module.permissionWarning.isEmpty()) {
            // forced: whoever wrote the module, its access to sites / files is said in red before anything else
            var warning = label(context, module.permissionWarning + (module.permissionsApproved ? "  (approved)"
                    : "  (not approved: it cannot use them until you approve)"), R.attr.textAppearanceBodyMedium);
            warning.setTextColor(IdeColors.ERROR);
            details.addView(warning);
            var approveRow = row(context);
            var permissionToggle = new Switch(context);
            permissionToggle.setText("Allow requested permissions");
            permissionToggle.setChecked(module.permissionsApproved);
            permissionToggle.setEnabled(editable(module) && module.primary && !module.duplicate && !module.sha256.isBlank());
            permissionToggle.setOnCheckedChangeListener((v, checked) -> {
                store(s -> s.approveModule(selected.id, checked));
            });
            approveRow.addView(permissionToggle);
            approveRow.addView(label(context, "Saved for this exact module file; changes require approval again.", R.attr.textAppearanceBodySmall));
            details.addView(approveRow);
        }
        var actions = row(context);
        if (!hub) actions.addView(button(context, "Decompile", R.attr.buttonTonalStyle,
                v -> prompt(v, "Decompile " + Path.of(selected.path).getFileName() + " into a project? A new project is made"
                                + (selected.decompiledTo != null ? " (it was decompiled before, into " + selected.decompiledTo
                                + ": that project stays)" : "") + ", the module is turned off and nothing runs until you press"
                                + " Run. Build module in that project replaces this module, and it shows as Changed here."
                                + " What it asks for (web, files...) stays off until /craftpy project trust. Code from others:"
                                + " read it first.", null, "Decompile",
                        ignored -> store(s -> s.unpackModule(selected.path)))));
        if (editable(selected)) actions.addView(button(context, "Open folder", R.attr.borderlessButtonStyle, v -> openFolder(selected.path)));
        if (!hub) actions.addView(button(context, "Use in project", R.attr.borderlessButtonStyle, v -> useInProject(v, selected.id)));
        if (!hub) actions.addView(button(context, "Delete", R.attr.borderlessButtonStyle,
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
        if (hub && selected.readme != null && !selected.readme.isBlank()) {
            markflow.setMarkdown(readme, selected.readme); // bundled in the archive: no request needed
            return;
        }
        String path = selected.path;
        TextView target = readme;
        store(s -> s.requestModuleReadme(path, text -> target.post(() -> {
            if (readme == target && path.equals(selectedPath)) {
                if (text == null || text.isBlank()) {
                    target.setText("This module has no README.");
                } else {
                    markflow.setMarkdown(target, text);
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

    /** What needs a look: rebuilt since it was decompiled, changed since it was approved, decompiled at all. */
    private static java.util.List<TextView> flagTags(Context context, ModuleInfo m) {
        java.util.List<TextView> out = new java.util.ArrayList<>();
        if (m.changedSinceDecompile) {
            out.add(tag(context, "Changed", IdeColors.WARNING));
        } else if (m.decompiledTo != null) {
            out.add(tag(context, "Decompiled", IdeColors.INFO));
        }
        if (m.changedSinceApproval) {
            out.add(tag(context, "Re-approve", IdeColors.ERROR));
        }
        if (m.permissions != null && m.permissions.java) {
            out.add(tag(context, "Full trust", IdeColors.ERROR));
        }
        return out;
    }

    private static String displayName(ModuleInfo module) {
        return module.name == null || module.name.isBlank() ? module.id : module.name;
    }

    private static String statusText(ModuleInfo m) {
        StringBuilder sb = new StringBuilder(m.enabled ? "Enabled" : "Disabled");
        if (m.pendingReload) {
            sb.append(" · Pending reload");
            if (m.pendingReason != null && !m.pendingReason.isBlank()) {
                sb.append(": ").append(m.pendingReason);
            }
        }
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
        store(store -> {
            if (store.activeProjectId() == null) {
                anchor.post(() -> {
                    if (anchor.isAttachedToWindow()) {
                        prompt(anchor, "Open a project in the editor first: the module is added to that project.",
                                null, "OK", ignored -> { });
                    }
                });
                return;
            }
            com.google.gson.JsonObject args = new com.google.gson.JsonObject();
            args.addProperty("module", moduleId);
            String line = "from modules." + moduleId + " import *";
            store.tool("add_dependency", args, reply -> {
                if (!reply.has("error")) {
                    IdeHost.get().setClipboard(line);
                }
                String message = reply.has("error") ? reply.get("error").getAsString()
                        : reply.get("message").getAsString() + "\n\nCopied to the clipboard: " + line;
                anchor.post(() -> {
                    if (anchor.isAttachedToWindow()) {
                        prompt(anchor, message, null, "OK", ignored -> { });
                    }
                });
            });
        });
    }
}
