package net.xmilon.craftpy.client.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * A graph of cards like the JSON Visualizer of VS Code: every object or array is a card with a title and one row per simple
 * value, and a row that holds an object or array has a line to that child's card. A JSON tree is laid out left to right as a
 * tidy tree (a parent beside the middle of its children, no card on top of another); a graph that is not a tree (the mod
 * map: what needs what) is laid out in layers, everything a card needs to its left. Big inputs stay usable: long arrays are
 * cut to {@code maxArray} items with a "+N more" row and at most {@code maxNodes} cards are made. Pure Java, no UI, so the
 * layout can run off the UI thread and is tested on its own.
 */
public final class Graph {
    /** What a row holds, for its color. */
    public static final int STRING = 0, NUMBER = 1, BOOL = 2, NULL = 3, CHILD = 4, MORE = 5, TEXT = 6;

    /** One line of a card: a key and its value; {@code child} is the card it leads to, or -1. */
    public static final class Row {
        public final String key;
        public final String value;
        public final int kind;
        public int child = -1;

        public Row(String key, String value, int kind) {
            this.key = key;
            this.value = value;
            this.kind = kind;
        }

        /** The text of the row as drawn: "key: value", or the value alone for an array item without a key. */
        public String text() {
            return key.isEmpty() ? value : key + ": " + value;
        }
    }

    /** A card. {@code accent} is a free number the viewer turns into a color (JSON: 0 object, 1 array). */
    public static final class Node {
        public final int id;
        public final String title;
        /** Where it is in the JSON ({@code $.items[2].name}) or the key of the thing it stands for. */
        public final String path;
        public final int accent;
        public final List<Row> rows = new ArrayList<>();
        public final List<Integer> children = new ArrayList<>();
        public int parent = -1;
        public boolean folded;
        /** Laid out position and size; only meaningful while {@link #shown}. */
        public double x, y, w, h;
        public boolean shown;

        Node(int id, String title, String path, int accent) {
            this.id = id;
            this.title = title;
            this.path = path;
            this.accent = accent;
        }
    }

    /** A line between two cards; {@code row} is the row of {@code from} it leaves from, or -1 for the card's side. */
    public record Edge(int from, int to, int row, String kind) {
    }

    /** Sizes used by the layout, in the viewer's units. */
    public record Metrics(double charWidth, double rowHeight, double headerHeight, double padding, double gapX, double gapY,
                          int maxChars) {
        public static final Metrics DEFAULT = new Metrics(7, 18, 24, 8, 56, 14, 44);
    }

    public final List<Node> nodes = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();
    /** True for a tree made by {@link #ofJson}: folding works and the tidy tree layout is used. */
    public final boolean tree;
    /** Objects and arrays that were not made into cards because of the card limit. */
    public int notShown;

    public Graph(boolean tree) {
        this.tree = tree;
    }

    public Node add(String title, String path, int accent) {
        Node node = new Node(nodes.size(), title, path, accent);
        nodes.add(node);
        return node;
    }

    /** A line from {@code from} to {@code to} (in a layered graph: {@code from} needs {@code to}, which goes to its left). */
    public void link(int from, int to, int row, String kind) {
        edges.add(new Edge(from, to, row, kind));
    }

    // ------------------------------------------------------------------
    // From JSON
    // ------------------------------------------------------------------

    /** The cards of a JSON document; the root card is named {@code rootName}. */
    public static Graph ofJson(JsonElement root, String rootName, int maxArray, int maxNodes) {
        Graph graph = new Graph(true);
        if (root == null || root.isJsonNull() || root.isJsonPrimitive()) {
            Node only = graph.add(rootName, "$", 0);
            only.rows.add(simpleRow("", root == null ? com.google.gson.JsonNull.INSTANCE : root));
            return graph;
        }
        ArrayDeque<Object[]> todo = new ArrayDeque<>();
        Node top = graph.add(rootName, "$", root.isJsonArray() ? 1 : 0);
        todo.add(new Object[]{top, root});
        while (!todo.isEmpty()) {
            Object[] next = todo.poll();
            Node node = (Node) next[0];
            JsonElement value = (JsonElement) next[1];
            if (value.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                    graph.addMember(node, entry.getKey(), node.path + "." + entry.getKey(), entry.getValue(), maxArray, maxNodes, todo);
                }
            } else {
                JsonArray array = value.getAsJsonArray();
                int count = Math.min(array.size(), maxArray);
                for (int i = 0; i < count; i++) {
                    graph.addMember(node, Integer.toString(i), node.path + "[" + i + "]", array.get(i), maxArray, maxNodes, todo);
                }
                if (array.size() > count) {
                    node.rows.add(new Row("", "+" + (array.size() - count) + " more", MORE));
                }
            }
        }
        return graph;
    }

    private void addMember(Node node, String key, String path, JsonElement value, int maxArray, int maxNodes, ArrayDeque<Object[]> todo) {
        if (!value.isJsonObject() && !value.isJsonArray()) {
            node.rows.add(simpleRow(key, value));
            return;
        }
        int size = value.isJsonObject() ? value.getAsJsonObject().size() : value.getAsJsonArray().size();
        String summary = value.isJsonObject() ? "{" + size + "}" : "[" + size + "]";
        Row row = new Row(key, summary, CHILD);
        node.rows.add(row);
        if (nodes.size() >= maxNodes) {
            notShown++;
            return;
        }
        String title = node.accent == 1 ? node.title + "[" + key + "]" : key;
        Node child = add(title, path, value.isJsonArray() ? 1 : 0);
        child.parent = node.id;
        node.children.add(child.id);
        row.child = child.id;
        link(node.id, child.id, node.rows.size() - 1, "child");
        todo.add(new Object[]{child, value});
    }

    private static Row simpleRow(String key, JsonElement value) {
        if (value.isJsonNull()) return new Row(key, "null", NULL);
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return new Row(key, primitive.getAsString(), BOOL);
        if (primitive.isNumber()) return new Row(key, primitive.getAsString(), NUMBER);
        return new Row(key, "\"" + primitive.getAsString() + "\"", STRING);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /** Sizes every card and places the shown ones; call again after folding. */
    public void layout(Metrics m) {
        for (Node node : nodes) {
            int chars = node.title.length();
            for (Row row : node.rows) chars = Math.max(chars, row.text().length());
            node.w = 2 * m.padding() + m.charWidth() * Math.min(chars, m.maxChars()) + (node.children.isEmpty() ? 0 : m.charWidth() * 2);
            node.h = m.headerHeight() + node.rows.size() * m.rowHeight() + m.padding() / 2;
            node.shown = false;
        }
        if (nodes.isEmpty()) return;
        if (tree) {
            layoutTree(m);
        } else {
            layoutLayers(m);
        }
    }

    private void layoutTree(Metrics m) {
        // columns: the widest card of each depth sets where the next depth starts
        List<Double> widths = new ArrayList<>();
        measureDepth(nodes.get(0), 0, widths);
        double[] columnX = new double[widths.size()];
        for (int i = 1; i < columnX.length; i++) columnX[i] = columnX[i - 1] + widths.get(i - 1) + m.gapX();
        place(nodes.get(0), 0, 0, columnX, m);
    }

    private void measureDepth(Node node, int depth, List<Double> widths) {
        // iterative: deep JSON must not overflow the stack
        ArrayDeque<Object[]> todo = new ArrayDeque<>();
        todo.push(new Object[]{node, depth});
        while (!todo.isEmpty()) {
            Object[] next = todo.pop();
            Node at = (Node) next[0];
            int d = (int) next[1];
            while (widths.size() <= d) widths.add(0.0);
            widths.set(d, Math.max(widths.get(d), at.w));
            at.shown = true;
            if (!at.folded) for (int child : at.children) todo.push(new Object[]{nodes.get(child), d + 1});
        }
    }

    /** Places the subtree of {@code node} starting at {@code top}; returns the bottom it reached. */
    private double place(Node root, int rootDepth, double rootTop, double[] columnX, Metrics m) {
        // an explicit stack of frames: {node, depth, top, next child index, bottom so far}
        record Frame(Node node, int depth, double top, int[] next, double[] bottom) {
        }
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, rootDepth, rootTop, new int[1], new double[]{rootTop}));
        double result = rootTop;
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            Node node = frame.node();
            node.x = columnX[frame.depth()];
            List<Integer> kids = node.folded ? List.of() : node.children;
            if (frame.next()[0] < kids.size()) {
                Node child = nodes.get(kids.get(frame.next()[0]++));
                double childTop = frame.next()[0] == 1 ? frame.top() : frame.bottom()[0] + m.gapY();
                stack.push(new Frame(child, frame.depth() + 1, childTop, new int[1], new double[]{childTop}));
                continue;
            }
            stack.pop();
            double bottom;
            if (kids.isEmpty()) {
                node.y = frame.top();
                bottom = node.y + node.h;
            } else {
                Node first = nodes.get(kids.get(0)), last = nodes.get(kids.get(kids.size() - 1));
                double middle = (first.y + last.y + last.h) / 2;
                node.y = middle - node.h / 2;
                double down = Math.max(0, frame.top() - node.y); // a card taller than its children would reach above its room
                if (down > 0) {
                    shift(node, down);
                }
                bottom = Math.max(frame.bottom()[0] + down, node.y + node.h);
            }
            Frame parent = stack.peek();
            if (parent != null) {
                parent.bottom()[0] = Math.max(parent.bottom()[0], bottom);
            } else {
                result = bottom;
            }
        }
        return result;
    }

    /** Moves a placed card and everything shown under it down by {@code dy}. */
    private void shift(Node node, double dy) {
        ArrayDeque<Node> todo = new ArrayDeque<>();
        todo.push(node);
        while (!todo.isEmpty()) {
            Node at = todo.pop();
            at.y += dy;
            if (!at.folded) for (int child : at.children) todo.push(nodes.get(child));
        }
    }

    private void layoutLayers(Metrics m) {
        int n = nodes.size();
        // layer = 1 + the deepest layer of what a card needs; a cycle is cut where it closes
        int[] layer = new int[n];
        int[] state = new int[n]; // 0 new, 1 on the path, 2 done
        List<List<Integer>> needs = new ArrayList<>();
        List<List<Integer>> neededBy = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            needs.add(new ArrayList<>());
            neededBy.add(new ArrayList<>());
        }
        for (Edge edge : edges) {
            if (edge.from() != edge.to()) {
                needs.get(edge.from()).add(edge.to());
                neededBy.get(edge.to()).add(edge.from());
            }
        }
        for (int start = 0; start < n; start++) {
            if (state[start] != 0) continue;
            ArrayDeque<int[]> stack = new ArrayDeque<>();
            stack.push(new int[]{start, 0});
            state[start] = 1;
            while (!stack.isEmpty()) {
                int[] top = stack.peek();
                List<Integer> deps = needs.get(top[0]);
                if (top[1] < deps.size()) {
                    int dep = deps.get(top[1]++);
                    if (state[dep] == 0) {
                        state[dep] = 1;
                        stack.push(new int[]{dep, 0});
                    }
                    continue;
                }
                stack.pop();
                int deepest = -1;
                for (int dep : deps) if (state[dep] == 2) deepest = Math.max(deepest, layer[dep]);
                layer[top[0]] = deepest + 1;
                state[top[0]] = 2;
            }
        }
        int layers = Arrays.stream(layer).max().orElse(0) + 1;
        List<List<Integer>> columns = new ArrayList<>();
        for (int i = 0; i < layers; i++) columns.add(new ArrayList<>());
        for (int i = 0; i < n; i++) columns.get(layer[i]).add(i);
        for (List<Integer> column : columns) column.sort(Comparator.comparing(i -> nodes.get(i).title.toLowerCase(Locale.ROOT)));
        // a few sweeps that put a card near the middle of what it is tied to, so lines cross less
        double[] order = new double[n];
        for (int sweep = 0; sweep < 4; sweep++) {
            for (List<Integer> column : columns) for (int i = 0; i < column.size(); i++) order[column.get(i)] = i;
            for (int c = 0; c < layers; c++) {
                List<Integer> column = columns.get(c);
                double[] key = new double[n];
                for (int i : column) {
                    List<Integer> ties = sweep % 2 == 0 ? needs.get(i) : neededBy.get(i);
                    double sum = 0;
                    int count = 0;
                    for (int t : ties) {
                        sum += order[t];
                        count++;
                    }
                    key[i] = count == 0 ? order[i] : sum / count;
                }
                column.sort(Comparator.comparingDouble(i -> key[i]));
                for (int i = 0; i < column.size(); i++) order[column.get(i)] = i;
            }
        }
        double x = 0;
        for (List<Integer> column : columns) {
            double width = 0, y = 0;
            for (int i : column) {
                Node node = nodes.get(i);
                node.x = x;
                node.y = y;
                node.shown = true;
                y += node.h + m.gapY();
                width = Math.max(width, node.w);
            }
            x += width + m.gapX() * 1.5;
        }
    }

    // ------------------------------------------------------------------
    // Questions the viewer asks
    // ------------------------------------------------------------------

    /** {minX, minY, maxX, maxY} of the shown cards. */
    public double[] bounds() {
        double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Node node : nodes) {
            if (!node.shown) continue;
            out[0] = Math.min(out[0], node.x);
            out[1] = Math.min(out[1], node.y);
            out[2] = Math.max(out[2], node.x + node.w);
            out[3] = Math.max(out[3], node.y + node.h);
        }
        return out[0] > out[2] ? new double[]{0, 0, 0, 0} : out;
    }

    /** The shown card at (x, y), or -1. */
    public int nodeAt(double x, double y) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            Node node = nodes.get(i);
            if (node.shown && x >= node.x && x < node.x + node.w && y >= node.y && y < node.y + node.h) return i;
        }
        return -1;
    }

    /** The row of a card at height {@code y}: -1 for the title, else the row index (or -2 outside the rows). */
    public int rowAt(int node, double y, Metrics m) {
        Node at = nodes.get(node);
        double inside = y - at.y;
        if (inside < m.headerHeight()) return -1;
        int row = (int) ((inside - m.headerHeight()) / m.rowHeight());
        return row < at.rows.size() ? row : -2;
    }

    /** Cards whose title, keys or values contain {@code query} (case-insensitive), in order; folded parents are opened. */
    public List<Integer> search(String query) {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<Integer> out = new ArrayList<>();
        if (needle.isEmpty()) return out;
        for (Node node : nodes) {
            boolean hit = node.title.toLowerCase(Locale.ROOT).contains(needle);
            for (int r = 0; !hit && r < node.rows.size(); r++) hit = node.rows.get(r).text().toLowerCase(Locale.ROOT).contains(needle);
            if (hit) {
                out.add(node.id);
                for (int up = node.parent; up >= 0; up = nodes.get(up).parent) nodes.get(up).folded = false;
            }
        }
        return out;
    }

    /** Folds every card below {@code depth} (0 shows the root alone), unfolds the rest. */
    public void foldBelow(int depth) {
        if (!tree || nodes.isEmpty()) return;
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        todo.push(new int[]{0, 0});
        while (!todo.isEmpty()) {
            int[] at = todo.pop();
            Node node = nodes.get(at[0]);
            node.folded = at[1] >= depth && !node.children.isEmpty();
            for (int child : node.children) todo.push(new int[]{child, at[1] + 1});
        }
    }
}
