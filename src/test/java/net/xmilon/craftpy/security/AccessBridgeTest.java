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

    /** The owner is what the host knows of the worker; tests that put an owner in the JSON call as that owner. */
    private JsonObject call(String method, String json) {
        JsonObject params = JsonParser.parseString(json).getAsJsonObject();
        String owner = params.has("owner") ? params.get("owner").getAsString() : "a";
        return AccessBridge.handle(owner, method, params, delivered::add);
    }

    @Test
    void anOwnerInTheParamsIsIgnored() {
        PermissionStore.declare("module:victim", new ModulePermissions(List.of(), true));
        PermissionStore.approve("module:victim", new ModulePermissions(List.of(), true));
        var write = AccessBridge.handle("module:victim", "files.write",
                JsonParser.parseString("{\"name\":\"secret.txt\",\"text\":\"VICTIM_PRIVATE_SENTINEL\"}").getAsJsonObject(),
                delivered::add);
        assertTrue(write.get("ok").getAsBoolean(), write.toString());
        // the attacker's worker claims to be the victim: the host answers as the attacker, who has no data folder
        var stolen = AccessBridge.handle("module:attacker", "files.read",
                JsonParser.parseString("{\"owner\":\"module:victim\",\"name\":\"secret.txt\"}").getAsJsonObject(),
                delivered::add);
        assertFalse(stolen.get("ok").getAsBoolean(), stolen.toString());
        assertTrue(stolen.get("error").getAsString().startsWith("module:attacker"), stolen.toString());
    }

    @Test
    void sharedFoldersNeedTheirOwnApproval() {
        PermissionStore.declare("module:notes", new ModulePermissions(List.of(), true));
        PermissionStore.approve("module:notes", new ModulePermissions(List.of(), true));
        var read = call("files.list", "{\"owner\":\"module:notes\",\"folder\":\"music\"}");
        assertFalse(read.get("ok").getAsBoolean(), read.toString());
        assertTrue(read.get("error").getAsString().contains("shared folder"), read.toString());
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
        assertTrue(text.startsWith("⚠ This module wants to access these sites: en.wikipedia.org, api.github.com, example.net"), text);
        assertTrue(text.contains("DANGER ZONE: api.github.com") && text.contains("the server does not allow: example.net"), text);
        assertTrue(text.contains("data folder") && text.endsWith("continue at your own risk!"), text);
        assertTrue(PermissionLabels.warning(new ModulePermissions(List.of(), true), general, danger).contains("data folder"));
        assertTrue(PermissionLabels.readmeBlock(new ModulePermissions(List.of("wikipedia.org"), false)).startsWith("> **"));
        ModulePermissions trusted = new ModulePermissions();
        trusted.java = true;
        trusted.commands = true;
        String full = PermissionLabels.warning(trusted, general, danger);
        assertTrue(full.contains("FULL TRUST") && full.contains("server commands"), full);
    }

    @Test
    void onlyProjectsMadeHereGetWhatTheyDeclare() throws Exception {
        Path projects = java.nio.file.Files.createDirectories(
                Path.of(System.getProperty("craftpy.home")).resolve("pyforge/projects/old"));
        ModulePermissions asked = new ModulePermissions(List.of(), true);
        // projects that were here before the list existed stay trusted
        PermissionStore.declareProject("old", asked);
        assertTrue(PermissionStore.filesAllowed("old"), projects.toString());
        // a module opened as a project (or a folder copied in) gets nothing until it is trusted or approved
        PermissionStore.distrust("unpacked");
        PermissionStore.declareProject("unpacked", asked);
        assertFalse(PermissionStore.filesAllowed("unpacked"));
        PermissionStore.trust("unpacked");
        PermissionStore.forget(); // the list is saved
        PermissionStore.declareProject("unpacked", asked);
        assertTrue(PermissionStore.filesAllowed("unpacked"));
        // trust taken away again: the self-approval goes with it
        PermissionStore.distrust("unpacked");
        PermissionStore.declareProject("unpacked", asked);
        assertFalse(PermissionStore.filesAllowed("unpacked"));
    }

    @Test
    void anApprovalHoldsOnlyForTheModuleFileItWasGivenTo() {
        ModulePermissions asked = new ModulePermissions(List.of(), true);
        PermissionStore.declare("module:notes", asked, "aaa");
        PermissionStore.approve("module:notes", asked);
        assertTrue(PermissionStore.filesAllowed("module:notes"));
        // the .cpm changed (a new version, or someone edited it): it asks again
        PermissionStore.declare("module:notes", asked, "bbb");
        assertTrue(PermissionStore.changedSinceApproval("module:notes"));
        assertFalse(PermissionStore.filesAllowed("module:notes"));
        assertFalse(PermissionStore.fullyApproved("module:notes"));
        // it survives a restart: the hash is saved with the approval
        PermissionStore.forget();
        PermissionStore.declare("module:notes", asked, "aaa");
        assertTrue(PermissionStore.filesAllowed("module:notes"));
        PermissionStore.declare("module:notes", asked, "bbb");
        PermissionStore.approve("module:notes", asked);
        assertTrue(PermissionStore.filesAllowed("module:notes"));
    }

    @Test
    void commandsAndJavaNeedDeclaringAndApproving() {
        ModulePermissions asked = new ModulePermissions();
        asked.commands = true;
        asked.java = true;
        assertFalse(PermissionStore.commandsAllowed("module:x"));
        PermissionStore.declare("module:x", asked);
        assertFalse(PermissionStore.commandsAllowed("module:x"));
        assertFalse(PermissionStore.javaAllowed("module:x"));
        assertFalse(PermissionStore.fullyApproved("module:x"));
        PermissionStore.approve("module:x", asked);
        assertTrue(PermissionStore.commandsAllowed("module:x"));
        assertTrue(PermissionStore.javaAllowed("module:x"));
        assertTrue(PermissionStore.fullyApproved("module:x"));
    }
}
