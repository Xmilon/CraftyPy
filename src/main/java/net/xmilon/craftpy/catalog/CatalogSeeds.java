package net.xmilon.craftpy.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Knowledge about mods that a dump of the running game cannot produce (the JSON layout of their recipe types, the
 * datapack folders they read). Shipped in {@code /craftpy_integrations/} as one file per mod in the {@link Catalog} format,
 * listed in {@code files.txt}. A seed only shows while its mod is installed.
 */
public final class CatalogSeeds {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CatalogSeeds() {
    }

    /** Seeds by mod id. A missing or broken file is skipped. */
    public static Map<String, Catalog> load() {
        Map<String, Catalog> seeds = new TreeMap<>();
        String manifest = read("/craftpy_integrations/files.txt");
        if (manifest == null) {
            return seeds;
        }
        for (String line : manifest.split("\\R")) {
            String name = line.trim();
            if (name.isEmpty() || name.contains("..")) {
                continue;
            }
            String json = read("/craftpy_integrations/" + name);
            if (json == null) {
                continue;
            }
            try {
                Catalog seed = GSON.fromJson(json, Catalog.class);
                if (seed != null && !seed.mod.isEmpty()) {
                    seed.recipeTypes.values().forEach(type -> type.source = "seed");
                    seeds.put(seed.mod, seed);
                }
            } catch (RuntimeException e) {
                // a broken seed must not take the others down
            }
        }
        return seeds;
    }

    private static String read(String resource) {
        try (InputStream in = CatalogSeeds.class.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
