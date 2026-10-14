package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Line tokenizer for the editor's syntax highlighting (Python, JSON, Markdown).
 * Pure logic: no game or UI types, so it is unit-tested directly.
 */
public final class Syntax {
    /** What a token is; the editor theme picks the color. */
    public enum Kind {
        TEXT, KEYWORD, BUILTIN, STRING, COMMENT, NUMBER, DECORATOR, FUNCTION, CLASS, SELF, CONSTANT,
        JSON_KEY, MD_HEADER, MD_LIST, MD_LINK, MD_CODE
    }

    private static final Set<String> KEYWORDS = Set.of("False", "None", "True", "and", "as", "assert", "async",
            "await", "break", "class", "continue", "def", "del", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
            "while", "with", "yield");

    private static final Set<String> BUILTINS = Set.of("print", "len", "range", "str", "int", "float", "list", "dict",
            "set", "tuple", "sum", "min", "max", "enumerate", "zip", "map", "filter", "isinstance", "type", "id",
            "open", "sorted", "reversed", "abs", "all", "any", "bool", "bytes", "chr", "ord", "divmod", "format",
            "frozenset", "getattr", "hasattr", "setattr", "hash", "hex", "oct", "bin", "input", "iter", "next",
            "object", "property", "slice", "super", "vars");

    public record Token(String text, Kind kind) {
    }

    private Syntax() {
    }

    /** Language id from a file path: "json", "md" or "py" (default). */
    public static String language(String path) {
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".json") || lower.endsWith(".mcmeta")) {
            return "json";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "md";
        }
        return "py";
    }

    /** Tokenizes one line as Python. */
    public static List<Token> tokenize(String line) {
        return tokenizePython(line);
    }

    public static List<Token> tokenize(String line, String language) {
        if (line.isEmpty()) {
            return new ArrayList<>();
        }
        return switch (language) {
            case "json" -> tokenizeJson(line);
            case "md" -> tokenizeMarkdown(line);
            default -> tokenizePython(line);
        };
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static List<Token> tokenizePython(String line) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        String prevWord = "";
        while (i < line.length()) {
            char c = line.charAt(i);
            int start = i;
            if (c == '#') {
                tokens.add(new Token(line.substring(i), Kind.COMMENT));
                return tokens;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < line.length()) {
                    if (line.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (line.charAt(i) == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
                i = Math.min(i, line.length());
                tokens.add(new Token(line.substring(start, i), Kind.STRING));
                continue;
            }
            if (Character.isDigit(c)) {
                while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '.'
                        || line.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(line.substring(start, i), Kind.NUMBER));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                while (i < line.length() && isWordChar(line.charAt(i))) {
                    i++;
                }
                String word = line.substring(start, i);
                int after = i;
                while (after < line.length() && line.charAt(after) == ' ') {
                    after++;
                }
                boolean call = after < line.length() && line.charAt(after) == '(';
                Kind kind = Kind.TEXT;
                if (word.equals("True") || word.equals("False") || word.equals("None")) {
                    kind = Kind.CONSTANT;
                } else if (KEYWORDS.contains(word)) {
                    kind = Kind.KEYWORD;
                } else if (word.equals("self") || word.equals("cls")) {
                    kind = Kind.SELF;
                } else if (prevWord.equals("class") || (Character.isUpperCase(word.charAt(0)) && hasLower(word))) {
                    kind = Kind.CLASS;
                } else if (BUILTINS.contains(word)) {
                    kind = Kind.BUILTIN;
                } else if (prevWord.equals("def") || call) {
                    kind = Kind.FUNCTION;
                } else if (word.length() > 1 && word.equals(word.toUpperCase(java.util.Locale.ROOT)) && hasLetter(word)) {
                    kind = Kind.CONSTANT;
                }
                prevWord = word;
                tokens.add(new Token(word, kind));
                continue;
            }
            if (c == '@') {
                i++;
                while (i < line.length() && (isWordChar(line.charAt(i)) || line.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(line.substring(start, i), Kind.DECORATOR));
                continue;
            }
            while (i < line.length() && !isWordChar(line.charAt(i)) && line.charAt(i) != '#'
                    && line.charAt(i) != '"' && line.charAt(i) != '\'' && line.charAt(i) != '@'
                    && !Character.isDigit(line.charAt(i))) {
                i++;
            }
            tokens.add(new Token(line.substring(start, i), Kind.TEXT));
        }
        return tokens;
    }

    private static boolean hasLower(String word) {
        for (int k = 1; k < word.length(); k++) {
            if (Character.isLowerCase(word.charAt(k))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLetter(String word) {
        for (int k = 0; k < word.length(); k++) {
            if (Character.isLetter(word.charAt(k))) {
                return true;
            }
        }
        return false;
    }

    private static List<Token> tokenizeJson(String line) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            int start = i;
            if (c == '"') {
                i++;
                while (i < line.length()) {
                    if (line.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (line.charAt(i) == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                i = Math.min(i, line.length());
                int after = i;
                while (after < line.length() && Character.isWhitespace(line.charAt(after))) {
                    after++;
                }
                // A string followed by ':' is an object key.
                boolean key = after < line.length() && line.charAt(after) == ':';
                tokens.add(new Token(line.substring(start, i), key ? Kind.JSON_KEY : Kind.STRING));
                continue;
            }
            if (Character.isDigit(c) || c == '-') {
                i++;
                while (i < line.length() && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(line.substring(start, i), Kind.NUMBER));
                continue;
            }
            if (Character.isLetter(c)) {
                while (i < line.length() && Character.isLetter(line.charAt(i))) {
                    i++;
                }
                String word = line.substring(start, i);
                boolean literal = "true".equals(word) || "false".equals(word) || "null".equals(word);
                tokens.add(new Token(word, literal ? Kind.KEYWORD : Kind.TEXT));
                continue;
            }
            i++;
            tokens.add(new Token(line.substring(start, i), Kind.TEXT));
        }
        return tokens;
    }

    private static List<Token> tokenizeMarkdown(String line) {
        List<Token> tokens = new ArrayList<>();
        if (line.startsWith("#")) {
            tokens.add(new Token(line, Kind.MD_HEADER));
            return tokens;
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("+ ")
                || line.matches("^\\s*\\d+\\.\\s.*")) {
            tokens.add(new Token(line, Kind.MD_LIST));
            return tokens;
        }
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            int start = i;
            if (c == '`') {
                i++;
                while (i < line.length() && line.charAt(i) != '`') i++;
                if (i < line.length()) i++;
                tokens.add(new Token(line.substring(start, i), Kind.MD_CODE));
                continue;
            }
            if (c == '[' || c == '(') {
                char endChar = (c == '[') ? ']' : ')';
                i++;
                while (i < line.length() && line.charAt(i) != endChar) i++;
                if (i < line.length()) i++;
                tokens.add(new Token(line.substring(start, i), Kind.MD_LINK));
                continue;
            }
            i++;
            while (i < line.length() && line.charAt(i) != '`' && line.charAt(i) != '[' && line.charAt(i) != '(') {
                i++;
            }
            tokens.add(new Token(line.substring(start, i), Kind.TEXT));
        }
        return tokens;
    }
}
