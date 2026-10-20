package net.xmilon.craftpy.lifecycle;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageDataTest {

    @Test
    void valuesSurviveSaveAndLoad() {
        Map<String, JsonElement> values = new HashMap<>();
        values.put("world/ores_mined", JsonParser.parseString("12"));
        values.put("world/home:Steve", JsonParser.parseString("[1, 64, -3]"));
        values.put("player:x/flags", JsonParser.parseString("{\"a\": null, \"b\": \"" + "ruby ".repeat(20000) + "\"}"));
        CompoundTag tag = new CompoundTag();
        StorageData.write(values, tag);

        Map<String, JsonElement> loaded = new HashMap<>();
        StorageData.read(tag, loaded);
        assertEquals(values, loaded);
    }

    @Test
    void emptyTagLoadsNothing() {
        Map<String, JsonElement> loaded = new HashMap<>();
        StorageData.read(new CompoundTag(), loaded);
        assertTrue(loaded.isEmpty());
    }
}
