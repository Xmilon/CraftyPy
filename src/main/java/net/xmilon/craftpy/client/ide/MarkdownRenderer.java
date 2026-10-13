package net.xmilon.craftpy.client.ide;

import java.util.ArrayList;
import java.util.List;

import net.xmilon.craftpy.client.ide.ScrollView.Row;
import net.xmilon.craftpy.client.ide.ScrollView.Span;
import net.xmilon.craftpy.client.ide.ScrollView.Style;

/**
 * Turns a Markdown document into styled {@link ScrollView.Row}s so the IDE can
 * render it for real (headings, bold/italic, inline code, fenced code blocks,
 * lists, quotes, links, tables and horizontal rules) instead of dumping the raw
 * source. Pure data - no Minecraft or OpenGL calls - so it is unit-testable.
 */
public final class MarkdownRenderer {

    private static final int TEXT = IdeColors.TEXT;
    private static final int MUTED = IdeColors.MUTED;
    private static final int ACCENT = IdeColors.ACCENT;
    private static final int CODE = 0xFFCE9178;
    private static final int LINK = 0xFF4FC1FF;
    private static final int HEADING = 0xFF7EA6FF;
    private static final int QUOTE = 0xFF9AA6B2;

    private MarkdownRenderer() {
    }

    public static List<Row> render(String markdown) {
        List<Row> rows = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            rows.add(new Row("Nothing to preview", MUTED, false));
            return rows;
        }
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean inCode = false;
        boolean firstBlock = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();

            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (!inCode) {
                    inCode = true;
                    if (!trimmed.equals("```") && !trimmed.equals("~~~")) {
                        String lang = trimmed.substring(3).strip();
                        if (!lang.isEmpty()) {
                            rows.add(new Row(lang, MUTED, false, List.of(new Span(lang.toUpperCase(), MUTED, true, false, false)),
                                    Style.CODE, 11));
                        }
                    }
                } else {
                    inCode = false;
                }
                continue;
            }
            if (inCode) {
                List<Span> spans = line.isEmpty() ? List.of(new Span(" ", CODE)) : List.of(new Span(line, CODE));
                rows.add(new Row(line, CODE, false, spans, Style.CODE, 11));
                continue;
            }

            if (isTableRow(trimmed)) {
                int end = i;
                List<List<String>> cells = new ArrayList<>();
                List<Boolean> separators = new ArrayList<>();
                while (end < lines.length && isTableRow(lines[end].strip())) {
                    String t = lines[end].strip();
                    boolean sep = isSeparatorRow(t);
                    separators.add(sep);
                    if (!sep) {
                        cells.add(splitCells(t));
                    } else {
                        cells.add(List.of());
                    }
                    end++;
                }
                renderTable(rows, cells, separators);
                i = end - 1;
                continue;
            }

            if (trimmed.isEmpty()) {
                rows.add(new Row(" ", TEXT, false, List.of(), Style.NORMAL, 5));
                firstBlock = false;
                continue;
            }

            if (isHorizontalRule(trimmed)) {
                rows.add(new Row("---", MUTED, false, List.of(), Style.RULE, 7));
                continue;
            }

            int hashes = headingLevel(trimmed);
            if (hashes > 0) {
                String text = trimmed.substring(hashes).strip();
                List<Span> spans = parseInline(text, headingColor(hashes), true);
                Style style = switch (hashes) {
                    case 1 -> Style.HEADING1;
                    case 2 -> Style.HEADING2;
                    case 3 -> Style.HEADING3;
                    default -> Style.HEADING4;
                };
                int height = switch (hashes) {
                    case 1 -> 16;
                    case 2 -> 14;
                    default -> 13;
                };
                if (hashes <= 2) {
                    rows.add(new Row(" ", TEXT, false, List.of(), Style.NORMAL, 3));
                }
                rows.add(new Row(text, headingColor(hashes), false, spans, style, height));
                firstBlock = false;
                continue;
            }

            if (trimmed.startsWith(">")) {
                String text = trimmed.substring(1).stripLeading();
                rows.add(new Row(text, QUOTE, false, parseInline(text, QUOTE, false), Style.QUOTE, 11));
                continue;
            }

            if (isBullet(trimmed)) {
                String text = trimmed.substring(1).stripLeading();
                List<Span> spans = new ArrayList<>();
                spans.add(new Span("\u2022 ", ACCENT, true, false, false));
                spans.addAll(parseInline(text, TEXT, false));
                rows.add(new Row(text, TEXT, false, spans, Style.BULLET, 11));
                continue;
            }

            if (isOrdered(trimmed)) {
                int dot = trimmed.indexOf(' ');
                String markerText = trimmed.substring(0, dot) + " ";
                String text = trimmed.substring(dot + 1);
                List<Span> spans = new ArrayList<>();
                spans.add(new Span(markerText, ACCENT, true, false, false));
                spans.addAll(parseInline(text, TEXT, false));
                rows.add(new Row(text, TEXT, false, spans, Style.ORDERED, 11));
                continue;
            }

            firstBlock = false;
            rows.add(new Row(trimmed, TEXT, false, parseInline(trimmed, TEXT, false), Style.NORMAL, 11));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Inline formatting
    // ------------------------------------------------------------------

    /** Parses bold, italic, inline code and links into colored spans. */
    public static List<Span> parseInline(String text, int baseColor, boolean forceBold) {
        List<Span> spans = new ArrayList<>();
        int i = 0;
        int n = text.length();
        StringBuilder plain = new StringBuilder();

        while (i < n) {
            char c = text.charAt(i);
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) {
                    flush(spans, plain, baseColor, forceBold, false, false);
                    spans.add(new Span(text.substring(i + 1, end), CODE, false, false, false));
                    i = end + 1;
                    continue;
                }
            }
            if ((c == '*' && has(text, i, "**")) || (c == '_' && has(text, i, "__"))) {
                String marker = text.substring(i, i + 2);
                int end = text.indexOf(marker, i + 2);
                if (end > i) {
                    flush(spans, plain, baseColor, forceBold, false, false);
                    spans.add(new Span(text.substring(i + 2, end), baseColor, true, false, false));
                    i = end + 2;
                    continue;
                }
            }
            if (c == '*' || c == '_') {
                int end = text.indexOf(c, i + 1);
                if (end > i + 1 && end < n) {
                    flush(spans, plain, baseColor, forceBold, false, false);
                    spans.add(new Span(text.substring(i + 1, end), baseColor, forceBold, true, false));
                    i = end + 1;
                    continue;
                }
            }
            if (c == '[') {
                int close = text.indexOf(']', i + 1);
                if (close > i && close + 1 < n && text.charAt(close + 1) == '(') {
                    int paren = text.indexOf(')', close + 2);
                    if (paren > close) {
                        flush(spans, plain, baseColor, forceBold, false, false);
                        String label = text.substring(i + 1, close);
                        spans.add(new Span(label, LINK, forceBold, false, true));
                        i = paren + 1;
                        continue;
                    }
                }
            }
            plain.append(c);
            i++;
        }
        flush(spans, plain, baseColor, forceBold, false, false);
        return spans;
    }

    private static void flush(List<Span> spans, StringBuilder plain, int color, boolean bold, boolean italic, boolean underline) {
        if (plain.length() > 0) {
            spans.add(new Span(plain.toString(), color, bold, italic, underline));
            plain.setLength(0);
        }
    }

    private static boolean has(String text, int index, String token) {
        return text.regionMatches(index, token, 0, token.length());
    }

    // ------------------------------------------------------------------
    // Block helpers
    // ------------------------------------------------------------------

    private static int headingLevel(String trimmed) {
        int level = 0;
        while (level < trimmed.length() && level < 6 && trimmed.charAt(level) == '#') {
            level++;
        }
        if (level > 0 && level < trimmed.length() && trimmed.charAt(level) == ' ') {
            return level;
        }
        return 0;
    }

    private static int headingColor(int level) {
        return level <= 2 ? HEADING : ACCENT;
    }

    private static boolean isHorizontalRule(String trimmed) {
        if (trimmed.length() < 3) {
            return false;
        }
        char c = trimmed.charAt(0);
        if (c != '-' && c != '*' && c != '_') {
            return false;
        }
        int count = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == c) {
                count++;
            } else if (ch != ' ') {
                return false;
            }
        }
        return count >= 3;
    }

    private static boolean isBullet(String trimmed) {
        return (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ "));
    }

    private static boolean isOrdered(String trimmed) {
        int i = 0;
        while (i < trimmed.length() && Character.isDigit(trimmed.charAt(i))) {
            i++;
        }
        return i > 0 && i + 1 < trimmed.length()
                && (trimmed.charAt(i) == '.' || trimmed.charAt(i) == ')')
                && trimmed.charAt(i + 1) == ' ';
    }

    private static boolean isTableRow(String trimmed) {
        return trimmed.indexOf('|') >= 0 && trimmed.length() > 1;
    }

    private static boolean isSeparatorRow(String trimmed) {
        String body = trimmed.replace("|", "").replace(":", "").replace(" ", "");
        if (body.isEmpty()) {
            return false;
        }
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) != '-') {
                return false;
            }
        }
        return body.length() >= 3;
    }

    private static List<String> splitCells(String trimmed) {
        String body = trimmed;
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : body.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static void renderTable(List<Row> rows, List<List<String>> cells, List<Boolean> separators) {
        int columns = 0;
        for (List<String> row : cells) {
            columns = Math.max(columns, row.size());
        }
        if (columns == 0) {
            return;
        }
        // Parse every cell into styled spans and measure visible width so the
        // alignment columns ignore Markdown markers such as ** or backticks.
        List<List<List<Span>>> parsed = new ArrayList<>();
        int[] widths = new int[columns];
        for (List<String> row : cells) {
            List<List<Span>> rowSpans = new ArrayList<>();
            for (int c = 0; c < columns; c++) {
                String cell = c < row.size() ? row.get(c) : "";
                List<Span> spans = parseInline(cell, TEXT, false);
                rowSpans.add(spans);
                widths[c] = Math.max(widths[c], visibleLength(spans));
            }
            parsed.add(rowSpans);
        }
        boolean headerDone = false;
        for (int r = 0; r < cells.size(); r++) {
            if (separators.get(r)) {
                rows.add(new Row("", MUTED, false, List.of(), Style.RULE, 6));
                headerDone = true;
                continue;
            }
            boolean header = !headerDone;
            StringBuilder plain = new StringBuilder(" ");
            List<Span> spans = new ArrayList<>();
            List<List<Span>> rowSpans = parsed.get(r);
            for (int c = 0; c < columns; c++) {
                List<Span> cellSpans = header ? headerSpans(rowSpans.get(c)) : rowSpans.get(c);
                int padding = widths[c] - visibleLength(cellSpans);
                spans.addAll(cellSpans);
                if (padding > 0) {
                    spans.add(new Span(" ".repeat(padding), header ? HEADING : TEXT, header, false, false));
                }
                plain.append(textOf(cellSpans)).append(" ".repeat(Math.max(0, padding)));
                if (c < columns - 1) {
                    spans.add(new Span(" \u2502 ", MUTED, false, false, false));
                    plain.append(" | ");
                }
            }
            rows.add(new Row(plain.toString(), header ? HEADING : TEXT, false, spans,
                    header ? Style.TABLE_HEADER : Style.TABLE_CELL, 11));
        }
    }

    private static List<Span> headerSpans(List<Span> spans) {
        List<Span> out = new ArrayList<>(spans.size());
        for (Span span : spans) {
            out.add(new Span(span.text(), HEADING, true, span.italic(), span.underline()));
        }
        return out;
    }

    private static int visibleLength(List<Span> spans) {
        int total = 0;
        for (Span span : spans) {
            total += span.text().length();
        }
        return total;
    }

    private static String textOf(List<Span> spans) {
        StringBuilder sb = new StringBuilder();
        for (Span span : spans) {
            sb.append(span.text());
        }
        return sb.toString();
    }
}
