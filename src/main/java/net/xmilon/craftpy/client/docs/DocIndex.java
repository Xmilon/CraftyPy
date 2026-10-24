package net.xmilon.craftpy.client.docs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The bundled Markdown documents cut into pages a reader can take in, and searched. A page is a {@code ##} section (the text
 * before the first one is the document's first page); a {@code ##} section longer than {@link #LONG} lines that has
 * {@code ###} parts (the API reference) is cut at those instead. Searching looks at every heading and line of every page: all
 * words of the query must be in one part, a match in a title counts most. Pure Java, built once per document off the UI
 * thread and kept.
 */
public final class DocIndex {
    /** A {@code ##} section with more lines than this is cut at its {@code ###} parts. */
    static final int LONG = 160;

    /** A heading inside a page: its level, title without Markdown marks, and its line in the page's Markdown. */
    public record Section(int level, String title, int line) {
    }

    /** One page: the document it is from, its place there, its title, Markdown and headings, and its first sentence. */
    public record Page(String doc, int number, String title, String markdown, List<Section> sections, String summary) {
        public String key() {
            return doc + "#" + number;
        }
    }

    /** A search result: the page, the heading of the part that matched, a line that shows the match, and how good it is. */
    public record Hit(Page page, String heading, int line, String snippet, int score) {
    }

    private DocIndex() {
    }

    /** Cuts a document into pages. */
    public static List<Page> pages(String doc, String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        boolean[] fenced = fences(lines);
        // where each ## (and ###) starts
        List<int[]> twos = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            int level = level(lines[i], fenced[i]);
            if (level == 2) twos.add(new int[]{i});
        }
        List<int[]> ranges = new ArrayList<>(); // {start, end, titleLine or -1}
        int first = twos.isEmpty() ? lines.length : twos.get(0)[0];
        if (first > 0 && !blank(lines, 0, first)) ranges.add(new int[]{0, first, -1});
        for (int t = 0; t < twos.size(); t++) {
            int start = twos.get(t)[0], end = t + 1 < twos.size() ? twos.get(t + 1)[0] : lines.length;
            List<Integer> threes = new ArrayList<>();
            for (int i = start + 1; i < end; i++) if (level(lines[i], fenced[i]) == 3) threes.add(i);
            if (end - start > LONG && !threes.isEmpty()) {
                ranges.add(new int[]{start, threes.get(0), start});
                for (int k = 0; k < threes.size(); k++) {
                    ranges.add(new int[]{threes.get(k), k + 1 < threes.size() ? threes.get(k + 1) : end, start});
                }
            } else {
                ranges.add(new int[]{start, end, -2});
            }
        }
        List<Page> out = new ArrayList<>();
        for (int[] range : ranges) {
            int start = range[0], end = range[1];
            if (blank(lines, start, end)) continue;
            StringBuilder text = new StringBuilder();
            List<Section> sections = new ArrayList<>();
            for (int i = start; i < end; i++) {
                int level = level(lines[i], fenced[i]);
                if (level >= 1 && level <= 3) sections.add(new Section(level, title(lines[i]), i - start));
                text.append(lines[i]).append('\n');
            }
            String title;
            if (range[2] == -1) {
                title = sections.isEmpty() ? "Overview" : sections.get(0).title();
            } else if (range[2] == -2 || range[2] == start) {
                title = title(lines[start]);
            } else {
                title = title(lines[range[2]]) + ": " + title(lines[start]);
            }
            String pageText = text.toString();
            if (range[2] >= 0 && range[2] != start) {
                // a part of a long section: the reader still sees which section it belongs to
                pageText = "## " + title + "\n" + pageText.substring(pageText.indexOf('\n') + 1);
                sections.set(0, new Section(2, title, 0));
            }
            out.add(new Page(doc, out.size(), title, pageText, sections, summary(lines, start, end, fenced)));
        }
        return out;
    }

    /** Pages whose parts hold every word of {@code query}, best first, at most {@code limit}. */
    public static List<Hit> search(List<Page> pages, String query, int limit) {
        List<String> words = new ArrayList<>();
        for (String word : query.toLowerCase(Locale.ROOT).split("[^a-z0-9_.:]+")) if (!word.isEmpty()) words.add(word);
        List<Hit> hits = new ArrayList<>();
        if (words.isEmpty()) return hits;
        String phrase = query.strip().toLowerCase(Locale.ROOT);
        for (Page page : pages) {
            String[] lines = page.markdown().split("\n", -1);
            // the parts of a page: from each heading to the next
            List<Section> parts = page.sections().isEmpty() ? List.of(new Section(2, page.title(), 0)) : page.sections();
            for (int p = 0; p < parts.size(); p++) {
                int from = parts.get(p).line(), to = p + 1 < parts.size() ? parts.get(p + 1).line() : lines.length;
                if (p == 0 && from > 0) from = 0;
                StringBuilder all = new StringBuilder();
                for (int i = from; i < to; i++) all.append(lines[i].toLowerCase(Locale.ROOT)).append('\n');
                String body = all.toString();
                if (!words.stream().allMatch(body::contains)) continue;
                String heading = parts.get(p).title();
                String lowerHeading = heading.toLowerCase(Locale.ROOT);
                int score = 0;
                for (String word : words) {
                    if (lowerHeading.contains(word)) score += lowerHeading.startsWith(word) || lowerHeading.contains(" " + word) ? 60 : 40;
                    if (page.title().toLowerCase(Locale.ROOT).contains(word)) score += 20;
                    score += Math.min(10, count(body, word));
                }
                if (body.contains(phrase)) score += 30;
                if (lowerHeading.contains(phrase)) score += 50;
                score -= parts.get(p).level(); // a bigger heading wins a tie
                int best = from;
                for (int i = from; i < to; i++) {
                    String lower = lines[i].toLowerCase(Locale.ROOT);
                    if (lower.contains(words.get(0)) && !lower.startsWith("#")) {
                        best = i;
                        break;
                    }
                }
                hits.add(new Hit(page, heading, parts.get(p).line(), snippet(lines[best], words.get(0)), score));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed().thenComparing(hit -> hit.page().number()));
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    /** The headings of a Markdown text (levels 1-3, not inside code blocks) with inline marks removed. */
    public static List<Section> headings(String markdown) {
        String[] lines = markdown.split("\n", -1);
        boolean[] fenced = fences(lines);
        List<Section> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            int level = level(lines[i], fenced[i]);
            if (level >= 1 && level <= 3) out.add(new Section(level, title(lines[i]), i));
        }
        return out;
    }

    /** For each line: true when it is inside a fenced code block (fence lines included). */
    private static boolean[] fences(String[] lines) {
        boolean[] out = new boolean[lines.length];
        boolean inside = false;
        for (int i = 0; i < lines.length; i++) {
            boolean fence = lines[i].strip().startsWith("```");
            out[i] = inside || fence;
            if (fence) inside = !inside;
        }
        return out;
    }

    /** The heading level of a line (1-6), or 0. */
    private static int level(String line, boolean fenced) {
        if (fenced) return 0;
        String trimmed = line.strip();
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
        return level == 0 || level > 6 || level == trimmed.length() || trimmed.charAt(level) != ' ' ? 0 : level;
    }

    static String title(String line) {
        String trimmed = line.strip();
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
        return trimmed.substring(level).strip().replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1")
                .replace("`", "").replace("**", "").replace("__", "");
    }

    private static boolean blank(String[] lines, int from, int to) {
        for (int i = from; i < to; i++) if (!lines[i].isBlank()) return false;
        return true;
    }

    /** The first sentence of the page's text (not a heading, not code), without Markdown marks. */
    private static String summary(String[] lines, int from, int to, boolean[] fenced) {
        for (int i = from; i < to; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || fenced[i] || line.startsWith("#") || line.startsWith("|") || line.startsWith(">")) continue;
            String plain = line.replaceAll("^[-*0-9.]+\\s+", "").replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1")
                    .replace("`", "").replace("**", "");
            int dot = plain.indexOf(". ");
            String sentence = dot > 20 ? plain.substring(0, dot + 1) : plain;
            return sentence.length() > 160 ? sentence.substring(0, 157) + "..." : sentence;
        }
        return "";
    }

    private static String snippet(String line, String word) {
        String plain = line.strip().replace("`", "").replace("**", "");
        int at = plain.toLowerCase(Locale.ROOT).indexOf(word);
        int start = Math.max(0, at - 50);
        String cut = plain.substring(start, Math.min(plain.length(), start + 140));
        return (start > 0 ? "..." : "") + cut + (start + 140 < plain.length() ? "..." : "");
    }

    private static int count(String text, String word) {
        int n = 0;
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + word.length())) n++;
        return n;
    }
}
