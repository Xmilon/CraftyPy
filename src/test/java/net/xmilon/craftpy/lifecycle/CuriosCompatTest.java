package net.xmilon.craftpy.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuriosCompatTest {
    @Test
    void slotNames() {
        assertTrue(CuriosCompat.isCurioSlot("curios") && CuriosCompat.isCurioSlot("curios:ring"));
        assertFalse(CuriosCompat.isCurioSlot("offhand") || CuriosCompat.isCurioSlot(null));
        assertTrue(CuriosCompat.applies("curios:ring", "ring") && CuriosCompat.applies("curios", "necklace"));
        assertFalse(CuriosCompat.applies("curios:ring", "necklace"));
    }
}
