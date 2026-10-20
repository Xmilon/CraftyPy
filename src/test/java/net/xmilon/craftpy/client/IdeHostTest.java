package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

class IdeHostTest {

    @Test
    void plainHostRunsInlineAndKeepsTheClipboard() {
        IdeHost host = IdeHost.PLAIN;
        int[] ran = {0};
        host.execute(() -> ran[0]++);
        assertEquals(1, ran[0]);
        host.setClipboard("abc");
        assertEquals("abc", host.clipboard());
        host.setClipboard(null);
        assertEquals("", host.clipboard());
    }

    @Test
    void plainHostReadsBundledDocsFromTheClasspath() throws Exception {
        try (InputStream guide = IdeHost.PLAIN.doc("guide.md")) {
            assertNotNull(guide);
        }
        assertNull(IdeHost.PLAIN.doc("nothing-here.md"));
    }
}
