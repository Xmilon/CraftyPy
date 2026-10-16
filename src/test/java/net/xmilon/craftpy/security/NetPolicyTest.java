package net.xmilon.craftpy.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetPolicyTest {

    @Test
    void domainsAreNamesNotNumbers() {
        assertEquals("api.github.com", NetPolicy.normalizeDomain(" API.GitHub.com. "));
        for (String bad : new String[]{"127.0.0.1", "localhost", "[::1]", "a b.com", "github", "-x.com", "x..com", "", null,
                "evil.com/path", "evil.com:8080", "évil.com"}) {
            assertNull(NetPolicy.normalizeDomain(bad), String.valueOf(bad));
        }
    }

    @Test
    void anEntryCoversItsSubdomainsOnly() {
        assertTrue(NetPolicy.covers("github.com", "github.com"));
        assertTrue(NetPolicy.covers("github.com", "api.github.com"));
        assertFalse(NetPolicy.covers("github.com", "notgithub.com"));
        assertFalse(NetPolicy.covers("github.com", "github.com.evil.org"));
        assertFalse(NetPolicy.covers("api.github.com", "github.com"));
    }

    @Test
    void dangerBeatsGeneral() {
        List<String> general = List.of("wikipedia.org", "github.com");
        List<String> danger = List.of("github.com", "pastebin.com");
        assertEquals(NetPolicy.Zone.GENERAL, NetPolicy.zone("en.wikipedia.org", general, danger));
        assertEquals(NetPolicy.Zone.DANGER, NetPolicy.zone("api.github.com", general, danger));
        assertEquals(NetPolicy.Zone.DANGER, NetPolicy.zone("pastebin.com", general, danger));
        assertEquals(NetPolicy.Zone.NONE, NetPolicy.zone("example.net", general, danger));
    }

    @Test
    void onlyPlainHttpsUrlsToNamedSites() {
        assertEquals("api.github.com", NetPolicy.hostOf(NetPolicy.parseUrl("https://api.github.com/repos?x=1")));
        assertEquals("a.com", NetPolicy.hostOf(NetPolicy.parseUrl("https://A.com:443/")));
        for (String bad : new String[]{"http://a.com/", "ftp://a.com/", "file:///etc/passwd", "https://127.0.0.1/", "https://localhost/",
                "https://user:pw@a.com/", "https://a.com:8443/", "https://[::1]/", "https://0x7f.1/", "//a.com/", "a.com",
                "https://a.com/é", "https://a.com/ x", "https://" + "a".repeat(2100) + ".com/", ""}) {
            String url = bad;
            assertThrows(IllegalArgumentException.class, () -> NetPolicy.parseUrl(url), bad);
        }
    }

    @Test
    void addressesOfThePrivateWorldAreRefused() throws Exception {
        for (String bad : new String[]{"127.0.0.1", "127.1.2.3", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "198.18.0.1", "::1", "::",
                "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "ff02::1"}) {
            assertFalse(NetPolicy.isPublic(InetAddress.getByName(bad)), bad);
        }
        for (String good : new String[]{"8.8.8.8", "1.1.1.1", "140.82.112.3", "172.32.0.1", "2606:4700::1111", "::ffff:8.8.8.8"}) {
            assertTrue(NetPolicy.isPublic(InetAddress.getByName(good)), good);
        }
    }

    @Test
    void headersCannotTouchTheConnection() {
        Map<String, String> ok = new LinkedHashMap<>();
        ok.put("Accept", "application/json");
        ok.put("X-Api-Version", "2");
        assertEquals(ok, NetPolicy.cleanHeaders(ok));
        for (String name : new String[]{"Host", "cookie", "Content-Length", "Transfer-Encoding", "Connection", "Proxy-Foo", "Sec-Fetch-Mode",
                "X-Forwarded-For", "Bad Name", "A:B", ""}) {
            assertThrows(IllegalArgumentException.class, () -> NetPolicy.cleanHeaders(Map.of(name, "x")), name);
        }
        assertThrows(IllegalArgumentException.class, () -> NetPolicy.cleanHeaders(Map.of("X-A", "one\r\nHost: evil")));
        assertThrows(IllegalArgumentException.class, () -> NetPolicy.cleanHeaders(Map.of("X-A", "café")));
    }

    @Test
    void keyEntries() {
        var key = NetPolicy.parseKey("openai=api.openai.com=Authorization: Bearer sk-1");
        assertEquals("openai", key.name());
        assertEquals("api.openai.com", key.domain());
        assertEquals("Authorization", key.header());
        assertEquals("Bearer sk-1", key.value());
        for (String bad : new String[]{"nokey", "a=b=c", "a=localhost=H: v", "=x.com=H: v", "a=x.com=H:", "a=x.com=Bad Name: v", null}) {
            assertNull(NetPolicy.parseKey(bad), String.valueOf(bad));
        }
    }
}
