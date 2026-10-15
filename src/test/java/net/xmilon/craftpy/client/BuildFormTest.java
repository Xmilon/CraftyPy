package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class BuildFormTest {
    @Test
    void updateOnlyNeedsAValidVersion() {
        assertNull(BuildForm.error(false, "1.2.0", "", Set.of("welcome")));
        assertNotNull(BuildForm.error(false, "", "", Set.of()));
        assertNotNull(BuildForm.error(false, "1 0", "", Set.of()));
    }

    @Test
    void newModuleNeedsAFreeValidId() {
        assertNull(BuildForm.error(true, "1.0.0", "my_pack", Set.of("welcome")));
        assertNotNull(BuildForm.error(true, "1.0.0", "My Pack", Set.of()));
        assertNotNull(BuildForm.error(true, "1.0.0", "9lives", Set.of()));
        assertTrue(BuildForm.error(true, "1.0.0", "welcome", Set.of("welcome")).contains("already installed"));
    }

    @Test
    void requestIsTheJsonTheServerReads() {
        var json = JsonParser.parseString(BuildForm.request(true, " 2.0 ", " pack ", " Pack ", false)).getAsJsonObject();
        assertEquals("2.0", json.get("version").getAsString());
        assertEquals("new", json.get("mode").getAsString());
        assertEquals("pack", json.get("newId").getAsString());
        assertEquals("Pack", json.get("newName").getAsString());
        assertEquals(false, json.get("install").getAsBoolean());
    }
}
