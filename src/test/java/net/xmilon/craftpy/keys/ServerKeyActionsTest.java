package net.xmilon.craftpy.keys;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerKeyActionsTest {
    @AfterEach void clear() { ServerKeyActions.clear(); }

    @Test void rejectsForgedStaleAndFloodedPackets() {
        var descriptions = new JsonArray(); descriptions.add(new KeyAction("open", "Open", "Tools", "h").json());
        ServerKeyActions.replace("module:shop", descriptions);
        long revision = JsonParser.parseString(ServerKeyActions.manifest()).getAsJsonArray().get(0).getAsJsonObject().get("revision").getAsLong();
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        assertFalse(ServerKeyActions.accept(alice, "module:other", "open", revision, 0));
        assertFalse(ServerKeyActions.accept(alice, "module:shop", "buy", revision, 0));
        assertFalse(ServerKeyActions.accept(alice, "module:shop", "open", revision - 1, 0));
        assertTrue(ServerKeyActions.accept(alice, "module:shop", "open", revision, 0));
        assertFalse(ServerKeyActions.accept(alice, "module:shop", "open", revision, 1));
        assertTrue(ServerKeyActions.accept(bob, "module:shop", "open", revision, 1));
        assertTrue(ServerKeyActions.accept(alice, "module:shop", "open", revision, 100_000_000));
        ServerKeyActions.forget("module:shop");
        assertFalse(ServerKeyActions.accept(alice, "module:shop", "open", revision, 300_000_000));
        ServerKeyActions.replace("module:shop", descriptions);
        assertFalse(ServerKeyActions.accept(alice, "module:shop", "open", revision, 400_000_000));
    }
}
