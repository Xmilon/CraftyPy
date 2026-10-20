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

import net.xmilon.craftpy.CraftPyLog;
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
 * {@code pyforge/system/slots.json} so placed blocks keep working across reloads and
 * restarts; only the live definition is detached on unload (inert placeholder).
 *
 * All mutation happens on the server thread or from the async load pipeline via
 * {@code server.execute(...)} snapshots - this class is effectively single
 * writer.
 */
public final class SlotRegistry {
    public enum Kind {
        BLOCK, ITEM, EFFECT, ENTITY, SOUND, MONSTER
    }

    /** Mobs (passive ones are {@code pcentity_N}, hostile ones {@code pcmonster_N}) and effects: fixed pools. */
    public static final int ENTITY_SLOTS = 32;
    public static final int MONSTER_SLOTS = 32;
    public static final int EFFECT_SLOTS = 32;

    /** The registry path of a slot: {@code pcblock_3}, {@code pcitem_3}, {@code pcentity_3}, {@code pcmonster_3}... */
    public static String slotName(SlotRef ref) {
        return switch (ref.kind()) {
            case BLOCK -> "pcblock_" + ref.index();
            case ITEM -> "pcitem_" + ref.index();
            case ENTITY -> "pcentity_" + ref.index();
            case MONSTER -> "pcmonster_" + ref.index();
            case EFFECT -> "pceffect_" + ref.index();
            case SOUND -> "pcsound_" + ref.index();
        };
    }

    /**
     * {@code project:id} of a declared mob or effect to the registry id ({@code craftpy:pcentity_3}); anything else
     * (vanilla, other mods, unknown) comes back unchanged.
     */
    public static String resolve(String ref, Kind... kinds) {
        if (ref == null) {
            return null;
        }
        for (Kind kind : kinds) {
            SlotRef slot = MAPPING.get(kind.name() + ":" + ref);
            if (slot != null) {
                return CraftPyLog.MODID + ":" + slotName(slot);
            }
        }
        return ref;
    }

    public record SlotRef(Kind kind, int index) {
    }

    /** Generic blocks registered at startup ({@code config/craftpy-slots.properties}, default 256, at most 4096). */
    public static int blockSlotCount() {
        return SlotCounts.blocks();
    }

    /** Generic items registered at startup ({@code config/craftpy-slots.properties}, default 256, at most 4096). */
    public static int itemSlotCount() {
        return SlotCounts.items();
    }

    private static final Map<String, SlotRef> MAPPING = new ConcurrentHashMap<>();
    private static final Map<String, Defs.BlockDef> BLOCK_DEFS = new ConcurrentHashMap<>();
    private static final Map<String, Defs.ItemDef> ITEM_DEFS = new ConcurrentHashMap<>();
    private static final Map<String, Defs.EffectDef> EFFECT_DEFS = new ConcurrentHashMap<>();
    private static final Map<String, Defs.EntityDef> ENTITY_DEFS = new ConcurrentHashMap<>();
    private static final Map<String, Declarations> PROJECT_DECLS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> ACTIVE_PROJECTS = new ConcurrentHashMap<>();

    private static final List<SlottedBlock> SLOT_BLOCKS = new ArrayList<>();
    private static final List<SlottedItem> SLOT_ITEMS = new ArrayList<>();
    private static final List<String> EVENT_HANDLER_PROJECTS = new ArrayList<>();

    private static boolean loaded = false;

    /** True for the generic {@code craftpy:pcblock_N} / {@code pcitem_N} pool entries (hidden from vanilla tab completion). */
    public static boolean isPlaceholder(net.minecraft.resources.ResourceLocation id) {
        if (!CraftPyLog.MODID.equals(id.getNamespace())) {
            return false;
        }
        String path = id.getPath();
        return path.startsWith("pcblock_") || path.startsWith("pcitem_") || path.startsWith("pcentity_")
                || path.startsWith("pcmonster_") || path.startsWith("pceffect_");
    }

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

    /** The live definition of a mob slot ({@link Kind#ENTITY} or {@link Kind#MONSTER}), null when unloaded. */
    public static Defs.EntityDef entityDef(Kind kind, int index) {
        return ENTITY_DEFS.get(key(kind, index));
    }

    public static Defs.EffectDef effectDef(int index) {
        return EFFECT_DEFS.get(key(Kind.EFFECT, index));
    }

    /** A SlotRef is its kind and index: no need to look it up (this used to scan the whole mapping on every call). */
    private static SlotRef refByIndex(Kind kind, int index) {
        return new SlotRef(kind, index);
    }

    /** slot -> content key, rebuilt after MAPPING changes (tooltips, names and ticks ask for it many times a second). */
    private static volatile Map<SlotRef, String> reverse;

    private static void mappingChanged() {
        reverse = null;
    }

    private static Map<SlotRef, String> reverse() {
        Map<SlotRef, String> current = reverse;
        if (current == null) {
            current = new java.util.HashMap<>();
            for (Map.Entry<String, SlotRef> entry : MAPPING.entrySet()) {
                current.putIfAbsent(entry.getValue(), entry.getKey());
            }
            reverse = current;
        }
        return current;
    }

    public static String holderForBlock(int index) {
        return holderFor(Kind.BLOCK, index);
    }

    public static String holderForItem(int index) {
        return holderFor(Kind.ITEM, index);
    }

    /** The content key of a slot ({@code ENTITY:project:id}), or {@code entity_3} when nothing was ever mapped to it. */
    public static String holderFor(Kind kind, int index) {
        String holder = reverse().get(refByIndex(kind, index));
        return holder != null ? holder : kind.name().toLowerCase() + "_" + index;
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
                        mappingChanged();
                    }
                }
                JsonElement active = root.get("active");
                if (active != null && active.isJsonArray()) {
                    for (JsonElement element : active.getAsJsonArray()) {
                        ACTIVE_PROJECTS.put(element.getAsString(), Boolean.TRUE);
                    }
                }
            } catch (Exception e) {
                CraftPyLog.LOGGER.warn("Failed to read slots.json, starting empty", e);
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
            CraftPyLog.LOGGER.error("Failed to write slots.json", e);
        }
    }

    private static Path slotsFile() {
        return PyForgePaths.slotsFile();
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
            try { cap = Config.get(Config.BLOCK_SLOTS); } catch (Throwable ignored) {}
            int index = allocateFree(Kind.BLOCK, poolSize(Kind.BLOCK, cap));
            ref = new SlotRef(Kind.BLOCK, index);
            MAPPING.put(contentKey, ref);
            mappingChanged();
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
            try { cap = Config.get(Config.ITEM_SLOTS); } catch (Throwable ignored) {}
            int index = allocateFree(Kind.ITEM, poolSize(Kind.ITEM, cap));
            ref = new SlotRef(Kind.ITEM, index);
            MAPPING.put(contentKey, ref);
            mappingChanged();
        }
        ITEM_DEFS.put(key(Kind.ITEM, ref.index()), def);
        ACTIVE_PROJECTS.put(projectId, Boolean.TRUE);
        return ref;
    }

    private static int poolSize(Kind kind, int configured) {
        // The pool is whatever was registered at startup; the old slotPool.* config values only ever lowered it.
        return switch (kind) {
            case BLOCK -> SLOT_BLOCKS.isEmpty() ? blockSlotCount() : SLOT_BLOCKS.size();
            case ITEM -> SLOT_ITEMS.isEmpty() ? itemSlotCount() : SLOT_ITEMS.size();
            case ENTITY -> ENTITY_SLOTS;
            case MONSTER -> MONSTER_SLOTS;
            case EFFECT -> EFFECT_SLOTS;
            default -> configured;
        };
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
        for (String contentKey : SlotPlan.keysOf(MAPPING, projectId)) {
            detach(MAPPING.get(contentKey));
        }
        ACTIVE_PROJECTS.remove(projectId);
        PROJECT_DECLS.remove(projectId);
        save();
    }

    /** Removes the live definition of one slot. Blocks and items have separate pools, so only its own kind is touched. */
    private static void detach(SlotRef ref) {
        if (ref == null) {
            return;
        }
        switch (ref.kind()) {
            case BLOCK -> BLOCK_DEFS.remove(key(Kind.BLOCK, ref.index()));
            case ITEM -> ITEM_DEFS.remove(key(Kind.ITEM, ref.index()));
            case ENTITY, MONSTER -> ENTITY_DEFS.remove(key(ref.kind(), ref.index()));
            case EFFECT -> EFFECT_DEFS.remove(key(Kind.EFFECT, ref.index()));
            default -> {
            }
        }
    }

    /**
     * Applies all blocks and items of one project (or module) as ONE step: every definition is parsed and every
     * slot is found first; only then the registry changes. A bad definition or a full pool throws before anything
     * was touched, so the previous version of the project keeps running. Content the project no longer declares
     * is detached (its slot stays reserved, so blocks already placed in the world keep their identity).
     *
     * @throws RuntimeException when nothing could be applied; the registry is unchanged
     */
    public static synchronized void applyAll(String projectId, Declarations declarations) {
        Map<String, Defs.BlockDef> blocks = new LinkedHashMap<>();
        Map<String, Defs.ItemDef> items = new LinkedHashMap<>();
        Map<String, Defs.EntityDef> mobs = new LinkedHashMap<>();
        Map<String, Defs.EntityDef> monsters = new LinkedHashMap<>();
        Map<String, Defs.EffectDef> effects = new LinkedHashMap<>();
        for (Declarations.Content content : declarations.contents()) {
            switch (content.kind()) {
                case "block" -> blocks.put(content.id(), Defs.BlockDef.from(content.definition()));
                case "item" -> items.put(content.id(), Defs.ItemDef.from(content.definition()));
                case "entity" -> {
                    // hostile mobs need the MONSTER spawn category, which an entity type gets once, at startup
                    Defs.EntityDef def = Defs.EntityDef.from(content.definition());
                    (def.hostile() ? monsters : mobs).put(content.id(), def);
                }
                case "effect" -> effects.put(content.id(), Defs.EffectDef.from(content.definition()));
                default -> {
                }
            }
        }
        int blockCap = 256;
        int itemCap = 256;
        try {
            blockCap = Config.get(Config.BLOCK_SLOTS);
            itemCap = Config.get(Config.ITEM_SLOTS);
        } catch (Throwable ignored) {
        }
        Map<String, SlotRef> next = SlotPlan.allocate(MAPPING, projectId,
                Map.of(Kind.BLOCK, blocks.keySet(), Kind.ITEM, items.keySet(), Kind.ENTITY, mobs.keySet(),
                        Kind.MONSTER, monsters.keySet(), Kind.EFFECT, effects.keySet()),
                Map.of(Kind.BLOCK, poolSize(Kind.BLOCK, blockCap), Kind.ITEM, poolSize(Kind.ITEM, itemCap),
                        Kind.ENTITY, ENTITY_SLOTS, Kind.MONSTER, MONSTER_SLOTS, Kind.EFFECT, EFFECT_SLOTS));

        // Commit: nothing below can fail for lack of room.
        java.util.Set<String> declared = new java.util.HashSet<>();
        blocks.keySet().forEach(id -> declared.add(SlotPlan.key(Kind.BLOCK, projectId, id)));
        items.keySet().forEach(id -> declared.add(SlotPlan.key(Kind.ITEM, projectId, id)));
        mobs.keySet().forEach(id -> declared.add(SlotPlan.key(Kind.ENTITY, projectId, id)));
        monsters.keySet().forEach(id -> declared.add(SlotPlan.key(Kind.MONSTER, projectId, id)));
        effects.keySet().forEach(id -> declared.add(SlotPlan.key(Kind.EFFECT, projectId, id)));
        for (String contentKey : SlotPlan.keysOf(MAPPING, projectId)) {
            if (!declared.contains(contentKey)) {
                detach(MAPPING.get(contentKey));
            }
        }
        MAPPING.putAll(next);
        mappingChanged();
        blocks.forEach((id, def) -> BLOCK_DEFS.put(key(Kind.BLOCK, next.get(SlotPlan.key(Kind.BLOCK, projectId, id)).index()), def));
        items.forEach((id, def) -> ITEM_DEFS.put(key(Kind.ITEM, next.get(SlotPlan.key(Kind.ITEM, projectId, id)).index()), def));
        mobs.forEach((id, def) -> ENTITY_DEFS.put(key(Kind.ENTITY, next.get(SlotPlan.key(Kind.ENTITY, projectId, id)).index()), def));
        monsters.forEach((id, def) -> ENTITY_DEFS.put(key(Kind.MONSTER, next.get(SlotPlan.key(Kind.MONSTER, projectId, id)).index()), def));
        effects.forEach((id, def) -> EFFECT_DEFS.put(key(Kind.EFFECT, next.get(SlotPlan.key(Kind.EFFECT, projectId, id)).index()), def));
        ACTIVE_PROJECTS.put(projectId, Boolean.TRUE);
        PROJECT_DECLS.put(projectId, declarations);
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

    /** True when any loaded project or module has an {@code @on(event)} handler. */
    public static boolean handlesEvent(String event) {
        for (Declarations declarations : PROJECT_DECLS.values()) {
            for (Declarations.Handler handler : declarations.handlers()) {
                if (event.equals(handler.event())) {
                    return true;
                }
            }
        }
        return false;
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
        mappingChanged();
        BLOCK_DEFS.clear();
        ITEM_DEFS.clear();
        ENTITY_DEFS.clear();
        EFFECT_DEFS.clear();
        PROJECT_DECLS.clear();
        ACTIVE_PROJECTS.clear();
        save();
    }
}