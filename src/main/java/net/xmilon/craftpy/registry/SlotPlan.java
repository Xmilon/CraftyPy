package net.xmilon.craftpy.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.xmilon.craftpy.registry.SlotRegistry.Kind;
import net.xmilon.craftpy.registry.SlotRegistry.SlotRef;

/**
 * Pure slot bookkeeping: which pool slot holds which content id. Works on copies, so a plan that does not fit
 * throws before anything is changed. Keys look like {@code BLOCK:<project>:<content id>}.
 */
final class SlotPlan {
    private SlotPlan() {
    }

    static String key(Kind kind, String projectId, String contentId) {
        return kind.name() + ":" + projectId + ":" + contentId;
    }

    /** Mapping keys that belong to {@code projectId}. Matches the whole project segment, not a substring. */
    static List<String> keysOf(Map<String, SlotRef> mapping, String projectId) {
        List<String> out = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            String prefix = kind.name() + ":" + projectId + ":";
            for (String key : mapping.keySet()) {
                if (key.startsWith(prefix)) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    /**
     * The mapping after giving every declared block and item a slot (existing ones keep theirs).
     *
     * @throws IllegalStateException when a pool is too small; {@code current} is not touched
     */
    static Map<String, SlotRef> allocate(Map<String, SlotRef> current, String projectId, Collection<String> blockIds,
                                         Collection<String> itemIds, int blockPool, int itemPool) {
        return allocate(current, projectId, Map.of(Kind.BLOCK, blockIds, Kind.ITEM, itemIds),
                Map.of(Kind.BLOCK, blockPool, Kind.ITEM, itemPool));
    }

    /** The same for any kinds: every kind in {@code ids} gets slots from its own pool in {@code pools}. */
    static Map<String, SlotRef> allocate(Map<String, SlotRef> current, String projectId,
                                         Map<Kind, ? extends Collection<String>> ids, Map<Kind, Integer> pools) {
        Map<String, SlotRef> next = new LinkedHashMap<>(current);
        for (Kind kind : Kind.values()) {
            if (ids.containsKey(kind)) {
                assign(next, kind, projectId, ids.get(kind), pools.getOrDefault(kind, 0));
            }
        }
        return next;
    }

    private static void assign(Map<String, SlotRef> next, Kind kind, String projectId, Collection<String> ids,
                               int pool) {
        for (String id : ids) {
            String key = key(kind, projectId, id);
            if (!next.containsKey(key)) {
                next.put(key, new SlotRef(kind, freeIndex(next, kind, pool)));
            }
        }
    }

    private static int freeIndex(Map<String, SlotRef> mapping, Kind kind, int pool) {
        boolean[] used = new boolean[Math.max(pool, 0)];
        for (SlotRef ref : mapping.values()) {
            if (ref.kind() == kind && ref.index() >= 0 && ref.index() < pool) {
                used[ref.index()] = true;
            }
        }
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) {
                return i;
            }
        }
        throw new IllegalStateException("Slot pool exhausted for " + kind
                + " (increase slotPool." + kind.name().toLowerCase() + "s in config)");
    }
}
