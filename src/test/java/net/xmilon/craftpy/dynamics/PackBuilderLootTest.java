package net.xmilon.craftpy.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Project blocks must behave like normal Minecraft blocks: mining one drops
 * itself. That drop comes from a loot table at the vanilla lookup path
 * ({@code loot_table/blocks/pcblock_<i>.json}) in the vanilla loot-table shape,
 * so the generated JSON structure is asserted here.
 */
class PackBuilderLootTest {

    @Test
    void selfDropTableMatchesVanillaShape() {
        JsonObject table = PackBuilder.selfDropLoot("craftpy:pcblock_3");

        assertEquals("minecraft:block", table.get("type").getAsString());

        JsonArray pools = table.getAsJsonArray("pools");
        assertEquals(1, pools.size());
        JsonObject pool = pools.get(0).getAsJsonObject();
        assertEquals(1.0f, pool.get("rolls").getAsFloat());

        JsonArray entries = pool.getAsJsonArray("entries");
        assertEquals(1, entries.size());
        JsonObject entry = entries.get(0).getAsJsonObject();
        assertEquals("minecraft:item", entry.get("type").getAsString());
        assertEquals("craftpy:pcblock_3", entry.get("name").getAsString());
    }

    @Test
    void selfDropIsDroppableJson() {
        // writeJson() skips empty objects; the table must never serialize empty.
        JsonObject table = PackBuilder.selfDropLoot("craftpy:pcitem_0");
        assertEquals(false, table.entrySet().isEmpty());
    }
}
