package net.xmilon.craftpy.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Module dependency tree (45): who needs whom, what is missing, disabled or circular. */
public final class ModuleGraph {
    public record Node(String id, boolean enabled, boolean loaded, List<String> dependencies) {
    }

    public enum Status { OK, DISABLED, MISSING, CYCLE, REPEAT }

    public record Row(int depth, String id, Status status, String note) {
    }

    private ModuleGraph() {
    }

    /**
     * The order to load modules in: every module after the modules it depends on ({@code id -> dependencies}, the
     * keys in their scan order). Unknown dependencies are ignored; a cycle keeps the scan order for its members.
     */
    public static List<String> loadOrder(Map<String, List<String>> dependencies) {
        List<String> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String id : dependencies.keySet()) {
            visit(id, dependencies, done, visiting, out);
        }
        return out;
    }

    private static void visit(String id, Map<String, List<String>> deps, Set<String> done, Set<String> visiting, List<String> out) {
        if (done.contains(id) || !deps.containsKey(id) || !visiting.add(id)) {
            return; // loaded, not installed, or a cycle: stop here
        }
        for (String dependency : deps.get(id)) {
            visit(idOf(dependency), deps, done, visiting, out);
        }
        visiting.remove(id);
        done.add(id);
        out.add(id);
    }

    /** "foo>=1.2" -> "foo". */
    static String idOf(String dependency) {
        return dependency.split("[<>=!~ ;]", 2)[0].trim();
    }

    /** Roots (modules nobody depends on) first, each followed by its dependencies, indented. */
    public static List<Row> tree(List<Node> nodes) {
        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node node : nodes) {
            byId.putIfAbsent(node.id(), node);
        }
        Set<String> depended = new HashSet<>();
        for (Node node : byId.values()) {
            for (String dep : node.dependencies()) {
                depended.add(idOf(dep));
            }
        }
        List<String> roots = new ArrayList<>();
        for (String id : byId.keySet()) {
            if (!depended.contains(id)) {
                roots.add(id);
            }
        }
        List<Row> rows = new ArrayList<>();
        Set<String> printed = new HashSet<>();
        for (String root : roots) {
            walk(byId, root, 0, new ArrayList<>(), printed, rows);
        }
        for (String id : byId.keySet()) { // only cycles are left unreached
            if (!printed.contains(id)) {
                walk(byId, id, 0, new ArrayList<>(), printed, rows);
            }
        }
        return rows;
    }

    private static void walk(Map<String, Node> byId, String id, int depth, List<String> stack, Set<String> printed,
                             List<Row> rows) {
        Node node = byId.get(id);
        if (node == null) {
            rows.add(new Row(depth, id, Status.MISSING, "not installed"));
            return;
        }
        if (stack.contains(id)) {
            rows.add(new Row(depth, id, Status.CYCLE, "circular: " + String.join(" > ", stack) + " > " + id));
            return;
        }
        if (!printed.add(id)) {
            rows.add(new Row(depth, id, Status.REPEAT, "listed above"));
            return;
        }
        rows.add(new Row(depth, id, node.enabled() ? Status.OK : Status.DISABLED,
                node.enabled() ? (node.loaded() ? "loaded" : "enabled") : "disabled"));
        stack.add(id);
        for (String dep : node.dependencies()) {
            walk(byId, idOf(dep), depth + 1, stack, printed, rows);
        }
        stack.remove(stack.size() - 1);
    }

    /** Problems a user must fix: missing or disabled dependencies of enabled modules, and cycles. */
    public static List<String> problems(List<Node> nodes) {
        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node node : nodes) {
            byId.putIfAbsent(node.id(), node);
        }
        List<String> out = new ArrayList<>();
        for (Node node : byId.values()) {
            if (!node.enabled()) {
                continue;
            }
            for (String dep : node.dependencies()) {
                Node target = byId.get(idOf(dep));
                if (target == null) {
                    out.add(node.id() + " needs " + idOf(dep) + ", which is not installed");
                } else if (!target.enabled()) {
                    out.add(node.id() + " needs " + idOf(dep) + ", which is disabled");
                }
            }
        }
        for (Row row : tree(nodes)) {
            if (row.status() == Status.CYCLE) {
                out.add(row.note());
            }
        }
        return out;
    }
}
