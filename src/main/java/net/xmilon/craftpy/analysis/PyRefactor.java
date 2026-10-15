package net.xmilon.craftpy.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extract variable / extract function (30). Both are text transformations that return one
 * {@link PyImports.Replacement}; a refusal comes back with a message and no replacement. The inferred
 * parameters / return values are heuristics (names assigned earlier / used later in the same function),
 * so the editor tells the user to review the result.
 */
public final class PyRefactor {
    public record Result(PyImports.Replacement replacement, String message, int cursorStart, int cursorEnd) {
        static Result refuse(String message) {
            return new Result(null, message, 0, 0);
        }
    }

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");
    private static final Set<String> KEYWORDS = Set.of("False", "None", "True", "and", "as", "assert", "async", "await",
            "break", "class", "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
            "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while",
            "with", "yield", "self");
    private static final Pattern ASSIGN_TARGETS = Pattern.compile(
            "^\\s*([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)*)\\s*(?:[-+*/%&|^]|//|\\*\\*|>>|<<)?=(?!=)");
    private static final Pattern FOR_TARGETS = Pattern.compile("^\\s*(?:async\\s+)?for\\s+([A-Za-z_][\\w\\s,]*?)\\s+in\\b");

    private PyRefactor() {
    }

    // -- extract variable -------------------------------------------------------

    public static Result extractVariable(String text, int selStart, int selEnd, String name) {
        if (!IDENT.matcher(name).matches() || KEYWORDS.contains(name)) {
            return Result.refuse("'" + name + "' is not a valid variable name");
        }
        if (selEnd <= selStart || text.substring(selStart, selEnd).isBlank()) {
            return Result.refuse("Select the expression to extract");
        }
        String expression = text.substring(selStart, selEnd);
        if (expression.contains("\n")) {
            return Result.refuse("Select an expression on one line");
        }
        String masked = PyLex.maskNonCode(text);
        for (PyLex.Span span : PyLex.spans(text)) {
            if (span.kind() == PyLex.Kind.CODE) {
                continue;
            }
            boolean startsInside = selStart > span.start() && selStart < span.end();
            boolean endsInside = selEnd > span.start() && selEnd < span.end();
            if (startsInside || endsInside) {
                return Result.refuse("The selection cuts through a string or comment");
            }
        }
        if (!balanced(masked.substring(selStart, selEnd))) {
            return Result.refuse("The selection has unbalanced brackets");
        }
        String[] lines = text.split("\n", -1);
        String[] code = masked.split("\n", -1);
        int line = TextOpsLite.lineOf(text, selStart);
        int statement = statementStart(code, line);
        String firstWord = code[statement].strip().split("[\\s(:]", 2)[0];
        if (Set.of("elif", "else", "except", "finally", "case").contains(firstWord)) {
            return Result.refuse("Extract from a statement inside the block, not from its header");
        }
        String indent = lines[statement].substring(0, lines[statement].length() - lines[statement].stripLeading().length());
        int insertAt = offsetOfLine(lines, statement);
        String before = text.substring(insertAt, selStart);
        String replacement = indent + name + " = " + expression + "\n" + before + name;
        int nameStart = insertAt + indent.length();
        return new Result(new PyImports.Replacement(insertAt, selEnd, replacement),
                "Extracted " + name, nameStart, nameStart + name.length());
    }

    private static int statementStart(String[] code, int line) {
        int depth = 0;
        int[] depthAtStart = new int[code.length];
        for (int i = 0; i < code.length; i++) {
            depthAtStart[i] = depth;
            for (char c : code[i].toCharArray()) {
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    depth = Math.max(0, depth - 1);
                }
            }
        }
        int start = line;
        while (start > 0 && (depthAtStart[start] > 0 || code[start - 1].stripTrailing().endsWith("\\"))) {
            start--;
        }
        return start;
    }

    // -- extract function ----------------------------------------------------------

    /** @param firstLine / lastLine 0-based, inclusive; whole lines are extracted. */
    public static Result extractFunction(String text, int firstLine, int lastLine, String name) {
        if (!IDENT.matcher(name).matches() || KEYWORDS.contains(name)) {
            return Result.refuse("'" + name + "' is not a valid function name");
        }
        String[] lines = text.split("\n", -1);
        String[] code = PyLex.maskNonCode(text).split("\n", -1);
        lastLine = Math.min(lastLine, lines.length - 1);
        while (lastLine > firstLine && lines[lastLine].isBlank()) {
            lastLine--;
        }
        if (firstLine < 0 || firstLine > lastLine || lines[firstLine].isBlank()) {
            return Result.refuse("Select the lines to move into a function");
        }
        List<PyOutline.Symbol> symbols = PyOutline.parse(text);
        List<PyOutline.Symbol> chain = PyOutline.chain(symbols, firstLine);
        PyOutline.Symbol scope = chain.isEmpty() ? null : chain.get(chain.size() - 1);
        if (scope != null && (lastLine > scope.endLine() || firstLine <= scope.signatureEndLine())) {
            return Result.refuse("Select only lines from the body of one function");
        }
        StringBuilder selected = new StringBuilder();
        int common = Integer.MAX_VALUE;
        for (int i = firstLine; i <= lastLine; i++) {
            selected.append(code[i]).append('\n');
            if (!lines[i].isBlank()) {
                common = Math.min(common, PyOutline.indentOf(lines[i]));
            }
        }
        String selectedCode = selected.toString();
        if (Pattern.compile("\\b(return|yield|await)\\b").matcher(selectedCode).find()) {
            return Result.refuse("The selection contains return / yield / await - select a part without it");
        }
        boolean hasLoopHeader = Pattern.compile("(?m)^\\s*(for|while)\\b").matcher(selectedCode).find();
        if (!hasLoopHeader && Pattern.compile("\\b(break|continue)\\b").matcher(selectedCode).find()) {
            return Result.refuse("The selection contains break / continue but not the loop around it");
        }
        // Names used in the selection (code plus f-string expressions).
        Set<String> used = identifiers(PyLex.codeAndFStringExpressions(joinLines(lines, firstLine, lastLine)));
        // Locals of the enclosing function: parameters and names assigned before the selection.
        Set<String> locals = new LinkedHashSet<>();
        if (scope != null) {
            for (String[] p : PyDocstring.parameters(scope.signature())) {
                locals.add(p[0]);
            }
            if (scope.signature().contains("(self") || scope.signature().contains("( self")) {
                locals.add("self");
            }
            for (int i = scope.signatureEndLine() + 1; i < firstLine; i++) {
                collectTargets(code[i], locals);
            }
        }
        List<String> params = new ArrayList<>();
        for (String id : locals) { // definition order: signature first, then in order of assignment
            if (used.contains(id) && (!KEYWORDS.contains(id) || id.equals("self"))) {
                params.add(id);
            }
        }
        // Names assigned inside the selection and read afterwards become return values.
        Set<String> assigned = new LinkedHashSet<>();
        for (int i = firstLine; i <= lastLine; i++) {
            collectTargets(code[i], assigned);
        }
        int scopeEnd = scope == null ? lines.length - 1 : scope.endLine();
        Set<String> after = identifiers(PyLex.codeAndFStringExpressions(joinLines(lines, lastLine + 1, scopeEnd)));
        List<String> outputs = new ArrayList<>();
        for (String id : assigned) {
            if (after.contains(id)) {
                outputs.add(id);
            }
        }

        String bodyIndent = "    ";
        StringBuilder function = new StringBuilder("def " + name + "(" + String.join(", ", params) + "):\n");
        for (int i = firstLine; i <= lastLine; i++) {
            function.append(lines[i].isBlank() ? "" : bodyIndent + lines[i].substring(Math.min(common, lines[i].length())))
                    .append('\n');
        }
        if (!outputs.isEmpty()) {
            function.append(bodyIndent).append("return ").append(String.join(", ", outputs)).append('\n');
        }
        function.append("\n\n");

        int insertLine = scope == null ? firstLine : topLevelStart(symbols, lines, firstLine);
        int insertAt = offsetOfLine(lines, insertLine);
        int selectionEnd = offsetOfLine(lines, lastLine) + lines[lastLine].length();
        String indent = lines[firstLine].substring(0, Math.min(common, lines[firstLine].length()));
        String call = indent + (outputs.isEmpty() ? "" : String.join(", ", outputs) + " = ") + name + "("
                + String.join(", ", params) + ")";
        String between = text.substring(insertAt, offsetOfLine(lines, firstLine));
        String replacement = function + between + call;
        int nameAt = insertAt + "def ".length();
        return new Result(new PyImports.Replacement(insertAt, selectionEnd, replacement),
                "Extracted " + name + "(" + String.join(", ", params) + ")"
                        + (outputs.isEmpty() ? "" : " returning " + String.join(", ", outputs))
                        + " - review the parameters",
                nameAt, nameAt + name.length());
    }

    /** First line of the top-level def/class containing {@code line}, including its decorators. */
    private static int topLevelStart(List<PyOutline.Symbol> symbols, String[] lines, int line) {
        PyOutline.Symbol top = null;
        for (PyOutline.Symbol s : PyOutline.chain(symbols, line)) {
            if (s.indent() == 0) {
                top = s;
            }
        }
        int start = top == null ? line : top.line();
        while (start > 0 && lines[start - 1].stripLeading().startsWith("@")) {
            start--;
        }
        return start;
    }

    private static void collectTargets(String maskedLine, Set<String> into) {
        Matcher assign = ASSIGN_TARGETS.matcher(maskedLine);
        if (assign.find()) {
            for (String part : assign.group(1).split(",")) {
                into.add(part.trim());
            }
        }
        Matcher loop = FOR_TARGETS.matcher(maskedLine);
        if (loop.find()) {
            for (String part : loop.group(1).split(",")) {
                into.add(part.trim());
            }
        }
        Matcher with = Pattern.compile("\\bas\\s+([A-Za-z_]\\w*)").matcher(maskedLine);
        while (with.find()) {
            into.add(with.group(1));
        }
    }

    private static Set<String> identifiers(String code) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = IDENT.matcher(code);
        while (m.find()) {
            if (m.start() == 0 || code.charAt(m.start() - 1) != '.') {
                out.add(m.group());
            }
        }
        return out;
    }

    private static boolean balanced(String code) {
        int depth = 0;
        for (char c : code.toCharArray()) {
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (--depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private static String joinLines(String[] lines, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, from); i <= to && i < lines.length; i++) {
            out.append(lines[i]).append('\n');
        }
        return out.toString();
    }

    private static int offsetOfLine(String[] lines, int line) {
        int offset = 0;
        for (int i = 0; i < line && i < lines.length; i++) {
            offset += lines[i].length() + 1;
        }
        return offset;
    }

    /** The one TextOps function needed here, so this package does not depend on the client package. */
    private static final class TextOpsLite {
        static int lineOf(String text, int offset) {
            int line = 0;
            for (int i = 0; i < offset && i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }
    }
}
