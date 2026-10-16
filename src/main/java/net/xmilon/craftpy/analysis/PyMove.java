package net.xmilon.craftpy.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Move function / class to another file" (IntelliJ's F6). Moves a top-level {@code def} or {@code class} (with its
 * decorators and the comment right above it) from one project file to another and keeps every file working: the target
 * gets the imports the moved code uses, the source imports it back when it still uses it, and the other files that did
 * {@code from source import name} (or wrote {@code source.name}) now take it from the target. Pure text: no IO.
 */
public final class PyMove {

    /** {@code newTexts}: the full new content of every changed file (the target may be a new file). */
    public record Plan(String error, Map<String, String> newTexts, String message) {
        public boolean ok() {
            return error == null;
        }

        static Plan refuse(String error) {
            return new Plan(error, Map.of(), "");
        }
    }

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");
    private static final Pattern FROM = Pattern.compile("^from\\s+([\\w.]+)\\s+import\\s+(.+)$");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(.+)$");

    private PyMove() {
    }

    /** The module name of a project file: {@code code/tools/util.py} is {@code tools.util}; null for other files. */
    public static String moduleOf(String path) {
        if (path == null || !path.startsWith("code/") || !path.endsWith(".py")) {
            return null;
        }
        String module = path.substring(5, path.length() - 3).replace('/', '.');
        if (module.endsWith(".__init__")) {
            module = module.substring(0, module.length() - ".__init__".length());
        }
        return module.matches("[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)*") ? module : null;
    }

    /**
     * @param texts  every Python file of the project (path to text), the source among them
     * @param target the file to move to; created when it is not in {@code texts}
     */
    public static Plan move(Map<String, String> texts, String source, String name, String target) {
        String sourceText = texts.get(source);
        String sourceModule = moduleOf(source);
        String targetModule = moduleOf(target);
        if (sourceText == null || sourceModule == null) {
            return Plan.refuse("Open a Python file under code/ first");
        }
        if (targetModule == null) {
            return Plan.refuse("'" + target + "' is not a Python file under code/ (like code/tools.py)");
        }
        if (target.equals(source)) {
            return Plan.refuse(name + " is already in " + source);
        }
        if (target.equals("code/main.py")) {
            return Plan.refuse("Nothing can import main.py: choose another file");
        }
        PyOutline.Symbol symbol = null;
        for (PyOutline.Symbol s : PyOutline.parse(sourceText)) {
            if (s.indent() == 0 && s.name().equals(name) && s.isScope()) {
                symbol = s;
            }
        }
        if (symbol == null) {
            return Plan.refuse("No top-level def or class '" + name + "' in " + source);
        }
        String[] lines = sourceText.split("\n", -1);
        int first = symbol.line();
        while (first > 0 && (lines[first - 1].startsWith("@") || lines[first - 1].startsWith("#"))) {
            first--;
        }
        int last = symbol.endLine();
        String block = String.join("\n", java.util.Arrays.copyOfRange(lines, first, last + 1)).stripTrailing();

        List<String> remainingLines = new ArrayList<>(List.of(lines).subList(0, first));
        int after = last + 1;
        while (after < lines.length && lines[after].isBlank() && after < last + 3) {
            after++; // the blank lines that separated the block from the next one
        }
        remainingLines.addAll(List.of(lines).subList(after, lines.length));
        String remaining = String.join("\n", remainingLines);

        Set<String> used = identifiers(block);
        Set<String> stillUsed = identifiers(remaining);
        Set<String> sourceTopNames = new LinkedHashSet<>();
        for (PyOutline.Symbol s : PyOutline.parse(remaining)) {
            if (s.indent() == 0) {
                sourceTopNames.add(s.name());
            }
        }
        List<String> needsFromSource = new ArrayList<>();
        for (String n : used) {
            if (!n.equals(name) && sourceTopNames.contains(n)) {
                needsFromSource.add(n);
            }
        }
        boolean sourceNeedsIt = stillUsed.contains(name);
        // @on / @command / @every register when their file runs: the source keeps importing the target so they still do
        boolean decorated = false;
        for (int i = first; i < symbol.line(); i++) {
            decorated |= lines[i].startsWith("@");
        }
        if (!needsFromSource.isEmpty() && (sourceNeedsIt || decorated)) {
            return Plan.refuse(name + " uses " + String.join(", ", needsFromSource) + " from " + source + ", and " + source
                    + (sourceNeedsIt ? " still uses " + name : " must keep importing it so its decorator runs")
                    + ": the two files would import each other. Move those names first.");
        }

        // the imports of the source the moved code needs, then the names it uses that stay in the source
        List<String> imports = new ArrayList<>();
        for (String statement : topLevelImports(sourceText)) {
            Set<String> bound = boundNames(statement);
            if (bound.contains("*") || bound.stream().anyMatch(used::contains)) {
                imports.add(statement);
            }
        }
        if (!needsFromSource.isEmpty()) {
            imports.add("from " + sourceModule + " import " + String.join(", ", needsFromSource));
        }

        Map<String, String> out = new LinkedHashMap<>();
        String targetText = texts.get(target);
        String newTarget = targetText == null || targetText.isBlank()
                ? String.join("\n", imports) + (imports.isEmpty() ? "" : "\n\n\n") + block + "\n"
                : appendBlock(withImports(targetText, imports), block);
        if (targetText != null && definesTopLevel(targetText, name)) {
            return Plan.refuse(target + " already defines " + name);
        }
        out.put(target, newTarget);
        out.put(source, sourceNeedsIt ? withImports(remaining, List.of("from " + targetModule + " import " + name))
                : decorated ? withImports(remaining, List.of("import " + targetModule)) : remaining);

        int others = 0;
        for (var entry : texts.entrySet()) {
            String path = entry.getKey();
            if (path.equals(source) || path.equals(target) || !path.endsWith(".py")) {
                continue;
            }
            String updated = redirect(entry.getValue(), sourceModule, targetModule, name);
            if (!updated.equals(entry.getValue())) {
                out.put(path, updated);
                others++;
            }
        }
        if (out.containsKey(target) && texts.containsKey(target) && !target.equals(source)) {
            String redirected = redirect(out.get(target), sourceModule, targetModule, name);
            out.put(target, dropSelfImport(redirected, targetModule, name));
        }
        return new Plan(null, out, "Moved " + name + " to " + target
                + (others == 0 ? "" : "; " + others + " other file(s) now import it from there"));
    }

    /** {@code from source import a, name, b} becomes {@code from source import a, b} + {@code from target import name}. */
    static String redirect(String text, String sourceModule, String targetModule, String name) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            Matcher from = FROM.matcher(line.strip());
            if (line.startsWith(" ") || line.startsWith("\t") || !from.matches() || !from.group(1).equals(sourceModule)
                    || from.group(2).contains("(")) {
                out.add(line);
                continue;
            }
            List<String> kept = new ArrayList<>();
            String moved = null;
            for (String part : from.group(2).split(",")) {
                String item = part.strip();
                if (item.equals(name) || item.matches(Pattern.quote(name) + "\\s+as\\s+\\w+")) {
                    moved = item;
                } else if (!item.isEmpty()) {
                    kept.add(item);
                }
            }
            if (moved == null) {
                out.add(line);
                continue;
            }
            if (!kept.isEmpty()) {
                out.add("from " + sourceModule + " import " + String.join(", ", kept));
            }
            out.add("from " + targetModule + " import " + moved);
        }
        String result = String.join("\n", out);
        Pattern qualified = Pattern.compile("(?<![\\w.])" + Pattern.quote(sourceModule) + "\\." + Pattern.quote(name) + "\\b");
        if (qualified.matcher(PyLex.maskNonCode(result)).find()) {
            result = withImports(replaceInCode(result, qualified, targetModule + "." + name), List.of("import " + targetModule));
        }
        return result;
    }

    /** Replaces matches found in the code (not in strings or comments) of {@code text}. */
    private static String replaceInCode(String text, Pattern pattern, String replacement) {
        String masked = PyLex.maskNonCode(text);
        StringBuilder out = new StringBuilder();
        Matcher m = pattern.matcher(masked);
        int at = 0;
        while (m.find()) {
            out.append(text, at, m.start()).append(replacement);
            at = m.end();
        }
        return out.append(text.substring(at)).toString();
    }

    /** The target's own {@code from target import name} after a redirect (it imported the name from the source). */
    private static String dropSelfImport(String text, String targetModule, String name) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (!line.strip().equals("from " + targetModule + " import " + name)) {
                out.add(line);
            }
        }
        return String.join("\n", out);
    }

    /** Adds each statement after the last top-level import (or the header comments) unless the text has it already. */
    static String withImports(String text, List<String> statements) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        int lastImport = -1;
        int afterHeader = 0;
        boolean header = true;
        Set<String> present = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith(" ") && (line.startsWith("import ") || line.startsWith("from "))) {
                lastImport = i;
                present.add(line.strip());
            }
            if (header && (line.isBlank() || line.startsWith("#"))) {
                afterHeader = i + 1;
            } else {
                header = false;
            }
        }
        int at = lastImport >= 0 ? lastImport + 1 : afterHeader;
        for (String statement : statements) {
            if (present.add(statement)) {
                lines.add(at++, statement);
            }
        }
        return String.join("\n", lines);
    }

    private static String appendBlock(String text, String block) {
        return text.stripTrailing() + "\n\n\n" + block + "\n";
    }

    private static boolean definesTopLevel(String text, String name) {
        for (PyOutline.Symbol s : PyOutline.parse(text)) {
            if (s.indent() == 0 && s.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** One-line top-level import statements (parenthesised multi-line ones are joined). */
    static List<String> topLevelImports(String text) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith(" ") || !(line.startsWith("import ") || line.startsWith("from "))) {
                continue;
            }
            StringBuilder statement = new StringBuilder(stripComment(line));
            while (statement.indexOf("(") >= 0 && statement.indexOf(")") < 0 && i + 1 < lines.length) {
                statement.append(' ').append(stripComment(lines[++i]).strip());
            }
            out.add(statement.toString().replace("( ", "(").replaceAll("\\s+", " ").strip());
        }
        return out;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    /** The names an import statement binds ({@code *} for a star import). */
    static Set<String> boundNames(String statement) {
        Set<String> out = new LinkedHashSet<>();
        Matcher from = FROM.matcher(statement);
        Matcher plain = IMPORT.matcher(statement);
        String list = from.matches() ? from.group(2) : plain.matches() ? plain.group(1) : "";
        list = list.replace("(", "").replace(")", "");
        for (String part : list.split(",")) {
            String item = part.strip();
            if (item.isEmpty()) {
                continue;
            }
            String[] words = item.split("\\s+as\\s+");
            String bound = words.length > 1 ? words[1].strip() : from.matches() ? words[0].strip() : words[0].strip().split("\\.")[0];
            out.add(bound);
        }
        return out;
    }

    private static Set<String> identifiers(String code) {
        String masked = PyLex.codeAndFStringExpressions(code);
        Set<String> out = new LinkedHashSet<>();
        Matcher m = IDENT.matcher(masked);
        while (m.find()) {
            if (m.start() == 0 || masked.charAt(m.start() - 1) != '.') {
                out.add(m.group());
            }
        }
        return out;
    }
}
