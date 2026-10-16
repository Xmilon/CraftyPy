package net.xmilon.craftpy.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * One HTTPS request over a socket to an address that was checked beforehand. The usual client looks the name up again
 * itself, so a name that answered with a public address for the check could answer with the player's own computer for the
 * connection (DNS rebinding). Here the connection goes to the checked address, while the certificate is still checked
 * against the site name. HTTP/1.1, {@code Connection: close}, no compression, no redirects (the caller follows them,
 * checking every hop).
 */
public final class PinnedHttps {
    public record Response(int status, Map<String, String> headers, byte[] body) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** Opens the connection; tests replace it with a plain local socket. */
    public interface Connector {
        Socket connect(String host, InetAddress address, int timeoutMs) throws IOException;
    }

    public static final Connector TLS = (host, address, timeoutMs) -> {
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(address, 443), timeoutMs);
            plain.setSoTimeout(timeoutMs);
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, 443, true);
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(List.of(new SNIHostName(host)));
            parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            ssl.setSSLParameters(parameters);
            ssl.startHandshake();
            return ssl;
        } catch (IOException | RuntimeException e) {
            plain.close();
            throw e;
        }
    };

    private PinnedHttps() {
    }

    public static Response fetch(Connector connector, String method, URI uri, List<InetAddress> checked,
                                 Map<String, String> headers, byte[] body, int timeoutMs, int maxBytes) throws IOException {
        String host = NetPolicy.hostOf(uri);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        IOException last = new IOException("No address to connect to");
        for (InetAddress address : checked) {
            try (Socket socket = connector.connect(host, address, timeoutMs)) {
                socket.setSoTimeout(timeoutMs);
                write(socket.getOutputStream(), method, uri, host, headers, body);
                return read(socket.getInputStream(), maxBytes, deadline);
            } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
                last = e;
            }
        }
        throw last;
    }

    private static void write(OutputStream out, String method, URI uri, String host, Map<String, String> headers, byte[] body)
            throws IOException {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        StringBuilder head = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(host).append("\r\n");
        head.append("User-Agent: CraftPy\r\nAccept-Encoding: identity\r\nConnection: close\r\n");
        headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        if (body != null) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        if (body != null) {
            out.write(body);
        }
        out.flush();
    }

    /** Reads status line, headers and body (content-length, chunked or until the server closes); stops at {@code maxBytes}. */
    static Response read(InputStream in, int maxBytes, long deadline) throws IOException {
        String status = line(in, deadline);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) {
            throw new IOException("The site did not answer with HTTP");
        }
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("The site sent a broken status line");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String header = line(in, deadline); !header.isEmpty(); header = line(in, deadline)) {
            int colon = header.indexOf(':');
            if (colon > 0 && headers.size() < 60) {
                headers.merge(header.substring(0, colon).strip().toLowerCase(Locale.ROOT), header.substring(colon + 1).strip(),
                        (a, b) -> a + ", " + b);
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if ("chunked".equalsIgnoreCase(headers.getOrDefault("transfer-encoding", ""))) {
            while (true) {
                String sizeLine = line(in, deadline);
                int semicolon = sizeLine.indexOf(';');
                long size;
                try {
                    size = Long.parseLong((semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).strip(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("The site sent a broken chunk");
                }
                if (size == 0) {
                    break;
                }
                copy(in, body, size, maxBytes, deadline);
                line(in, deadline); // the line break after a chunk
            }
        } else if (headers.containsKey("content-length")) {
            long size;
            try {
                size = Long.parseLong(headers.get("content-length").strip());
            } catch (NumberFormatException e) {
                throw new IOException("The site sent a broken content-length");
            }
            if (size > maxBytes) {
                throw new IOException("The answer is larger than " + maxBytes / 1024 + " KB");
            }
            copy(in, body, size, maxBytes, deadline);
        } else {
            copy(in, body, Long.MAX_VALUE, maxBytes, deadline);
        }
        return new Response(code, headers, body.toByteArray());
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, long count, int maxBytes, long deadline) throws IOException {
        byte[] buffer = new byte[8192];
        long left = count;
        while (left > 0) {
            checkDeadline(deadline);
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                if (count == Long.MAX_VALUE) {
                    return; // no length given: the end of the stream is the end of the body
                }
                throw new IOException("The connection closed before the answer was complete");
            }
            out.write(buffer, 0, read);
            left -= read;
            if (out.size() > maxBytes) {
                throw new IOException("The answer is larger than " + maxBytes / 1024 + " KB");
            }
        }
    }

    private static String line(InputStream in, long deadline) throws IOException {
        StringBuilder text = new StringBuilder();
        while (true) {
            checkDeadline(deadline);
            int c = in.read();
            if (c < 0) {
                if (text.length() == 0) {
                    throw new IOException("The connection closed unexpectedly");
                }
                break;
            }
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                text.append((char) c);
            }
            if (text.length() > 16 * 1024) {
                throw new IOException("The site sent a line that is too long");
            }
        }
        return text.toString();
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (System.nanoTime() > deadline) {
            throw new java.net.SocketTimeoutException("The site took too long");
        }
    }
}
