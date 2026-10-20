package net.xmilon.craftpy.security;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedHttpsTest {
    private static PinnedHttps.Response parse(String raw, int max) throws IOException {
        return PinnedHttps.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), max, System.nanoTime() + 5_000_000_000L);
    }

    @Test
    void contentLengthChunkedAndUntilClose() throws IOException {
        var plain = parse("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello", 1000);
        assertEquals(200, plain.status());
        assertEquals("hello", plain.text());
        assertEquals("text/plain", plain.headers().get("content-type"));

        var chunked = parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n", 1000);
        assertEquals("hello world", chunked.text());

        var untilClose = parse("HTTP/1.0 200 OK\r\nX-A: 1\r\n\r\nrest of the stream", 1000);
        assertEquals("rest of the stream", untilClose.text());
    }

    @Test
    void sizeLimitsApplyToEveryKindOfBody() {
        assertThrows(IOException.class, () -> parse("HTTP/1.1 200 OK\r\nContent-Length: 5000\r\n\r\n" + "x".repeat(5000), 1000));
        assertThrows(IOException.class, () -> parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + Integer.toHexString(5000) + "\r\n" + "x".repeat(5000) + "\r\n0\r\n\r\n", 1000));
        assertThrows(IOException.class, () -> parse("HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(5000), 1000));
    }

    @Test
    void brokenAnswersAreErrorsNotHangs() {
        for (String broken : new String[]{"", "garbage\r\n\r\n", "HTTP/1.1 abc OK\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort",
                "HTTP/1.1 200 OK\r\nContent-Length: nine\r\n\r\n", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n",
                "HTTP/1.1 200 OK\r\n" + "A".repeat(20000)}) {
            assertThrows(IOException.class, () -> parse(broken, 1000), broken.length() > 40 ? broken.substring(0, 40) : broken);
        }
    }

    @Test
    void aSlowSiteIsStoppedByTheDeadline() {
        var stuck = new java.io.InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                return 'a';
            }
        };
        var error = assertThrows(IOException.class, () -> PinnedHttps.read(stuck, 1000, System.nanoTime() + 200_000_000L));
        assertTrue(error.getMessage().contains("too long") || error.getMessage().contains("too long"), error.getMessage());
    }
}
