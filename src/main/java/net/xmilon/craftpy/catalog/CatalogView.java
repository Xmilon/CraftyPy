package net.xmilon.craftpy.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Everything the installed mods offer, merged from their catalogs (and the shipped seeds for mods that have no dump yet).
 * Immutable; build a new one when the set of mods or a catalog changes. This is what completion and checks ask.
 */
public final class CatalogView {
    /** One id and the mod that provides it. */
    public record Entry(String id, String mod) {
    }

    public static final CatalogView EMPTY = new CatalogView(Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), List.of(), List.of(), Map.of());

    private final Map<String, List<Entry>> ids;
    private final Map<String, Set<String>> idSets;
    private final Map<String, Catalog.RecipeType> recipeTypes;
    private final Set<String> installed;
    private final Map<String, String> modOfNamespace;
    private final List<String> events;
    private final List<String> classes;
    private final Map<String, List<String>> folders;

    private CatalogView(Map<String, List<Entry>> ids, Map<String, Set<String>> idSets, Map<String, Catalog.RecipeType> recipeTypes,
                        Set<String> installed, Map<String, String> modOfNamespace, List<String> events, List<String> classes,
                        Map<String, List<String>> folders) {
        this.ids = ids;
        this.idSets = idSets;
        this.recipeTypes = recipeTypes;
        this.installed = installed;
        this.modOfNamespace = modOfNamespace;
        this.events = events;
        this.classes = classes;
        this.folders = folders;
    }

    /**
     * @param catalogs  the saved catalogs of installed mods
     * @param seeds     shipped knowledge by mod id; fills recipe types and folders a dump did not provide
     * @param installed ids of the installed mods
     */
    public static CatalogView of(Collection<Catalog> catalogs, Map<String, Catalog> seeds, Set<String> installed) {
        Map<String, List<Entry>> ids = new TreeMap<>();
        Map<String, Set<String>> idSets = new HashMap<>();
        Map<String, Catalog.RecipeType> recipeTypes = new TreeMap<>();
        Map<String, String> modOfNamespace = new HashMap<>();
        Set<String> events = new LinkedHashSet<>();
        Set<String> classes = new LinkedHashSet<>();
        Map<String, List<String>> folders = new TreeMap<>();
        for (String mod : installed) {
            modOfNamespace.putIfAbsent(mod, mod);
        }
        for (Catalog catalog : catalogs) {
            modOfNamespace.put(catalog.namespace, catalog.mod);
            catalog.registries.forEach((kind, list) -> {
                for (String id : list) {
                    ids.computeIfAbsent(kind, k -> new ArrayList<>()).add(new Entry(id, catalog.mod));
                    idSets.computeIfAbsent(kind, k -> new HashSet<>()).add(id);
                }
            });
            catalog.tags.forEach((kind, list) -> {
                for (String id : list) {
                    ids.computeIfAbsent("tag:" + kind, k -> new ArrayList<>()).add(new Entry(id, catalog.mod));
                    idSets.computeIfAbsent("tag:" + kind, k -> new HashSet<>()).add(id);
                }
            });
            catalog.recipeTypes.forEach(recipeTypes::put);
            events.addAll(catalog.events);
            classes.addAll(catalog.classes);
            if (!catalog.folders.isEmpty()) {
                folders.computeIfAbsent(catalog.mod, k -> new ArrayList<>()).addAll(catalog.folders);
            }
        }
        for (var seed : seeds.entrySet()) {
            if (!installed.contains(seed.getKey())) {
                continue; // a mod that is gone shows nothing, seed or not
            }
            Catalog catalog = seed.getValue();
            if (!catalog.namespace.isEmpty()) {
                modOfNamespace.putIfAbsent(catalog.namespace, seed.getKey());
            }
            catalog.recipeTypes.forEach((type, schema) -> {
                Catalog.RecipeType dumped = recipeTypes.get(type);
                if (dumped == null) {
                    recipeTypes.put(type, schema);
                } else {
                    // copied: the dumped object may be shared with a cached catalog
                    Catalog.RecipeType merged = new Catalog.RecipeType(dumped.count, new ArrayList<>(dumped.fields),
                            dumped.sample != null ? dumped.sample : schema.sample,
                            dumped.note.isEmpty() ? schema.note : dumped.note, dumped.source);
                    for (String field : schema.fields) {
                        if (!merged.fields.contains(field)) {
                            merged.fields.add(field);
                        }
                    }
                    recipeTypes.put(type, merged);
                }
            });
            List<String> known = folders.computeIfAbsent(seed.getKey(), k -> new ArrayList<>());
            for (String folder : catalog.folders) {
                if (!known.contains(folder)) {
                    known.add(folder);
                }
            }
        }
        for (List<Entry> list : ids.values()) {
            list.sort((a, b) -> {
                int byMod = Integer.compare(rank(a.mod()), rank(b.mod()));
                return byMod != 0 ? byMod : a.id().compareTo(b.id());
            });
        }
        return new CatalogView(ids, idSets, recipeTypes, Set.copyOf(installed), modOfNamespace, new ArrayList<>(events),
                new ArrayList<>(classes), folders);
    }

    private static int rank(String mod) {
        return mod.equals("minecraft") ? 0 : 1;
    }

    /** Ids of the installed mods, sorted. */
    public List<String> installedMods() {
        return installed.stream().sorted().toList();
    }

    public boolean isEmpty() {
        return ids.isEmpty() && recipeTypes.isEmpty();
    }

    /** True when the catalog knows entries of this registry ({@code item}, {@code mob_effect}, {@code tag:item}...). */
    public boolean has(String kind) {
        return ids.containsKey(kind);
    }

    public Set<String> kinds() {
        return Collections.unmodifiableSet(ids.keySet());
    }

    public boolean contains(String kind, String id) {
        Set<String> set = idSets.get(kind);
        return set != null && set.contains(id);
    }

    public List<Entry> all(String kind) {
        return ids.getOrDefault(kind, List.of());
    }

    /**
     * Entries of a registry matching what was typed: ids that start with it first, then ids whose path starts with it, then
     * ids that contain it; vanilla before mods inside each group.
     */
    public List<Entry> search(String kind, String typed, int limit) {
        String query = typed.toLowerCase(Locale.ROOT);
        List<Entry> startsWith = new ArrayList<>();
        List<Entry> pathStarts = new ArrayList<>();
        List<Entry> contains = new ArrayList<>();
        for (Entry entry : all(kind)) {
            String id = entry.id();
            if (query.isEmpty() || id.startsWith(query)) {
                startsWith.add(entry);
            } else {
                String path = id.substring(id.indexOf(':') + 1);
                if (path.startsWith(query)) {
                    pathStarts.add(entry);
                } else if (path.contains(query) || query.contains(":") && id.contains(query)) { // the namespace alone is not a match
                    contains.add(entry);
                }
            }
        }
        List<Entry> out = new ArrayList<>(startsWith);
        out.addAll(pathStarts);
        out.addAll(contains);
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public Map<String, Catalog.RecipeType> recipeTypes() {
        return Collections.unmodifiableMap(recipeTypes);
    }

    public Catalog.RecipeType recipeType(String type) {
        return recipeTypes.get(type);
    }

    /** The mod that owns a namespace, or null when no installed mod does. */
    public String modOf(String namespace) {
        return modOfNamespace.get(namespace);
    }

    public boolean modInstalled(String mod) {
        return installed.contains(mod);
    }

    public List<String> events() {
        return events;
    }

    public List<String> classes() {
        return classes;
    }

    public List<String> folders(String mod) {
        return folders.getOrDefault(mod, List.of());
    }
}
