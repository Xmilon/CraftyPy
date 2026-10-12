package net.xmilon.craftpy.registry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.Config;
import net.xmilon.craftpy.block.SlottedBlock;
import net.xmilon.craftpy.block.SlottedItem;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.python.Declarations;
import net.xmilon.craftpy.util.Json;

/**
 * The slot-pool registry. Pre-registered generic blocks/items are assigned to
 * project content at load time. The mapping is persisted to
 * {@code pyforge/slots.json} so placed blocks keep working across reloads and
 * restarts; only the live definition is detached on unload (inert placeholder).
 *
 * All mutation happens on the server thread or from the async load pipeline via
 * {@code server.execute(...)} snapshots - this class is effectively single
 * writer.
 */
public final class SlotRegistry {
    public enum Kind {
        BLOCK, ITEM, EFFECT, ENTITY, SOUND
    }

    public record SlotRef(Kind kind, int index) {
    }

    /** Hard cap on pre-registered generic blocks; config {@code slotPool.blocks} is clamped to this. */
    public static final int BLOCK_CAP = 256;
    /** Hard cap on pre-registered generic items; config {@code slotPool.items} is clamped to this. */
    public static final int ITEM_CAP = 256;

    private static final Map<String, SlotRef> MAPPING = new ConcurrentHashMap<>();
    private static final Map<String, Defs.BlockDef> BLOCK_DEFS = new ConcurrentHashMap<>();
    private static final Map<String, Defs.ItemDef> ITEM_DEFS = new ConcurrentHashMap<>();
    private static final Map<String, Defs.EffectDef> EFFECT_DEFS = new ConcurrentHashMap<>();
    private static final Map<String, Defs.EntityDef> ENTITY_DEFS = new ConcurrentHashMap<>();
    private static final Map<String, Declarations> PROJECT_DECLS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> ACTIVE_PROJECTS = new ConcurrentHashMap<>();

    private static final List<SlottedBlock> SLOT_BLOCKS = new ArrayList<>();
    private static final List<SlottedItem> SLOT_ITEMS = new ArrayList<>();
    private static final List<Object> SLOT_EFFECTS = new ArrayList<>();
    private static final List<Object> SLOT_ENTITIES = new ArrayList<>();
    private static final List<String> EVENT_HANDLER_PROJECTS = new ArrayList<>();

    private static boolean loaded = false;

    private SlotRegistry() {
    }

    // ------------------------------------------------------------------
    // Registration support (populated from ModBlocks/ModItems)
    // ------------------------------------------------------------------

    public static void registerBlock(int index, SlottedBlock block) {
        while (SLOT_BLOCKS.size() <= index) {
            SLOT_BLOCKS.add(null);
        }
        SLOT_BLOCKS.set(index, block);
    }

    public static void registerItem(int index, SlottedItem item) {
        while (SLOT_ITEMS.size() <= index) {
            SLOT_ITEMS.add(null);
        }
        SLOT_ITEMS.set(index, item);
    }

    public static List<SlottedBlock> slotBlocks() {
        return List.copyOf(SLOT_BLOCKS);
    }

    public static List<SlottedItem> slotItems() {
        return List.copyOf(SLOT_ITEMS);
    }

    public static Defs.BlockDef blockDef(int index) {
        SlotRef ref = refByIndex(Kind.BLOCK, index);
        if (ref == null) {
            return null;
        }
        return BLOCK_DEFS.get(key(ref.kind, ref.index));
    }

    public static Defs.ItemDef itemDef(int index) {
        SlotRef ref = refByIndex(Kind.ITEM, index);
        if (ref == null) {
            return null;
        }
        return ITEM_DEFS.get(key(ref.kind, ref.index));
    }

    private static SlotRef refByIndex(Kind kind, int index) {
        for (Map.Entry<String, SlotRef> entry : MAPPING.entrySet()) {
            SlotRef ref = entry.getValue();
            if (ref.kind() == kind && ref.index() == index) {
                return ref;
            }
        }
        return new SlotRef(kind, index);
    }

    public static String holderForBlock(int index) {
        return holderFor(Kind.BLOCK, index);
    }

    public static String holderForItem(int index) {
        return holderFor(Kind.ITEM, index);
    }

    private static String holderFor(Kind kind, int index) {
        SlotRef ref = refByIndex(kind, index);
        for (Map.Entry<String, SlotRef> entry : MAPPING.entrySet()) {
            if (entry.getValue().equals(ref)) {
                return entry.getKey();
            }
        }
        return kind.name().toLowerCase() + "_" + index;
    }

    private static String key(Kind kind, int index) {
        return kind.name() + "#" + index;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public static synchronized void init() {
        if (loaded) {
            return;
        }
        Path file = slotsFile();
        if (Files.isRegularFile(file)) {
            try {
                JsonObject root = Json.read(file, JsonObject.class);
                JsonObject map = root.getAsJsonObject("map");
                if (map != null) {
                    for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
                        JsonObject value = entry.getValue().getAsJsonObject();
                        Kind kind = Kind.valueOf(value.get("kind").getAsString());
                        int index = value.get("index").getAsInt();
                        MAPPING.put(entry.getKey(), new SlotRef(kind, index));
                    }
                }
                JsonElement active = root.get("active");
                if (active != null && active.isJsonArray()) {
                    for (JsonElement element : active.getAsJsonArray()) {
                        ACTIVE_PROJECTS.put(element.getAsString(), Boolean.TRUE);
                    }
                }
            } catch (Exception e) {
                CraftPy.LOGGER.warn("Failed to read slots.json, starting empty", e);
            }
        }
        loaded = true;
    }

    public static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            JsonObject map = new JsonObject();
            for (Map.Entry<String, SlotRef> entry : MAPPING.entrySet()) {
                JsonObject value = new JsonObject();
                value.addProperty("kind", entry.getValue().kind().name());
                value.addProperty("index", entry.getValue().index());
                map.add(entry.getKey(), value);
            }
            root.add("map", map);
            JsonArray active = new JsonArray();
            for (String projectId : ACTIVE_PROJECTS.keySet()) {
                active.add(projectId);
            }
            root.add("active", active);
            Files.createDirectories(slotsFile().getParent());
            Files.writeString(slotsFile(), Json.GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CraftPy.LOGGER.error("Failed to write slots.json", e);
        }
    }

    private static Path slotsFile() {
        return PyForgePaths.root().resolve("slots.json");
    }

    // ------------------------------------------------------------------
    // Allocation / application (server thread)
    // ------------------------------------------------------------------

    /**
     * Applies a block declaration. Returns the assigned registry reference.
     * If the content already has a slot, it is reused (stable across reloads).
     */
    public static synchronized SlotRef applyBlock(String projectId, String contentId, JsonObject definition) {
        String contentKey = "BLOCK:" + projectId + ":" + contentId;
        Defs.BlockDef def = Defs.BlockDef.from(definition);
        SlotRef ref = MAPPING.get(contentKey);
        if (ref == null) {
            int cap = 256;
            try { cap = Config.BLOCK_SLOTS.get(); } catch (Throwable ignored) {}
            int index = allocateFree(Kind.BLOCK, poolSize(Kind.BLOCK, cap));
            ref = new SlotRef(Kind.BLOCK, index);
            MAPPING.put(contentKey, ref);
        }
        BLOCK_DEFS.put(key(Kind.BLOCK, ref.index()), def);
        ACTIVE_PROJECTS.put(projectId, Boolean.TRUE);
        return ref;
    }

    public static synchronized SlotRef applyItem(String projectId, String contentId, JsonObject definition) {
        String contentKey = "ITEM:" + projectId + ":" + contentId;
        Defs.ItemDef def = Defs.ItemDef.from(definition);
        SlotRef ref = MAPPING.get(contentKey);
        if (ref == null) {
            int cap = 256;
            try { cap = Config.ITEM_SLOTS.get(); } catch (Throwable ignored) {}
            int index = allocateFree(Kind.ITEM, poolSize(Kind.ITEM, cap));
            ref = new SlotRef(Kind.ITEM, index);
            MAPPING.put(contentKey, ref);
        }
        ITEM_DEFS.put(key(Kind.ITEM, ref.index()), def);
        ACTIVE_PROJECTS.put(projectId, Boolean.TRUE);
        return ref;
    }

    private static int poolSize(Kind kind, int configured) {
        int registered = switch (kind) {
            case BLOCK -> SLOT_BLOCKS.size() == 0 ? BLOCK_CAP : SLOT_BLOCKS.size();
            case ITEM -> SLOT_ITEMS.size() == 0 ? ITEM_CAP : SLOT_ITEMS.size();
            default -> configured;
        };
        return Math.min(configured, registered);
    }

    /** Persists the current mapping. Call once after a batch of apply/release ops. */
    public static synchronized void flush() {
        save();
    }

    private static int allocateFree(Kind kind, int poolSize) {
        Set<String> used = new java.util.TreeSet<>();
        for (SlotRef ref : MAPPING.values()) {
            if (ref.kind() == kind && ref.index() < poolSize) {
                used.add(Integer.toString(ref.index()));
            }
        }
        for (int i = 0; i < poolSize; i++) {
            if (!used.contains(Integer.toString(i))) {
                return i;
            }
        }
        throw new IllegalStateException("Slot pool exhausted for " + kind
                + " (increase slotPool." + kind.name().toLowerCase() + "s in config)");
    }

    /** Detaches the live definitions of one project; mapping stays so old grids remain stable. */
    public static synchronized void releaseProject(String projectId) {
        for (String contentKey : new ArrayList<>(MAPPING.keySet())) {
            if (contentKey.contains(":" + projectId + ":")) {
                SlotRef ref = MAPPING.get(contentKey);
                BLOCK_DEFS.remove(key(Kind.BLOCK, ref.index()));
                ITEM_DEFS.remove(key(Kind.ITEM, ref.index()));
            }
        }
        ACTIVE_PROJECTS.remove(projectId);
        PROJECT_DECLS.remove(projectId);
        save();
    }

    public static synchronized void storeDeclarations(String projectId, Declarations declarations) {
        if (declarations != null) {
            PROJECT_DECLS.put(projectId, declarations);
        }
    }

    public static Declarations declarations(String projectId) {
        return PROJECT_DECLS.get(projectId);
    }

    public static boolean isProjectActive(String projectId) {
        return Boolean.TRUE.equals(ACTIVE_PROJECTS.get(projectId));
    }

    public static List<String> activeProjectIds() {
        return new ArrayList<>(ACTIVE_PROJECTS.keySet());
    }

    public static Map<String, SlotRef> snapshotByHolder() {
        return new LinkedHashMap<>(MAPPING);
    }

    public static void clearAll() {
        MAPPING.clear();
        BLOCK_DEFS.clear();
        ITEM_DEFS.clear();
        PROJECT_DECLS.clear();
        ACTIVE_PROJECTS.clear();
        save();
    }
}