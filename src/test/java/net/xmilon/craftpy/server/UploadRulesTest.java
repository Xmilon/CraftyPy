package net.xmilon.craftpy.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UploadRulesTest {
    private static final long LIMIT = 8L * 1024 * 1024;

    @Test
    void acceptsAssetsOfTheKnownTypes() {
        assertNull(UploadRules.check("assets/welcome/textures/a.png", 0, 1, LIMIT));
        assertNull(UploadRules.check("assets/sounds/a.OGG", 2, 3, LIMIT));
        assertNull(UploadRules.check("assets/sounds/a.wav", 0, 1, LIMIT));
        assertNull(UploadRules.check("assets/textures/water.png.mcmeta", 0, 1, LIMIT));
    }

    @Test
    void refusesOutsideAssetsAndOddPaths() {
        assertNotNull(UploadRules.check("project.json", 0, 1, LIMIT));
        assertNotNull(UploadRules.check("code/main.json", 0, 1, LIMIT));
        assertNotNull(UploadRules.check("assets/a.png:stream", 0, 1, LIMIT));
        assertNotNull(UploadRules.check("assets/a.exe", 0, 1, LIMIT));
    }

    @Test
    void refusesBadChunkNumbersAndHugeTotals() {
        assertNotNull(UploadRules.check("assets/a.png", 1, 1, LIMIT));
        assertNotNull(UploadRules.check("assets/a.png", 0, 0, LIMIT));
        assertNotNull(UploadRules.check("assets/a.png", 0, Integer.MAX_VALUE, LIMIT));
    }

    @Test
    void chunkLimitMatchesTheByteLimit() {
        // 16000 base64 chars carry 12000 bytes
        assertEquals(1, UploadRules.maxChunks(12_000));
        assertEquals(2, UploadRules.maxChunks(12_001));
        assertTrue(UploadRules.maxChunks(LIMIT) >= 700);
    }
}
