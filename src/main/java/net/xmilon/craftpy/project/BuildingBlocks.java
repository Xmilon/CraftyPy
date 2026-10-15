package net.xmilon.craftpy.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.xmilon.craftpy.CraftPyLog;

/**
 * Ready-made pieces of a project (a window, a sword, an ore, a join greeting...) that the editor's Building Blocks tool
 * window inserts at the cursor or adds as a new file connected to {@code main.py}. Each one is a Python file under
 * {@code /craftpy_blocks/} (listed in {@code index.txt}) whose first lines describe it:
 *
 * <pre>
 * #: title: Window with buttons
 * #: category: UI elements
 * #: doc: api.md#Windows          the section of the bundled docs to show
 * #: module: counter_window       default file name (code/&lt;module&gt;.py)
 * #: about: one line for the list
 * </pre>
 * <p>
 * In the text {@code {{name}}} becomes the module name and {@code {{id}}} the project id. Pure: no game, no UI.
 */
public final class BuildingBlocks {
    public record Block(String file, String title, String category, String about, String doc, String module, String template) {
        /** The template filled in for {@code module} of {@code project}. */
        public String fill(String module, String project) {
            return template.replace("{{name}}", module).replace("{{id}}", project == null ? "" : project);
        }

        /** What Insert puts at the cursor: the filled file without its {@code from craftpy import *} line. */
        public String snippet(String module, String project) {
            return BuildingBlocks.snippet(fill(module, project));
        }

        /** The docs file and the heading of the section, from {@code api.md#Windows}. */
        public String docFile() {
            int hash = doc.indexOf('#');
            return hash < 0 ? doc : doc.substring(0, hash);
        }

        public String docHeading() {
            int hash = doc.indexOf('#');
            return hash < 0 ? "" : doc.substring(hash + 1);
        }
    }

    private static volatile List<Block> cache;

    private BuildingBlocks() {
    }

    /** Every block shipped with the mod, in the order of {@code index.txt}. */
    public static List<Block> all() {
        List<Block> blocks = cache;
        if (blocks == null) {
            blocks = List.copyOf(load());
            cache = blocks;
        }
        return blocks;
    }

    /** The blocks grouped by category, categories in order of first appearance. */
    public static Map<String, List<Block>> byCategory(Collection<Block> blocks) {
        Map<String, List<Block>> out = new LinkedHashMap<>();
        for (Block block : blocks) {
            out.computeIfAbsent(block.category(), k -> new ArrayList<>()).add(block);
        }
        return out;
    }

    /** Blocks whose title, category or description contains every word of {@code query}. */
    public static List<Block> search(Collection<Block> blocks, String query) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Block> out = new ArrayList<>();
        for (Block block : blocks) {
            String haystack = (block.title() + " " + block.category() + " " + block.about()).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String word : words) {
                if (!word.isEmpty() && !haystack.contains(word)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                out.add(block);
            }
        }
        return out;
    }

    private static List<Block> load() {
        List<Block> out = new ArrayList<>();
        String index = read("index.txt");
        if (index == null) {
            CraftPyLog.LOGGER.warn("Building blocks index is missing from the jar");
            return out;
        }
        for (String line : index.split("\n")) {
            String file = line.strip();
            if (file.isEmpty() || file.startsWith("#")) {
                continue;
            }
            String text = read(file);
            if (text == null) {
                CraftPyLog.LOGGER.warn("Building block {} is listed but missing", file);
                continue;
            }
            out.add(parse(file, text));
        }
        return out;
    }

    private static String read(String file) {
        try (InputStream in = BuildingBlocks.class.getResourceAsStream("/craftpy_blocks/" + file)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            return null;
        }
    }

    /** Reads the {@code #: key: value} header; the template is the rest of the file. Package-visible for tests. */
    static Block parse(String file, String text) {
        Map<String, String> meta = new LinkedHashMap<>();
        StringBuilder template = new StringBuilder();
        boolean header = true;
        for (String line : text.split("\n", -1)) {
            if (header && line.startsWith("#:")) {
                String entry = line.substring(2).strip();
                int colon = entry.indexOf(':');
                if (colon > 0) {
                    meta.put(entry.substring(0, colon).strip(), entry.substring(colon + 1).strip());
                }
                continue;
            }
            header = false;
            template.append(line).append('\n');
        }
        String body = template.toString().strip() + "\n";
        String fallback = file.substring(file.lastIndexOf('/') + 1).replace(".py", "");
        return new Block(file, meta.getOrDefault("title", fallback), meta.getOrDefault("category", "Other"),
                meta.getOrDefault("about", ""), meta.getOrDefault("doc", "api.md"), meta.getOrDefault("module", fallback), body);
    }

    /** A filled template without its {@code from craftpy import *} line and the blank lines around it. */
    static String snippet(String filled) {
        StringBuilder out = new StringBuilder();
        for (String line : filled.split("\n", -1)) {
            if (!line.strip().equals("from craftpy import *")) {
                out.append(line).append('\n');
            }
        }
        return out.toString().strip() + "\n";
    }

    /** {@code base}, or {@code base_2}, {@code base_3}... so that {@code code/<name>.py} is not one of {@code files}. */
    public static String freeModule(String base, Collection<String> files) {
        String clean = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (clean.isEmpty() || Character.isDigit(clean.charAt(0))) {
            clean = "part_" + clean;
        }
        String name = clean;
        for (int n = 2; files.contains("code/" + name + ".py"); n++) {
            name = clean + "_" + n;
        }
        return name;
    }

    /**
     * The section of a Markdown document under the heading that contains {@code heading} (case-insensitive), down to the next
     * heading of the same or a higher level; the whole document when no heading matches.
     */
    public static String section(String markdown, String heading) {
        if (heading == null || heading.isBlank()) {
            return markdown;
        }
        String[] lines = markdown.split("\n", -1);
        String wanted = heading.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.length; i++) {
            int level = headingLevel(lines[i]);
            if (level > 0 && lines[i].toLowerCase(Locale.ROOT).contains(wanted)) {
                StringBuilder out = new StringBuilder(lines[i]).append('\n');
                for (int j = i + 1; j < lines.length; j++) {
                    int next = headingLevel(lines[j]);
                    if (next > 0 && next <= level) {
                        break;
                    }
                    out.append(lines[j]).append('\n');
                }
                return out.toString();
            }
        }
        return markdown;
    }

    private static int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        return level > 0 && level < line.length() && line.charAt(level) == ' ' ? level : 0;
    }
}
