package net.xmilon.craftpy.dynamics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the generated JSON that CraftPy writes to the dynamic data/resource
 * packs. This has nothing to do with Minecraft types and runs on a normal JVM.
 */
class PackBuilderSmokeTest {

    @Test
    void selfDropLoot_tableHasTypeAndPools() {
        JsonObject table = PackBuilder.selfDropLoot("minecraft:stone");
        assertEquals("minecraft:block", table.get("type").getAsString());
        JsonArray pools = table.getAsJsonArray("pools");
        assertEquals(1, pools.size());
        JsonObject pool = pools.get(0).getAsJsonObject();
        assertEquals(1.0f, pool.get("rolls").getAsFloat());
        JsonArray entries = pool.getAsJsonArray("entries");
        assertEquals(1, entries.size());
        JsonObject entry = entries.get(0).getAsJsonObject();
        assertEquals("minecraft:item", entry.get("type").getAsString());
        assertEquals("minecraft:stone", entry.get("name").getAsString());
    }

    @Test
    void selfDropLoot_plainItemIdStillWorks() {
        JsonObject table = PackBuilder.selfDropLoot("stick");
        JsonObject entry = table.getAsJsonArray("pools").get(0).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("stick", entry.get("name").getAsString());
    }

    @Test
    void mapRef_unknownReferenceIsReturnedUnchanged() {
        String mapped = PackBuilder.mapRef("unknownmod:thing", "buildproj");
        assertEquals("unknownmod:thing", mapped);
    }
}
