package net.xmilon.craftpy.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContentTooltipTest {

    @Test
    void holdersBecomeIds() {
        assertArrayEquals(new String[]{"ruby_rush", "ruby"}, ContentTooltip.split("ITEM:ruby_rush:ruby"));
        assertArrayEquals(new String[]{"welcome", "gem_ore"}, ContentTooltip.split("BLOCK:welcome:gem_ore"));
        assertNull(ContentTooltip.split("item_4"));
        assertNull(ContentTooltip.split("ITEM::x"));
    }

    @Test
    void unknownOwnersGetATitleCaseName() {
        assertEquals("Ruby Rush", ContentTooltip.ownerName("ruby_rush"));
    }
}
