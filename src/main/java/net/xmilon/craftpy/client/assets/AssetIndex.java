package net.xmilon.craftpy.client.assets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.xmilon.craftpy.assets.AssetKind;

/**
 * Client-side catalogue of available assets, rebuilt on every resource reload so
 * dynamic project packs are included. Kept intentionally simple: ids are stored
 * as strings and sorted; thumbnail rendering happens lazily in the picker.
 */
public final class AssetIndex implements ResourceManagerReloadListener {
    private static final AssetIndex INSTANCE = new AssetIndex();

    private volatile List<String> textures = List.of();
    private volatile List<String> models = List.of();
    private volatile List<String> sounds = List.of();
    private volatile List<String> items = List.of();
    private volatile List<String> blocks = List.of();
    private volatile Set<String> namespaces = Set.of();
    private volatile boolean built = false;

    private AssetIndex() {
    }

    public static AssetIndex get() {
        return INSTANCE;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        AssetPreview.invalidate(); // cached model text and textures belong to the old packs
        rebuild(resourceManager);
    }

    public synchronized void rebuild(ResourceManager resourceManager) {
        Set<String> tex = new TreeSet<>();
        Set<String> mdl = new TreeSet<>();
        Set<String> ns = new TreeSet<>();
        if (resourceManager != null) {
            for (ResourceLocation rl : resourceManager.listResources("textures",
                    loc -> loc.getPath().endsWith(".png")).keySet()) {
                tex.add(strip(rl.toString(), "textures/", ".png"));
                ns.add(rl.getNamespace());
            }
            for (ResourceLocation rl : resourceManager.listResources("models",
                    loc -> loc.getPath().endsWith(".json")).keySet()) {
                mdl.add(strip(rl.toString(), "models/", ".json"));
                ns.add(rl.getNamespace());
            }
        }

        Set<String> snd = new TreeSet<>();
        for (ResourceLocation rl : BuiltInRegistries.SOUND_EVENT.keySet()) {
            snd.add(rl.toString());
            ns.add(rl.getNamespace());
        }

        Set<String> itm = new TreeSet<>();
        for (ResourceLocation rl : BuiltInRegistries.ITEM.keySet()) {
            if (isSlotContent(rl)) {
                continue;
            }
            itm.add(rl.toString());
            ns.add(rl.getNamespace());
        }

        Set<String> blk = new TreeSet<>();
        for (ResourceLocation rl : BuiltInRegistries.BLOCK.keySet()) {
            if (isSlotContent(rl)) {
                continue;
            }
            blk.add(rl.toString());
            ns.add(rl.getNamespace());
        }

        textures = List.copyOf(tex);
        models = List.copyOf(mdl);
        sounds = List.copyOf(snd);
        items = List.copyOf(itm);
        blocks = List.copyOf(blk);
        namespaces = Collections.unmodifiableSet(new LinkedHashSet<>(ns));
        built = true;
    }

    /** True once the first build has finished (the lists are empty until then). */
    public boolean isBuilt() {
        return built;
    }

    /** True when the build threw: the lists stay empty and no new build is started. */
    public boolean isFailed() {
        return failed;
    }

    public void ensureBuilt() {
        if (built || building || failed) {
            return;
        }
        building = true;
        Minecraft mc = Minecraft.getInstance();
        ResourceManager resourceManager = mc == null ? null : mc.getResourceManager();
        // Enumerating every texture/model + registry entry is expensive; never
        // block the render thread. Results simply appear once ready.
        net.xmilon.craftpy.util.CraftPyExecutors.runAsync(() -> {
            try {
                rebuild(resourceManager);
            } catch (RuntimeException e) {
                failed = true;
                net.xmilon.craftpy.CraftPy.LOGGER.warn("Could not build the asset index", e);
            } finally {
                building = false;
            }
        }, net.xmilon.craftpy.util.CraftPyExecutors.ui());
    }

    private volatile boolean building = false;
    private volatile boolean failed = false;

    /** Project slot content is not an "existing thing" to reference; keep it out of the bank. */
    private static boolean isSlotContent(ResourceLocation rl) {
        String path = rl.getPath();
        return "craftpy".equals(rl.getNamespace())
                && (path.startsWith("pcblock_") || path.startsWith("pcitem_"));
    }

    private static String strip(String full, String prefix, String suffix) {
        // full looks like "<ns>:<prefix><path><suffix>"
        int colon = full.indexOf(':');
        if (colon < 0) {
            return full;
        }
        String ns = full.substring(0, colon);
        String path = full.substring(colon + 1);
        if (path.startsWith(prefix)) {
            path = path.substring(prefix.length());
        }
        if (path.endsWith(suffix)) {
            path = path.substring(0, path.length() - suffix.length());
        }
        return ns + ":" + path;
    }

    public List<String> ids(AssetKind kind) {
        ensureBuilt();
        return switch (kind) {
            case TEXTURE -> textures;
            case MODEL -> models;
            case SOUND -> sounds;
            case ITEM -> items;
            case BLOCK -> blocks;
        };
    }

    public boolean exists(AssetKind kind, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String normalized = normalize(kind, id);
        return ids(kind).stream().anyMatch(normalized::equals);
    }

    /** Accepts ids with or without namespace; defaults to minecraft. */
    public static String normalize(AssetKind kind, String id) {
        if (id == null) {
            return "";
        }
        String value = id.trim();
        if (value.contains(":")) {
            return value;
        }
        return "minecraft:" + value;
    }

    public Set<String> namespaces() {
        ensureBuilt();
        return namespaces;
    }

    public List<String> filter(AssetKind kind, String query, String namespace) {
        String q = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT).trim();
        List<String> result = new ArrayList<>();
        for (String id : ids(kind)) {
            if (namespace != null && !namespace.isEmpty()) {
                int colon = id.indexOf(':');
                String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
                if (!ns.equals(namespace)) {
                    continue;
                }
            }
            if (!q.isEmpty() && !fuzzy(id.toLowerCase(java.util.Locale.ROOT), q)) {
                continue;
            }
            result.add(id);
        }
        return result;
    }

    /** Simple subsequence fuzzy match (all query chars appear in order). */
    static boolean fuzzy(String haystack, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        int i = 0;
        for (int j = 0; j < haystack.length() && i < needle.length(); j++) {
            if (haystack.charAt(j) == needle.charAt(i)) {
                i++;
            }
        }
        if (i == needle.length()) {
            return true;
        }
        return haystack.contains(needle);
    }

    /**
     * Returns ids carrying the given tag (items/blocks only). The tag may be
     * namespaced ("minecraft:planks"); a bare "planks" defaults to minecraft.
     * Unknown tags return an empty list.
     */
    public List<String> byTag(AssetKind kind, String tag) {
        if (kind != AssetKind.ITEM && kind != AssetKind.BLOCK) {
            return List.of();
        }
        if (tag == null || tag.isBlank()) {
            return List.of();
        }
        String value = tag.trim();
        String full = value.contains(":") ? value : "minecraft:" + value;
        try {
            ResourceLocation rl = ResourceLocation.parse(full);
            if (kind == AssetKind.ITEM) {
                TagKey<Item> key = TagKey.create(Registries.ITEM, rl);
                List<String> out = new ArrayList<>();
                for (String id : ids(kind)) {
                    ResourceLocation idRl = ResourceLocation.parse(id);
                    if (!BuiltInRegistries.ITEM.containsKey(idRl)) {
                        continue;
                    }
                    if (BuiltInRegistries.ITEM.wrapAsHolder(BuiltInRegistries.ITEM.get(idRl)).is(key)) {
                        out.add(id);
                    }
                }
                return out;
            }
            TagKey<Block> key = TagKey.create(Registries.BLOCK, rl);
            List<String> out = new ArrayList<>();
            for (String id : ids(kind)) {
                ResourceLocation idRl = ResourceLocation.parse(id);
                if (!BuiltInRegistries.BLOCK.containsKey(idRl)) {
                    continue;
                }
                if (BuiltInRegistries.BLOCK.wrapAsHolder(BuiltInRegistries.BLOCK.get(idRl)).is(key)) {
                    out.add(id);
                }
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}