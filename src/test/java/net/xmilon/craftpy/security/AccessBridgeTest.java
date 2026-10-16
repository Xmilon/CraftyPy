package net.xmilon.craftpy.security;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessBridgeTest {
    private final List<JsonObject> delivered = new CopyOnWriteArrayList<>();

    @BeforeEach
    void home(@TempDir Path dir) {
        System.setProperty("craftpy.home", dir.toString());
        PermissionStore.forget();
    }

    @AfterEach
    void clear() {
        System.clearProperty("craftpy.home");
        PermissionStore.forget();
    }

    private JsonObject call(String method, String json) {
        return AccessBridge.handle(method, JsonParser.parseString(json).getAsJsonObject(), delivered::add);
    }

    @Test
    void otherCallsAreLeftToTheHost() {
        assertNull(call("world.get_block", "{}"));
        assertNull(call("storage.get", "{}"));
    }

    @Test
    void filesNeedDeclaringAndApproving() {
        String write = "{\"owner\":\"module:notes\",\"name\":\"a.txt\",\"text\":\"hi\"}";
        var none = call("files.write", write);
        assertFalse(none.get("ok").getAsBoolean());
        assertTrue(none.get("error").getAsString().contains("did not declare"), none.toString());

        PermissionStore.declare("module:notes", new ModulePermissions(List.of(), true));
        assertTrue(call("files.write", write).get("error").getAsString().contains("not approved yet"));

        PermissionStore.approve("module:notes", new ModulePermissions(List.of(), true));
        assertTrue(call("files.write", write).get("ok").getAsBoolean());
        assertEquals("hi", call("files.read", "{\"owner\":\"module:notes\",\"name\":\"a.txt\"}").get("text").getAsString());
        assertTrue(call("files.exists", "{\"owner\":\"module:notes\",\"name\":\"a.txt\"}").get("exists").getAsBoolean());
        assertEquals(1, call("files.list", "{\"owner\":\"module:notes\"}").getAsJsonArray("names").size());
        var missing = call("files.read", "{\"owner\":\"module:notes\",\"name\":\"nope.txt\"}");
        assertFalse(missing.get("ok").getAsBoolean());
        assertTrue(missing.get("missing").getAsBoolean());
        assertTrue(call("files.delete", "{\"owner\":\"module:notes\",\"name\":\"a.txt\"}").get("deleted").getAsBoolean());
        var bad = call("files.read", "{\"owner\":\"module:notes\",\"name\":\"../../x\"}");
        assertFalse(bad.get("ok").getAsBoolean());
    }

    @Test
    void ownersMustLookLikeOwners() {
        for (String owner : new String[]{"", "../x", "module:../x", "Module:A", "a b", "x".repeat(100)}) {
            var result = call("files.read", "{\"owner\":\"" + owner + "\",\"name\":\"a.txt\"}");
            assertFalse(result.get("ok").getAsBoolean(), owner);
            assertEquals("Unknown owner", result.get("error").getAsString());
        }
    }

    @Test
    void webCallsAreRefusedUpFrontWithTheReason() {
        var undeclared = call("http.request", "{\"owner\":\"a\",\"id\":1,\"method\":\"GET\",\"url\":\"https://en.wikipedia.org/\"}");
        assertFalse(undeclared.get("ok").getAsBoolean());
        assertTrue(undeclared.get("error").getAsString().contains("did not declare"));
        var http = call("http.request", "{\"owner\":\"a\",\"id\":1,\"method\":\"GET\",\"url\":\"http://en.wikipedia.org/\"}");
        assertTrue(http.get("error").getAsString().contains("https"));
        var local = call("http.request", "{\"owner\":\"a\",\"id\":1,\"method\":\"GET\",\"url\":\"https://127.0.0.1/\"}");
        assertFalse(local.get("ok").getAsBoolean());
        assertTrue(delivered.isEmpty());
    }

    @Test
    void theWarningNamesTheSitesAndTheDangerZone() {
        var general = List.of("wikipedia.org");
        var danger = List.of("github.com");
        assertEquals("", PermissionLabels.warning(new ModulePermissions(), general, danger));
        String text = PermissionLabels.warning(new ModulePermissions(List.of("en.wikipedia.org", "api.github.com", "example.net"), true), general, danger);
        assertTrue(text.startsWith("⚠ This module is trying to access these sites: en.wikipedia.org, api.github.com, example.net"), text);
        assertTrue(text.contains("DANGER ZONE: api.github.com") && text.contains("the server does not allow: example.net"), text);
        assertTrue(text.contains("data folder") && text.endsWith("continue at your own risk!"), text);
        assertTrue(PermissionLabels.warning(new ModulePermissions(List.of(), true), general, danger).contains("data folder"));
        assertTrue(PermissionLabels.readmeBlock(new ModulePermissions(List.of("wikipedia.org"), false)).startsWith("> **"));
    }
}
