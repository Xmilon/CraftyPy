package net.xmilon.craftpy.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileLocksTest {

    @AfterEach
    void reset() {
        FileLocks.clear();
    }

    @Test
    void theLastSaverHoldsTheFileForAWhile() {
        assertNull(FileLocks.claim("p", "code/a.py", "alex", "Alex", 0));
        assertNull(FileLocks.claim("p", "code/a.py", "alex", "Alex", 1000)); // the holder saves again
        FileLocks.Lock refused = FileLocks.claim("p", "code/a.py", "steve", "Steve", 60_000);
        assertEquals("Alex", refused.name());
        assertTrue(refused.describe(120_000).contains("Alex is editing this file (saved 1 min ago)"));
        assertNull(FileLocks.claim("p", "code/b.py", "steve", "Steve", 60_000)); // other files are free
        assertNull(FileLocks.claim("p", "code/a.py", "steve", "Steve", 1000 + FileLocks.HOLD_MS)); // expired
        assertEquals("Steve", FileLocks.holder("p", "code/a.py", "alex", 1000 + FileLocks.HOLD_MS).name());
    }

    @Test
    void leavingReleases() {
        FileLocks.claim("p", "code/a.py", "alex", "Alex", 0);
        FileLocks.release("alex");
        assertNull(FileLocks.claim("p", "code/a.py", "steve", "Steve", 1));
    }
}
