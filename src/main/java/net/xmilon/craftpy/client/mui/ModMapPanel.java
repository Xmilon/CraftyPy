package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * The mod map tool window. One node is in focus: in the middle what it is and what it adds, on the left what it needs or
 * uses, on the right what needs or uses it, each card saying why ("requires", "optional", "uses create:mixing ...").
 * A click on a card puts that one in focus. Below: search, kind filters and the list of everything.
 * Lines: green = requires, gray = optional, blue = uses its content, red = missing.
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
    private final Links focus;
    private final LinearLayout list;
    private final LinearLayout details;
    private final View[] chips = new View[4];
    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
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

        focus = new Links(context);
        var focusScroll = new ScrollView(context);
        focusScroll.setBackground(new ColorDrawable(IdeColors.EDITOR));
        focusScroll.addView(focus, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        view.addView(focusScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.2f));

        details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(view.dp(10), view.dp(4), view.dp(10), view.dp(4));
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(details);
        column.addView(list);
        var scroll = new ScrollView(context);
        scroll.addView(column, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        view.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
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
        var name = CraftPyFragment.label(context, node.name() + "  (" + node.kind() + ")", R.attr.textAppearanceTitleSmall);
        name.setTextColor(IdeColors.TEXT);
        details.addView(name);
        JsonObject data = node.data();
        if (data.has("warning")) {
            var warning = CraftPyFragment.label(context, data.get("warning").getAsString(), R.attr.textAppearanceBodySmall);
            warning.setTextColor(IdeColors.ERROR);
            details.addView(warning);
        }
        if (data.has("adds") && data.getAsJsonObject("adds").size() > 0) {
            List<String> parts = new ArrayList<>();
            data.getAsJsonObject("adds").entrySet().forEach(e -> parts.add(e.getValue().getAsInt() + " " + e.getKey().replace('_', ' ')));
            line(context, "Adds: " + String.join(", ", parts));
        }
        group(context, "Needs", e -> e.from().equals(node.key()) && !e.kind().equals("uses"), Edge::to);
        group(context, "Needed by", e -> e.to().equals(node.key()) && !e.kind().equals("uses"), Edge::from);
        group(context, "Uses content of", e -> e.from().equals(node.key()) && e.kind().equals("uses"), Edge::to);
        group(context, "Its content is used by", e -> e.to().equals(node.key()) && e.kind().equals("uses"), Edge::from);
        var separator = new View(context);
        separator.setBackground(new ColorDrawable(IdeColors.SEPARATOR));
        details.addView(separator, new LinearLayout.LayoutParams(MATCH_PARENT, view.dp(1)));
    }

    private void group(Context context, String title, java.util.function.Predicate<Edge> which,
                       java.util.function.Function<Edge, String> other) {
        List<String> parts = new ArrayList<>();
        for (Edge edge : edges) {
            if (which.test(edge)) {
                String key = other.apply(edge);
                String label = nameOf(key) + (edge.kind().equals("optional") ? " (optional)" : "")
                        + (edge.data().has("ids") ? " " + edge.data().getAsJsonArray("ids").toString().replace("\"", "") : "");
                parts.add(label);
            }
        }
        if (!parts.isEmpty()) {
            line(context, title + ": " + String.join(", ", parts));
        }
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

    // -- the focus view ------------------------------------------------------------------------------------------------

    private static final int MAX_CARDS = 12;

    private void buildFocus() {
        focus.clear();
        Node center = nodes.stream().filter(n -> n.key().equals(selected)).findFirst().orElse(null);
        if (center == null) {
            focus.empty(nodes.isEmpty() ? "Reading the mods ..." : "Pick something in the list below.");
            return;
        }
        Context context = focus.getContext();
        List<Edge> outgoing = new ArrayList<>();
        List<Edge> incoming = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge.from().equals(center.key())) {
                outgoing.add(edge);
            } else if (edge.to().equals(center.key())) {
                incoming.add(edge);
            }
        }
        focus.left.addView(heading(context, "Needs / uses (" + outgoing.size() + ")"));
        for (int i = 0; i < Math.min(MAX_CARDS, outgoing.size()); i++) {
            focus.addLeft(card(context, outgoing.get(i).to(), outgoing.get(i)), outgoing.get(i).kind());
        }
        more(context, focus.left, outgoing.size());
        focus.right.addView(heading(context, "Needed / used by (" + incoming.size() + ")"));
        for (int i = 0; i < Math.min(MAX_CARDS, incoming.size()); i++) {
            focus.addRight(card(context, incoming.get(i).from(), incoming.get(i)), incoming.get(i).kind());
        }
        more(context, focus.right, incoming.size());
        focus.setCenter(centerCard(context, center));
    }

    private TextView heading(Context context, String text) {
        var label = CraftPyFragment.label(context, text, R.attr.textAppearanceLabelSmall);
        label.setTextColor(IdeColors.TEXT_SECONDARY);
        label.setPadding(0, 0, 0, view.dp(4));
        return label;
    }

    private void more(Context context, LinearLayout column, int count) {
        if (count > MAX_CARDS) {
            column.addView(heading(context, "+ " + (count - MAX_CARDS) + " more (see below)"));
        }
        if (count == 0) {
            column.addView(heading(context, "nothing"));
        }
    }

    /** A neighbour: its name and kind, and the reason of the link in one or two short lines. */
    private View card(Context context, String key, Edge edge) {
        Node node = nodes.stream().filter(n -> n.key().equals(key)).findFirst().orElse(null);
        String kind = node == null ? "missing" : node.kind();
        var box = box(context, colorOf(kind));
        var name = CraftPyFragment.label(context, badge(kind) + " " + (node == null ? key : node.name()), R.attr.textAppearanceLabelLarge);
        name.setTextColor(IdeColors.TEXT);
        name.setSingleLine();
        box.addView(name);
        String reason = switch (edge.kind()) {
            case "required" -> "requires";
            case "optional" -> "optional";
            case "uses" -> "uses " + (edge.data().has("count") ? edge.data().get("count").getAsInt() + " id(s): " : "")
                    + (edge.data().has("ids") ? String.join(", ", strings(edge.data().getAsJsonArray("ids"), 3)) : "");
            default -> edge.kind();
        };
        var why = CraftPyFragment.label(context, reason + (kind.equals("missing") ? "  - not installed" : ""), R.attr.textAppearanceBodySmall);
        why.setTextColor(kind.equals("missing") ? IdeColors.ERROR : IdeColors.TEXT_SECONDARY);
        why.setMaxLines(2);
        box.addView(why);
        box.setClickable(true);
        box.setOnClickListener(v -> select(key));
        return box;
    }

    private View centerCard(Context context, Node node) {
        var box = box(context, IdeColors.ACCENT);
        var name = CraftPyFragment.label(context, node.name(), R.attr.textAppearanceTitleSmall);
        name.setTextColor(IdeColors.TEXT);
        box.addView(name);
        var kind = CraftPyFragment.label(context, node.kind() + (node.version().isEmpty() ? "" : "  " + node.version()),
                R.attr.textAppearanceBodySmall);
        kind.setTextColor(colorOf(node.kind()));
        box.addView(kind);
        JsonObject data = node.data();
        if (data.has("adds") && data.getAsJsonObject("adds").size() > 0) {
            List<String> parts = new ArrayList<>();
            data.getAsJsonObject("adds").entrySet().forEach(e -> parts.add(e.getValue().getAsInt() + " " + e.getKey().replace('_', ' ')));
            var adds = CraftPyFragment.label(context, "adds " + String.join(", ", parts), R.attr.textAppearanceBodySmall);
            adds.setTextColor(IdeColors.TEXT_SECONDARY);
            box.addView(adds);
        }
        if (data.has("warning")) {
            var warning = CraftPyFragment.label(context, data.get("warning").getAsString(), R.attr.textAppearanceBodySmall);
            warning.setTextColor(IdeColors.ERROR);
            box.addView(warning);
        }
        return box;
    }

    private LinearLayout box(Context context, int accent) {
        var box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(view.dp(8), view.dp(5), view.dp(8), view.dp(5));
        var bg = new icyllis.modernui.graphics.drawable.ShapeDrawable();
        bg.setCornerRadius(view.dp(6));
        bg.setColor(IdeColors.RAISED);
        bg.setStroke(view.dp(1), accent);
        box.setBackground(bg);
        return box;
    }

    private static List<String> strings(com.google.gson.JsonArray array, int max) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, array.size()); i++) {
            out.add(array.get(i).getAsString());
        }
        return out;
    }

    /**
     * Three columns (left cards, the center card, right cards) with a line from each side card to the center card,
     * drawn behind them. Positions come from the laid-out views, so nothing overlaps and all text stays horizontal.
     */
    private final class Links extends LinearLayout {
        final LinearLayout left;
        final LinearLayout right;
        private final LinearLayout middle;
        private final List<View> leftCards = new ArrayList<>();
        private final List<View> rightCards = new ArrayList<>();
        private final List<String> leftKinds = new ArrayList<>();
        private final List<String> rightKinds = new ArrayList<>();
        private View center;
        private final Paint paint = new Paint();

        Links(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(8), dp(8), dp(8), dp(8));
            setWillNotDraw(false);
            left = column(context);
            middle = column(context);
            middle.setGravity(icyllis.modernui.view.Gravity.CENTER_VERTICAL);
            right = column(context);
            var side = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
            var mid = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1);
            mid.leftMargin = mid.rightMargin = dp(18);
            addView(left, side);
            addView(middle, mid);
            addView(right, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        }

        private LinearLayout column(Context context) {
            var column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            return column;
        }

        void clear() {
            left.removeAllViews();
            right.removeAllViews();
            middle.removeAllViews();
            leftCards.clear();
            rightCards.clear();
            leftKinds.clear();
            rightKinds.clear();
            center = null;
        }

        void empty(String text) {
            var label = CraftPyFragment.label(getContext(), text, R.attr.textAppearanceBodySmall);
            label.setTextColor(IdeColors.TEXT_SECONDARY);
            middle.addView(label);
        }

        void addLeft(View card, String kind) {
            add(left, card);
            leftCards.add(card);
            leftKinds.add(kind);
        }

        void addRight(View card, String kind) {
            add(right, card);
            rightCards.add(card);
            rightKinds.add(kind);
        }

        private void add(LinearLayout column, View card) {
            var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            column.addView(card, lp);
        }

        void setCenter(View card) {
            center = card;
            middle.addView(card, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            if (center == null) {
                return;
            }
            float cy = middle.getTop() + center.getTop() + center.getHeight() / 2f;
            float cl = middle.getLeft() + center.getLeft();
            float cr = middle.getLeft() + center.getRight();
            paint.setStyle(Paint.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            for (int i = 0; i < leftCards.size(); i++) {
                View card = leftCards.get(i);
                line(canvas, left.getLeft() + card.getRight(), left.getTop() + card.getTop() + card.getHeight() / 2f, cl, cy, leftKinds.get(i));
            }
            for (int i = 0; i < rightCards.size(); i++) {
                View card = rightCards.get(i);
                line(canvas, cr, cy, right.getLeft() + card.getLeft(), right.getTop() + card.getTop() + card.getHeight() / 2f, rightKinds.get(i));
            }
        }

        private void line(Canvas canvas, float x0, float y0, float x1, float y1, String kind) {
            paint.setColor(0xFF000000 | (switch (kind) {
                case "required" -> IdeColors.SUCCESS;
                case "uses" -> IdeColors.INFO;
                case "optional" -> IdeColors.TEXT_DISABLED;
                default -> IdeColors.ERROR;
            } & 0xFFFFFF));
            // a soft S: across, then over, then across (reads better than a straight diagonal)
            float mid = (x0 + x1) / 2f;
            canvas.drawLine(x0, y0, mid, y0, paint);
            canvas.drawLine(mid, y0, mid, y1, paint);
            canvas.drawLine(mid, y1, x1, y1, paint);
        }
    }
}
