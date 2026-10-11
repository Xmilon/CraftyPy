package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side "Problems" data for the open file. Full Python validation is done
 * by the Python worker (see the runtime milestone); this supplies lightweight
 * structural checks that run instantly so obvious mistakes are flagged before a
 * save, and acts as the staging area for worker-reported diagnostics.
 */
public final class CodeProblems {
    public enum Severity {
        INFO, WARNING, ERROR
    }

    public record Problem(int line, int column, Severity severity, String message) {
    }

    private static final List<Problem> PROBLEMS = new ArrayList<>();

    private CodeProblems() {
    }

    public static List<Problem> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(PROBLEMS));
    }

    public static void clear() {
        PROBLEMS.clear();
    }

    public static void setForFile(String file, String content) {
        clear();
        basicCheck(file, content);
    }

    /**
     * Lightweight structural checks: unbalanced brackets (strings and comments
     * ignored) and mixed indentation. Bracket matching, strings and comments are
     * tracked across the whole file, so valid multi-line code such as
     * <pre>
     * print(
     *     "hello"
     * )
     * </pre>
     * or a triple-quoted docstring containing brackets is never mis-flagged.
     * Runs on the client thread; intended to be fast enough for per-keypress use.
     */
    public static void basicCheck(String file, String content) {
        PROBLEMS.clear();
        if (content == null || content.isEmpty()) {
            return;
        }
        String[] lines = content.split("\n", -1);
        java.util.ArrayDeque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false;
        char stringChar = 0;
        int stringLen = 1;
        int stringStartLine = 0;
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i];
            boolean inComment = false;
            int col = 0;
            while (col < text.length()) {
                char c = text.charAt(col);
                if (inString) {
                    if (c == '\\') {
                        col += 2;
                        continue;
                    }
                    if (c == stringChar && runLength(text, col, stringChar) >= stringLen) {
                        col += stringLen;
                        inString = false;
                        continue;
                    }
                    col++;
                    continue;
                }
                if (inComment) {
                    col++;
                    continue;
                }
                if (c == '#') {
                    inComment = true;
                    col++;
                    continue;
                }
                if (c == '"' || c == '\'') {
                    int run = runLength(text, col, c);
                    stringChar = c;
                    stringLen = run >= 3 ? 3 : 1;
                    stringStartLine = i;
                    inString = true;
                    col += stringLen;
                    continue;
                }
                switch (c) {
                    case '(', '[', '{' -> stack.push(c);
                    case ')' -> reportIfUnbalanced(stack, '(', i, col, "Unexpected ')'");
                    case ']' -> reportIfUnbalanced(stack, '[', i, col, "Unexpected ']'");
                    case '}' -> reportIfUnbalanced(stack, '{', i, col, "Unexpected '}'");
                    default -> {
                    }
                }
                col++;
            }
            if (!text.isBlank() && (text.endsWith(" ") || text.endsWith("\t"))) {
                PROBLEMS.add(new Problem(i, text.length() - 1, Severity.WARNING, "Trailing whitespace"));
            }
        }
        if (!stack.isEmpty()) {
            PROBLEMS.add(new Problem(lines.length - 1, 0, Severity.ERROR,
                    "Unclosed '" + stack.peek() + "'"));
        }
        if (inString) {
            PROBLEMS.add(new Problem(stringStartLine, 0, Severity.WARNING,
                    "Unterminated string literal (opened on line " + (stringStartLine + 1) + ")"));
        }
        checkIndentation(lines);
        checkEntryPoint(lines);
    }

    private static int runLength(String text, int start, char c) {
        int run = 0;
        while (start + run < text.length() && text.charAt(start + run) == c) {
            run++;
        }
        return run;
    }

    private static void reportIfUnbalanced(java.util.ArrayDeque<Character> stack, char expected, int line, int col,
            String message) {
        if (match(stack, expected)) {
            return;
        }
        PROBLEMS.add(new Problem(line, col, Severity.ERROR, message));
    }

    private static boolean match(java.util.ArrayDeque<Character> stack, char expected) {
        if (stack.isEmpty() || stack.peek() != expected) {
            return false;
        }
        stack.pop();
        return true;
    }

    private static void checkIndentation(String[] lines) {
        boolean seenSpaces = false;
        boolean seenTabs = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith(" ")) {
                seenSpaces = true;
            }
            if (line.startsWith("\t")) {
                seenTabs = true;
            }
            if (seenSpaces && seenTabs) {
                PROBLEMS.add(new Problem(i, 0, Severity.ERROR,
                        "File mixes spaces and tabs for indentation (Python 2 style taboo)"));
                return;
            }
        }
    }

    private static void checkEntryPoint(String[] lines) {
        boolean hasDef = false;
        for (String line : lines) {
            if (line.matches("^\\s*def\\s+.+")) {
                hasDef = true;
                break;
            }
        }
        // Heuristic: definitions without a call site get a note, but this is noisy;
        // we only flag when there are defs and nothing calls "main(".
        if (hasDef) {
            boolean callsMain = false;
            for (String line : lines) {
                if (line.contains("main(") || line.contains("ctx.")) {
                    callsMain = true;
                    break;
                }
            }
            if (!callsMain) {
                PROBLEMS.add(new Problem(0, 0, Severity.INFO,
                        "No entry point detected; CraftPy looks for a 'main(ctx)' function."));
            }
        }
    }
}