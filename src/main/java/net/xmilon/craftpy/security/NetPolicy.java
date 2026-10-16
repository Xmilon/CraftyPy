package net.xmilon.craftpy.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules for web access, as pure functions: which host a list entry covers, whether an address is public, what a URL
 * and a header may look like. No network, no config: the callers pass the lists in, so every rule is testable.
 */
public final class NetPolicy {
    public enum Zone { GENERAL, DANGER, NONE }

    private static final Pattern DOMAIN = Pattern.compile("(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("host", "content-length", "connection", "cookie", "set-cookie",
            "transfer-encoding", "upgrade", "te", "trailer", "expect", "accept-encoding", "user-agent", "proxy-authorization",
            "proxy-connection", "keep-alive", "x-forwarded-for", "x-real-ip", "forwarded");

    private NetPolicy() {
    }

    /** A lowercase domain name ({@code api.github.com}), or null when the text is not one (IP numbers are not). */
    public static String normalizeDomain(String text) {
        if (text == null) {
            return null;
        }
        String domain = text.strip().toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        return DOMAIN.matcher(domain).matches() ? domain : null;
    }

    /** True when {@code entry} is {@code host} or a parent domain of it: {@code github.com} covers {@code api.github.com}. */
    public static boolean covers(String entry, String host) {
        return entry != null && host != null && (host.equals(entry) || host.endsWith("." + entry));
    }

    /** The zone of a host. A host that is on both lists counts as danger, the stricter reading. */
    public static Zone zone(String host, List<String> general, List<String> danger) {
        for (String entry : danger) {
            if (covers(entry, host)) {
                return Zone.DANGER;
            }
        }
        for (String entry : general) {
            if (covers(entry, host)) {
                return Zone.GENERAL;
            }
        }
        return Zone.NONE;
    }

    /** The URL as a URI, or an IllegalArgumentException that says why not: https only, a domain name, no login, port 443. */
    public static URI parseUrl(String text) {
        if (text == null || text.length() > 2048 || text.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new IllegalArgumentException("The URL must be plain ASCII text of at most 2048 characters");
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a valid URL: " + e.getReason());
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only https:// addresses are allowed");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Addresses with a user name or password are not allowed");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("Only the normal https port (443) is allowed");
        }
        String host = normalizeDomain(uri.getHost());
        if (host == null) {
            throw new IllegalArgumentException("The address must use a site name, not an IP number or a local name");
        }
        return uri;
    }

    public static String hostOf(URI uri) {
        return normalizeDomain(uri.getHost());
    }

    /** False for every address that is not on the public internet: this computer, the home network, link-local, multicast. */
    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return !(first == 0 || first >= 240 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0) || (first == 198 && (second == 18 || second == 19)));
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return false; // unique local fc00::/7
            }
            boolean mapped = true; // ::ffff:a.b.c.d is the IPv4 address in disguise
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (java.net.UnknownHostException e) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Headers a module may send: valid names and values, none of the ones the connection itself owns. */
    public static Map<String, String> cleanHeaders(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        int total = 0;
        for (var entry : headers.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().strip();
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Header name '" + name + "' is not allowed");
            }
            if (FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT)) || name.toLowerCase(Locale.ROOT).startsWith("proxy-")
                    || name.toLowerCase(Locale.ROOT).startsWith("sec-")) {
                throw new IllegalArgumentException("Header '" + name + "' is set by CraftPy and cannot be sent");
            }
            if (value.length() > 2048 || value.chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0 || c > 0x7e)) {
                throw new IllegalArgumentException("Header '" + name + "' has a value that is not allowed");
            }
            total += name.length() + value.length();
            if (out.size() >= 30 || total > 8192) {
                throw new IllegalArgumentException("Too many or too large headers");
            }
            out.put(name, value);
        }
        return out;
    }

    /** One entry of {@code net.httpKeys}: {@code name=domain=Header: value}. */
    public record Key(String name, String domain, String header, String value) {
    }

    public static Key parseKey(String entry) {
        if (entry == null) {
            return null;
        }
        String[] parts = entry.split("=", 3);
        if (parts.length != 3) {
            return null;
        }
        String name = parts[0].strip();
        String domain = normalizeDomain(parts[1]);
        int colon = parts[2].indexOf(':');
        if (name.isEmpty() || domain == null || colon <= 0) {
            return null;
        }
        String header = parts[2].substring(0, colon).strip();
        String value = parts[2].substring(colon + 1).strip();
        return HEADER_NAME.matcher(header).matches() && !value.isEmpty() ? new Key(name, domain, header, value) : null;
    }
}
