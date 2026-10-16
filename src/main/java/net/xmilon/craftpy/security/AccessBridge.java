package net.xmilon.craftpy.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The calls {@code http.*} and {@code files.*} of Python, shared by the server's host and the client's host. The owner (a
 * project id or {@code module:<id>}) comes from the worker, which sets it itself from the code that is running; every call
 * checks it against what was approved. Web requests answer at once ("started") and deliver the result later as the event
 * {@code http_response}, so a slow site never holds up the game.
 */
public final class AccessBridge {
    private static final Pattern OWNER = Pattern.compile("(module:)?[a-z][a-z0-9_]{0,63}");

    private AccessBridge() {
    }

    /** Null when {@code method} is not one of ours, so the host can go on with its own. */
    public static JsonObject handle(String method, JsonObject p, Consumer<JsonObject> deliver) {
        if (!method.startsWith("http.") && !method.startsWith("files.")) {
            return null;
        }
        String owner = string(p, "owner");
        if (!OWNER.matcher(owner).matches()) {
            return fail("Unknown owner");
        }
        try {
            return method.equals("http.request") ? request(owner, p, deliver) : files(owner, method, p);
        } catch (IOException | IllegalArgumentException e) {
            return fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static JsonObject request(String owner, JsonObject p, Consumer<JsonObject> deliver) {
        long id = p.has("id") && p.get("id").isJsonPrimitive() ? p.get("id").getAsLong() : -1;
        String url = string(p, "url");
        // refuse at once what is certain to be refused, so the developer sees the reason where the call is made
        if (!AccessConfig.get(AccessConfig.NET_ENABLED)) {
            return fail("Web access is switched off in craftpy-access.toml (net.enabled)");
        }
        String early;
        try {
            early = NetService.checkSite(owner, NetPolicy.hostOf(NetPolicy.parseUrl(url)));
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
        if (early != null) {
            return fail(early);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (p.has("headers") && p.get("headers").isJsonObject()) {
            p.getAsJsonObject("headers").entrySet().forEach(e -> headers.put(e.getKey(),
                    e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : ""));
        }
        String body = p.has("body") && p.get("body").isJsonPrimitive() ? p.get("body").getAsString() : null;
        String key = p.has("key") && p.get("key").isJsonPrimitive() ? p.get("key").getAsString() : null;
        int timeout = p.has("timeout") && p.get("timeout").isJsonPrimitive() ? p.get("timeout").getAsInt() : 10;
        NetService.request(owner, string(p, "method"), url, headers, body, key, timeout).thenAccept(result -> {
            JsonObject event = new JsonObject();
            event.addProperty("id", id);
            event.addProperty("owner", owner);
            event.addProperty("ok", result.ok());
            event.addProperty("status", result.status());
            event.addProperty("text", result.text());
            event.addProperty("error", result.error());
            JsonObject received = new JsonObject();
            result.headers().forEach(received::addProperty);
            event.add("headers", received);
            deliver.accept(event);
        });
        JsonObject started = new JsonObject();
        started.addProperty("ok", true);
        return started;
    }

    private static JsonObject files(String owner, String method, JsonObject p) throws IOException {
        if (!AccessConfig.get(AccessConfig.FILES_ENABLED)) {
            return fail("files.* is switched off in craftpy-access.toml (files.enabled)");
        }
        if (!PermissionStore.filesAllowed(owner)) {
            return fail(PermissionStore.declared(owner).files
                    ? owner + " asked for its data folder but it is not approved yet: approve it on the Modules page"
                    : owner + " did not declare files: add \"permissions\": {\"files\": true} to its project.json / module.json");
        }
        String name = string(p, "name");
        String folder = string(p, "folder");
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        switch (method) {
            case "files.read" -> {
                try {
                    out.addProperty("text", FileStore.read(owner, name, folder));
                } catch (IOException e) {
                    JsonObject missing = fail(e.getMessage());
                    missing.addProperty("missing", e.getMessage() != null && e.getMessage().startsWith("No file"));
                    return missing;
                }
            }
            case "files.write" -> FileStore.write(owner, name, string(p, "text"));
            case "files.delete" -> out.addProperty("deleted", FileStore.delete(owner, name));
            case "files.exists" -> out.addProperty("exists", FileStore.exists(owner, name, folder));
            case "files.list" -> {
                JsonArray names = new JsonArray();
                FileStore.list(owner, folder).forEach(names::add);
                out.add("names", names);
            }
            default -> {
                return fail("Unknown call " + method);
            }
        }
        return out;
    }

    private static String string(JsonObject p, String key) {
        JsonElement value = p.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static JsonObject fail(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", message);
        return out;
    }
}
