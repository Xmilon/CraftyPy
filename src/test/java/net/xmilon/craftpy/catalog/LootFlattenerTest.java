package net.xmilon.craftpy.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class LootFlattenerTest {
    /** minecraft:entities/zombie as 1.21.1 encodes it. */
    private static final String ZOMBIE = """
            {"type": "minecraft:entity", "pools": [
              {"bonus_rolls": 0.0, "rolls": 1.0, "entries": [{"type": "minecraft:item", "name": "minecraft:rotten_flesh",
                "functions": [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 0.0, "max": 2.0}, "add": false},
                              {"function": "minecraft:enchanted_count_increase", "count": {"type": "minecraft:uniform", "min": 0.0, "max": 1.0}, "enchantment": "minecraft:looting"}]}]},
              {"bonus_rolls": 0.0, "rolls": 1.0, "conditions": [{"condition": "minecraft:killed_by_player"},
                {"condition": "minecraft:random_chance_with_enchanted_bonus", "enchantment": "minecraft:looting", "unenchanted_chance": 0.025,
                 "enchanted_chance": {"type": "minecraft:linear", "base": 0.035, "per_level_above_first": 0.01}}],
               "entries": [{"type": "minecraft:item", "name": "minecraft:iron_ingot"}, {"type": "minecraft:item", "name": "minecraft:carrot"},
                           {"type": "minecraft:item", "name": "minecraft:potato", "functions": [{"function": "minecraft:furnace_smelt"}]}]}]}
            """;

    @Test
    void zombieDropsFleshAlwaysAndRareItemsOnPlayerKills() {
        List<LootFlattener.Drop> drops = LootFlattener.flatten(JsonParser.parseString(ZOMBIE).getAsJsonObject(), id -> null);
        assertEquals(4, drops.size());
        LootFlattener.Drop flesh = drops.get(0);
        assertEquals("minecraft:rotten_flesh", flesh.item());
        assertEquals(0, flesh.min());
        assertEquals(2, flesh.max());
        assertEquals(1.0, flesh.chance(), 1e-9);
        assertEquals(1, flesh.bonusMax());
        LootFlattener.Drop iron = drops.get(1);
        assertTrue(iron.playerKill());
        assertEquals(0.025 / 3, iron.chance(), 1e-9);
        assertTrue(drops.get(3).notes().contains("cooked when the mob burns"));
    }

    @Test
    void nestedTablesAndTagsAreFollowed() {
        JsonObject outer = JsonParser.parseString("""
                {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:loot_table", "value": "x:inner", "weight": 1},
                                                    {"type": "minecraft:empty", "weight": 1}]}]}""").getAsJsonObject();
        JsonObject inner = JsonParser.parseString("""
                {"pools": [{"rolls": {"min": 2, "max": 2}, "entries": [{"type": "minecraft:tag", "name": "c:gems"}]}]}""").getAsJsonObject();
        List<LootFlattener.Drop> drops = LootFlattener.flatten(outer, id -> id.equals("x:inner") ? inner : null);
        assertEquals(1, drops.size());
        assertEquals("#c:gems", drops.get(0).item());
        assertEquals(0.5, drops.get(0).chance(), 1e-9);
    }

    @Test
    void aTableThatNamesItselfDoesNotLoopForever() {
        JsonObject self = JsonParser.parseString("""
                {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:loot_table", "value": "x:self"}]}]}""").getAsJsonObject();
        assertTrue(LootFlattener.flatten(self, id -> self).isEmpty());
    }
}
