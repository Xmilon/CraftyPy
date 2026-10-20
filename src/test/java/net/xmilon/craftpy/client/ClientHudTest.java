package net.xmilon.craftpy.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientHudTest {

    @AfterEach
    void clear() {
        ClientHud.clearAll();
    }

    private static JsonObject element(String owner, String id, String json) {
        JsonObject p = JsonParser.parseString(json).getAsJsonObject();
        p.addProperty("owner", owner);
        p.addProperty("id", id);
        return p;
    }

    @Test
    void storesReplacesAndRemoves() {
        assertTrue(ClientHud.set(element("m", "clock", "{\"kind\":\"text\",\"anchor\":\"top_right\",\"text\":\"12:00\",\"color\":\"gold\"}")));
        assertTrue(ClientHud.set(element("m", "clock", "{\"kind\":\"text\",\"anchor\":\"top_right\",\"text\":\"12:01\"}")));
        assertTrue(ClientHud.set(element("m", "mana", "{\"kind\":\"bar\",\"anchor\":\"bottom_center\",\"value\":30,\"max\":100}")));
        assertEquals(List.of("clock", "mana"), ClientHud.idsOf("m"));
        ClientHud.remove("m", "clock");
        assertEquals(List.of("mana"), ClientHud.idsOf("m"));
        ClientHud.clear("m");
        assertEquals(0, ClientHud.size());
    }

    @Test
    void malformedElementsAndLimitsAreRefused() {
        assertFalse(ClientHud.set(element("m", "a", "{\"kind\":\"video\",\"anchor\":\"top_left\"}")));
        assertFalse(ClientHud.set(element("m", "a", "{\"kind\":\"text\",\"anchor\":\"nowhere\"}")));
        assertFalse(ClientHud.set(JsonParser.parseString("{\"kind\":\"text\",\"anchor\":\"center\"}").getAsJsonObject()));
        for (int i = 0; i < ClientHud.PER_OWNER; i++) {
            assertTrue(ClientHud.set(element("busy", "e" + i, "{\"kind\":\"text\",\"anchor\":\"center\"}")));
        }
        assertFalse(ClientHud.set(element("busy", "one-too-many", "{\"kind\":\"text\",\"anchor\":\"center\"}")));
        assertTrue(ClientHud.set(element("busy", "e0", "{\"kind\":\"text\",\"anchor\":\"center\",\"text\":\"replaced\"}")));
        assertTrue(ClientHud.set(element("other", "x", "{\"kind\":\"text\",\"anchor\":\"center\"}")));
    }

    @Test
    void valuesAreClampedAndCut() {
        var parsed = ClientHud.parse(element("m", "a", "{\"kind\":\"bar\",\"anchor\":\"center\",\"x\":99999,\"scale\":500,\"width\":0,"
                + "\"height\":9999,\"max\":0,\"label\":\"" + "L".repeat(200) + "\"}"));
        assertEquals(4000, parsed.x());
        assertEquals(8, parsed.scale());
        assertEquals(1, parsed.width());
        assertEquals(60, parsed.height());
        assertTrue(parsed.max() > 0);
        assertEquals(64, parsed.label().length());
    }
}
