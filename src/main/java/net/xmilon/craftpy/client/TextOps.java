package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Pure text operations behind the editor commands (no UI types, unit-tested).
 * An {@link Edit} replaces {@code [start, end)} with {@code text} and puts the cursor at
 * {@code cursor} (and the selection end at {@code selectionEnd}).
 */
public final class TextOps {
    public record Edit(int start, int end, String text, int cursor, int selectionEnd) {
        public Edit(int start, int end, String text, int cursor) {
            this(start, end, text, cursor, cursor);
        }
    }

    private TextOps() {
    }

    public static int lineStart(String text, int offset) {
        return text.lastIndexOf('\n', Math.max(0, offset) - 1) + 1;
    }

    public static int lineEnd(String text, int offset) {
        int end = text.indexOf('\n', offset);
        return end < 0 ? text.length() : end;
    }

    /** 0-based line number of an offset. */
    public static int lineOf(String text, int offset) {
        int line = 0;
        for (int i = 0; i < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Offset where a 0-based line starts (end of text when the line does not exist). */
    public static int offsetOfLine(String text, int line) {
        int offset = 0;
        for (int i = 0; i < line; i++) {
            int next = text.indexOf('\n', offset);
            if (next < 0) {
                return text.length();
            }
            offset = next + 1;
        }
        return offset;
    }

    /** Smart Home: first press goes to the first non-blank character, the next to column 0. */
    public static int smartHome(String text, int cursor) {
        int start = lineStart(text, cursor);
        int firstNonBlank = start;
        int end = lineEnd(text, start);
        while (firstNonBlank < end && Character.isWhitespace(text.charAt(firstNonBlank))) {
            firstNonBlank++;
        }
        return cursor == firstNonBlank ? start : firstNonBlank;
    }

    /** Whole lines covered by a selection: [start, end) without the final newline. */
    private static int[] lineRange(String text, int selStart, int selEnd) {
        int from = Math.min(selStart, selEnd);
        int to = Math.max(selStart, selEnd);
        if (to > from && to > 0 && text.charAt(to - 1) == '\n') {
            to--; // a selection ending at the start of a line does not include that line
        }
        if (from == to) {
            // No selection: the whole document.
            return new int[] { 0, text.length() };
        }
        return new int[] { lineStart(text, from), lineEnd(text, to) };
    }

    public static Edit sortLines(String text, int selStart, int selEnd) {
        int[] r = lineRange(text, selStart, selEnd);
        List<String> lines = new ArrayList<>(Arrays.asList(text.substring(r[0], r[1]).split("\n", -1)));
        lines.sort(String.CASE_INSENSITIVE_ORDER);
        String sorted = String.join("\n", lines);
        return new Edit(r[0], r[1], sorted, r[0], r[0] + sorted.length());
    }

    public static Edit removeDuplicateLines(String text, int selStart, int selEnd) {
        int[] r = lineRange(text, selStart, selEnd);
        LinkedHashSet<String> unique = new LinkedHashSet<>(Arrays.asList(text.substring(r[0], r[1]).split("\n", -1)));
        String joined = String.join("\n", unique);
        return new Edit(r[0], r[1], joined, r[0], r[0] + joined.length());
    }

    /** Ctrl+J: joins the selected lines (or the current line with the next) with single spaces. */
    public static Edit joinLines(String text, int selStart, int selEnd) {
        int from = lineStart(text, Math.min(selStart, selEnd));
        int to = lineEnd(text, Math.max(selStart, selEnd));
        if (lineOf(text, from) == lineOf(text, to)) {
            if (to >= text.length()) {
                return null; // last line: nothing to join
            }
            to = lineEnd(text, to + 1);
        }
        String[] lines = text.substring(from, to).split("\n", -1);
        StringBuilder out = new StringBuilder(lines[0].stripTrailing());
        for (int i = 1; i < lines.length; i++) {
            String part = lines[i].strip();
            if (!part.isEmpty()) {
                out.append(' ').append(part);
            }
        }
        int cursor = from + lines[0].stripTrailing().length();
        return new Edit(from, to, out.toString(), cursor);
    }

    /** Splits the line at the cursor, keeping its indentation; the cursor stays where it was. */
    public static Edit splitLine(String text, int cursor) {
        int start = lineStart(text, cursor);
        String indent = leadingWhitespace(text.substring(start, lineEnd(text, start)));
        int end = cursor;
        while (end < text.length() && text.charAt(end) == ' ') {
            end++;
        }
        return new Edit(cursor, end, "\n" + indent, cursor);
    }

    /** On save: strip trailing whitespace on every line and end with exactly one newline. */
    public static String trimForSave(String text) {
        if (text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            out.append(lines[i].stripTrailing());
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\n') {
            end--;
        }
        out.setLength(end);
        return out.append('\n').toString();
    }

    public static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /** The closing character for an auto-paired opener, or 0. */
    public static char closerFor(char c) {
        return switch (c) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            case '"' -> '"';
            case '\'' -> '\'';
            default -> 0;
        };
    }

    /**
     * After {@code typed} was inserted at {@code at} (replacing {@code replaced}): wraps the old
     * selection (auto-surround) or adds the closing character. Returns null when nothing should change.
     */
    public static Edit afterTyped(String text, int at, char typed, String replaced) {
        char closer = closerFor(typed);
        if (closer == 0) {
            return null;
        }
        if (replaced != null && !replaced.isEmpty() && !replaced.contains("\n")) {
            // Surround: "(" over "abc" -> "(abc)", keeping abc selected.
            String wrapped = replaced + closer;
            return new Edit(at + 1, at + 1, wrapped, at + 1, at + 1 + replaced.length());
        }
        char next = at + 1 < text.length() ? text.charAt(at + 1) : 0;
        char prev = at > 0 ? text.charAt(at - 1) : 0;
        boolean quote = typed == '"' || typed == '\'';
        if (Character.isLetterOrDigit(next) || next == '_') {
            return null; // typing before a word: no pair
        }
        if (quote && (Character.isLetterOrDigit(prev) || prev == '_' || inString(text, at))) {
            return null; // it's'  /  closing an existing string
        }
        return new Edit(at + 1, at + 1, String.valueOf(closer), at + 1);
    }

    /** True when an unclosed quote of the same kind precedes {@code at} on its line. */
    private static boolean inString(String text, int at) {
        char quote = text.charAt(at);
        int count = 0;
        for (int i = lineStart(text, at); i < at; i++) {
            if (text.charAt(i) == '\\') {
                i++;
            } else if (text.charAt(i) == quote) {
                count++;
            }
        }
        return count % 2 == 1;
    }

    /** Typing a closer right before the same auto-inserted closer just steps over it. */
    public static boolean shouldSkipCloser(String textBefore, int at, char typed) {
        return (typed == ')' || typed == ']' || typed == '}' || typed == '"' || typed == '\'')
                && at < textBefore.length() && textBefore.charAt(at) == typed;
    }

    /**
     * After a ':' that completes {@code else:}, {@code elif ...:}, {@code except ...:} or
     * {@code finally:}, re-indents the line to its matching {@code if}/{@code try}/{@code for}/{@code while}.
     */
    public static Edit dedentBlockKeyword(String text, int cursor) {
        int start = lineStart(text, cursor);
        String line = text.substring(start, lineEnd(text, start));
        String stripped = line.strip();
        String keyword = stripped.split("[\\s:(]", 2)[0];
        List<String> openers = switch (keyword) {
            case "else" -> List.of("if", "elif", "for", "while", "try", "except");
            case "elif" -> List.of("if", "elif");
            case "except" -> List.of("try", "except");
            case "finally" -> List.of("try", "except", "else");
            default -> null;
        };
        if (openers == null || !stripped.endsWith(":")) {
            return null;
        }
        int indent = leadingWhitespace(line).length();
        // Walk up to the nearest less-or-equally indented opener of the right kind.
        int lineStart = start;
        while (lineStart > 0) {
            int prevStart = lineStart(text, lineStart - 1);
            String prev = text.substring(prevStart, lineStart - 1);
            lineStart = prevStart;
            if (prev.isBlank()) {
                continue;
            }
            int prevIndent = leadingWhitespace(prev).length();
            String prevKeyword = prev.strip().split("[\\s:(]", 2)[0];
            if (prevIndent < indent && !openers.contains(prevKeyword)) {
                return null; // left the enclosing block without finding an opener
            }
            if (prevIndent <= indent && openers.contains(prevKeyword)) {
                if (prevIndent == indent) {
                    return null; // already aligned
                }
                String newIndent = leadingWhitespace(prev);
                int oldIndent = leadingWhitespace(line).length();
                return new Edit(start, start + oldIndent, newIndent, cursor - oldIndent + newIndent.length());
            }
        }
        return null;
    }

    /** Offsets of every occurrence of {@code query} in [from, to) (case-insensitive when asked). */
    public static List<int[]> findAll(String text, String query, boolean matchCase, int from, int to) {
        List<int[]> out = new ArrayList<>();
        if (query == null || query.isEmpty()) {
            return out;
        }
        String hay = matchCase ? text : text.toLowerCase(java.util.Locale.ROOT);
        String needle = matchCase ? query : query.toLowerCase(java.util.Locale.ROOT);
        int index = hay.indexOf(needle, Math.max(0, from));
        while (index >= 0 && index + needle.length() <= to && out.size() < 10000) {
            out.add(new int[] { index, index + needle.length() });
            index = hay.indexOf(needle, index + Math.max(1, needle.length()));
        }
        return out;
    }

    /** The identifier around {@code offset}, or null. */
    public static int[] wordAt(String text, int offset) {
        int start = Math.min(Math.max(0, offset), text.length());
        int end = start;
        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isWordChar(text.charAt(end))) {
            end++;
        }
        return end > start ? new int[] { start, end } : null;
    }

    public static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Whole-word occurrences of {@code word}. */
    public static List<int[]> findWord(String text, String word, int limit) {
        List<int[]> out = new ArrayList<>();
        int index = text.indexOf(word);
        while (index >= 0 && out.size() < limit) {
            int end = index + word.length();
            boolean startOk = index == 0 || !isWordChar(text.charAt(index - 1));
            boolean endOk = end == text.length() || !isWordChar(text.charAt(end));
            if (startOk && endOk) {
                out.add(new int[] { index, end });
            }
            index = text.indexOf(word, end);
        }
        return out;
    }

    /** Line numbers after an edit that replaced {@code removedLines} lines with {@code addedLines} at {@code atLine}. */
    public static java.util.TreeSet<Integer> shiftLines(java.util.Set<Integer> lines, int atLine, int removedLines,
                                                         int addedLines) {
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        int delta = addedLines - removedLines;
        for (int line : lines) {
            if (line <= atLine) {
                out.add(line);
            } else if (line <= atLine + removedLines) {
                out.add(Math.max(atLine, line + Math.min(0, delta)));
            } else {
                out.add(line + delta);
            }
        }
        return out;
    }

    /** One match in the replace preview: its line (0-based) and the line before/after the replacement. */
    public record ReplaceRow(int index, int line, String before, String after) {
    }

    public static List<ReplaceRow> previewReplace(String text, List<int[]> matches, String replacement) {
        List<ReplaceRow> rows = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            int[] m = matches.get(i);
            int start = lineStart(text, m[0]);
            int end = lineEnd(text, m[1]);
            String before = text.substring(start, end);
            String after = text.substring(start, m[0]) + replacement + text.substring(m[1], end);
            rows.add(new ReplaceRow(i, lineOf(text, m[0]), before.strip(), after.strip()));
        }
        return rows;
    }

    /** Applies the replacement only to the matches whose {@code accept} flag is set. */
    public static String replaceMatches(String text, List<int[]> matches, boolean[] accept, String replacement) {
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        for (int i = 0; i < matches.size(); i++) {
            if (i < accept.length && accept[i]) {
                out.append(text, last, matches.get(i)[0]).append(replacement);
                last = matches.get(i)[1];
            }
        }
        return out.append(text.substring(last)).toString();
    }

    /**
     * The bracket just before or at {@code caret} and the one that closes or opens it, skipping strings and comments
     * ({@code [at, partner]}), or null. Searches at most 20000 characters away.
     */
    public static int[] matchingBracket(String text, int caret) {
        for (int at : new int[]{caret - 1, caret}) {
            if (at < 0 || at >= text.length() || "()[]{}".indexOf(text.charAt(at)) < 0) {
                continue;
            }
            String masked = net.xmilon.craftpy.analysis.PyLex.maskNonCode(text);
            char c = masked.charAt(at);
            if ("()[]{}".indexOf(c) < 0) {
                continue; // the bracket is inside a string or a comment
            }
            boolean open = "([{".indexOf(c) >= 0;
            char partner = open ? closerFor(c) : "([{".charAt(")]}".indexOf(c));
            int depth = 0;
            int step = open ? 1 : -1;
            for (int i = at, n = 0; i >= 0 && i < masked.length() && n < 20_000; i += step, n++) {
                char d = masked.charAt(i);
                if (d == c) {
                    depth++;
                } else if (d == partner && --depth == 0) {
                    return new int[]{at, i};
                }
            }
        }
        return null;
    }
}
