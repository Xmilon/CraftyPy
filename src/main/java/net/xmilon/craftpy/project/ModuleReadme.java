package net.xmilon.craftpy.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.xmilon.craftpy.analysis.PyOutline;

/**
 * The README.md every module carries, so the Modules page (and anyone opening the .cpm) can tell what is inside. A
 * project's own README.md is used as it is; without one, or while it is still the template of a new project, this
 * writes one from the project: what it is, where it works, and what its code declares.
 */
public final class ModuleReadme {
    /** A line of the README a new project starts with: such a file was never written by its author. */
    static final String TEMPLATE_MARK = "Describe what this module adds here.";

    private static final Pattern COMMAND = Pattern.compile("@command\\(\\s*[\"']([a-z0-9_]+)[\"']");
    private static final Pattern EVENT = Pattern.compile("@on(?:\\.([a-z_]+)|\\(\\s*[\"']([a-z_]+)[\"'])");
    private static final Pattern WINDOW = Pattern.compile("@window\\(\\s*[\"']([a-z0-9_]+)[\"']");
    private static final Pattern FOREIGN = Pattern.compile("[\"']([a-z0-9_.-]+):[a-z0-9_./-]+[\"']");
    private static final Set<String> NOT_A_MOD = Set.of("minecraft", "c", "neoforge", "forge");

    private ModuleReadme() {
    }

    /** True when {@code readme} is missing, blank or the untouched template. */
    public static boolean needsGenerating(String readme) {
        return readme == null || readme.isBlank() || readme.contains(TEMPLATE_MARK);
    }

    /** @param code the project's .py files (path to text) */
    public static String generate(String id, String name, String description, String author, String version,
                                  String apiVersion, ModuleSide side, Map<String, String> code) {
        Map<String, List<String>> content = new TreeMap<>();
        Set<String> commands = new TreeSet<>();
        Set<String> events = new TreeSet<>();
        Set<String> windows = new TreeSet<>();
        Set<String> mods = new TreeSet<>();
        for (String text : code.values()) {
            for (PyOutline.Symbol symbol : PyOutline.parse(text)) {
                if (symbol.kind() == PyOutline.Kind.CONTENT && !symbol.contentId().isEmpty()) {
                    content.computeIfAbsent(symbol.detail(), k -> new ArrayList<>()).add(id + ":" + symbol.contentId());
                }
            }
            collect(COMMAND, text, commands);
            collect(WINDOW, text, windows);
            Matcher event = EVENT.matcher(text);
            while (event.find()) {
                events.add(event.group(1) != null ? event.group(1) : event.group(2));
            }
            Matcher foreign = FOREIGN.matcher(text);
            while (foreign.find()) {
                String namespace = foreign.group(1);
                if (!namespace.equals(id) && !NOT_A_MOD.contains(namespace)) {
                    mods.add(namespace);
                }
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("# ").append(blank(name) ? id : name).append("\n\n");
        if (!blank(description)) {
            out.append(description.strip()).append("\n\n");
        }
        // a list, not a table: the in-game Markdown viewer cannot draw tables
        out.append("- **Module id**: `").append(id).append("`\n");
        out.append("- **Version**: ").append(blank(version) ? "-" : version).append("\n");
        if (!blank(author)) {
            out.append("- **Author**: ").append(author).append("\n");
        }
        out.append("- **Works on**: ").append(switch (side) {
            case BOTH -> "client and server";
            case CLIENT -> "client only (a resource pack and Python that runs on the player's computer)";
            case SERVER -> "server only (behaviour; players install nothing)";
        }).append("\n");
        out.append("- **CraftPy Python API**: ").append(blank(apiVersion) ? "-" : apiVersion).append("\n");
        if (!mods.isEmpty()) {
            out.append("- **Uses content of**: ").append(String.join(", ", mods)).append("\n");
        }
        out.append("\n## What is inside\n\n");
        boolean any = false;
        for (var entry : content.entrySet()) {
            out.append("- **").append(entry.getKey().equals("Block") ? "Blocks" : "Items").append("**: ");
            out.append(String.join(", ", entry.getValue().stream().map(v -> "`" + v + "`").toList())).append("\n");
            any = true;
        }
        if (!commands.isEmpty()) {
            out.append("- **Commands**: ").append(String.join(", ", commands.stream().map(c -> "`/" + id + ":" + c + "`").toList()))
                    .append("\n");
            any = true;
        }
        if (!events.isEmpty()) {
            out.append("- **Reacts to**: ").append(String.join(", ", events)).append("\n");
            any = true;
        }
        if (!windows.isEmpty()) {
            out.append("- **Windows**: ").append(String.join(", ", windows)).append("\n");
            any = true;
        }
        if (!any) {
            out.append(side == ModuleSide.CLIENT ? "Client-side: resources and screen code (HUD, keys, windows).\n"
                    : "No blocks, items or commands found in the code.\n");
        }
        out.append("\n_Written by CraftPy when the module was built; add a README.md to the project to write your own._\n");
        return out.toString();
    }

    private static void collect(Pattern pattern, String text, Set<String> into) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            into.add(m.group(1));
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
