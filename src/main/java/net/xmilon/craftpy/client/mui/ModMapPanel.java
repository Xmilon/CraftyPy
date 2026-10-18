package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * The mod map tool window, read top to bottom:
 * <ol>
 * <li><b>Problems</b> of the whole setup (a missing dependency, mods that don't work together, modules waiting for
 * approval or failing, events many modules handle); a click shows the one it is about.</li>
 * <li>The item <b>in focus</b>: what it is, what it does, what it adds, where it runs and what it may do.</li>
 * <li>How it is tied to the rest, one row per link in plain words ("Needs Create: it does not load without it",
 * "Imported by jukebox: songs, VOLUME"); a click on a row puts that one in focus.</li>
 * <li>Its details (commands, the events it reacts to and who else reacts to them), then everything, searchable.</li>
 * </ol>
 */
final class ModMapPanel {
    interface Host {
        /** Runs the {@code modmap} tool; the reply arrives on the UI thread. */
        void request(JsonObject args, Consumer<JsonObject> reply);

        void hide();
    }

    record Node(String key, String name, String kind, String version, JsonObject data) {
    }

    record Edge(String from, String to, String kind, JsonObject data) {
    }

    private final Host host;
    private final LinearLayout view;
    private final EditText search;
    private final TextView status;
    private final LinearLayout problems;
    private final LinearLayout focus;
    private final LinearLayout list;
    private final LinearLayout details;
    private final View[] chips = new View[4];
    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<JsonObject> problemList = new ArrayList<>();
    private boolean allProblems;
    private String kindFilter = "";
    private String selected;

    ModMapPanel(Context context, Host host) {
        this.host = host;
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(new ColorDrawable(IdeColors.PANEL));

        var header = CraftPyFragment.row(context);
        header.setPadding(header.dp(12), 0, header.dp(4), 0);
        var title = CraftPyFragment.label(context, "Mod map", R.attr.textAppearanceLabelLarge);
        title.setTextColor(IdeColors.TEXT);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        header.addView(EditorFragment.action(context, IdeIcon.Glyph.RELOAD, "Read the mods and modules again", v -> refresh()));
        header.addView(EditorFragment.action(context, IdeIcon.Glyph.MINUS, "Hide", v -> host.hide()));
        view.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, header.dp(36)));

        search = CraftPyFragment.field(context);
        search.setSingleLine();
        search.setHint("Search mods, modules, projects");
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render();
            }
        });
        var searchLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        searchLp.leftMargin = searchLp.rightMargin = view.dp(8);
        view.addView(search, searchLp);

        var filters = CraftPyFragment.row(context);
        filters.setPadding(view.dp(6), view.dp(2), view.dp(6), view.dp(2));
        String[][] kinds = {{"", "All"}, {"mod", "Mods"}, {"module", "Modules"}, {"project", "Projects"}};
        for (int i = 0; i < kinds.length; i++) {
            String kind = kinds[i][0];
            chips[i] = CraftPyFragment.button(context, kinds[i][1], R.attr.borderlessButtonStyle, v -> {
                kindFilter = kind;
                render();
            });
            filters.addView(chips[i]);
        }
        view.addView(filters);
        status = CraftPyFragment.label(context, "", R.attr.textAppearanceBodySmall);
        status.setTextColor(IdeColors.TEXT_SECONDARY);
        status.setPadding(view.dp(10), 0, view.dp(10), view.dp(2));
        view.addView(status);
        problems = column(context);
        focus = column(context);
        details = column(context);
        list = column(context);
        var hint = CraftPyFragment.label(context, "Click any name to look at it.", R.attr.textAppearanceBodySmall);
        hint.setTextColor(IdeColors.TEXT_DISABLED);
        var all = CraftPyFragment.label(context, "Everything", R.attr.textAppearanceLabelLarge);
        all.setTextColor(IdeColors.TEXT);
        all.setPadding(view.dp(10), view.dp(8), view.dp(10), view.dp(2));
        var content = column(context);
        content.addView(problems);
        content.addView(hint);
        content.addView(focus);
        content.addView(details);
        content.addView(all);
        content.addView(list);
        for (View part : new View[] {problems, hint, focus, details}) {
            part.setPadding(view.dp(10), view.dp(4), view.dp(10), view.dp(4));
        }
        var scroll = new ScrollView(context);
        scroll.setBackground(new ColorDrawable(IdeColors.EDITOR));
        scroll.addView(content, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        view.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
    }

    private static LinearLayout column(Context context) {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    View view() {
        return view;
    }

    void focusSearch() {
        search.requestFocus();
    }

    void refresh() {
        status.setText("Reading the mods ...");
        host.request(new JsonObject(), this::onReply);
    }

    private void onReply(JsonObject reply) {
        if (reply.has("error")) {
            status.setText(reply.get("error").getAsString());
            return;
        }
        nodes.clear();
        edges.clear();
        for (JsonElement e : reply.getAsJsonArray("nodes")) {
            JsonObject o = e.getAsJsonObject();
            nodes.add(new Node(o.get("key").getAsString(), o.get("name").getAsString(), o.get("kind").getAsString(),
                    o.has("version") ? o.get("version").getAsString() : "", o));
        }
        for (JsonElement e : reply.getAsJsonArray("edges")) {
            JsonObject o = e.getAsJsonObject();
            edges.add(new Edge(o.get("from").getAsString(), o.get("to").getAsString(), o.get("kind").getAsString(), o));
        }
        problemList.clear();
        if (reply.has("problems")) {
            reply.getAsJsonArray("problems").forEach(e -> problemList.add(e.getAsJsonObject()));
        }
        if (selected == null || nodes.stream().noneMatch(n -> n.key().equals(selected))) {
            selected = defaultFocus();
        }
        render();
    }

    // -- filtering ------------------------------------------------------------------------------------------------------

    /** Nodes that match the search and the kind chip; a search also keeps their direct neighbours visible in the graph. */
    private Set<String> visibleKeys(boolean withNeighbours) {
        String query = search.getText().toString().strip().toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        for (Node node : nodes) {
            boolean kindOk = kindFilter.isEmpty() || node.kind().equals(kindFilter);
            boolean textOk = query.isEmpty() || node.name().toLowerCase(Locale.ROOT).contains(query)
                    || node.key().toLowerCase(Locale.ROOT).contains(query);
            if (kindOk && textOk) {
                out.add(node.key());
            }
        }
        if (withNeighbours && !query.isEmpty()) {
            Set<String> matched = new LinkedHashSet<>(out);
            for (Edge edge : edges) {
                if (matched.contains(edge.from())) {
                    out.add(edge.to());
                }
                if (matched.contains(edge.to())) {
                    out.add(edge.from());
                }
            }
        }
        return out;
    }

    private void render() {
        String[] kinds = {"", "mod", "module", "project"};
        for (int i = 0; i < chips.length; i++) {
            CraftPyFragment.styleChip(chips[i], kinds[i].equals(kindFilter));
        }
        Set<String> shown = visibleKeys(false);
        long mods = nodes.stream().filter(n -> n.kind().equals("mod")).count();
        long modules = nodes.stream().filter(n -> n.kind().equals("module")).count();
        long projects = nodes.stream().filter(n -> n.kind().equals("project")).count();
        status.setText(mods + " mods, " + modules + " modules, " + projects + " projects, " + edges.size() + " links"
                + (shown.size() < nodes.size() ? " - showing " + shown.size() : ""));
        showProblems();
        buildFocus();
        Context context = list.getContext();
        list.removeAllViews();
        for (Node node : nodes) {
            if (!shown.contains(node.key())) {
                continue;
            }
            var row = CraftPyFragment.label(context, badge(node.kind()) + "  " + node.name()
                    + (node.version().isEmpty() ? "" : "  " + node.version()) + links(node.key()), R.attr.textAppearanceBodyMedium);
            row.setTextColor(node.key().equals(selected) ? IdeColors.ACCENT : colorOf(node.kind()));
            row.setPadding(view.dp(10), view.dp(3), view.dp(10), view.dp(3));
            row.setClickable(true);
            CraftPyFragment.hoverBackground(row);
            row.setOnClickListener(v -> select(node.key()));
            list.addView(row);
        }
        showDetails();
    }

    private String links(String key) {
        long out = edges.stream().filter(e -> e.from().equals(key)).count();
        long in = edges.stream().filter(e -> e.to().equals(key)).count();
        return out + in == 0 ? "" : "   (" + out + " out, " + in + " in)";
    }

    private void select(String key) {
        selected = key;
        render();
    }

    /** Your first project, else the module or mod with the most links. */
    private String defaultFocus() {
        for (Node node : nodes) {
            if (node.kind().equals("project")) {
                return node.key();
            }
        }
        String best = null;
        long most = -1;
        for (Node node : nodes) {
            long links = edges.stream().filter(e -> e.from().equals(node.key()) || e.to().equals(node.key())).count();
            if (links > most) {
                most = links;
                best = node.key();
            }
        }
        return best;
    }

    private void showDetails() {
        details.removeAllViews();
        Node node = nodes.stream().filter(n -> n.key().equals(selected)).findFirst().orElse(null);
        if (node == null) {
            return;
        }
        Context context = details.getContext();
        JsonObject data = node.data();
        if (data.has("commands") && data.getAsJsonArray("commands").size() > 0) {
            section(context, "Commands", String.join(", ", strings(data.getAsJsonArray("commands"), 20)));
        }
        // what it reacts to, and who else reacts to the same thing (handlers of several owners run one after the other)
        if (data.has("events") && data.getAsJsonArray("events").size() > 0) {
            List<String> parts = new ArrayList<>();
            for (JsonElement e : data.getAsJsonArray("events")) {
                JsonObject row = e.getAsJsonObject();
                List<String> also = new ArrayList<>();
                row.getAsJsonArray("also").forEach(o -> also.add(nameOf(o.getAsString())));
                parts.add(row.get("event").getAsString() + (also.isEmpty() ? "" : " (also " + String.join(", ", also) + ")"));
            }
            section(context, "Reacts to", String.join("; ", parts));
        } else if (data.has("state") && !data.get("state").getAsString().equals("loaded")) {
            section(context, "Reacts to", "known once it runs");
        }
        if (data.has("decompiledTo")) {
            line(context, node.kind().equals("project") ? "Decompiled " + data.get("decompiledTo").getAsString()
                    : "Decompiled into project " + data.get("decompiledTo").getAsString()
                    + (data.has("changed") && data.get("changed").getAsBoolean() ? ", rebuilt since" : ""));
        }
        var separator = new View(context);
        separator.setBackground(new ColorDrawable(IdeColors.SEPARATOR));
        details.addView(separator, new LinearLayout.LayoutParams(MATCH_PARENT, view.dp(1)));
    }

    private void section(Context context, String title, String text) {
        line(context, title + ": " + text);
    }

    private void line(Context context, String text) {
        var label = CraftPyFragment.label(context, text, R.attr.textAppearanceBodySmall);
        label.setTextColor(IdeColors.TEXT_SECONDARY);
        label.setTextIsSelectable(true);
        details.addView(label);
    }

    private String nameOf(String key) {
        return nodes.stream().filter(n -> n.key().equals(key)).map(Node::name).findFirst().orElse(key);
    }

    private static String badge(String kind) {
        return switch (kind) {
            case "mod" -> "■";
            case "module" -> "◆";
            case "project" -> "●";
            default -> "?";
        };
    }

    static int colorOf(String kind) {
        return switch (kind) {
            case "mod" -> IdeColors.INFO;
            case "module" -> IdeColors.WARNING;
            case "project" -> IdeColors.SUCCESS;
            default -> IdeColors.ERROR;
        };
    }

    // -- problems -----------------------------------------------------------------------------------------------------

    private static final int SHOWN_PROBLEMS = 5;

    private void showProblems() {
        problems.removeAllViews();
        Context context = problems.getContext();
        if (problemList.isEmpty()) {
            if (!nodes.isEmpty()) {
                var ok = CraftPyFragment.label(context, "✓ No problems found", R.attr.textAppearanceLabelLarge);
                ok.setTextColor(IdeColors.SUCCESS);
                problems.addView(ok);
            }
            return;
        }
        var title = CraftPyFragment.label(context, "Problems (" + problemList.size() + ")", R.attr.textAppearanceLabelLarge);
        title.setTextColor(IdeColors.TEXT);
        problems.addView(title);
        int shown = allProblems ? problemList.size() : Math.min(SHOWN_PROBLEMS, problemList.size());
        for (int i = 0; i < shown; i++) {
            JsonObject problem = problemList.get(i);
            boolean error = "error".equals(problem.get("level").getAsString());
            var row = CraftPyFragment.label(context, (error ? "✖ " : "⚠ ") + problem.get("text").getAsString(),
                    R.attr.textAppearanceBodySmall);
            row.setTextColor(error ? IdeColors.ERROR : IdeColors.WARNING);
            row.setPadding(view.dp(4), view.dp(2), view.dp(4), view.dp(2));
            var keys = problem.getAsJsonArray("keys");
            if (!keys.isEmpty()) {
                String key = keys.get(0).getAsString();
                row.setClickable(true);
                CraftPyFragment.hoverBackground(row);
                row.setOnClickListener(v -> select(key));
            }
            problems.addView(row);
        }
        if (problemList.size() > SHOWN_PROBLEMS) {
            var more = CraftPyFragment.label(context, allProblems ? "Show fewer" : "Show all " + problemList.size(),
                    R.attr.textAppearanceBodySmall);
            more.setTextColor(IdeColors.ACCENT);
            more.setClickable(true);
            more.setOnClickListener(v -> {
                allProblems = !allProblems;
                showProblems();
            });
            problems.addView(more);
        }
    }

    // -- the item in focus and its links ----------------------------------------------------------------------------------

    private static final int MAX_ROWS = 30;

    private void buildFocus() {
        focus.removeAllViews();
        Context context = focus.getContext();
        Node center = nodes.stream().filter(n -> n.key().equals(selected)).findFirst().orElse(null);
        if (center == null) {
            var empty = CraftPyFragment.label(context, nodes.isEmpty() ? "Reading the mods ..." : "Pick something below.",
                    R.attr.textAppearanceBodySmall);
            empty.setTextColor(IdeColors.TEXT_SECONDARY);
            focus.addView(empty);
            return;
        }
        var cardLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        cardLp.bottomMargin = view.dp(6);
        focus.addView(centerCard(context, center), cardLp);
        String key = center.key();
        int groups = 0;
        groups += relation(context, "Needs", e -> e.from().equals(key) && e.kind().equals("required"), Edge::to);
        groups += relation(context, "Can work with", e -> e.from().equals(key) && e.kind().equals("optional"), Edge::to);
        groups += relation(context, "Doesn't work with", e -> (e.from().equals(key) || e.to().equals(key))
                        && (e.kind().equals("incompatible") || e.kind().equals("discouraged")),
                e -> e.from().equals(key) ? e.to() : e.from());
        groups += relation(context, "Imports code from", e -> e.from().equals(key) && e.kind().equals("imports"), Edge::to);
        groups += relation(context, "Uses blocks and items of", e -> e.from().equals(key) && e.kind().equals("uses"), Edge::to);
        groups += relation(context, "Needed by", e -> e.to().equals(key) && e.kind().equals("required"), Edge::from);
        groups += relation(context, "Optional for", e -> e.to().equals(key) && e.kind().equals("optional"), Edge::from);
        groups += relation(context, "Its code is imported by", e -> e.to().equals(key) && e.kind().equals("imports"), Edge::from);
        groups += relation(context, "Its blocks and items are used by", e -> e.to().equals(key) && e.kind().equals("uses"),
                Edge::from);
        if (groups == 0) {
            var alone = CraftPyFragment.label(context, "Not tied to anything else here.", R.attr.textAppearanceBodySmall);
            alone.setTextColor(IdeColors.TEXT_SECONDARY);
            focus.addView(alone);
        }
    }

    /** A heading and one clickable row per linked item ("■ Create   create:mixing, ..."); 1 when shown, else 0. */
    private int relation(Context context, String title, java.util.function.Predicate<Edge> which,
                         java.util.function.Function<Edge, String> other) {
        List<Edge> matching = edges.stream().filter(which).toList();
        if (matching.isEmpty()) {
            return 0;
        }
        var heading = CraftPyFragment.label(context, title + " (" + matching.size() + ")", R.attr.textAppearanceLabelMedium);
        heading.setTextColor(IdeColors.TEXT_SECONDARY);
        heading.setPadding(0, view.dp(6), 0, view.dp(2));
        focus.addView(heading);
        for (int i = 0; i < Math.min(MAX_ROWS, matching.size()); i++) {
            Edge edge = matching.get(i);
            String key = other.apply(edge);
            Node node = nodes.stream().filter(n -> n.key().equals(key)).findFirst().orElse(null);
            String kind = node == null ? "missing" : node.kind();
            String detail = kind.equals("missing") ? "not installed" : detail(edge);
            var text = new icyllis.modernui.text.SpannableStringBuilder();
            text.append(badge(kind) + " " + (node == null ? key.substring(key.indexOf(':') + 1) : node.name()));
            text.setSpan(new icyllis.modernui.text.style.ForegroundColorSpan(colorOf(kind)), 0, text.length(),
                    icyllis.modernui.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!detail.isEmpty()) {
                int start = text.length();
                text.append("   ").append(detail);
                text.setSpan(new icyllis.modernui.text.style.ForegroundColorSpan(
                                kind.equals("missing") ? IdeColors.ERROR : IdeColors.TEXT_SECONDARY), start, text.length(),
                        icyllis.modernui.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            var row = CraftPyFragment.label(context, "", R.attr.textAppearanceBodySmall);
            row.setText(text);
            row.setPadding(view.dp(8), view.dp(2), view.dp(4), view.dp(2));
            row.setClickable(true);
            CraftPyFragment.hoverBackground(row);
            if (node != null && node.data().has("description")) {
                row.setTooltipText(node.data().get("description").getAsString());
            }
            row.setOnClickListener(v -> select(key));
            focus.addView(row);
        }
        if (matching.size() > MAX_ROWS) {
            var more = CraftPyFragment.label(context, "+ " + (matching.size() - MAX_ROWS) + " more (search below)",
                    R.attr.textAppearanceBodySmall);
            more.setTextColor(IdeColors.TEXT_DISABLED);
            focus.addView(more);
        }
        return 1;
    }

    /** What the link carries: the imported names or the ids used. */
    private static String detail(Edge edge) {
        JsonObject data = edge.data();
        if (data.has("names") && data.getAsJsonArray("names").size() > 0) {
            return String.join(", ", strings(data.getAsJsonArray("names"), 6))
                    + (data.getAsJsonArray("names").size() > 6 ? ", ..." : "");
        }
        if (data.has("ids") && data.getAsJsonArray("ids").size() > 0) {
            int count = data.has("count") ? data.get("count").getAsInt() : data.getAsJsonArray("ids").size();
            return String.join(", ", strings(data.getAsJsonArray("ids"), 3)) + (count > 3 ? " and " + (count - 3) + " more" : "");
        }
        return "";
    }

    private View centerCard(Context context, Node node) {
        var box = box(context, IdeColors.ACCENT);
        var name = CraftPyFragment.label(context, badge(node.kind()) + " " + node.name(), R.attr.textAppearanceTitleSmall);
        name.setTextColor(IdeColors.TEXT);
        box.addView(name);
        var kind = CraftPyFragment.label(context, node.kind() + (node.version().isEmpty() ? "" : "  " + node.version()),
                R.attr.textAppearanceBodySmall);
        kind.setTextColor(colorOf(node.kind()));
        box.addView(kind);
        JsonObject data = node.data();
        if (data.has("description")) {
            var description = CraftPyFragment.label(context, data.get("description").getAsString(), R.attr.textAppearanceBodySmall);
            description.setTextColor(IdeColors.TEXT);
            box.addView(description);
        } else if (node.kind().equals("module") || node.kind().equals("project")) {
            var none = CraftPyFragment.label(context, "No description (add one to its "
                    + (node.kind().equals("module") ? "module.json" : "project.json") + ").", R.attr.textAppearanceBodySmall);
            none.setTextColor(IdeColors.TEXT_DISABLED);
            box.addView(none);
        }
        if (data.has("author")) {
            var author = CraftPyFragment.label(context, "by " + data.get("author").getAsString(), R.attr.textAppearanceBodySmall);
            author.setTextColor(IdeColors.TEXT_SECONDARY);
            box.addView(author);
        }
        if (data.has("adds") && data.getAsJsonObject("adds").size() > 0) {
            var adds = CraftPyFragment.label(context, "adds " + addsText(data), R.attr.textAppearanceBodySmall);
            adds.setTextColor(IdeColors.TEXT_SECONDARY);
            box.addView(adds);
        }
        if (data.has("warning")) {
            var warning = CraftPyFragment.label(context, data.get("warning").getAsString(), R.attr.textAppearanceBodySmall);
            warning.setTextColor(IdeColors.ERROR);
            box.addView(warning);
        }
        // a few tags per row, so a narrow panel never cuts them off
        List<TextView> tags = tagsOf(context, data);
        for (int i = 0; i < tags.size(); i += 3) {
            var row = CraftPyFragment.tags(context, tags.subList(i, Math.min(tags.size(), i + 3)));
            row.setPadding(0, view.dp(3), 0, 0);
            box.addView(row);
        }
        return box;
    }

    private static String addsText(JsonObject data) {
        List<String> parts = new ArrayList<>();
        data.getAsJsonObject("adds").entrySet().forEach(e -> parts.add(e.getValue().getAsInt() + " " + e.getKey().replace('_', ' ')));
        return String.join(", ", parts);
    }

    /** At a glance: where it runs, whether it runs, what it may do and whether that was allowed. */
    private static List<TextView> tagsOf(Context context, JsonObject data) {
        List<TextView> out = new ArrayList<>();
        String side = data.has("side") ? data.get("side").getAsString() : "";
        if (side.equals("client")) {
            out.add(CraftPyFragment.tag(context, "client", IdeColors.INFO));
        } else if (side.equals("server")) {
            out.add(CraftPyFragment.tag(context, "server", IdeColors.INFO));
        }
        if (data.has("state")) {
            String state = data.get("state").getAsString();
            out.add(CraftPyFragment.tag(context, state, switch (state) {
                case "loaded" -> IdeColors.SUCCESS;
                case "error" -> IdeColors.ERROR;
                case "restart" -> IdeColors.WARNING;
                default -> IdeColors.TEXT_SECONDARY;
            }));
        } else if (data.has("enabled")) {
            out.add(CraftPyFragment.tag(context, data.get("enabled").getAsBoolean() ? "enabled" : "disabled",
                    IdeColors.TEXT_SECONDARY));
        }
        if (data.has("permissions")) {
            JsonObject p = data.getAsJsonObject("permissions");
            if (p.get("java").getAsBoolean()) {
                out.add(CraftPyFragment.tag(context, "FULL TRUST", IdeColors.ERROR));
            }
            if (p.get("commands").getAsBoolean()) {
                out.add(CraftPyFragment.tag(context, "commands", IdeColors.ERROR));
            }
            if (p.getAsJsonArray("net").size() > 0) {
                out.add(CraftPyFragment.tag(context, "web", IdeColors.WARNING));
            }
            if (p.get("files").getAsBoolean()) {
                out.add(CraftPyFragment.tag(context, "files", IdeColors.WARNING));
            }
            if (p.getAsJsonArray("shared").size() > 0) {
                out.add(CraftPyFragment.tag(context, "shared", IdeColors.WARNING));
            }
        }
        String approval = data.has("approval") ? data.get("approval").getAsString() : "none";
        switch (approval) {
            case "needed" -> out.add(CraftPyFragment.tag(context, "not approved", IdeColors.ERROR));
            case "changed" -> out.add(CraftPyFragment.tag(context, "re-approve", IdeColors.ERROR));
            case "approved" -> out.add(CraftPyFragment.tag(context, "approved", IdeColors.SUCCESS));
            default -> {
            }
        }
        if (data.has("changed") && data.get("changed").getAsBoolean()) {
            out.add(CraftPyFragment.tag(context, "changed", IdeColors.WARNING));
        }
        return out;
    }

    private LinearLayout box(Context context, int accent) {
        var box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(view.dp(8), view.dp(5), view.dp(8), view.dp(5));
        box.setBackground(boxBackground(accent));
        return box;
    }

    /** The card frame; a hovered card is lighter (state list: hovered, then normal). */
    private icyllis.modernui.graphics.drawable.Drawable boxBackground(int accent) {
        return IdeColors.fading(view, 6, accent, IdeColors.SELECTED, IdeColors.HOVER, IdeColors.RAISED, IdeColors.RAISED,
                IdeColors.RAISED);
    }

    private static List<String> strings(com.google.gson.JsonArray array, int max) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, array.size()); i++) {
            out.add(array.get(i).getAsString());
        }
        return out;
    }
}
