package net.xmilon.craftpy.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project-wide static analysis on plain text: where assets are used (48) and "rename this content id
 * everywhere" (33). Pure: callers pass the project's file list and the text of its text files.
 */
public final class ProjectScan {
    public record Reference(String file, int line) {
    }

    public record AssetUse(String path, List<Reference> references) {
    }

    public record MissingAsset(String value, String file, int line) {
    }

    public record UsageReport(List<AssetUse> assets, List<MissingAsset> missing) {
    }

    public record Edit(String file, int line, String before, String after) {
    }

    public record Move(String from, String to) {
    }

    /** {@code newTexts} holds the full new content of every changed text file. */
    public record RenamePlan(String error, List<Edit> edits, List<Move> moves, Map<String, String> newTexts,
                             List<String> warnings) {
        public boolean ok() {
            return error == null;
        }

        static RenamePlan failed(String message) {
            return new RenamePlan(message, List.of(), List.of(), Map.of(), List.of());
        }
    }

    private static final Pattern QUOTED = Pattern.compile("\"([^\"\\\\\\n]*)\"|'([^'\\\\\\n]*)'");
    private static final Pattern NAMESPACE = Pattern.compile("^([a-z0-9_.-]+):(.*)$");
    private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Set<String> ASSET_EXTENSIONS = Set.of("png", "jpg", "jpeg", "ogg", "wav", "json", "mcmeta");
    private static final Set<String> MEDIA_EXTENSIONS = Set.of("png", "jpg", "jpeg", "ogg", "wav");

    private ProjectScan() {
    }

    // -- 48: asset usage ------------------------------------------------------

    public static UsageReport assetUsage(List<String> paths, Map<String, String> texts) {
        Map<String, AssetUse> uses = new TreeMap<>();
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (String path : paths) {
            if (!path.startsWith("assets/") || path.startsWith("assets/lang/")) {
                continue;
            }
            String ext = extension(path);
            if (!ASSET_EXTENSIONS.contains(ext)) {
                continue;
            }
            uses.put(path, new AssetUse(path, new ArrayList<>()));
            String afterAssets = path.substring("assets/".length());
            int slash = afterAssets.indexOf('/');
            String kind = slash < 0 ? "" : afterAssets.substring(0, slash);
            String rel = slash < 0 ? afterAssets : afterAssets.substring(slash + 1);
            String fileName = rel.substring(rel.lastIndexOf('/') + 1);
            for (String key : new String[] { rel, withoutExtension(rel), fileName, withoutExtension(fileName),
                    kind + "/" + rel, kind + "/" + withoutExtension(rel) }) {
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(path);
            }
        }
        List<MissingAsset> missing = new ArrayList<>();
        for (var entry : new TreeMap<>(texts).entrySet()) {
            String file = entry.getKey();
            if (!isScannable(file)) {
                continue;
            }
            String[] lines = entry.getValue().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = QUOTED.matcher(lines[i]);
                while (m.find()) {
                    String value = m.group(1) != null ? m.group(1) : m.group(2);
                    String bare = value;
                    Matcher ns = NAMESPACE.matcher(value);
                    boolean vanilla = false;
                    if (ns.matches()) {
                        vanilla = ns.group(1).equals("minecraft");
                        bare = ns.group(2);
                    }
                    List<String> hits = byKey.get(bare);
                    if (hits != null) {
                        for (String hit : hits) {
                            if (!hit.equals(file)) {
                                List<Reference> refs = uses.get(hit).references();
                                Reference ref = new Reference(file, i);
                                if (!refs.contains(ref)) {
                                    refs.add(ref);
                                }
                            }
                        }
                    } else if (!vanilla && !bare.contains(" ") && !bare.isEmpty()
                            && MEDIA_EXTENSIONS.contains(extension(bare)) && !file.startsWith("assets/models/")) {
                        missing.add(new MissingAsset(value, file, i));
                    }
                }
            }
        }
        return new UsageReport(new ArrayList<>(uses.values()), missing);
    }

    private static boolean isScannable(String file) {
        String ext = extension(file);
        return ext.equals("py") || ext.equals("json") || ext.equals("mcfunction");
    }

    // -- 33: rename a content id everywhere ---------------------------------------

    public static RenamePlan renameId(List<String> paths, Map<String, String> texts, String oldId, String newId) {
        if (!VALID_ID.matcher(newId).matches()) {
            return RenamePlan.failed("'" + newId + "' is not a valid id (lowercase letters, digits and _, starting with a letter)");
        }
        if (oldId.equals(newId)) {
            return RenamePlan.failed("The new id is the same as the old one");
        }
        Set<String> declared = new LinkedHashSet<>();
        for (var entry : texts.entrySet()) {
            if (entry.getKey().endsWith(".py")) {
                for (PyOutline.Symbol s : PyOutline.parse(entry.getValue())) {
                    if (s.kind() == PyOutline.Kind.CONTENT) {
                        declared.add(s.contentId());
                    }
                }
            }
        }
        if (!declared.contains(oldId)) {
            return RenamePlan.failed("No Block/Item with id '" + oldId + "' is declared in this project");
        }
        if (declared.contains(newId)) {
            return RenamePlan.failed("'" + newId + "' is already used by another Block/Item");
        }

        Map<String, String> newTexts = new LinkedHashMap<>();
        List<Edit> edits = new ArrayList<>();
        for (var entry : new TreeMap<>(texts).entrySet()) {
            String file = entry.getKey();
            String text = entry.getValue();
            String ext = extension(file);
            String changed = ext.equals("py") ? renameInPython(text, oldId, newId)
                    : ext.equals("json") ? renameInJson(text, oldId, newId) : text;
            if (!changed.equals(text)) {
                newTexts.put(file, changed);
                String[] before = text.split("\n", -1);
                String[] after = changed.split("\n", -1);
                for (int i = 0; i < Math.min(before.length, after.length); i++) {
                    if (!before[i].equals(after[i])) {
                        edits.add(new Edit(file, i, before[i].strip(), after[i].strip()));
                    }
                }
            }
        }
        List<Move> moves = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> existing = new LinkedHashSet<>(paths);
        for (String path : paths) {
            if (!(path.startsWith("assets/") || path.startsWith("data/")) || path.startsWith("assets/lang/")) {
                continue;
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            String ext = extension(name);
            if (withoutExtension(name).equals(oldId)) {
                String target = path.substring(0, path.length() - name.length()) + newId + (ext.isEmpty() ? "" : "." + ext);
                if (existing.contains(target)) {
                    warnings.add("Not renamed, target exists: " + target);
                } else {
                    moves.add(new Move(path, target));
                }
            }
        }
        if (edits.isEmpty() && moves.isEmpty()) {
            return RenamePlan.failed("Nothing refers to '" + oldId + "'");
        }
        warnings.add("Other projects and modules that use '" + oldId + "' are not changed.");
        return new RenamePlan(null, edits, moves, newTexts, warnings);
    }

    private static String renameInJson(String text, String oldId, String newId) {
        Matcher m = Pattern.compile("\"([^\"\\\\\\n]*)\"").matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start(1)).append(renameString(m.group(1), oldId, newId));
            last = m.end(1);
        }
        return out.append(text.substring(last)).toString();
    }

    private static String renameInPython(String text, String oldId, String newId) {
        List<int[]> ranges = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        for (PyLex.Span span : PyLex.spans(text)) {
            if (span.kind() != PyLex.Kind.STRING) {
                continue;
            }
            String content = text.substring(span.contentStart(), span.contentEnd());
            if (span.fstring() && content.indexOf('{') >= 0) {
                continue; // identifiers inside the braces are handled with the code below
            }
            String renamed = renameString(content, oldId, newId);
            if (!renamed.equals(content)) {
                ranges.add(new int[] { span.contentStart(), span.contentEnd() });
                replacements.add(renamed);
            }
        }
        String masked = PyLex.maskNonCode(text);
        renameIdentifiers(text, masked, 0, masked.length(), oldId, newId, depthsOf(masked), ranges, replacements);
        for (PyLex.Span span : PyLex.spans(text)) {
            if (span.kind() == PyLex.Kind.STRING && span.fstring()) {
                for (int[] expression : PyLex.fstringExpressions(text, span)) {
                    renameIdentifiers(text, text, expression[0], expression[1], oldId, newId, null, ranges, replacements);
                }
            }
        }
        // Apply from the end so earlier offsets stay valid.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> ranges.get(i)[0]).reversed());
        StringBuilder out = new StringBuilder(text);
        for (int i : order) {
            out.replace(ranges.get(i)[0], ranges.get(i)[1], replacements.get(i));
        }
        return out.toString();
    }

    private static int[] depthsOf(String masked) {
        int[] depth = new int[masked.length() + 1];
        int d = 0;
        for (int i = 0; i < masked.length(); i++) {
            depth[i] = d;
            char c = masked.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                d++;
            } else if (c == ')' || c == ']' || c == '}') {
                d = Math.max(0, d - 1);
            }
        }
        depth[masked.length()] = d;
        return depth;
    }

    private static void renameIdentifiers(String text, String scan, int from, int to, String oldId, String newId,
                                          int[] depths, List<int[]> ranges, List<String> replacements) {
        Matcher m = Pattern.compile("(?<![\\w.])" + Pattern.quote(oldId) + "(?!\\w)").matcher(scan);
        m.region(from, to);
        while (m.find()) {
            int start = m.start();
            String before = scan.substring(Math.max(0, start - 8), start);
            if (before.matches("(?s).*\\b(def|class)\\s+")) {
                continue;
            }
            String after = scan.substring(m.end(), Math.min(scan.length(), m.end() + 4)).stripLeading();
            boolean keyword = after.startsWith("=") && !after.startsWith("==");
            if (keyword && depths != null && depths[start] > 0) {
                continue; // a keyword argument name, not the variable
            }
            ranges.add(new int[] { start, m.end() });
            replacements.add(newId);
        }
    }

    /** Renames {@code oldId} when the whole string is an id reference: {@code ns:old}, {@code block/old.png}, ... */
    static String renameString(String value, String oldId, String newId) {
        Matcher ref = Pattern.compile("^((?:[a-z0-9_.-]+:)?(?:[a-z0-9_./-]*/)?)" + Pattern.quote(oldId)
                + "((?:\\.(?:png|json|ogg|wav|mcmeta))?)$").matcher(value);
        if (ref.matches()) {
            return ref.group(1) + newId + ref.group(2);
        }
        Matcher lang = Pattern.compile("^((?:block|item|entity|effect)\\.[a-z0-9_]+\\.)" + Pattern.quote(oldId)
                + "((?:\\.[a-z0-9_.]+)?)$").matcher(value);
        if (lang.matches()) {
            return lang.group(1) + newId + lang.group(2);
        }
        return value;
    }

    // -- helpers ----------------------------------------------------------------

    static String extension(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    static String withoutExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot > path.lastIndexOf('/') ? path.substring(0, dot) : path;
    }
}
