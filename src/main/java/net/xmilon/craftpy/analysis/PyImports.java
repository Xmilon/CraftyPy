package net.xmilon.craftpy.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Organise imports (sort, merge, drop unused) and find standard modules used without an import. */
public final class PyImports {
    /** Replace {@code [start, end)} of the source with {@code text}. */
    public record Replacement(int start, int end, String text) {
        public String applyTo(String source) {
            return source.substring(0, start) + text + source.substring(end);
        }
    }

    public record Result(Replacement replacement, List<String> removed, String message) {
    }

    /** Modules a script can use without installing anything (the sandbox blocks os, sys, ...). */
    private static final Set<String> STDLIB = Set.of(
            "__future__", "abc", "argparse", "array", "ast", "base64", "bisect", "calendar", "cmath", "collections",
            "colorsys", "contextlib", "copy", "csv", "dataclasses", "datetime", "decimal", "difflib", "enum",
            "fractions", "functools", "hashlib", "heapq", "hmac", "html", "io", "itertools", "json", "keyword",
            "locale", "logging", "math", "numbers", "operator", "pprint", "queue", "random", "re", "reprlib",
            "secrets", "statistics", "string", "struct", "textwrap", "time", "types", "typing", "unicodedata",
            "uuid", "weakref", "zlib");

    /** Modules worth offering in "add missing imports". */
    private static final Set<String> OFFERED = Set.of(
            "bisect", "collections", "copy", "dataclasses", "datetime", "enum", "functools", "heapq", "itertools",
            "json", "math", "operator", "random", "re", "statistics", "string", "textwrap", "time", "typing", "uuid");

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern FROM = Pattern.compile("^from\\s+(\\S+)\\s+import\\s+(.+)$", Pattern.DOTALL);

    private PyImports() {
    }

    /** One imported binding: {@code from module import name as alias} (or {@code import module as alias}). */
    private record Item(String module, String name, String alias, boolean from) {
        String bound() {
            if (!alias.isEmpty()) {
                return alias;
            }
            return from ? name : module.split("\\.")[0];
        }

        String text() {
            if (from) {
                return name + (alias.isEmpty() ? "" : " as " + alias);
            }
            return module + (alias.isEmpty() ? "" : " as " + alias);
        }
    }

    private record Statement(int firstLine, int lastLine, String raw, boolean pinned) {
    }

    public static Result organise(String text) {
        return organise(text, List.of(), true);
    }

    /**
     * @param extra        modules to add as {@code import x}
     * @param removeUnused drop names never used in the rest of the file
     */
    public static Result organise(String text, List<String> extra, boolean removeUnused) {
        String[] lines = text.split("\n", -1);
        String masked = PyLex.maskNonCode(text);
        String[] code = masked.split("\n", -1);
        List<Statement> statements = new ArrayList<>();
        int regionStart = -1;
        int regionEnd = -1;
        int i = 0;
        while (i < lines.length) {
            if (code[i].isBlank()) {
                i++;
                continue;
            }
            boolean isImport = !Character.isWhitespace(code[i].charAt(0))
                    && (code[i].startsWith("import ") || code[i].startsWith("from "));
            if (!isImport) {
                if (regionStart >= 0) {
                    break;
                }
                // Leading docstring/comments/other code before the first import: only skip comment lines and docstrings.
                if (lines[i].strip().startsWith("#") || code[i].isBlank()) {
                    i++;
                    continue;
                }
                if (isDocstringLine(lines, code, i)) {
                    i = docstringEnd(lines, code, i) + 1;
                    continue;
                }
                break;
            }
            int last = statementEnd(code, i);
            if (regionStart < 0) {
                regionStart = i;
            }
            boolean trailing = hasTrailingComment(lines, code, i, last);
            statements.add(new Statement(i, last, joined(code, i, last), trailing));
            regionEnd = last;
            i = last + 1;
        }

        List<Item> items = new ArrayList<>();
        List<String> pinnedText = new ArrayList<>();
        for (Statement st : statements) {
            if (st.pinned()) {
                pinnedText.add(String.join("\n", java.util.Arrays.copyOfRange(lines, st.firstLine(), st.lastLine() + 1)));
                continue;
            }
            parse(st.raw(), items);
        }
        for (String module : extra) {
            items.add(new Item(module, "", "", false));
        }
        Set<String> used = regionStart < 0 ? usedNames(text, -1, -1) : usedNames(text, regionStart, regionEnd);
        List<String> removed = new ArrayList<>();
        List<Item> kept = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean star = false;
        for (Item item : items) {
            if (item.from() && item.name().equals("*")) {
                if (seen.add(item.module() + "|*")) {
                    kept.add(item);
                }
                star = true;
                continue;
            }
            boolean future = item.from() && item.module().equals("__future__");
            if (removeUnused && !future && !used.contains(item.bound()) && !extra.contains(item.module())) {
                removed.add(item.text() + (item.from() ? " (from " + item.module() + ")" : ""));
                continue;
            }
            if (seen.add(item.module() + "|" + item.name() + "|" + item.alias() + "|" + item.from())) {
                kept.add(item);
            }
        }
        String block = render(kept, pinnedText);
        if (regionStart < 0) {
            if (kept.isEmpty()) {
                return new Result(null, removed, "No imports to organise");
            }
            int at = insertionPoint(lines, code);
            int offset = offsetOfLine(lines, at);
            String insert = block + "\n" + (at < lines.length && !lines[at].isBlank() ? "\n" : "");
            return new Result(new Replacement(offset, offset, insert), removed, "Added imports");
        }
        int start = offsetOfLine(lines, regionStart);
        int end = offsetOfLine(lines, regionEnd) + lines[regionEnd].length();
        String original = text.substring(start, end);
        if (original.equals(block)) {
            return new Result(null, removed, star ? "Imports already organised" : "Imports already organised");
        }
        return new Result(new Replacement(start, end, block), removed,
                removed.isEmpty() ? "Imports organised" : "Imports organised, removed " + removed.size() + " unused");
    }

    /** Standard modules used as {@code name.something} without being imported or defined in the file. */
    public static List<String> missing(String text) {
        String code = PyLex.codeAndFStringExpressions(text);
        Set<String> bound = new TreeSet<>();
        Matcher definitions = Pattern.compile(
                "(?m)^\\s*(?:(?:async\\s+)?def|class)\\s+(\\w+)|\\b(\\w+)\\s*(?::[^=\\n]+)?=(?!=)|\\bfor\\s+(\\w+)|\\bas\\s+(\\w+)|"
                        + "^\\s*import\\s+([\\w., ]+)|^\\s*from\\s+\\S+\\s+import\\s+([^\\n]+)").matcher(code);
        while (definitions.find()) {
            for (int g = 1; g <= 6; g++) {
                if (definitions.group(g) != null) {
                    for (String part : definitions.group(g).split("[,\\s()]+")) {
                        bound.add(part.split("\\.")[0]);
                    }
                }
            }
        }
        // Function parameters: any identifier inside def (...) lists.
        Matcher params = Pattern.compile("(?s)\\bdef\\s+\\w+\\s*\\(([^)]*)\\)").matcher(code);
        while (params.find()) {
            for (String part : params.group(1).split("[,\\s=:*]+")) {
                bound.add(part);
            }
        }
        Set<String> out = new TreeSet<>();
        Matcher use = Pattern.compile("(?<![\\w.])([A-Za-z_]\\w*)\\.").matcher(code);
        while (use.find()) {
            String name = use.group(1);
            if (OFFERED.contains(name) && !bound.contains(name)) {
                out.add(name);
            }
        }
        return new ArrayList<>(out);
    }

    // -- parsing --------------------------------------------------------------

    private static void parse(String raw, List<Item> items) {
        String flat = raw.replace("\\\n", " ").replaceAll("\\s+", " ").trim();
        Matcher imp = IMPORT.matcher(flat);
        if (imp.matches()) {
            for (String part : imp.group(1).split(",")) {
                String[] words = part.trim().split(" as ");
                if (!words[0].isBlank()) {
                    items.add(new Item(words[0].trim(), "", words.length > 1 ? words[1].trim() : "", false));
                }
            }
            return;
        }
        Matcher from = FROM.matcher(flat);
        if (from.matches()) {
            String names = from.group(2).trim();
            if (names.startsWith("(") && names.endsWith(")")) {
                names = names.substring(1, names.length() - 1);
            }
            for (String part : names.split(",")) {
                String[] words = part.trim().split(" as ");
                if (!words[0].isBlank()) {
                    items.add(new Item(from.group(1), words[0].trim(), words.length > 1 ? words[1].trim() : "", true));
                }
            }
        }
    }

    private static String render(List<Item> kept, List<String> pinnedText) {
        TreeMap<Integer, List<String>> groups = new TreeMap<>();
        List<Item> plain = new ArrayList<>();
        TreeMap<String, List<Item>> froms = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Item item : kept) {
            if (item.from()) {
                froms.computeIfAbsent(item.module(), k -> new ArrayList<>()).add(item);
            } else {
                plain.add(item);
            }
        }
        plain.sort(Comparator.comparing(Item::module, String.CASE_INSENSITIVE_ORDER));
        for (Item item : plain) {
            groups.computeIfAbsent(group(item.module()), k -> new ArrayList<>()).add("import " + item.text());
        }
        // Group order inside a section: plain imports first, then from-imports (isort's default).
        TreeMap<Integer, List<String>> fromLines = new TreeMap<>();
        for (var entry : froms.entrySet()) {
            List<Item> list = entry.getValue();
            list.sort(Comparator.comparing((Item it) -> it.name().equals("*") ? "" : it.name().toLowerCase(Locale.ROOT)));
            List<String> names = new ArrayList<>();
            for (Item it : list) {
                names.add(it.name().equals("*") ? "*" : it.text());
            }
            String line = "from " + entry.getKey() + " import " + String.join(", ", names);
            if (line.length() > 100) {
                line = "from " + entry.getKey() + " import (\n    " + String.join(",\n    ", names) + ",\n)";
            }
            fromLines.computeIfAbsent(group(entry.getKey()), k -> new ArrayList<>()).add(line);
        }
        List<String> sections = new ArrayList<>();
        for (int g = 0; g <= 3; g++) {
            List<String> section = new ArrayList<>();
            section.addAll(groups.getOrDefault(g, List.of()));
            section.addAll(fromLines.getOrDefault(g, List.of()));
            if (g == 3) {
                section.addAll(pinnedText);
            }
            if (!section.isEmpty()) {
                sections.add(String.join("\n", section));
            }
        }
        return String.join("\n\n", sections);
    }

    /** 0 = __future__, 1 = standard library, 2 = craftpy, 3 = the project's own files and relative imports. */
    private static int group(String module) {
        String top = module.split("\\.")[0];
        if (top.equals("__future__")) {
            return 0;
        }
        if (top.isEmpty() || module.startsWith(".")) {
            return 3;
        }
        if (top.equals("craftpy")) {
            return 2;
        }
        // nothing else can be installed in the sandbox, so any other name is one of the project's own files
        return STDLIB.contains(top) ? 1 : 3;
    }

    // -- text helpers -----------------------------------------------------------

    private static Set<String> usedNames(String text, int skipFrom, int skipTo) {
        String[] lines = text.split("\n", -1);
        StringBuilder rest = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (skipFrom >= 0 && i >= skipFrom && i <= skipTo) {
                rest.append('\n');
            } else {
                rest.append(lines[i]).append('\n');
            }
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher word = Pattern.compile("[A-Za-z_]\\w*").matcher(PyLex.codeAndFStringExpressions(rest.toString()));
        while (word.find()) {
            names.add(word.group());
        }
        return names;
    }

    private static int statementEnd(String[] code, int from) {
        int depth = 0;
        for (int i = from; i < code.length; i++) {
            String line = code[i];
            for (char c : line.toCharArray()) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
            if (depth <= 0 && !line.stripTrailing().endsWith("\\")) {
                return i;
            }
        }
        return code.length - 1;
    }

    private static String joined(String[] code, int from, int to) {
        return String.join("\n", java.util.Arrays.copyOfRange(code, from, to + 1)).trim();
    }

    private static boolean hasTrailingComment(String[] lines, String[] code, int from, int to) {
        for (int i = from; i <= to; i++) {
            if (!lines[i].stripTrailing().equals(code[i].stripTrailing())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDocstringLine(String[] lines, String[] code, int i) {
        String s = lines[i].strip();
        return code[i].isBlank() && (s.startsWith("\"\"\"") || s.startsWith("'''") || s.startsWith("\"") || s.startsWith("'")
                || s.startsWith("r\"") || s.startsWith("r'"));
    }

    private static int docstringEnd(String[] lines, String[] code, int i) {
        int j = i;
        while (j + 1 < lines.length && code[j + 1].isBlank() && !lines[j + 1].isBlank()
                && !lines[j + 1].strip().startsWith("#") && !lines[j].stripTrailing().endsWith("\"\"\"")) {
            j++;
        }
        if (j + 1 < lines.length && code[j + 1].isBlank() && !lines[j + 1].isBlank() && !lines[j + 1].strip().startsWith("#")) {
            j++;
        }
        return j;
    }

    private static int insertionPoint(String[] lines, String[] code) {
        int i = 0;
        while (i < lines.length) {
            if (lines[i].isBlank() || lines[i].strip().startsWith("#")) {
                i++;
            } else if (isDocstringLine(lines, code, i)) {
                i = docstringEnd(lines, code, i) + 1;
            } else {
                break;
            }
        }
        // Keep one blank line between a header comment block and the imports.
        return i;
    }

    private static int offsetOfLine(String[] lines, int line) {
        int offset = 0;
        for (int i = 0; i < line && i < lines.length; i++) {
            offset += lines[i].length() + 1;
        }
        return offset;
    }
}
