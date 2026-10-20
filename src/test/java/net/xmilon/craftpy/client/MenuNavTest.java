package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MenuNavTest {
    private static final boolean[] ROWS = {true, false, true, true};

    @Test
    void downSkipsUnusableRowsAndWraps() {
        assertEquals(0, MenuNav.step(ROWS, -1, 1));
        assertEquals(2, MenuNav.step(ROWS, 0, 1));
        assertEquals(0, MenuNav.step(ROWS, 3, 1));
    }

    @Test
    void upStartsAtTheLastRowAndWraps() {
        assertEquals(3, MenuNav.step(ROWS, -1, -1));
        assertEquals(0, MenuNav.step(ROWS, 2, -1));
        assertEquals(3, MenuNav.step(ROWS, 0, -1));
    }

    @Test
    void nothingUsable() {
        assertEquals(-1, MenuNav.step(new boolean[]{false, false}, -1, 1));
        assertEquals(-1, MenuNav.step(new boolean[0], -1, 1));
    }
}
