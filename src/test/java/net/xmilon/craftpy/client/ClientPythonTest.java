package net.xmilon.craftpy.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A client-side project in a real client worker process: what it draws arrives in {@link ClientHud}. */
class ClientPythonTest {

    @AfterEach
    void cleanUp() {
        ClientPython.get().shutdown();
        ClientHost.reset();
        System.clearProperty("craftpy.home");
    }

    @Test
    void aClientProjectDrawsOnTheHudAndBindsKeysAndCannotTouchTheServer(@TempDir Path home) throws Exception {
        System.setProperty("craftpy.home", home.toString());
        assumeTrue(ClientPython.get().available(), "no Python on this computer");
        Path code = Files.createDirectories(home.resolve("pyforge/projects/cproj/code"));
        Files.writeString(code.resolve("main.py"), String.join("\n",
                "from craftpy import *",
                "from extras import label",
                "hud.text('title', label(), anchor='top_center', y=4, color='gold')",
                "hud.bar('mana', 40, max=100, anchor='bottom_left', x=4, y=-4, label='Mana')",
                "@keys.bind('ctrl+shift+m')",
                "def pressed(ctx):",
                "    hud.text('pressed', 'yes')",
                ""));
        Files.writeString(code.resolve("extras.py"), "def label():\n    return 'Hello from the client'\n");
        Files.writeString(code.getParent().resolve("project.json"), "{\"id\":\"cproj\",\"name\":\"C\",\"side\":\"client\"}");

        ClientPython.get().runProject("cproj");
        assertTrue(waitFor(() -> ClientHud.idsOf("cproj").containsAll(java.util.List.of("title", "mana"))),
                "the HUD elements did not arrive: " + ClientHud.idsOf("cproj"));
        assertTrue(waitFor(() -> ClientHost.isBound("ctrl+shift+m")), "the key was not registered");
        assertFalse(ClientHost.isBound("ctrl+k"));

        ClientPython.get().key("ctrl+shift+m");
        assertTrue(waitFor(() -> ClientHud.idsOf("cproj").contains("pressed")), "the key press did not reach the function");

        // the server side of the API is simply not there
        var refused = ClientHost.handle("world.set_block", new com.google.gson.JsonObject());
        assertFalse(refused.get("ok").getAsBoolean());
        assertTrue(refused.get("error").getAsString().contains("not available in client-side code"));
        assertEquals(3, ClientHud.idsOf("cproj").size());
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
