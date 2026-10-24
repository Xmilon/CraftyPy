package net.xmilon.craftpy.client.docs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Markflow draws no tables, so every Markdown viewer runs its text through {@link #flatten} first: a table becomes an aligned
 * block of columns (a fenced block tagged {@code table}, which is monospaced and not colored as code), long cells wrapped inside
 * their column. Fenced code is left alone. Pure text in, text out.
 */
public final class MarkdownTables {
    /** The widest a drawn table gets, in characters (a README column of prose wraps instead of running off the window). */
    static final int MAX_WIDTH = 88;
    private static final int MIN_COLUMN = 8;
    private static final Pattern DELIMITER = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$");

    private MarkdownTables() {
    }

    public static String flatten(String markdown) {
        if (markdown == null || markdown.indexOf('|') < 0) return markdown;
        String[] lines = markdown.split("\n", -1);
        StringBuilder out = new StringBuilder(markdown.length() + 64);
        boolean fenced = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().startsWith("```") || line.strip().startsWith("~~~")) fenced = !fenced;
            if (!fenced && line.indexOf('|') >= 0 && i + 1 < lines.length && lines[i + 1].indexOf('|') >= 0
                    && DELIMITER.matcher(lines[i + 1].stripTrailing()).matches() && !line.strip().startsWith("```")) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(cells(line));
                int j = i + 2;
                while (j < lines.length && lines[j].indexOf('|') >= 0 && !lines[j].isBlank()) {
                    rows.add(cells(lines[j]));
                    j++;
                }
                // a table needs a blank line before it to be a block of its own once drawn
                if (out.length() > 0 && !endsWithBlankLine(out)) out.append('\n');
                out.append(draw(rows, cells(lines[i + 1]).size()));
                i = j - 1;
                continue;
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static boolean endsWithBlankLine(StringBuilder text) {
        int n = text.length();
        return n >= 2 && text.charAt(n - 1) == '\n' && text.charAt(n - 2) == '\n';
    }

    /** The cells of one table line; {@code \|} is a pipe inside a cell. */
    static List<String> cells(String line) {
        String body = line.strip();
        if (body.startsWith("|")) body = body.substring(1);
        if (body.endsWith("|") && !body.endsWith("\\|")) body = body.substring(0, body.length() - 1);
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '|') {
                out.add(plain(cell.toString()));
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(plain(cell.toString()));
        return out;
    }

    /** A cell's text without inline Markdown (its marks would show raw inside a block). */
    static String plain(String cell) {
        return cell.strip()
                .replaceAll("!?\\[([^\\]]*)]\\([^)]*\\)", "$1")
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("[`*_~]{1,3}(?=\\S)([^`*_~]*)[`*_~]{1,3}", "$1")
                .replace("`", "")
                .replaceAll("\\s+", " ");
    }

    private static String draw(List<List<String>> rows, int columns) {
        int[] width = new int[columns];
        for (List<String> row : rows) {
            for (int c = 0; c < columns; c++) width[c] = Math.max(width[c], c < row.size() ? row.get(c).length() : 0);
        }
        for (int c = 0; c < columns; c++) width[c] = Math.max(width[c], 1);
        int separators = 3 * (columns - 1);
        while (sum(width) + separators > MAX_WIDTH) { // the widest column gives way first
            int widest = 0;
            for (int c = 1; c < columns; c++) if (width[c] > width[widest]) widest = c;
            if (width[widest] <= MIN_COLUMN) break;
            width[widest]--;
        }
        StringBuilder out = new StringBuilder("```table\n");
        for (int r = 0; r < rows.size(); r++) {
            List<List<String>> wrapped = new ArrayList<>();
            int height = 1;
            for (int c = 0; c < columns; c++) {
                List<String> cellLines = wrap(c < rows.get(r).size() ? rows.get(r).get(c) : "", width[c]);
                wrapped.add(cellLines);
                height = Math.max(height, cellLines.size());
            }
            for (int h = 0; h < height; h++) {
                StringBuilder line = new StringBuilder();
                for (int c = 0; c < columns; c++) {
                    String text = h < wrapped.get(c).size() ? wrapped.get(c).get(h) : "";
                    if (c > 0) line.append(" │ ");
                    line.append(text);
                    if (c < columns - 1) line.append(" ".repeat(Math.max(0, width[c] - text.length())));
                }
                out.append(line).append('\n');
            }
            if (r == 0 || height > 1 && r < rows.size() - 1) out.append(rule(width)).append('\n');
        }
        return out.append("```\n").toString();
    }

    private static String rule(int[] width) {
        StringBuilder out = new StringBuilder();
        for (int c = 0; c < width.length; c++) {
            if (c > 0) out.append("─┼─");
            out.append("─".repeat(width[c]));
        }
        return out.toString();
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }

    /** Words wrapped to {@code width}; a word longer than the column is cut. */
    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            while (word.length() > width) {
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                out.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        out.add(line.toString());
        return out;
    }
}
