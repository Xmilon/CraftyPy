package net.xmilon.craftpy.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.xmilon.craftpy.registry.SlotRegistry.Kind;
import net.xmilon.craftpy.registry.SlotRegistry.SlotRef;

class SlotPlanTest {
    @Test
    void existingContentKeepsItsSlotAndNewContentTakesTheLowestFree() {
        Map<String, SlotRef> current = new LinkedHashMap<>();
        current.put("BLOCK:a:ore", new SlotRef(Kind.BLOCK, 1));
        var next = SlotPlan.allocate(current, "a", List.of("ore", "brick"), List.of(), 4, 4);
        assertEquals(1, next.get("BLOCK:a:ore").index());
        assertEquals(0, next.get("BLOCK:a:brick").index());
        assertEquals(1, current.size(), "the input mapping is not changed");
    }

    @Test
    void blocksAndItemsHaveSeparatePools() {
        Map<String, SlotRef> current = new LinkedHashMap<>();
        current.put("BLOCK:a:ore", new SlotRef(Kind.BLOCK, 0));
        var next = SlotPlan.allocate(current, "b", List.of(), List.of("gem"), 1, 1);
        assertEquals(0, next.get("ITEM:b:gem").index());
        assertEquals(0, next.get("BLOCK:a:ore").index());
    }

    @Test
    void exhaustedPoolThrowsWithoutChangingAnything() {
        Map<String, SlotRef> current = new LinkedHashMap<>();
        current.put("BLOCK:a:one", new SlotRef(Kind.BLOCK, 0));
        assertThrows(IllegalStateException.class, () -> SlotPlan.allocate(current, "a", List.of("one", "two"), List.of(), 1, 1));
        assertEquals(1, current.size());
    }

    @Test
    void mobsMonstersAndEffectsHaveTheirOwnPools() {
        Map<String, SlotRef> current = new LinkedHashMap<>();
        current.put("ITEM:zoo:gem", new SlotRef(Kind.ITEM, 0));
        var next = SlotPlan.allocate(current, "zoo", Map.of(Kind.ENTITY, List.of("pig"), Kind.MONSTER, List.of("goblin"),
                Kind.EFFECT, List.of("rage")), Map.of(Kind.ENTITY, 2, Kind.MONSTER, 2, Kind.EFFECT, 2));
        assertEquals(0, next.get("ENTITY:zoo:pig").index());
        assertEquals(0, next.get("MONSTER:zoo:goblin").index());
        assertEquals(0, next.get("EFFECT:zoo:rage").index());
        assertEquals(List.of("ITEM:zoo:gem", "ENTITY:zoo:pig", "MONSTER:zoo:goblin", "EFFECT:zoo:rage").stream().sorted().toList(),
                SlotPlan.keysOf(next, "zoo").stream().sorted().toList());
    }

    @Test
    void keysOfMatchesTheWholeProjectSegment() {
        Map<String, SlotRef> mapping = new LinkedHashMap<>();
        mapping.put("BLOCK:ab:x", new SlotRef(Kind.BLOCK, 0));
        mapping.put("ITEM:a:y", new SlotRef(Kind.ITEM, 0));
        mapping.put("BLOCK:xa:z", new SlotRef(Kind.BLOCK, 1));
        assertEquals(List.of("ITEM:a:y"), SlotPlan.keysOf(mapping, "a"));
    }

    @Test
    void slotCountsAreClampedAndFallBackToTheDefault() {
        assertEquals(1024, SlotCounts.parse(" 1024 "));
        assertEquals(SlotCounts.MAX, SlotCounts.parse("999999"));
        assertEquals(1, SlotCounts.parse("0"));
        assertEquals(SlotCounts.DEFAULT, SlotCounts.parse("many"));
        assertEquals(SlotCounts.DEFAULT, SlotCounts.parse(null));
    }
}
