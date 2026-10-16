package net.xmilon.craftpy.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.util.CraftPyExecutors;

/**
 * Web access for Python code. The code never opens a connection: it asks, and this class decides and does the request.
 * Every request passes, in this order: the master switch, the owner's approved permission for the site, the access
 * lists, the address check (public addresses only), the rate limit, size and time limits, and the same checks again for
 * every redirect. Keys from the config are added here, so the module never holds them.
 */
public final class NetService {
    public record Result(boolean ok, int status, String text, Map<String, String> headers, String error) {
        static Result failed(String error) {
            return new Result(false, 0, "", Map.of(), error);
        }
    }

    /** Looks a name up; tests replace it. */
    public interface Resolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    static volatile Resolver resolver = host -> Arrays.asList(InetAddress.getAllByName(host));
    static volatile PinnedHttps.Connector connector = PinnedHttps.TLS;

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE");
    private static final int MAX_HOPS = 3;
    private static final Semaphore RUNNING = new Semaphore(4);
    private static final Map<String, ArrayDeque<Long>> RECENT = new HashMap<>();

    private NetService() {
    }

    public static CompletableFuture<Result> request(String owner, String method, String url, Map<String, String> headers,
                                                    String body, String keyName, int timeoutSeconds) {
        return CraftPyExecutors.supplyAsync(() -> {
            try {
                return requestNow(owner, method, url, headers, body, keyName, timeoutSeconds);
            } catch (RuntimeException e) {
                CraftPyLog.LOGGER.warn("Web request of {} failed: {}", owner, e.toString());
                return Result.failed("The request failed: " + e.getMessage());
            }
        }, CraftPyExecutors.io());
    }

    /** Blocking; the caller is a worker thread. Never throws for a refused or failed request: the error is in the result. */
    public static Result requestNow(String owner, String method, String url, Map<String, String> headers, String body,
                                    String keyName, int timeoutSeconds) {
        if (!AccessConfig.get(AccessConfig.NET_ENABLED)) {
            return Result.failed("Web access is switched off in craftpy-access.toml (net.enabled)");
        }
        String verb = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        if (!METHODS.contains(verb)) {
            return Result.failed("Method " + method + " is not allowed (GET, POST, PUT, DELETE)");
        }
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        if (payload != null && payload.length > AccessConfig.get(AccessConfig.MAX_REQUEST_KB) * 1024) {
            return Result.failed("The request body is larger than " + AccessConfig.get(AccessConfig.MAX_REQUEST_KB) + " KB");
        }
        Map<String, String> clean;
        try {
            clean = NetPolicy.cleanHeaders(headers == null ? Map.of() : headers);
        } catch (IllegalArgumentException e) {
            return Result.failed(e.getMessage());
        }
        String limited = rateLimit(owner);
        if (limited != null) {
            return Result.failed(limited);
        }
        if (!RUNNING.tryAcquire()) {
            return Result.failed("Too many web requests are running at once; try again in a moment");
        }
        try {
            int timeoutMs = Math.min(Math.max(timeoutSeconds, 1), AccessConfig.get(AccessConfig.TIMEOUT_SECONDS)) * 1000;
            int maxBytes = AccessConfig.get(AccessConfig.MAX_RESPONSE_KB) * 1024;
            String current = url;
            for (int hop = 0; hop <= MAX_HOPS; hop++) {
                URI uri;
                try {
                    uri = NetPolicy.parseUrl(current);
                } catch (IllegalArgumentException e) {
                    return Result.failed(e.getMessage());
                }
                String host = NetPolicy.hostOf(uri);
                String refused = checkSite(owner, host);
                if (refused != null) {
                    return Result.failed(refused);
                }
                List<InetAddress> addresses;
                try {
                    addresses = resolver.resolve(host);
                } catch (UnknownHostException e) {
                    return Result.failed("The site " + host + " could not be found");
                }
                if (addresses.isEmpty() || !addresses.stream().allMatch(NetPolicy::isPublic)) {
                    return Result.failed(host + " points to a private or local address: refused");
                }
                Map<String, String> sent = new java.util.LinkedHashMap<>(clean);
                if (keyName != null && !keyName.isBlank()) {
                    NetPolicy.Key key = keyFor(keyName, host);
                    if (key == null) {
                        return Result.failed("The key '" + keyName + "' does not exist or is not for " + host
                                + " (craftpy-access.toml, net.httpKeys)");
                    }
                    sent.keySet().removeIf(name -> name.equalsIgnoreCase(key.header()));
                    sent.put(key.header(), key.value());
                }
                PinnedHttps.Response response;
                try {
                    response = PinnedHttps.fetch(connector, verb, uri, addresses, sent, payload, timeoutMs, maxBytes);
                } catch (IOException e) {
                    return Result.failed(e.getMessage() == null ? e.toString() : e.getMessage());
                }
                if (response.status() >= 300 && response.status() < 400 && response.headers().containsKey("location")) {
                    try {
                        current = uri.resolve(response.headers().get("location")).toString();
                    } catch (IllegalArgumentException e) {
                        return Result.failed("The site sent a broken redirect");
                    }
                    if (response.status() != 307 && response.status() != 308) {
                        verb = "GET"; // like a browser; the body is not sent again
                        payload = null;
                    }
                    sent.clear();
                    continue;
                }
                return new Result(response.status() < 400, response.status(), response.text(), response.headers(), "");
            }
            return Result.failed("Too many redirects");
        } finally {
            RUNNING.release();
        }
    }

    /** Null when {@code owner} may use {@code host}; otherwise the reason, written for the person reading the console. */
    static String checkSite(String owner, String host) {
        NetPolicy.Zone zone = NetPolicy.zone(host, AccessConfig.strings(AccessConfig.ALLOWED_DOMAINS),
                AccessConfig.strings(AccessConfig.DANGER_DOMAINS));
        if (zone == NetPolicy.Zone.NONE) {
            return host + " is not on the list of sites the server allows (craftpy-access.toml)";
        }
        if (PermissionStore.netEntry(owner, host) != null) {
            return null;
        }
        if (PermissionStore.declared(owner).covering(host) != null) {
            return owner + " asked for " + host + " but it is not approved yet: open the Modules page (or the project's"
                    + " Permissions) and approve it" + (zone == NetPolicy.Zone.DANGER ? " - this site is in the danger zone" : "");
        }
        return owner + " did not declare " + host + ": add it to \"permissions\": {\"net\": [...]} in its project.json / module.json";
    }

    private static NetPolicy.Key keyFor(String name, String host) {
        for (String entry : AccessConfig.strings(AccessConfig.HTTP_KEYS)) {
            NetPolicy.Key key = NetPolicy.parseKey(entry);
            if (key != null && key.name().equals(name) && NetPolicy.covers(key.domain(), host)) {
                return key;
            }
        }
        return null;
    }

    private static String rateLimit(String owner) {
        long now = System.currentTimeMillis();
        int max = AccessConfig.get(AccessConfig.MAX_REQUESTS_PER_MINUTE);
        synchronized (RECENT) {
            ArrayDeque<Long> times = RECENT.computeIfAbsent(owner, k -> new ArrayDeque<>());
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) {
                times.pollFirst();
            }
            if (times.size() >= max) {
                return owner + " made " + max + " web requests in the last minute: slow down";
            }
            times.addLast(now);
        }
        return null;
    }

    /** For tests: forget the rate limit history. */
    static void resetRate() {
        synchronized (RECENT) {
            RECENT.clear();
        }
    }
}
