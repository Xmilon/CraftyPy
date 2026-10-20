package net.xmilon.craftpy.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole request path against a local fake server: nothing here touches the internet. */
class NetServiceTest {
    private ServerSocket server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile Function<String, String> handler = request -> "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi";
    private final List<String> resolved = new ArrayList<>();

    @BeforeEach
    void start(@TempDir Path home) throws IOException {
        System.setProperty("craftpy.home", home.toString());
        PermissionStore.forget();
        NetService.resetRate();
        server = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    String request = readRequest(socket.getInputStream());
                    requests.add(request);
                    socket.getOutputStream().write(handler.apply(request).getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (IOException closed) {
                    // the test ended
                }
            }
        }, "fake-server");
        thread.setDaemon(true);
        thread.start();
        NetService.resolver = host -> {
            resolved.add(host);
            return List.of(InetAddress.getByAddress(new byte[]{8, 8, 8, 8}));
        };
        NetService.connector = (host, address, timeout) -> new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        NetService.resolver = host -> java.util.Arrays.asList(InetAddress.getAllByName(host));
        NetService.connector = PinnedHttps.TLS;
        PermissionStore.forget();
        System.clearProperty("craftpy.home");
    }

    private static String readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last4 = 0;
        while (true) {
            int c = in.read();
            if (c < 0) {
                break;
            }
            head.write(c);
            last4 = (last4 << 8) | c;
            if (last4 == 0x0d0a0d0a) {
                break;
            }
        }
        String text = head.toString(StandardCharsets.UTF_8);
        int at = text.toLowerCase().indexOf("content-length:");
        if (at >= 0) {
            int length = Integer.parseInt(text.substring(at + 15, text.indexOf('\r', at)).strip());
            byte[] body = in.readNBytes(length);
            text += new String(body, StandardCharsets.UTF_8);
        }
        return text;
    }

    private static void allow(String owner, String... sites) {
        PermissionStore.declare(owner, new ModulePermissions(List.of(sites), false));
        PermissionStore.approve(owner, new ModulePermissions(List.of(sites), false));
    }

    private static NetService.Result get(String owner, String url) {
        return NetService.requestNow(owner, "GET", url, Map.of(), null, null, 5);
    }

    @Test
    void anApprovedModuleReachesAListedSite() {
        allow("module:weather", "open-meteo.com");
        var result = get("module:weather", "https://api.open-meteo.com/v1/forecast?x=1");
        assertTrue(result.ok(), result.error());
        assertEquals("hi", result.text());
        assertEquals(200, result.status());
        assertTrue(requests.get(0).startsWith("GET /v1/forecast?x=1 HTTP/1.1\r\nHost: api.open-meteo.com\r\n"), requests.get(0));
        assertTrue(requests.get(0).contains("Connection: close"));
    }

    @Test
    void askingIsNotHaving() {
        PermissionStore.declare("module:a", new ModulePermissions(List.of("wikipedia.org"), false));
        var unapproved = get("module:a", "https://en.wikipedia.org/");
        assertFalse(unapproved.ok());
        assertTrue(unapproved.error().contains("not approved yet"), unapproved.error());

        var undeclared = get("module:b", "https://en.wikipedia.org/");
        assertTrue(undeclared.error().contains("did not declare"), undeclared.error());

        allow("module:c", "wikipedia.org");
        var otherSite = get("module:c", "https://www.bbc.com/");
        assertTrue(otherSite.error().contains("did not declare"), otherSite.error()); // approved for wikipedia, not for bbc
        assertTrue(requests.isEmpty() && resolved.isEmpty(), "nothing was looked up or sent: " + requests);
    }

    @Test
    void aNewVersionAskingForMoreDoesNotInheritTheOldApproval() {
        allow("module:v", "wikipedia.org");
        PermissionStore.declare("module:v", new ModulePermissions(List.of("wikipedia.org", "bbc.com"), false));
        assertTrue(get("module:v", "https://en.wikipedia.org/").ok());
        assertFalse(get("module:v", "https://www.bbc.com/").ok());
        assertFalse(PermissionStore.fullyApproved("module:v"));
    }

    @Test
    void theServerListsDecideEvenForApprovedModules() {
        allow("module:x", "example.net", "evil.example");
        var result = get("module:x", "https://example.net/");
        assertFalse(result.ok());
        assertTrue(result.error().contains("not on the list"), result.error());
    }

    @Test
    void dangerZoneSitesNeedApprovalTooAndWorkWhenApproved() {
        PermissionStore.declare("module:g", new ModulePermissions(List.of("api.github.com"), false));
        assertFalse(get("module:g", "https://api.github.com/zen").ok());
        assertTrue(NetService.checkSite("module:g", "api.github.com").contains("danger zone"));
        PermissionStore.approve("module:g", new ModulePermissions(List.of("api.github.com"), false));
        assertTrue(get("module:g", "https://api.github.com/zen").ok());
    }

    @Test
    void addressesOfThisComputerAndTheHomeNetworkAreRefused() {
        allow("module:p", "wikipedia.org");
        for (String address : new String[]{"127.0.0.1", "192.168.0.10", "10.1.2.3", "169.254.169.254"}) {
            NetService.resolver = host -> List.of(InetAddress.getByAddress(parts(address)));
            var result = get("module:p", "https://en.wikipedia.org/");
            assertFalse(result.ok(), address);
            assertTrue(result.error().contains("private or local"), result.error());
        }
        // one bad answer among good ones is enough to refuse
        NetService.resolver = host -> List.of(InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        assertFalse(get("module:p", "https://en.wikipedia.org/").ok());
        NetService.resolver = host -> {
            throw new UnknownHostException(host);
        };
        assertTrue(get("module:p", "https://en.wikipedia.org/").error().contains("could not be found"));
        assertTrue(requests.isEmpty());
    }

    private static byte[] parts(String ip) {
        String[] p = ip.split("\\.");
        return new byte[]{(byte) Integer.parseInt(p[0]), (byte) Integer.parseInt(p[1]), (byte) Integer.parseInt(p[2]), (byte) Integer.parseInt(p[3])};
    }

    @Test
    void redirectsAreFollowedOnlyToSitesThatPassTheSameChecks() {
        allow("module:r", "wikipedia.org");
        handler = request -> request.startsWith("GET /start")
                ? "HTTP/1.1 302 Found\r\nLocation: /final\r\nContent-Length: 0\r\n\r\n"
                : "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndone";
        var followed = get("module:r", "https://en.wikipedia.org/start");
        assertTrue(followed.ok() && followed.text().equals("done"), followed.error());
        assertEquals(2, requests.size());
        assertTrue(requests.get(1).startsWith("GET /final "), requests.get(1));

        requests.clear();
        handler = request -> "HTTP/1.1 301 Moved\r\nLocation: https://127.0.0.1/admin\r\nContent-Length: 0\r\n\r\n";
        var toLocal = get("module:r", "https://en.wikipedia.org/start");
        assertFalse(toLocal.ok());
        handler = request -> "HTTP/1.1 301 Moved\r\nLocation: https://www.bbc.com/\r\nContent-Length: 0\r\n\r\n";
        var toOther = get("module:r", "https://en.wikipedia.org/start");
        assertTrue(toOther.error().contains("did not declare"), toOther.error());
        handler = request -> "HTTP/1.1 302 Found\r\nLocation: /again\r\nContent-Length: 0\r\n\r\n";
        assertEquals("Too many redirects", get("module:r", "https://en.wikipedia.org/loop").error());
    }

    @Test
    void postBodiesAndHeadersAreSentAndForbiddenHeadersRefused() {
        allow("module:post", "httpbin.org");
        var result = NetService.requestNow("module:post", "POST", "https://httpbin.org/post", Map.of("X-Test", "1"),
                "{\"a\":1}", null, 5);
        assertTrue(result.ok(), result.error());
        String sent = requests.get(0);
        assertTrue(sent.startsWith("POST /post HTTP/1.1") && sent.contains("X-Test: 1") && sent.contains("Content-Length: 7")
                && sent.endsWith("{\"a\":1}"), sent);
        assertFalse(NetService.requestNow("module:post", "POST", "https://httpbin.org/post", Map.of("Cookie", "x"), "", null, 5).ok());
        assertFalse(NetService.requestNow("module:post", "TRACE", "https://httpbin.org/", Map.of(), null, null, 5).ok());
        assertFalse(NetService.requestNow("module:post", "GET", "https://httpbin.org/", Map.of(), null, "nokey", 5).ok());
    }

    @Test
    void tooLargeAnswersAndErrorsAreReported() {
        allow("module:big", "httpbin.org");
        handler = request -> "HTTP/1.1 200 OK\r\nContent-Length: 99999999\r\n\r\n";
        var big = get("module:big", "https://httpbin.org/big");
        assertFalse(big.ok());
        assertTrue(big.error().contains("larger than"), big.error());
        handler = request -> "HTTP/1.1 404 Not Found\r\nContent-Length: 3\r\n\r\nnah";
        var missing = get("module:big", "https://httpbin.org/none");
        assertFalse(missing.ok());
        assertEquals(404, missing.status());
        assertEquals("nah", missing.text());
    }

    @Test
    void aModuleCannotFloodRequests() {
        allow("module:flood", "httpbin.org");
        int allowed = AccessConfig.get(AccessConfig.MAX_REQUESTS_PER_MINUTE);
        for (int i = 0; i < allowed; i++) {
            assertTrue(get("module:flood", "https://httpbin.org/").ok());
        }
        var refused = get("module:flood", "https://httpbin.org/");
        assertFalse(refused.ok());
        assertTrue(refused.error().contains("slow down"), refused.error());
        allow("module:other", "httpbin.org");
        assertTrue(get("module:other", "https://httpbin.org/").ok()); // another module has its own allowance
    }
}
