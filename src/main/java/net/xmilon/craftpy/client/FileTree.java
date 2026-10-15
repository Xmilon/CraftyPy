package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * The explorer's tree model: turns the flat file list ({@code a/b.py}; empty folders as {@code a/dir/}) into the rows
 * that are visible for a set of expanded folders, folders first like VS Code and IntelliJ. No UI in here.
 */
public final class FileTree {
    private FileTree() {
    }

    /** One visible row. {@code path} of a folder has no trailing slash. */
    public record Row(String path, String name, boolean folder, int depth, boolean expanded) {
        /** Folder path with the trailing slash used by the file list and by "new file in this folder". */
        public String prefix() {
            return folder ? path + "/" : path.substring(0, Math.max(0, path.lastIndexOf('/') + 1));
        }
    }

    private static final class Node {
        final String name;
        final String path;
        final boolean folder;
        final TreeMap<String, Node> children = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        Node(String name, String path, boolean folder) {
            this.name = name;
            this.path = path;
            this.folder = folder;
        }
    }

    /** Every folder path (no trailing slash) that appears in the list, for "collapse/expand all". */
    public static List<String> folders(Collection<String> entries) {
        Node root = build(entries);
        List<String> out = new ArrayList<>();
        collectFolders(root, out);
        return out;
    }

    private static void collectFolders(Node node, List<String> out) {
        for (Node child : node.children.values()) {
            if (child.folder) {
                out.add(child.path);
                collectFolders(child, out);
            }
        }
    }

    /** The folders above {@code path}, outermost first ({@code a/b/c.py} gives a, a/b). */
    public static List<String> ancestors(String path) {
        List<String> out = new ArrayList<>();
        int slash = path.indexOf('/');
        while (slash >= 0) {
            out.add(path.substring(0, slash));
            slash = path.indexOf('/', slash + 1);
        }
        return out;
    }

    public static List<Row> visible(Collection<String> entries, Set<String> expanded) {
        List<Row> rows = new ArrayList<>();
        flatten(build(entries), expanded, 0, rows);
        return rows;
    }

    private static void flatten(Node parent, Set<String> expanded, int depth, List<Row> out) {
        List<Node> ordered = new ArrayList<>(parent.children.values());
        ordered.sort(Comparator.comparing((Node n) -> !n.folder)
                .thenComparing(n -> n.name.toLowerCase(Locale.ROOT)));
        for (Node node : ordered) {
            boolean open = node.folder && expanded.contains(node.path);
            out.add(new Row(node.path, node.name, node.folder, depth, open));
            if (open) {
                flatten(node, expanded, depth + 1, out);
            }
        }
    }

    private static Node build(Collection<String> entries) {
        Node root = new Node("", "", true);
        for (String entry : entries) {
            boolean folderEntry = entry.endsWith("/");
            String clean = folderEntry ? entry.substring(0, entry.length() - 1) : entry;
            if (clean.isEmpty()) {
                continue;
            }
            Node at = root;
            int start = 0;
            while (start <= clean.length()) {
                int slash = clean.indexOf('/', start);
                boolean last = slash < 0;
                String part = clean.substring(start, last ? clean.length() : slash);
                String path = last ? clean : clean.substring(0, slash);
                boolean folder = !last || folderEntry;
                Node next = at.children.get(part);
                if (next == null) {
                    next = new Node(part, path, folder);
                    at.children.put(part, next);
                }
                at = next;
                if (last) {
                    break;
                }
                start = slash + 1;
            }
        }
        return root;
    }
}
