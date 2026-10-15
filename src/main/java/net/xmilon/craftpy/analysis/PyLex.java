package net.xmilon.craftpy.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits Python source into code, string and comment spans (triple-quoted strings, prefixes and
 * escapes included). Every analyzer works on {@link #maskNonCode} so docstrings and comments can
 * never be mistaken for code.
 */
public final class PyLex {
    public enum Kind { CODE, STRING, COMMENT }

    /**
     * A span {@code [start, end)}. For strings, {@code [contentStart, contentEnd)} is the text between
     * the quotes and {@code fstring} tells whether the prefix contains {@code f}.
     */
    public record Span(Kind kind, int start, int end, int contentStart, int contentEnd, boolean fstring) {
    }

    private static final Set<String> PREFIXES = Set.of("r", "b", "u", "f", "rb", "br", "fr", "rf");

    private PyLex() {
    }

    public static List<Span> spans(String text) {
        List<Span> out = new ArrayList<>();
        int n = text.length();
        int codeStart = 0;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '#') {
                flushCode(out, codeStart, i);
                int end = text.indexOf('\n', i);
                if (end < 0) {
                    end = n;
                }
                out.add(new Span(Kind.COMMENT, i, end, i, end, false));
                i = end;
                codeStart = i;
            } else if (c == '\'' || c == '"') {
                i = string(text, out, codeStart, i, i, false);
                codeStart = i;
            } else if (Character.isLetter(c) || c == '_') {
                int wordEnd = i + 1;
                while (wordEnd < n && (Character.isLetterOrDigit(text.charAt(wordEnd)) || text.charAt(wordEnd) == '_')) {
                    wordEnd++;
                }
                String word = text.substring(i, wordEnd).toLowerCase();
                if (wordEnd < n && (text.charAt(wordEnd) == '\'' || text.charAt(wordEnd) == '"')
                        && PREFIXES.contains(word)) {
                    i = string(text, out, codeStart, i, wordEnd, word.indexOf('f') >= 0);
                    codeStart = i;
                } else {
                    i = wordEnd;
                }
            } else {
                i++;
            }
        }
        flushCode(out, codeStart, n);
        return out;
    }

    private static void flushCode(List<Span> out, int from, int to) {
        if (to > from) {
            out.add(new Span(Kind.CODE, from, to, from, to, false));
        }
    }

    /** Reads the string starting with its quote at {@code quoteAt}; returns the offset after it. */
    private static int string(String text, List<Span> out, int codeStart, int start, int quoteAt, boolean fstring) {
        flushCode(out, codeStart, start);
        int n = text.length();
        char quote = text.charAt(quoteAt);
        boolean triple = text.startsWith(String.valueOf(quote).repeat(3), quoteAt);
        int contentStart = quoteAt + (triple ? 3 : 1);
        int i = contentStart;
        int contentEnd = n;
        int end = n;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (triple ? text.startsWith(String.valueOf(quote).repeat(3), i) : c == quote) {
                contentEnd = i;
                end = i + (triple ? 3 : 1);
                break;
            }
            if (!triple && c == '\n') {
                contentEnd = i;
                end = i;
                break;
            }
            i++;
        }
        if (i >= n) {
            contentEnd = n;
            end = n;
        }
        out.add(new Span(Kind.STRING, start, end, contentStart, Math.min(contentEnd, n), fstring));
        return end;
    }

    /** Same length as {@code text}: strings and comments become spaces (newlines kept), code is unchanged. */
    public static String maskNonCode(String text) {
        char[] chars = text.toCharArray();
        for (Span span : spans(text)) {
            if (span.kind() != Kind.CODE) {
                for (int i = span.start(); i < span.end(); i++) {
                    if (chars[i] != '\n') {
                        chars[i] = ' ';
                    }
                }
            }
        }
        return new String(chars);
    }

    /** Code with the expressions inside f-string braces appended, so "used name" scans see {@code f"{x}"}. */
    public static String codeAndFStringExpressions(String text) {
        StringBuilder out = new StringBuilder(maskNonCode(text));
        for (Span span : spans(text)) {
            if (span.kind() == Kind.STRING && span.fstring()) {
                out.append('\n');
                for (int[] range : fstringExpressions(text, span)) {
                    out.append(text, range[0], range[1]).append('\n');
                }
            }
        }
        return out.toString();
    }

    /** Offset ranges of the {@code {expression}} parts of an f-string ({@code {{} escapes skipped). */
    public static List<int[]> fstringExpressions(String text, Span span) {
        List<int[]> out = new ArrayList<>();
        int i = span.contentStart();
        while (i < span.contentEnd()) {
            char c = text.charAt(i);
            if (c == '{' && i + 1 < span.contentEnd() && text.charAt(i + 1) == '{') {
                i += 2;
            } else if (c == '{') {
                int depth = 1;
                int j = i + 1;
                while (j < span.contentEnd() && depth > 0) {
                    char d = text.charAt(j);
                    if (d == '{') {
                        depth++;
                    } else if (d == '}') {
                        depth--;
                    }
                    j++;
                }
                out.add(new int[] { i + 1, depth == 0 ? j - 1 : j });
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    public static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
