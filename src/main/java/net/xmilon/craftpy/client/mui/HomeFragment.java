package net.xmilon.craftpy.client.mui;

import java.util.List;
import java.util.Locale;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.mc.ui.ClampingScrollView;
import icyllis.modernui.mc.ui.ThemeControl;
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

    /** CREATE request body: the project name and the starter script to write. */
    private record CreateRequest(String name, String template) {
    }

    /** Chips for the starter templates (50) with the description of the selected one. */
    private View templateGallery(Context context, View anchor) {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        var caption = label(context, "Start from", R.attr.textAppearanceLabelMedium);
        caption.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        caption.setPadding(0, column.dp(10), 0, column.dp(4));
        column.addView(caption);
        var chips = row(context);
        var description = label(context, "", R.attr.textAppearanceBodySmall);
        description.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        description.setPadding(0, column.dp(6), 0, 0);
        var scroll = new icyllis.modernui.widget.HorizontalScrollView(context);
        scroll.addView(chips);
        column.addView(scroll);
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
            var lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            lp.rightMargin = chip.dp(6);
            chips.addView(chip, lp);
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

        page.addView(label(context, "CraftPy", R.attr.textAppearanceHeadlineMedium));
        var subtitle = label(context, "Write Python, build Minecraft content. Pick a project to start.",
                R.attr.textAppearanceBodyMedium);
        subtitle.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
        page.addView(subtitle);

        // New project
        page.addView(sectionTitle(context, "New project"));
        {
            var card = card(context);
            var row = row(context);
            var name = new EditText(context, null, R.attr.editTextOutlinedStyle);
            name.setSingleLine();
            name.setHint("Project name");
            row.addView(name, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            Runnable create = () -> {
                String value = name.getText().toString().trim();
                if (value.isEmpty()) {
                    name.requestFocus();
                    return;
                }
                name.setText("");
                openAfterCreate = true;
                String chosen = template;
                store(s -> s.request(ProjectAction.CREATE, "",
                        net.xmilon.craftpy.util.Json.COMPACT.toJson(new CreateRequest(value, chosen))));
            };
            name.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEY_ENTER) {
                    create.run();
                    return true;
                }
                return false;
            });
            var lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            lp.leftMargin = row.dp(8);
            row.addView(button(context, "Create", R.attr.buttonStyle, v -> create.run()), lp);
            card.addView(row);
            card.addView(templateGallery(context, card));
            page.addView(card, cardParams(page));
        }

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
                    var row = label(context, path + "   ·   " + project, R.attr.textAppearanceBodyMedium);
                    int dp12 = row.dp(12);
                    row.setPadding(dp12, row.dp(6), dp12, row.dp(6));
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

        // Projects
        {
            var header = row(context);
            var title = sectionTitle(context, "Projects");
            header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            header.addView(button(context, "Refresh", R.attr.borderlessButtonStyle, v -> store(ClientProjectStore::refresh)));
            page.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

            projectList = card(context);
            projectList.setPadding(0, page.dp(6), 0, page.dp(6));
            projectList.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
            projectList.setDividerDrawable(ThemeControl.makeDivider(projectList));
            page.addView(projectList, cardParams(page));
        }

        status = label(context, "", R.attr.textAppearanceBodySmall);
        status.setTextColor(color(context, R.attr.colorOnSurfaceVariant));
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
        status.setText(lastLog);
        projectList.removeAllViews();
        Context context = projectList.getContext();
        if (projects.isEmpty()) {
            var empty = label(context, "No projects yet - create one above.", R.attr.textAppearanceBodyMedium);
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
