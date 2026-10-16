package net.xmilon.craftpy.python;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version of the Python API this CraftPy ships. The one place it is written is {@code API_VERSION} in worker.py
 * (next to {@code API_CHANGES}); Java reads it from there so the two never disagree.
 */
public final class PyApi {
    private static final Pattern LINE = Pattern.compile("(?m)^API_VERSION = \"(\\d+\\.\\d+)\"");
    private static volatile String version;

    private PyApi() {
    }

    /** {@code "2.1"}; {@code "0.0"} only when worker.py is missing from the jar. */
    public static String version() {
        String known = version;
        if (known == null) {
            known = version = read();
        }
        return known;
    }

    static String parse(String workerSource) {
        Matcher matcher = LINE.matcher(workerSource);
        return matcher.find() ? matcher.group(1) : "0.0";
    }

    private static String read() {
        try (InputStream in = PyApi.class.getResourceAsStream("/craftpy_runtime/worker.py")) {
            return in == null ? "0.0" : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "0.0";
        }
    }
}
