package net.xmilon.craftpy.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.xmilon.craftpy.catalog.CatalogView;
import net.xmilon.craftpy.python.ValidationProblem;

/**
 * Warns about ids in the code that no installed mod provides: a typo ({@code minecraft:diamnd}), or content of a mod that is
 * not installed ({@code create:cogwheel} without Create). Only registries the catalog actually knows are checked, so an empty
 * catalog (no game started yet) produces no warnings at all. The project's own content is never reported.
 */
public final class IdChecker {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?");
    private static final Pattern OWN = Pattern.compile("\\b(?:Item|Block|Food)\\(\\s*[\"']([a-z0-9_]+)[\"']");

    private IdChecker() {
    }

    /** The bare ids of the items and blocks a set of project sources declares ({@code Item("gem")}). */
    public static Set<String> ownNames(Iterable<String> sources) {
        Set<String> names = new java.util.HashSet<>();
        for (String source : sources) {
            Matcher matcher = OWN.matcher(source);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * @param file      path shown in the problem
     * @param projectId namespace of the project's own content
     * @param own       bare ids the project declares
     */
    public static List<ValidationProblem> check(String file, String text, CatalogView view, String projectId, Set<String> own) {
        List<ValidationProblem> out = new ArrayList<>();
        if (view.isEmpty()) {
            return out;
        }
        for (StringContext.Located string : StringContext.all(text)) {
            StringContext.Call call = string.call();
            String kind = IdKinds.kindFor(call);
            String value = call.typed();
            if (kind == null || kind.equals(IdKinds.CLASS) || value.startsWith("#") || !ID.matcher(value).matches()) {
                continue;
            }
            String problem = kind.equals("recipe_type") ? recipeType(view, value) : id(view, kind, value, projectId, own);
            if (problem == null && kind.equals("recipe_type") && call.argument() == 0 && call.callee().equals("recipe")) {
                problem = recipeFields(view, value, text, string.end());
            }
            if (problem != null) {
                int line = 0;
                int lineStart = 0;
                for (int i = 0; i < string.start(); i++) {
                    if (text.charAt(i) == '\n') {
                        line++;
                        lineStart = i + 1;
                    }
                }
                out.add(new ValidationProblem(file, line, string.start() - lineStart, "warning", problem));
            }
        }
        return out;
    }

    private static String id(CatalogView view, String kind, String value, String projectId, Set<String> own) {
        if (!view.has(kind)) {
            return null; // nothing is known about this registry
        }
        String id = value.contains(":") ? value : "minecraft:" + value;
        String namespace = id.substring(0, id.indexOf(':'));
        if (namespace.equals(projectId) || (!value.contains(":") && own.contains(value))) {
            return null;
        }
        if (view.contains(kind, id)) {
            return null;
        }
        if (view.modOf(namespace) == null && !namespace.equals("minecraft")) {
            return "unknown " + label(kind) + " '" + value + "': no installed mod uses the namespace '" + namespace + "'";
        }
        return "unknown " + label(kind) + " '" + value + "'" + (value.contains(":") ? "" : " (a bare name means minecraft:" + value + ")");
    }

    private static String recipeType(CatalogView view, String value) {
        if (view.recipeTypes().isEmpty() || view.recipeType(value) != null) {
            return null;
        }
        String namespace = value.contains(":") ? value.substring(0, value.indexOf(':')) : "minecraft";
        if (view.modOf(namespace) == null && !namespace.equals("minecraft")) {
            return "recipe type '" + value + "' needs the mod '" + namespace + "', which is not installed (the recipe is skipped without it)";
        }
        return "unknown recipe type '" + value + "'";
    }

    /**
     * {@code recipe("mod:type", field=...)}: keywords the recipe type was never seen with (the dump records the fields of
     * every recipe of the type). Null when they all fit, or nothing is known about the type's fields.
     */
    static String recipeFields(CatalogView view, String type, String text, int stringEnd) {
        var known = view.recipeType(type);
        if (known == null || known.fields == null || known.fields.isEmpty()) {
            return null;
        }
        String masked = PyLex.maskNonCode(text);
        java.util.List<String> unknown = new java.util.ArrayList<>();
        int depth = 0;
        int segment = stringEnd + 1;
        for (int i = stringEnd + 1; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    keyword(masked.substring(segment, i), known.fields, unknown);
                    break;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                keyword(masked.substring(segment, i), known.fields, unknown);
                segment = i + 1;
            }
        }
        if (unknown.isEmpty()) {
            return null;
        }
        return "recipe type '" + type + "' was never seen with " + (unknown.size() == 1 ? "the field " : "the fields ")
                + String.join(", ", unknown) + " (known: " + String.join(", ", known.fields) + ")";
    }

    private static final java.util.regex.Pattern KEYWORD = java.util.regex.Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*=(?!=)");

    private static void keyword(String argument, java.util.List<String> fields, java.util.List<String> unknown) {
        var m = KEYWORD.matcher(argument);
        if (m.find() && !m.group(1).equals("id") && !fields.contains(m.group(1))) {
            unknown.add(m.group(1));
        }
    }

    private static String label(String kind) {
        return kind.replace('_', ' ');
    }
}
