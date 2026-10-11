package net.xmilon.craftpy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.xmilon.craftpy.registry.SlotRegistry;

/**
 * Resolution rules for {@code /craftpy give <player> <project>:<id> <block|item>}:
 * the id is matched against the project content mapping (not the item registry),
 * with a {@code project_}-prefixed alias and a bare-content-id fallback.
 * Minecraft-free by design, like the other unit tests.
 */
class CraftPyCommandResolveTest {

    private static Map<String, SlotRegistry.SlotRef> mapping() {
        Map<String, SlotRegistry.SlotRef> map = new LinkedHashMap<>();
        map.put("project_bb:ruby", new SlotRegistry.SlotRef(SlotRegistry.Kind.BLOCK, 3));
        map.put("bb:gem", new SlotRegistry.SlotRef(SlotRegistry.Kind.ITEM, 7));
        map.put("demo:ruby", new SlotRegistry.SlotRef(SlotRegistry.Kind.BLOCK, 1));
        return map;
    }

    @Test
    void exactMappingKeyWins() {
        assertEquals("BLOCK#3", CraftPyCommand.resolve(mapping(), "project_bb:ruby", SlotRegistry.Kind.BLOCK));
    }

    @Test
    void projectPrefixedAliasStripsPrefix() {
        Map<String, SlotRegistry.SlotRef> map = new LinkedHashMap<>();
        map.put("bb:ruby", new SlotRegistry.SlotRef(SlotRegistry.Kind.BLOCK, 3));
        assertEquals("BLOCK#3", CraftPyCommand.resolve(map, "project_bb:ruby", SlotRegistry.Kind.BLOCK));
    }

    @Test
    void bareContentIdMatchesUniqueEntry() {
        Map<String, SlotRegistry.SlotRef> map = new LinkedHashMap<>();
        map.put("bb:gem", new SlotRegistry.SlotRef(SlotRegistry.Kind.ITEM, 7));
        assertEquals("ITEM#7", CraftPyCommand.resolve(map, "gem", SlotRegistry.Kind.ITEM));
    }

    @Test
    void kindMismatchIsRejected() {
        String result = CraftPyCommand.resolve(mapping(), "project_bb:ruby", SlotRegistry.Kind.ITEM);
        assertTrue(result.startsWith("ERROR:"), result);
    }

    @Test
    void unknownContentIsRejected() {
        String result = CraftPyCommand.resolve(mapping(), "bb:does_not_exist", SlotRegistry.Kind.BLOCK);
        assertTrue(result.startsWith("ERROR:"), result);
    }

    @Test
    void ambiguousBareIdAsksForProjectPrefix() {
        // "ruby" exists in two projects; the caller must disambiguate.
        String result = CraftPyCommand.resolve(mapping(), "ruby", SlotRegistry.Kind.BLOCK);
        assertTrue(result.startsWith("ERROR:"), result);
    }
}
