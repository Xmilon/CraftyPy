package net.xmilon.craftpy.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The structure of one Python file: classes, functions, methods, event handlers, commands, constants
 * and CraftPy content declarations ({@code ruby = Item("ruby", ...)}). Lines are 0-based.
 */
public final class PyOutline {
    public enum Kind { CLASS, FUNCTION, METHOD, HANDLER, COMMAND, CONTENT, CONSTANT }

    /**
     * @param line          line of the {@code def}/{@code class}/assignment (decorators are above it)
     * @param endLine       last line of the body (equal to {@code signatureEndLine} for one-liners)
     * @param parent        enclosing class/function name, or empty
     * @param detail        decorator text, or the declaring call name ({@code Block}, {@code Item})
     * @param contentId     the id string of a CONTENT declaration, otherwise empty
     * @param signature     the {@code def} header collapsed to one line
     */
    public record Symbol(String name, Kind kind, int line, int endLine, int indent, String parent, String detail,
                         String contentId, int signatureEndLine, String signature) {
        public boolean isScope() {
            return kind == Kind.CLASS || kind == Kind.FUNCTION || kind == Kind.METHOD || kind == Kind.HANDLER
                    || kind == Kind.COMMAND;
        }

        public String qualifiedName() {
            return parent.isEmpty() ? name : parent + "." + name;
        }
    }

    private static final Pattern DEF = Pattern.compile("^(\\s*)(?:async\\s+)?(def|class)\\s+([A-Za-z_]\\w*)");
    private static final Pattern ASSIGN = Pattern.compile("^([A-Za-z_]\\w*)\\s*(?::[^=]+)?=(?!=)\\s*(.*)$");
    private static final Pattern CONTENT_CALL = Pattern.compile("^(Block|Item)\\(\\s*[\"']([^\"']+)[\"']");
    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z0-9_]*");

    private PyOutline() {
    }

    public static List<Symbol> parse(String text) {
        String masked = PyLex.maskNonCode(text);
        String[] lines = text.split("\n", -1);
        String[] code = masked.split("\n", -1);
        int[] starts = new int[lines.length];
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            starts[i] = offset;
            offset += lines[i].length() + 1;
        }

        List<Symbol> symbols = new ArrayList<>();
        List<int[]> scopes = new ArrayList<>(); // {symbol index, indent, endLine} of open scopes
        String decorator = "";
        for (int i = 0; i < lines.length; i++) {
            String line = code[i];
            if (line.isBlank()) {
                continue;
            }
            int indent = indentOf(line);
            String stripped = line.strip();
            if (stripped.startsWith("@")) {
                if (decorator.isEmpty()) {
                    decorator = lines[i].strip().substring(1).strip();
                }
                continue;
            }
            Matcher def = DEF.matcher(line);
            if (def.find()) {
                boolean isClass = def.group(2).equals("class");
                int sigEnd = signatureEnd(code, i);
                String signature = collapse(lines, i, sigEnd);
                int end = bodyEnd(lines, code, sigEnd, indent);
                String parent = enclosing(symbols, scopes, i, indent);
                Kind kind = isClass ? Kind.CLASS : Kind.FUNCTION;
                if (!isClass) {
                    if (decorator.startsWith("on.") || decorator.startsWith("on(") || decorator.startsWith("every")) {
                        kind = Kind.HANDLER;
                    } else if (decorator.startsWith("command")) {
                        kind = Kind.COMMAND;
                    } else if (parentIsClass(symbols, parent, indent)) {
                        kind = Kind.METHOD;
                    }
                }
                symbols.add(new Symbol(def.group(3), kind, i, end, indent, parent, decorator, "", sigEnd, signature));
                scopes.add(new int[] { symbols.size() - 1, indent, end });
                decorator = "";
                continue;
            }
            decorator = "";
            if (indent == 0) {
                topLevel(symbols, lines[i], line, i);
            }
        }
        return symbols;
    }

    private static void topLevel(List<Symbol> symbols, String original, String masked, int lineNo) {
        Matcher assign = ASSIGN.matcher(masked.strip());
        if (assign.find()) {
            String name = assign.group(1);
            String rhs = original.substring(original.indexOf('=', original.indexOf(name) + name.length()) + 1).strip();
            Matcher call = CONTENT_CALL.matcher(rhs);
            if (call.find()) {
                symbols.add(new Symbol(name, Kind.CONTENT, lineNo, lineNo, 0, "", call.group(1), call.group(2), lineNo,
                        original.strip()));
            } else if (CONSTANT.matcher(name).matches()) {
                symbols.add(new Symbol(name, Kind.CONSTANT, lineNo, lineNo, 0, "", "", "", lineNo, original.strip()));
            }
            return;
        }
        Matcher bare = CONTENT_CALL.matcher(original.strip());
        if (bare.find()) {
            symbols.add(new Symbol(bare.group(2), Kind.CONTENT, lineNo, lineNo, 0, "", bare.group(1), bare.group(2),
                    lineNo, original.strip()));
        }
    }

    /** Scopes whose line range contains {@code line}, outermost first (for breadcrumbs). */
    public static List<Symbol> chain(List<Symbol> symbols, int line) {
        List<Symbol> out = new ArrayList<>();
        for (Symbol s : symbols) {
            if (s.isScope() && s.line() <= line && line <= s.endLine()) {
                out.add(s);
            }
        }
        out.sort((a, b) -> Integer.compare(a.line(), b.line()));
        return out;
    }

    /** The innermost def/class that contains {@code line}, or null. */
    public static Symbol innermost(List<Symbol> symbols, int line) {
        List<Symbol> chain = chain(symbols, line);
        return chain.isEmpty() ? null : chain.get(chain.size() - 1);
    }

    private static String enclosing(List<Symbol> symbols, List<int[]> scopes, int line, int indent) {
        String parent = "";
        int best = -1;
        for (int[] scope : scopes) {
            Symbol s = symbols.get(scope[0]);
            if (s.line() < line && line <= scope[2] && s.indent() < indent && s.indent() > best) {
                best = s.indent();
                parent = s.qualifiedName();
            }
        }
        return parent;
    }

    private static boolean parentIsClass(List<Symbol> symbols, String parent, int indent) {
        for (int i = symbols.size() - 1; i >= 0; i--) {
            Symbol s = symbols.get(i);
            if (s.qualifiedName().equals(parent)) {
                return s.kind() == Kind.CLASS;
            }
        }
        return false;
    }

    /** Index of the line holding the {@code :} that ends the header (handles multi-line signatures). */
    static int signatureEnd(String[] code, int from) {
        int depth = 0;
        for (int i = from; i < code.length && i < from + 40; i++) {
            for (char c : code[i].toCharArray()) {
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                } else if (c == ':' && depth <= 0) {
                    return i;
                }
            }
        }
        return from;
    }

    private static int bodyEnd(String[] lines, String[] code, int sigEnd, int indent) {
        int end = sigEnd;
        String afterColon = code[sigEnd].substring(code[sigEnd].indexOf(':') < 0 ? 0 : code[sigEnd].lastIndexOf(':') + 1);
        if (!afterColon.isBlank()) {
            return sigEnd; // one-liner: def f(): return 1
        }
        for (int i = sigEnd + 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            if (code[i].isBlank()) {
                // A comment (neutral) or the inside of a multi-line string (part of the body).
                if (!lines[i].strip().startsWith("#")) {
                    end = i;
                }
                continue;
            }
            if (indentOf(code[i]) <= indent) {
                break;
            }
            end = i;
        }
        return end;
    }

    private static String collapse(String[] lines, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(lines[i].strip());
        }
        return out.toString();
    }

    static int indentOf(String line) {
        int n = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                n++;
            } else if (c == '\t') {
                n += 4;
            } else {
                break;
            }
        }
        return n;
    }
}
