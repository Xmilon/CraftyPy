package net.xmilon.craftpy.client.mui;

import java.util.List;
import java.util.Locale;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.mc.ui.ClampingScrollView;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.assets.AssetUploader;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.project.ProjectSummary;
import net.xmilon.craftpy.project.ProjectTemplates;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/** Main menu: create a project, browse projects, open one in the editor. */
public class HomeFragment extends Fragment {

    private LinearLayout projectList;
    private TextView status;
    private volatile boolean openAfterCreate;
    private String template = ProjectTemplates.DEFAULT;

    /** Client thread: snapshot the store, render on the UI thread. */
    private final Runnable storeListener = () -> {
        ClientProjectStore store = ClientProjectStore.getInstance();
        List<ProjectSummary> projects = List.copyOf(store.projects());
        String selected = store.selectedId();
        List<String> log = store.log();
        String last = log.isEmpty() ? "" : log.get(log.size() - 1);
        boolean open = openAfterCreate && selected != null;
        if (open) {
            openAfterCreate = false;
        }
        View list = projectList;
        if (list != null) {
            list.post(() -> {
                render(projects, selected, last);
                if (open) {
                    CraftPyFragment.navigate(this, PAGE_EDITOR);
                }
            });
        }
    };

    private static final String[] TIPS = {
            "Ctrl+Shift+A finds any command of the editor by name.",
            "The Building blocks window (Alt+8) adds a ready-made window, item, block or event to your project in one click.",
            "Type onj and press Tab: a player-join handler appears. Try onb, cmd, win, item, every too.",
            "Ctrl+\\ splits the editor: two files side by side, drag the bar between them.",
            "Ctrl+click a name to jump to where it is defined; Alt+Left jumps back.",
            "Ctrl+Click a traceback line in the console to open the file at the line that failed.",
            "Alt+Shift+E runs the selected lines in the Python console.",
            "Ctrl+Alt+L tidies the whitespace of a file without changing what it does.",
            "Shift+F6 renames a name everywhere in the file, but not inside strings and comments.",
            "Double click an editor tab to hide every tool window; double click again to bring them back.",
            "Inside a string, completion lists the ids of every installed mod: items, blocks, effects, sounds...",
            "Hover an id in a string to see which mod it comes from, or that it does not exist.",
            "Ctrl+Shift+F searches every file of the project; the TODO list finds your # TODO comments.",
            "F5 saves, re-runs the project and reloads every installed module.",
            "The Assets window (Alt+9) shows every texture, model and sound; drag one into your code.",
            "Ctrl+Alt+S opens the settings: color scheme, font size, minimap, indent guides...",
            "gradlew runIde opens this IDE without starting Minecraft.",
    };

    private String query = "";
    private List<ProjectSummary> lastProjects = List.of();
    private String lastSelected;
    private String lastLog = "";

    /** New Project (IntelliJ: a dialog with the name and the template to start from). */
    private void newProjectDialog(View anchor) {
        Context context = anchor.getContext();
        var dialog = Overlays.dialog(anchor, "New Project", 520, -1);
        var name = CraftPyFragment.field(context);
        name.setSingleLine();
        name.setHint("Project name");
        dialog.body.addView(name, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        dialog.body.addView(templateGallery(context, dialog.body));
        var clientOnly = new icyllis.modernui.widget.CheckBox(context);
        clientOnly.setText("Client side only: runs on each player's own computer (HUD, keys, windows), on any server;"
                + " no blocks, items or world code. You can switch later in Tools > Project side.");
        var clientLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        clientLp.topMargin = clientOnly.dp(8);
        dialog.body.addView(clientOnly, clientLp);
        Runnable create = () -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) {
                name.requestFocus();
                return;
            }
            dialog.dismiss();
            openAfterCreate = true;
            String chosen = template;
            String side = clientOnly.isChecked() ? "client" : "both";
            store(s -> s.request(ProjectAction.CREATE, "",
                    net.xmilon.craftpy.util.Json.COMPACT.toJson(new CreateRequest(value, chosen, side))));
        };
        name.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEY_ENTER) {
                create.run();
                return true;
            }
            return false;
        });
        var buttons = row(context);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(button(context, "Cancel", R.attr.borderlessButtonStyle, v -> dialog.dismiss()));
        var createLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        createLp.leftMargin = buttons.dp(6);
        buttons.addView(button(context, "Create", R.attr.buttonStyle, v -> create.run()), createLp);
        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.topMargin = buttons.dp(12);
        dialog.body.addView(buttons, lp);
        dialog.show();
        name.requestFocus();
    }

    /** CREATE request body: the project name, the starter script to write and where the project works. */
    private record CreateRequest(String name, String template, String side) {
    }

    /** A wrapped grid of the starter templates (50) with the description of the selected one. */
    private View templateGallery(Context context, View anchor) {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        var caption = label(context, "Start from", R.attr.textAppearanceLabelMedium);
        caption.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        caption.setPadding(0, column.dp(10), 0, column.dp(4));
        column.addView(caption);
        var chips = new FlowLayout(context);
        var description = label(context, "", R.attr.textAppearanceBodySmall);
        description.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        description.setPadding(0, column.dp(6), 0, 0);
        var scroll = new ScrollView(context);
        scroll.addView(chips);
        column.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, column.dp(170)));
        column.addView(description);
        List<TextView> views = new java.util.ArrayList<>();
        Runnable refresh = () -> {
            for (int i = 0; i < views.size(); i++) {
                ProjectTemplates.Template t = ProjectTemplates.ALL.get(i);
                boolean selected = t.id().equals(template);
                styleChip(views.get(i), selected);
                views.get(i).setTextColor(color(context,
                        selected ? R.attr.colorOnSecondaryContainer : R.attr.colorOnSurfaceVariant));
                if (selected) {
                    description.setText(t.description());
                }
            }
        };
        for (ProjectTemplates.Template t : ProjectTemplates.ALL) {
            var chip = label(context, t.label(), R.attr.textAppearanceLabelLarge);
            chip.setPadding(chip.dp(12), chip.dp(6), chip.dp(12), chip.dp(6));
            chip.setClickable(true);
            chip.setOnClickListener(v -> {
                template = t.id();
                refresh.run();
            });
            chips.addView(chip);
            views.add(chip);
        }
        refresh.run();
        return column;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        Context context = requireContext();
        var page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        int dp20 = page.dp(20);
        page.setPadding(dp20, dp20, dp20, dp20);

        // IntelliJ's welcome screen: a title, then a search box and the New Project button above the project list
        var welcome = label(context, "Welcome to CraftPy " + net.xmilon.craftpy.CraftPyLog.version(),
                R.attr.textAppearanceHeadlineSmall);
        welcome.setTextColor(IdeColors.TEXT);
        page.addView(welcome);
        var subtitle = label(context, "Write Python, build Minecraft content. Open a project or start a new one.",
                R.attr.textAppearanceBodyMedium);
        subtitle.setTextColor(IdeColors.TEXT_SECONDARY);
        page.addView(subtitle);

        var bar = row(context);
        var search = CraftPyFragment.field(context);
        search.setSingleLine();
        search.setHint("Search projects");
        search.addTextChangedListener(new icyllis.modernui.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(icyllis.modernui.text.Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                render(lastProjects, lastSelected, lastLog);
            }
        });
        bar.addView(search, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        var newLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        newLp.leftMargin = bar.dp(8);
        bar.addView(button(context, "New Project", R.attr.buttonStyle, this::newProjectDialog), newLp);
        var refreshLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        refreshLp.leftMargin = bar.dp(4);
        bar.addView(button(context, "Refresh", R.attr.borderlessButtonStyle, v -> store(ClientProjectStore::refresh)), refreshLp);
        var barLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        barLp.topMargin = page.dp(16);
        page.addView(bar, barLp);

        // Projects
        projectList = card(context);
        projectList.setPadding(0, page.dp(6), 0, page.dp(6));
        projectList.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        projectList.setDividerDrawable(IdeColors.divider(projectList, false));
        page.addView(projectList, cardParams(page));

        // Recent files (opened in the editor)
        {
            var recent = net.xmilon.craftpy.client.EditorSettings.get().recent;
            if (!recent.isEmpty()) {
                page.addView(sectionTitle(context, "Recent files"));
                var card = card(context);
                card.setPadding(0, page.dp(6), 0, page.dp(6));
                for (String key : recent.subList(0, Math.min(6, recent.size()))) {
                    int slash = key.indexOf('/');
                    if (slash <= 0) {
                        continue;
                    }
                    String project = key.substring(0, slash);
                    String path = key.substring(slash + 1);
                    var row = row(context);
                    int dp12 = row.dp(12);
                    row.setPadding(dp12, row.dp(6), dp12, row.dp(6));
                    var icon = new IdeIcon(context, EditorFragment.fileGlyph(path));
                    var iconLp = new LinearLayout.LayoutParams(row.dp(16), row.dp(16));
                    iconLp.rightMargin = row.dp(8);
                    row.addView(icon, iconLp);
                    var file = label(context, path, R.attr.textAppearanceBodyMedium);
                    file.setTextColor(IdeColors.TEXT);
                    row.addView(file, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
                    var where = label(context, project, R.attr.textAppearanceBodySmall);
                    where.setTextColor(IdeColors.TEXT_SECONDARY);
                    row.addView(where);
                    row.setClickable(true);
                    hoverBackground(row);
                    row.setOnClickListener(v -> {
                        store(s -> s.select(project, path));
                        CraftPyFragment.navigate(this, PAGE_EDITOR);
                    });
                    card.addView(row);
                }
                page.addView(card, cardParams(page));
            }
        }

        // Tip of the day
        {
            var card = card(context);
            var head = row(context);
            var title = label(context, "Tip", R.attr.textAppearanceLabelLarge);
            title.setTextColor(IdeColors.ACCENT);
            head.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            var tip = label(context, "", R.attr.textAppearanceBodyMedium);
            tip.setTextColor(IdeColors.TEXT);
            int[] index = {new java.util.Random().nextInt(TIPS.length)};
            tip.setText(TIPS[index[0]]);
            var next = label(context, "Next tip", R.attr.textAppearanceLabelMedium);
            next.setTextColor(IdeColors.ACCENT);
            next.setPadding(next.dp(8), next.dp(2), next.dp(8), next.dp(2));
            next.setClickable(true);
            hoverBackground(next);
            next.setOnClickListener(v -> {
                index[0] = (index[0] + 1) % TIPS.length;
                tip.setText(TIPS[index[0]]);
            });
            head.addView(next);
            card.addView(head);
            card.addView(tip);
            var tipLp = cardParams(page);
            tipLp.topMargin = page.dp(16);
            page.addView(card, tipLp);
        }

        status = label(context, "", R.attr.textAppearanceBodySmall);
        status.setTextColor(IdeColors.TEXT_SECONDARY);
        page.addView(status);

        var scroll = new ClampingScrollView(context);
        scroll.setChildMaxWidth(page.dp(840));
        scroll.addView(page, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        render(List.of(), null, "");
        store(s -> {
            s.setListener(storeListener);
            s.refresh();
        });
        return scroll;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        store(s -> s.clearListener(storeListener));
        projectList = null;
    }

    private void render(List<ProjectSummary> projects, @Nullable String selected, String lastLog) {
        if (projectList == null) {
            return;
        }
        this.lastProjects = projects;
        this.lastSelected = selected;
        this.lastLog = lastLog;
        if (!query.isEmpty()) {
            projects = projects.stream().filter(p -> (p.id + " " + p.name).toLowerCase(Locale.ROOT).contains(query)).toList();
        }
        status.setText(lastLog);
        projectList.removeAllViews();
        Context context = projectList.getContext();
        if (projects.isEmpty()) {
            var empty = label(context, query.isEmpty() ? "No projects yet - New Project starts one."
                    : "No project matches \"" + query + "\"", R.attr.textAppearanceBodyMedium);
            empty.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
            int dp12 = projectList.dp(12);
            empty.setPadding(dp12, dp12, dp12, dp12);
            projectList.addView(empty);
            return;
        }
        for (ProjectSummary project : projects) {
            projectList.addView(projectRow(context, project, project.id.equals(selected)));
        }
    }

    private View projectRow(Context context, ProjectSummary project, boolean selected) {
        var row = row(context);
        int dp12 = row.dp(12);
        row.setPadding(dp12, row.dp(8), row.dp(4), row.dp(8));
        row.setMinimumHeight(row.dp(56));
        hoverBackground(row);
        var avatar = label(context, "", R.attr.textAppearanceLabelLarge);
        IdeColors.avatar(avatar, project.name.isBlank() ? project.id : project.name);
        var avatarLp = new LinearLayout.LayoutParams(row.dp(32), row.dp(32));
        avatarLp.rightMargin = row.dp(12);
        row.addView(avatar, avatarLp);

        var text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        var name = label(context, project.name.isBlank() ? project.id : project.name, R.attr.textAppearanceTitleSmall);
        if (selected) {
            name.setTextColor(color(context, R.attr.colorPrimary));
        }
        text.addView(name);
        var meta = label(context, project.id + "  ·  v" + project.version + "  ·  " + project.fileCount + " files  ·  "
                + humanSize(project.sizeBytes), R.attr.textAppearanceBodySmall);
        meta.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        text.addView(meta);
        if (project.description != null && !project.description.isBlank()) {
            var description = label(context, project.description, R.attr.textAppearanceBodySmall);
            description.setMaxLines(2);
            text.addView(description);
        }
        if (project.error != null && !project.error.isBlank()) {
            var error = label(context, project.error, R.attr.textAppearanceBodySmall);
            error.setTextColor(color(context, R.attr.colorError));
            text.addView(error);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));

        if (project.loaded) {
            var badge = label(context, "Running", R.attr.textAppearanceLabelMedium);
            badge.setTextColor(color(context, R.attr.colorPrimary));
            var lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            lp.rightMargin = row.dp(8);
            row.addView(badge, lp);
        }
        row.addView(button(context, "Open", R.attr.buttonTonalStyle, v -> openProject(project.id)));
        row.addView(button(context, "More", R.attr.borderlessButtonStyle, v -> showMenu(v, project)));
        row.setOnClickListener(v -> openProject(project.id));
        return row;
    }

    private void openProject(String id) {
        View anchor = projectList;
        if (anchor == null) {
            return;
        }
        store(s -> {
            boolean switching = !id.equals(s.activeProjectId());
            if (switching && s.hasUnsavedChanges()) {
                anchor.post(() -> prompt(anchor, "Open '" + id + "' and discard unsaved edits in the current project?",
                        null, "Discard", ignored -> {
                            store(st -> st.select(id));
                            CraftPyFragment.navigate(this, PAGE_EDITOR);
                        }));
                return;
            }
            s.select(id);
            anchor.post(() -> CraftPyFragment.navigate(this, PAGE_EDITOR));
        });
    }

    private void showMenu(View anchor, ProjectSummary project) {
        var menu = Overlays.menu(anchor);
        menu.getMenu().add(0, 1, 0, "Run");
        menu.getMenu().add(0, 2, 1, "Stop");
        menu.getMenu().add(0, 3, 2, "Rename...");
        menu.getMenu().add(0, 4, 3, "Duplicate");
        menu.getMenu().add(0, 5, 4, "Import texture...");
        menu.getMenu().add(0, 6, 5, "Delete...");
        String id = project.id;
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1 -> store(s -> s.request(ProjectAction.LOAD, id, ""));
                case 2 -> store(s -> s.request(ProjectAction.UNLOAD, id, ""));
                case 3 -> prompt(anchor, "Rename project", project.name, "Rename",
                        name -> store(s -> s.request(ProjectAction.RENAME, id, name)));
                case 4 -> store(s -> s.request(ProjectAction.DUPLICATE, id, ""));
                case 5 -> store(s -> AssetUploader.openDialog(id, AssetKind.TEXTURE));
                case 6 -> prompt(anchor, "Delete '" + id + "' and all its files?", null, "Delete",
                        ignored -> store(s -> s.request(ProjectAction.DELETE, id, "")));
                default -> {
                    return false;
                }
            }
            return true;
        });
        menu.show();
    }

    static TextView sectionTitle(Context context, String text) {
        var title = label(context, text, R.attr.textAppearanceTitleMedium);
        title.setTextColor(color(context, R.attr.colorPrimary));
        title.setPadding(0, title.dp(18), 0, title.dp(2));
        return title;
    }

    static LinearLayout.LayoutParams cardParams(View parent) {
        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.setMargins(0, parent.dp(6), 0, parent.dp(6));
        return lp;
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
