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
 * The mod map tool window: installed mods, CraftPy modules and projects as a zoomable graph (wheel to zoom, drag to pan)
 * and as a searchable list. Lines: green = requires, gray dashed = optional, blue = uses its content (ids in the code),
 * red = something that is missing. A click shows what a node adds, what it needs and who uses it.
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
    private final Graph graph;
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

        graph = new Graph(context);
        view.addView(graph, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.2f));

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
        graph.layoutNodes();
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
        graph.setVisible(visibleKeys(true));
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
        graph.invalidate();
        render();
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

    // -- the graph -----------------------------------------------------------------------------------------------------

    /** Nodes on rings (projects inside, modules, mods outside, missing at the edge); labels are child views. */
    private final class Graph extends FrameLayout {
        private final Paint paint = new Paint();
        private final Map<String, float[]> world = new HashMap<>();
        private final Map<String, TextView> labels = new HashMap<>();
        private Set<String> visible = Set.of();
        private float zoom = 1f;
        private float panX;
        private float panY;
        private float lastX;
        private float lastY;
        private boolean dragged;

        Graph(Context context) {
            super(context);
            setWillNotDraw(false);
            setBackground(new ColorDrawable(IdeColors.EDITOR));
            setClickable(true);
        }

        void layoutNodes() {
            world.clear();
            removeAllViews();
            labels.clear();
            Map<String, List<Node>> byKind = new HashMap<>();
            for (Node node : nodes) {
                byKind.computeIfAbsent(node.kind(), k -> new ArrayList<>()).add(node);
            }
            ring(byKind.getOrDefault("project", List.of()), 0.28f);
            ring(byKind.getOrDefault("module", List.of()), 0.6f);
            ring(byKind.getOrDefault("mod", List.of()), 1f);
            ring(byKind.getOrDefault("missing", List.of()), 1.2f);
            for (Node node : nodes) {
                var label = CraftPyFragment.label(getContext(), node.name(), R.attr.textAppearanceLabelSmall);
                label.setTextColor(colorOf(node.kind()));
                label.setSingleLine();
                labels.put(node.key(), label);
                addView(label, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            }
            zoom = 1f;
            panX = panY = 0;
            requestLayout();
        }

        private void ring(List<Node> ring, float radius) {
            int n = ring.size();
            for (int i = 0; i < n; i++) {
                double angle = n == 1 && radius < 0.5f ? 0 : Math.PI * 2 * i / n - Math.PI / 2;
                float r = n == 1 && radius < 0.5f ? 0 : radius;
                world.put(ring.get(i).key(), new float[]{(float) Math.cos(angle) * r, (float) Math.sin(angle) * r});
            }
        }

        void setVisible(Set<String> keys) {
            visible = keys;
            for (var entry : labels.entrySet()) {
                entry.getValue().setVisibility(keys.contains(entry.getKey()) ? View.VISIBLE : View.GONE);
            }
            placeLabels();
            invalidate();
        }

        private float[] screen(String key) {
            float[] w = world.get(key);
            if (w == null) {
                return null;
            }
            float unit = Math.min(getWidth(), getHeight()) / 2.7f * zoom;
            return new float[]{getWidth() / 2f + panX + w[0] * unit, getHeight() / 2f + panY + w[1] * unit};
        }

        private void placeLabels() {
            for (var entry : labels.entrySet()) {
                float[] at = screen(entry.getKey());
                if (at != null) {
                    entry.getValue().setTranslationX(at[0] + dp(6));
                    entry.getValue().setTranslationY(at[1] - dp(7));
                }
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            placeLabels();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            paint.setStyle(Paint.STROKE);
            for (Edge edge : edges) {
                if (!visible.contains(edge.from()) || !visible.contains(edge.to())) {
                    continue;
                }
                float[] a = screen(edge.from());
                float[] b = screen(edge.to());
                if (a == null || b == null) {
                    continue;
                }
                boolean touches = edge.from().equals(selected) || edge.to().equals(selected);
                paint.setColor((touches ? 0xFF000000 : 0x90000000) | (switch (edge.kind()) {
                    case "required" -> IdeColors.SUCCESS;
                    case "uses" -> IdeColors.INFO;
                    case "optional" -> IdeColors.TEXT_DISABLED;
                    default -> IdeColors.ERROR;
                } & 0xFFFFFF));
                paint.setStrokeWidth(dp(touches ? 2 : 1));
                if (edge.kind().equals("optional")) {
                    dashed(canvas, a, b);
                } else {
                    canvas.drawLine(a[0], a[1], b[0], b[1], paint);
                }
            }
            paint.setStyle(Paint.FILL);
            for (Node node : nodes) {
                if (!visible.contains(node.key())) {
                    continue;
                }
                float[] at = screen(node.key());
                paint.setColor(0xFF000000 | colorOf(node.kind()));
                canvas.drawCircle(at[0], at[1], dp(node.key().equals(selected) ? 6 : 4), paint);
            }
        }

        private void dashed(Canvas canvas, float[] a, float[] b) {
            float dx = b[0] - a[0];
            float dy = b[1] - a[1];
            float length = (float) Math.hypot(dx, dy);
            float step = dp(6);
            for (float t = 0; t < length; t += step * 2) {
                float t2 = Math.min(length, t + step);
                canvas.drawLine(a[0] + dx * t / length, a[1] + dy * t / length, a[0] + dx * t2 / length, a[1] + dy * t2 / length, paint);
            }
        }

        @Override
        public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_SCROLL) {
                float wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                zoom = Math.max(0.3f, Math.min(6f, zoom * (wheel > 0 ? 1.15f : 1 / 1.15f)));
                placeLabels();
                invalidate();
                return true;
            }
            return super.onGenericMotionEvent(event);
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> {
                    lastX = event.getX();
                    lastY = event.getY();
                    dragged = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(2)) {
                        dragged = true;
                    }
                    panX += dx;
                    panY += dy;
                    lastX = event.getX();
                    lastY = event.getY();
                    placeLabels();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        pick(event.getX(), event.getY());
                    }
                    return true;
                }
                default -> {
                    return super.onTouchEvent(event);
                }
            }
        }

        private void pick(float x, float y) {
            String best = null;
            float bestDistance = dp(14);
            for (Node node : nodes) {
                if (!visible.contains(node.key())) {
                    continue;
                }
                float[] at = screen(node.key());
                float distance = (float) Math.hypot(at[0] - x, at[1] - y);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = node.key();
                }
            }
            if (best != null) {
                select(best);
            }
        }
    }
}
