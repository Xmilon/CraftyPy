package net.xmilon.craftpy.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.xmilon.craftpy.catalog.CatalogView;
import net.xmilon.craftpy.project.ModuleInfo;

/**
 * The mod map: every installed mod, every CraftPy module and every project as a node, and what ties them together as
 * edges - a mod requiring or supporting another (its own dependency list), a module depending on a module, and a module
 * or project using another mod's content (the ids its code names). Pure assembly; the inputs come from the caller.
 */
public final class ModMap {
    private static final Pattern ID = Pattern.compile("[\"'](#?)([a-z0-9_.-]+):[a-z0-9_./-]+[\"']");
    private static final Set<String> NOT_MODS = Set.of("minecraft", "c", "neoforge", "forge", "java");
    private static final String[] KINDS = {"item", "block", "entity_type", "attribute", "mob_effect", "enchantment", "sound_event"};

    /** One installed mod as the game reports it. */
    public record Mod(String id, String name, String version, List<Dependency> dependencies) {
    }

    /** {@code type}: required, optional, incompatible, discouraged. */
    public record Dependency(String modId, String type) {
    }

    /**
     * What the map says about a module or project besides its links (keyed {@code module:<id>} / {@code project:<id>}).
     * {@code state}: loaded, enabled, disabled, error, restart. {@code approval}: own, approved, needed, changed, none.
     * {@code adds}, {@code commands} and {@code events} come from its last run; empty while it is not running.
     */
    public record Facts(String description, String author, String side, String state,
                        net.xmilon.craftpy.security.ModulePermissions permissions, String approval, List<String> dependencies,
                        Map<String, Integer> adds, List<String> commands, List<String> events, String decompiledTo,
                        boolean changed) {
    }

    /** {@code from modules.jukebox import songs, VOLUME} and {@code import modules.jukebox as jb}. */
    private static final Pattern FROM_MODULE = Pattern.compile(
            "(?m)^[ \\t]*from[ \\t]+modules\\.([a-z][a-z0-9_]*)(?:\\.[A-Za-z_][\\w.]*)?[ \\t]+import[ \\t]+\\(?([^\\n#)]+)");
    private static final Pattern IMPORT_MODULE = Pattern.compile("(?m)^[ \\t]*import[ \\t]+modules\\.([a-z][a-z0-9_]*)");
    private static final int MAX_LIST = 20;

    private ModMap() {
    }

    public static JsonObject build(List<Mod> mods, List<ModuleInfo> modules, Map<String, Map<String, String>> projectCode,
                                   Map<String, Map<String, String>> moduleCode, CatalogView catalog) {
        return build(mods, modules, projectCode, moduleCode, catalog, Map.of());
    }

    public static JsonObject build(List<Mod> mods, List<ModuleInfo> modules, Map<String, Map<String, String>> projectCode,
                                   Map<String, Map<String, String>> moduleCode, CatalogView catalog,
                                   Map<String, Facts> facts) {
        Map<String, JsonObject> nodes = new LinkedHashMap<>();
        JsonArray edges = new JsonArray();
        Set<String> installed = new LinkedHashSet<>();
        for (Mod mod : mods) {
            installed.add(mod.id());
        }
        Map<String, Map<String, Integer>> counts = counts(catalog);
        for (Mod mod : mods) {
            JsonObject node = node("mod:" + mod.id(), mod.name(), "mod", mod.version());
            JsonObject adds = new JsonObject();
            counts.getOrDefault(mod.id(), Map.of()).forEach(adds::addProperty);
            node.add("adds", adds);
            nodes.put("mod:" + mod.id(), node);
            for (Dependency dep : mod.dependencies()) {
                if (installed.contains(dep.modId()) && !NOT_MODS.contains(dep.modId()) && !dep.modId().equals(mod.id())) {
                    edges.add(edge("mod:" + mod.id(), "mod:" + dep.modId(), dep.type()));
                }
            }
        }
        for (ModuleInfo module : modules) {
            String key = "module:" + module.id;
            JsonObject node = node(key, module.name == null || module.name.isBlank() ? module.id : module.name, "module", module.version);
            node.addProperty("side", module.side);
            node.addProperty("enabled", module.enabled);
            if (module.permissionWarning != null && !module.permissionWarning.isEmpty()) {
                node.addProperty("warning", module.permissionWarning);
            }
            if (module.description != null && !module.description.isBlank()) {
                node.addProperty("description", module.description);
            }
            nodes.put(key, node);
            for (String dependency : module.dependencies == null ? List.<String>of() : module.dependencies) {
                String id = idOf(dependency);
                edges.add(edge(key, (installed.contains(id) ? "mod:" : "module:") + id, "required"));
            }
            Map<String, String> code = moduleCode.getOrDefault(module.id, Map.of());
            uses(key, code, module.id, installed, edges, node);
            imports(key, code, edges);
        }
        for (var project : projectCode.entrySet()) {
            String key = "project:" + project.getKey();
            JsonObject node = node(key, project.getKey(), "project", "");
            nodes.put(key, node);
            Facts known = facts.get(key);
            for (String dependency : known == null || known.dependencies() == null ? List.<String>of() : known.dependencies()) {
                String id = idOf(dependency);
                edges.add(edge(key, (installed.contains(id) ? "mod:" : "module:") + id, "required"));
            }
            uses(key, project.getValue(), project.getKey(), installed, edges, node);
            imports(key, project.getValue(), edges);
        }
        // what each module and project is, does and may do; and who else reacts to the same events
        Map<String, List<String>> handlers = new TreeMap<>();
        facts.forEach((key, f) -> {
            for (String event : f.events() == null ? List.<String>of() : f.events()) {
                handlers.computeIfAbsent(event, e -> new java.util.ArrayList<>()).add(key);
            }
        });
        facts.forEach((key, f) -> {
            JsonObject node = nodes.get(key);
            if (node != null) {
                describe(node, key, f, handlers);
            }
        });
        // an edge to something that is not there (a module depending on a missing mod) still shows, as a missing node
        for (var element : edges) {
            String to = element.getAsJsonObject().get("to").getAsString();
            if (!nodes.containsKey(to)) {
                JsonObject missing = node(to, to.substring(to.indexOf(':') + 1), "missing", "");
                nodes.put(to, missing);
            }
        }
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        nodes.values().forEach(list::add);
        out.add("nodes", list);
        out.add("edges", edges);
        out.add("problems", problems(nodes, edges, handlers));
        return out;
    }

    /**
     * What is wrong in the whole setup, worst first: {@code {level: error|warn, text, keys[]}} where {@code keys} are the
     * nodes it is about (the first one is focused when the row is clicked).
     */
    private static JsonArray problems(Map<String, JsonObject> nodes, JsonArray edges, Map<String, List<String>> handlers) {
        JsonArray errors = new JsonArray();
        JsonArray warnings = new JsonArray();
        for (var element : edges) {
            JsonObject edge = element.getAsJsonObject();
            String from = edge.get("from").getAsString();
            String to = edge.get("to").getAsString();
            String kind = edge.get("kind").getAsString();
            JsonObject target = nodes.get(to);
            if (target != null && "missing".equals(target.get("kind").getAsString())
                    && ("required".equals(kind) || "imports".equals(kind))) {
                errors.add(problem("error", nameOf(nodes, from) + " needs " + nameOf(nodes, to) + ", which is not installed",
                        from, to));
            } else if ("incompatible".equals(kind)) {
                errors.add(problem("error", nameOf(nodes, from) + " does not work with " + nameOf(nodes, to), from, to));
            } else if ("discouraged".equals(kind)) {
                warnings.add(problem("warn", nameOf(nodes, from) + " advises against " + nameOf(nodes, to), from, to));
            }
        }
        for (var entry : nodes.entrySet()) {
            JsonObject node = entry.getValue();
            String name = nameOf(nodes, entry.getKey());
            String state = node.has("state") ? node.get("state").getAsString() : "";
            String approval = node.has("approval") ? node.get("approval").getAsString() : "";
            if ("error".equals(state)) {
                errors.add(problem("error", name + " failed to load (see the console)", entry.getKey()));
            }
            if ("needed".equals(approval)) {
                warnings.add(problem("warn", name + " waits for your approval (Modules page)", entry.getKey()));
            } else if ("changed".equals(approval)) {
                warnings.add(problem("warn", name + " changed since you approved it: approve it again", entry.getKey()));
            }
        }
        handlers.forEach((event, owners) -> {
            if (owners.size() >= 3) {
                List<String> names = owners.stream().map(key -> nameOf(nodes, key)).toList();
                warnings.add(problem("warn", event + " is handled by " + String.join(", ", names)
                        + ": check they don't undo each other", owners.toArray(String[]::new)));
            }
        });
        errors.addAll(warnings);
        return errors;
    }

    private static JsonObject problem(String level, String text, String... keys) {
        JsonObject problem = new JsonObject();
        problem.addProperty("level", level);
        problem.addProperty("text", text);
        JsonArray list = new JsonArray();
        for (String key : keys) {
            list.add(key);
        }
        problem.add("keys", list);
        return problem;
    }

    private static String nameOf(Map<String, JsonObject> nodes, String key) {
        JsonObject node = nodes.get(key);
        return node != null && node.has("name") ? node.get("name").getAsString() : key.substring(key.indexOf(':') + 1);
    }

    /** "uses" edges: the other mods whose ids the code names, with how often, and which ids (a few). */
    private static void uses(String from, Map<String, String> code, String ownNamespace, Set<String> installed, JsonArray edges,
                             JsonObject node) {
        Map<String, Set<String>> byMod = new TreeMap<>();
        for (String text : code.values()) {
            Matcher m = ID.matcher(text);
            while (m.find()) {
                String namespace = m.group(2);
                if (!namespace.equals(ownNamespace) && !NOT_MODS.contains(namespace)) {
                    byMod.computeIfAbsent(namespace, k -> new LinkedHashSet<>()).add(m.group().substring(1, m.group().length() - 1));
                }
            }
        }
        JsonArray usesList = new JsonArray();
        for (var entry : byMod.entrySet()) {
            String target = (installed.contains(entry.getKey()) ? "mod:" : "missing:") + entry.getKey();
            JsonObject edge = edge(from, target, "uses");
            JsonArray examples = new JsonArray();
            entry.getValue().stream().limit(8).forEach(examples::add);
            edge.add("ids", examples);
            edge.addProperty("count", entry.getValue().size());
            edges.add(edge);
            usesList.add(entry.getKey());
        }
        node.add("uses", usesList);
    }

    /** "imports" edges: the installed modules the code imports names from, with those names. */
    private static void imports(String from, Map<String, String> code, JsonArray edges) {
        Map<String, Set<String>> byModule = new TreeMap<>();
        for (String text : code.values()) {
            Matcher m = FROM_MODULE.matcher(text);
            while (m.find()) {
                Set<String> names = byModule.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>());
                for (String part : m.group(2).split(",")) {
                    String name = part.trim().split("[ \\t]+as[ \\t]+")[0].trim();
                    if (name.matches("[A-Za-z_*][\\w]*")) {
                        names.add(name);
                    }
                }
            }
            m = IMPORT_MODULE.matcher(text);
            while (m.find()) {
                byModule.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>());
            }
        }
        byModule.forEach((module, names) -> {
            if (!from.equals("module:" + module)) {
                JsonObject edge = edge(from, "module:" + module, "imports");
                JsonArray list = new JsonArray();
                names.stream().limit(MAX_LIST).forEach(list::add);
                edge.add("names", list);
                edges.add(edge);
            }
        });
    }

    private static void describe(JsonObject node, String key, Facts f, Map<String, List<String>> handlers) {
        put(node, "description", f.description());
        put(node, "author", f.author());
        put(node, "side", f.side());
        put(node, "state", f.state());
        put(node, "approval", f.approval());
        put(node, "decompiledTo", f.decompiledTo());
        node.addProperty("changed", f.changed());
        var p = f.permissions();
        if (p != null && !p.isEmpty()) {
            JsonObject perms = new JsonObject();
            JsonArray net = new JsonArray();
            (p.net == null ? List.<String>of() : p.net).stream().limit(MAX_LIST).forEach(net::add);
            perms.add("net", net);
            perms.addProperty("files", p.files);
            JsonArray shared = new JsonArray();
            (p.shared == null ? List.<String>of() : p.shared).forEach(shared::add);
            perms.add("shared", shared);
            perms.addProperty("commands", p.commands);
            perms.addProperty("java", p.java);
            node.add("permissions", perms);
        }
        if (f.adds() != null && !f.adds().isEmpty()) {
            JsonObject adds = node.has("adds") ? node.getAsJsonObject("adds") : new JsonObject();
            f.adds().forEach(adds::addProperty);
            node.add("adds", adds);
        }
        JsonArray commands = new JsonArray();
        (f.commands() == null ? List.<String>of() : f.commands()).stream().limit(MAX_LIST).forEach(commands::add);
        node.add("commands", commands);
        JsonArray events = new JsonArray();
        for (String event : (f.events() == null ? List.<String>of() : f.events()).stream().limit(MAX_LIST).toList()) {
            JsonObject row = new JsonObject();
            row.addProperty("event", event);
            JsonArray also = new JsonArray();
            handlers.getOrDefault(event, List.of()).stream().filter(other -> !other.equals(key)).limit(8).forEach(also::add);
            row.add("also", also);
            events.add(row);
        }
        node.add("events", events);
    }

    private static void put(JsonObject node, String key, String value) {
        if (value != null && !value.isBlank()) {
            node.addProperty(key, value);
        }
    }

    /** "foo>=1.2" -> "foo". */
    private static String idOf(String dependency) {
        return dependency.split("[<>=!~ ;]", 2)[0].trim();
    }

    private static Map<String, Map<String, Integer>> counts(CatalogView catalog) {
        Map<String, Map<String, Integer>> out = new TreeMap<>();
        for (String kind : KINDS) {
            for (CatalogView.Entry entry : catalog.all(kind)) {
                out.computeIfAbsent(entry.mod(), k -> new TreeMap<>()).merge(kind, 1, Integer::sum);
            }
        }
        catalog.recipeTypes().keySet().forEach(type -> {
            String mod = type.contains(":") ? type.substring(0, type.indexOf(':')) : "minecraft";
            out.computeIfAbsent(mod, k -> new TreeMap<>()).merge("recipe_type", 1, Integer::sum);
        });
        return out;
    }

    private static JsonObject node(String key, String name, String kind, String version) {
        JsonObject node = new JsonObject();
        node.addProperty("key", key);
        node.addProperty("name", name == null ? key : name);
        node.addProperty("kind", kind);
        node.addProperty("version", version == null ? "" : version);
        return node;
    }

    private static JsonObject edge(String from, String to, String kind) {
        JsonObject edge = new JsonObject();
        edge.addProperty("from", from);
        edge.addProperty("to", to);
        edge.addProperty("kind", kind);
        return edge;
    }

    /** code/**.py of a folder on disk (path to text), at most 2 MB in all. */
    public static Map<String, String> pythonFiles(Path dir) {
        Map<String, String> out = new TreeMap<>();
        Path code = dir.resolve("code");
        if (!Files.isDirectory(code)) {
            return out;
        }
        long budget = 2L * 1024 * 1024;
        try (Stream<Path> walk = Files.walk(code, 6)) {
            for (Path file : walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".py")).toList()) {
                long size = Files.size(file);
                if (size > budget) {
                    break;
                }
                budget -= size;
                out.put(dir.relativize(file).toString().replace('\\', '/'), Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException unreadable) {
            // a project that cannot be read simply has no "uses" edges
        }
        return out;
    }
}
