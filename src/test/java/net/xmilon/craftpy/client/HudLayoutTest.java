package net.xmilon.craftpy.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudLayoutTest {

    @Test
    void anchorsPutElementsAtTheScreenEdges() {
        assertArrayEquals(new int[]{4, 4}, HudLayout.position("top_left", 4, 4, 50, 10, 400, 300));
        assertArrayEquals(new int[]{346, 4}, HudLayout.position("top_right", -4, 4, 50, 10, 400, 300));
        assertArrayEquals(new int[]{150, 250}, HudLayout.position("bottom_center", 0, -40, 100, 10, 400, 300));
        assertArrayEquals(new int[]{175, 145}, HudLayout.position("center", 0, 0, 50, 10, 400, 300));
        assertArrayEquals(new int[]{0, 145}, HudLayout.position("center_left", 0, 0, 50, 10, 400, 300));
        assertTrue(HudLayout.isAnchor("bottom_right") && HudLayout.isAnchor("center"));
        assertFalse(HudLayout.isAnchor("middle") || HudLayout.isAnchor(null) || HudLayout.isAnchor("top_top"));
    }

    @Test
    void colorsAreNamesOrHex() {
        assertEquals(0xFFAA00, HudLayout.color("gold", 0));
        assertEquals(0xFF8800, HudLayout.color("#ff8800", 0));
        assertEquals(0x123456, HudLayout.color("nonsense", 0x123456));
        assertEquals(7, HudLayout.color(null, 7));
    }

    @Test
    void keyNamesMatchTheWorker() throws Exception {
        assertEquals("k", KeyCombo.name(75, 0));
        assertEquals("ctrl+shift+k", KeyCombo.name(75, 3));
        assertEquals("ctrl+alt+f6", KeyCombo.name(295, 6));
        assertEquals("space", KeyCombo.name(32, 0));
        assertNull(KeyCombo.name(256, 0)); // escape is never bindable
        // the same set of names as worker.py: letters, digits, f1-f12 and the named keys
        String worker = Files.readString(Path.of("src/main/resources/craftpy_runtime/worker.py"));
        Matcher named = Pattern.compile("frozenset\\(\\{\\s*((?:\"[a-z_]+\",?\\s*)+)\\}\\)\\s*\\n").matcher(
                worker.substring(worker.indexOf("_KEY_NAMES")));
        assertTrue(named.find());
        int namedKeys = named.group(1).split(",").length;
        assertEquals(26 + 10 + 12 + namedKeys, KeyCombo.knownKeys());
    }
}
