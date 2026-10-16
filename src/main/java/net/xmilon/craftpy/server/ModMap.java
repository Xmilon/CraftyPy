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

    private ModMap() {
    }

    public static JsonObject build(List<Mod> mods, List<ModuleInfo> modules, Map<String, Map<String, String>> projectCode,
                                   Map<String, Map<String, String>> moduleCode, CatalogView catalog) {
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
            nodes.put(key, node);
            for (String dependency : module.dependencies == null ? List.<String>of() : module.dependencies) {
                edges.add(edge(key, (installed.contains(dependency) ? "mod:" : "module:") + dependency, "required"));
            }
            uses(key, moduleCode.getOrDefault(module.id, Map.of()), module.id, installed, edges, node);
        }
        for (var project : projectCode.entrySet()) {
            String key = "project:" + project.getKey();
            JsonObject node = node(key, project.getKey(), "project", "");
            nodes.put(key, node);
            uses(key, project.getValue(), project.getKey(), installed, edges, node);
        }
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
        return out;
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
