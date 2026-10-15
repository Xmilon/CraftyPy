package net.xmilon.craftpy.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generates a Google-style docstring skeleton for the function or class around a line. */
public final class PyDocstring {
    public record Result(PyImports.Replacement replacement, String message) {
    }

    private PyDocstring() {
    }

    /** @param line 0-based line of the cursor; the innermost def/class containing it gets the docstring. */
    public static Result generate(String text, int line) {
        List<PyOutline.Symbol> symbols = PyOutline.parse(text);
        PyOutline.Symbol target = PyOutline.innermost(symbols, line);
        if (target == null) {
            return new Result(null, "Put the cursor inside a function or class");
        }
        String[] lines = text.split("\n", -1);
        String[] code = PyLex.maskNonCode(text).split("\n", -1);
        if (target.endLine() == target.signatureEndLine()) {
            return new Result(null, "One-line definitions have no body to document");
        }
        int first = target.signatureEndLine() + 1;
        while (first < lines.length && lines[first].isBlank()) {
            first++;
        }
        if (first >= lines.length) {
            return new Result(null, "No body to document");
        }
        String trimmed = lines[first].strip();
        if (code[first].isBlank() && (trimmed.startsWith("\"") || trimmed.startsWith("'") || trimmed.startsWith("r\""))) {
            return new Result(null, target.name() + " already has a docstring");
        }
        String indent = lines[first].substring(0, lines[first].length() - lines[first].stripLeading().length());
        if (indent.isEmpty()) {
            indent = " ".repeat(target.indent() + 4);
        }

        StringBuilder doc = new StringBuilder();
        doc.append(indent).append("\"\"\"").append(summary(target)).append('\n');
        if (target.kind() != PyOutline.Kind.CLASS) {
            List<String[]> params = parameters(target.signature());
            if (!params.isEmpty()) {
                doc.append('\n').append(indent).append("Args:\n");
                for (String[] p : params) {
                    doc.append(indent).append("    ").append(p[0]).append(p[1].isEmpty() ? "" : " (" + p[1] + ")")
                            .append(": ").append(describe(p[0])).append('\n');
                }
            }
            String body = bodyCode(symbols, target, code);
            if (Pattern.compile("(?m)\\byield\\b").matcher(body).find()) {
                doc.append('\n').append(indent).append("Yields:\n").append(indent).append("    ").append(returnType(target.signature(), "The next value.")).append('\n');
            } else if (Pattern.compile("(?m)\\breturn\\s+\\S").matcher(body).find()) {
                doc.append('\n').append(indent).append("Returns:\n").append(indent).append("    ").append(returnType(target.signature(), "The result.")).append('\n');
            }
            Matcher raises = Pattern.compile("\\braise\\s+([A-Za-z_][\\w.]*)").matcher(body);
            List<String> errors = new ArrayList<>();
            while (raises.find()) {
                if (!errors.contains(raises.group(1))) {
                    errors.add(raises.group(1));
                }
            }
            if (!errors.isEmpty()) {
                doc.append('\n').append(indent).append("Raises:\n");
                for (String error : errors) {
                    doc.append(indent).append("    ").append(error).append(": When it fails.\n");
                }
            }
        }
        doc.append(indent).append("\"\"\"\n");
        int offset = 0;
        for (int i = 0; i < first; i++) {
            offset += lines[i].length() + 1;
        }
        return new Result(new PyImports.Replacement(offset, offset, doc.toString()),
                "Docstring added to " + target.name());
    }

    /** Body code of the target with nested defs/classes blanked out (their returns are not ours). */
    private static String bodyCode(List<PyOutline.Symbol> symbols, PyOutline.Symbol target, String[] code) {
        boolean[] skip = new boolean[code.length];
        for (PyOutline.Symbol s : symbols) {
            if (s.isScope() && s != target && s.line() > target.line() && s.endLine() <= target.endLine()) {
                for (int i = s.line(); i <= s.endLine() && i < skip.length; i++) {
                    skip[i] = true;
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = target.signatureEndLine() + 1; i <= target.endLine() && i < code.length; i++) {
            if (!skip[i]) {
                out.append(code[i]).append('\n');
            }
        }
        return out.toString();
    }

    private static String summary(PyOutline.Symbol s) {
        String words = s.name().replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        if (words.isEmpty()) {
            return "Summary.";
        }
        return Character.toUpperCase(words.charAt(0)) + words.substring(1) + ".";
    }

    private static String describe(String param) {
        return "The " + param.replace('_', ' ') + ".";
    }

    private static String returnType(String signature, String fallback) {
        int arrow = signature.lastIndexOf("->");
        if (arrow < 0) {
            return fallback;
        }
        String type = signature.substring(arrow + 2).strip();
        if (type.endsWith(":")) {
            type = type.substring(0, type.length() - 1).strip();
        }
        return type.isEmpty() ? fallback : type + ": " + fallback;
    }

    /** {name, annotation} for every parameter except self/cls, bare * and /. */
    static List<String[]> parameters(String signature) {
        List<String[]> out = new ArrayList<>();
        int open = signature.indexOf('(');
        if (open < 0) {
            return out;
        }
        int depth = 0;
        int close = -1;
        for (int i = open; i < signature.length(); i++) {
            char c = signature.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            }
        }
        if (close < 0) {
            return out;
        }
        String inside = signature.substring(open + 1, close);
        List<String> parts = new ArrayList<>();
        int level = 0;
        int start = 0;
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                level++;
            } else if (c == ')' || c == ']' || c == '}') {
                level--;
            } else if (c == ',' && level == 0) {
                parts.add(inside.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(inside.substring(start));
        for (String part : parts) {
            String p = part.strip();
            int eq = topLevel(p, '=');
            if (eq >= 0) {
                p = p.substring(0, eq).strip();
            }
            int colon = topLevel(p, ':');
            String name = (colon >= 0 ? p.substring(0, colon) : p).strip();
            String type = colon >= 0 ? p.substring(colon + 1).strip() : "";
            name = name.replaceFirst("^\\*+", "");
            if (name.isEmpty() || name.equals("/") || name.equals("self") || name.equals("cls")) {
                continue;
            }
            out.add(new String[] { name, type });
        }
        return out;
    }

    private static int topLevel(String s, char target) {
        int level = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                level++;
            } else if (c == ')' || c == ']' || c == '}') {
                level--;
            } else if (c == target && level == 0) {
                return i;
            }
        }
        return -1;
    }
}
