package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.xmilon.craftpy.analysis.PyLex;
import net.xmilon.craftpy.client.TextOps.Edit;

/**
 * IntelliJ's line commands as pure text functions (no UI, unit-tested): toggle comment, duplicate, delete and move lines,
 * reformat, live templates, smart backspace and renaming a name inside one file. Each returns an {@link Edit} (or null
 * when there is nothing to do); the editor applies it as one undoable change.
 */
public final class LineEdits {
    private static final int INDENT = 4;

    private LineEdits() {
    }

    /** [start, end) of the whole lines a selection touches (end excludes the last line's newline). */
    static int[] lines(String text, int selStart, int selEnd) {
        int from = Math.min(selStart, selEnd);
        int to = Math.max(selStart, selEnd);
        // a selection that ends at the start of a line does not include that line (like IntelliJ)
        if (to > from && to > 0 && text.charAt(to - 1) == '\n') {
            to--;
        }
        return new int[]{TextOps.lineStart(text, from), TextOps.lineEnd(text, to)};
    }

    /** Ctrl+/: comments the lines out with "# " at their common indentation, or removes the comment when all have one. */
    public static Edit toggleComment(String text, int selStart, int selEnd) {
        int[] r = lines(text, selStart, selEnd);
        String[] lines = text.substring(r[0], r[1]).split("\n", -1);
        boolean allCommented = true;
        int indent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int lead = TextOps.leadingWhitespace(line).length();
            indent = Math.min(indent, lead);
            if (!line.substring(lead).startsWith("#")) {
                allCommented = false;
            }
        }
        if (indent == Integer.MAX_VALUE) {
            return null; // only blank lines
        }
        StringBuilder out = new StringBuilder();
        int caretShift = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String changed = line;
            if (!line.isBlank()) {
                if (allCommented) {
                    int lead = TextOps.leadingWhitespace(line).length();
                    int cut = line.startsWith("# ", lead) ? 2 : 1;
                    changed = line.substring(0, lead) + line.substring(lead + cut);
                } else {
                    changed = line.substring(0, indent) + "# " + line.substring(indent);
                }
            }
            if (i == 0) {
                caretShift = changed.length() - line.length();
            }
            out.append(changed);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        String replaced = out.toString();
        boolean single = Math.min(selStart, selEnd) == Math.max(selStart, selEnd);
        if (single) {
            int cursor = Math.max(r[0], Math.min(r[0] + replaced.length(), selStart + caretShift));
            return new Edit(r[0], r[1], replaced, cursor);
        }
        return new Edit(r[0], r[1], replaced, r[0], r[0] + replaced.length());
    }

    /** Ctrl+D: duplicates the selection, or the cursor's line(s) below themselves with the cursor on the copy. */
    public static Edit duplicate(String text, int selStart, int selEnd) {
        int from = Math.min(selStart, selEnd);
        int to = Math.max(selStart, selEnd);
        if (from != to && text.substring(from, to).indexOf('\n') < 0) {
            String piece = text.substring(from, to);
            return new Edit(to, to, piece, to, to + piece.length());
        }
        int[] r = lines(text, selStart, selEnd);
        String block = text.substring(r[0], r[1]);
        int offset = block.length() + 1;
        return new Edit(r[1], r[1], "\n" + block, selStart + offset, selEnd + offset);
    }

    /** Ctrl+Y: deletes the cursor's line(s) with their newline. */
    public static Edit deleteLines(String text, int selStart, int selEnd) {
        int[] r = lines(text, selStart, selEnd);
        int start = r[0];
        int end = r[1];
        if (end < text.length()) {
            end++; // the newline after
        } else if (start > 0) {
            start--; // last line: the newline before
        }
        return new Edit(start, end, "", start);
    }

    /** Alt+Shift+Up/Down: moves the cursor's line(s) one line up or down, keeping the selection on them. */
    public static Edit moveLines(String text, int selStart, int selEnd, boolean up) {
        int[] r = lines(text, selStart, selEnd);
        String block = text.substring(r[0], r[1]);
        if (up) {
            if (r[0] == 0) {
                return null;
            }
            int prevStart = TextOps.lineStart(text, r[0] - 1);
            String previous = text.substring(prevStart, r[0] - 1);
            String swapped = block + "\n" + previous;
            int shift = previous.length() + 1;
            return new Edit(prevStart, r[1], swapped, selStart - shift, selEnd - shift);
        }
        if (r[1] >= text.length()) {
            return null;
        }
        int nextEnd = TextOps.lineEnd(text, r[1] + 1);
        String next = text.substring(r[1] + 1, nextEnd);
        String swapped = next + "\n" + block;
        int shift = next.length() + 1;
        return new Edit(r[0], nextEnd, swapped, selStart + shift, selEnd + shift);
    }

    /**
     * Ctrl+Alt+L, whitespace only so it can never change what the code does: tabs in indentation become 4 spaces, trailing
     * spaces go, at most two blank lines in a row, two blank lines before a top-level def/class (and its decorators), one
     * newline at the end. Lines inside multi-line strings are left alone.
     */
    public static String reformat(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        String normalized = text.replace("\r\n", "\n");
        boolean[] inString = insideStrings(normalized, lines, false);
        boolean[] endsInString = insideStrings(normalized, lines, true);
        List<String> out = new ArrayList<>();
        int blanks = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (inString[i]) {
                out.add(line);
                blanks = 0;
                continue;
            }
            String lead = TextOps.leadingWhitespace(line);
            line = lead.replace("\t", " ".repeat(INDENT)) + line.substring(lead.length());
            if (!endsInString[i]) { // trailing spaces inside a multi-line string are part of it
                line = line.stripTrailing();
            }
            if (line.isEmpty()) {
                blanks++;
                if (blanks <= 2) {
                    out.add(line);
                }
                continue;
            }
            boolean topLevelDef = (line.startsWith("def ") || line.startsWith("class ") || line.startsWith("async def ")
                    || line.startsWith("@")) && !previousIsDecorator(out);
            if (topLevelDef && !out.isEmpty() && !onlyCommentsAndBlanks(out)) {
                while (blanks < 2) {
                    out.add("");
                    blanks++;
                }
            }
            blanks = 0;
            out.add(line);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out.isEmpty() ? "" : String.join("\n", out) + "\n";
    }

    private static boolean previousIsDecorator(List<String> out) {
        for (int i = out.size() - 1; i >= 0; i--) {
            if (!out.get(i).isEmpty()) {
                return out.get(i).startsWith("@");
            }
        }
        return false;
    }

    private static boolean onlyCommentsAndBlanks(List<String> out) {
        for (String line : out) {
            if (!line.isEmpty() && !line.startsWith("#")) {
                return false;
            }
        }
        return true;
    }

    /** For each line: does it start (or, with {@code atEnd}, end) inside a string literal, like a triple-quoted one? */
    private static boolean[] insideStrings(String text, String[] lines, boolean atEnd) {
        boolean[] out = new boolean[lines.length];
        List<PyLex.Span> strings = new ArrayList<>();
        for (PyLex.Span span : PyLex.spans(text)) {
            if (span.kind() == PyLex.Kind.STRING) {
                strings.add(span);
            }
        }
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            int at = atEnd ? offset + lines[i].length() : offset;
            for (PyLex.Span span : strings) {
                if (at > span.start() && at < span.end()) {
                    out[i] = true;
                    break;
                }
            }
            offset += lines[i].length() + 1;
        }
        return out;
    }

    /** Backspace in the indentation: removes spaces back to the previous indent level instead of one. */
    public static Edit smartBackspace(String text, int cursor) {
        int start = TextOps.lineStart(text, cursor);
        String before = text.substring(start, cursor);
        if (before.isEmpty() || !before.isBlank() || before.contains("\t")) {
            return null;
        }
        int remove = before.length() % INDENT == 0 ? INDENT : before.length() % INDENT;
        return new Edit(cursor - remove, cursor, "", cursor - remove);
    }

    /** Live templates: an abbreviation alone on its line followed by Tab. {@code $0} marks where the cursor goes. */
    public static final Map<String, String> TEMPLATES = templates();

    private static Map<String, String> templates() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("main", "def main(ctx):\n    $0");
        t.put("item", "$0 = Item(\"id\", name=\"Name\", texture=\"minecraft:diamond\")");
        t.put("block", "$0 = Block(\"id\", name=\"Name\", texture=\"minecraft:stone\", hardness=2, tool=\"pickaxe\")");
        t.put("onj", "@on.player_join\ndef greet(event):\n    $0");
        t.put("onb", "@on.block_break\ndef broke(event):\n    $0");
        t.put("onp", "@on.block_place\ndef placed(event):\n    $0");
        t.put("oni", "@on.block_interact\ndef clicked(event):\n    $0");
        t.put("onu", "@on.item_use\ndef used(event):\n    $0");
        t.put("onc", "@on.chat\ndef chat(event):\n    if event.message == \"$0\":\n        pass");
        t.put("ond", "@on.entity_death\ndef died(event):\n    $0");
        t.put("cmd", "@command(\"name\")\ndef name(ctx):\n    \"\"\"/project:name - what it does.\"\"\"\n    $0");
        t.put("every", "@every(seconds=10)\ndef tick():\n    $0");
        t.put("after", "after(5, lambda: $0)");
        t.put("win", "@window(\"id\", title=\"Title\", size=(260, 160))\nclass MyWindow(Window):\n    def build(self, ctx):\n"
                + "        return VBox(\n            Label(\"$0\"),\n            Button(\"Close\", on_click=ctx.close, style=\"text\"),\n"
                + "        )");
        t.put("ext", "@extension\nclass MyExtension(Extension):\n    def on_hit(self, event):\n        $0");
        t.put("shaped", "shaped(result, \"AAA\", \" B \", \" B \", A=\"$0\", B=\"stick\")");
        t.put("for", "for player in players.online():\n    $0");
        return java.util.Collections.unmodifiableMap(t);
    }

    /** Expands the template whose abbreviation ends at {@code cursor}, indented like its line; null when there is none. */
    public static Edit expandTemplate(String text, int cursor) {
        int start = TextOps.lineStart(text, cursor);
        String before = text.substring(start, cursor);
        String word = before.strip();
        String template = TEMPLATES.get(word);
        if (template == null || !before.endsWith(word)) {
            return null;
        }
        String indent = TextOps.leadingWhitespace(before);
        String body = template.replace("\n", "\n" + indent);
        int caret = body.indexOf("$0");
        body = body.replace("$0", "");
        int wordStart = cursor - word.length();
        return new Edit(wordStart, cursor, body, wordStart + Math.max(0, caret));
    }

    /**
     * Shift+F6 inside one file: every use of the name at {@code offset} in code (not in strings or comments) becomes
     * {@code newName}. Null when there is no name there or the new name is not an identifier.
     */
    public static Edit renameInFile(String text, int offset, String newName) {
        if (newName == null || !newName.matches("[A-Za-z_]\\w*")) {
            return null;
        }
        int[] word = TextOps.wordAt(text, offset);
        if (word == null || word[0] == word[1]) {
            return null;
        }
        String name = text.substring(word[0], word[1]);
        if (!name.matches("[A-Za-z_]\\w*") || name.equals(newName)) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int cursorShift = 0;
        int last = 0;
        for (PyLex.Span span : PyLex.spans(text)) {
            if (span.kind() != PyLex.Kind.CODE) {
                continue;
            }
            int i = span.start();
            while (i < span.end()) {
                int found = text.indexOf(name, i);
                if (found < 0 || found + name.length() > span.end()) {
                    break;
                }
                boolean before = found > 0 && TextOps.isWordChar(text.charAt(found - 1));
                boolean after = found + name.length() < text.length() && TextOps.isWordChar(text.charAt(found + name.length()));
                boolean attribute = found > 0 && text.charAt(found - 1) == '.'; // obj.name is another object's name
                if (!before && !after && !attribute) {
                    out.append(text, last, found).append(newName);
                    if (found < offset) {
                        cursorShift += newName.length() - name.length();
                    }
                    last = found + name.length();
                }
                i = found + name.length();
            }
        }
        out.append(text.substring(last));
        return new Edit(0, text.length(), out.toString(), offset + cursorShift);
    }
}
