package net.xmilon.craftpy.keys;

import java.nio.file.Path;
import java.util.List;
import net.xmilon.craftpy.client.KeyCombo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BindingStoreTest {
    @TempDir Path directory;
    private final KeyAction open = new KeyAction("open", "Open window", "Tools", "ctrl+h");

    @Test void remapsSurviveDefaultsReloadDisableAndRestart() throws Exception {
        Path file = directory.resolve("keys.json");
        var store = new BindingStore(file);
        var id = new BindingStore.Id("client", "jukebox", "open");
        store.replace("client", "jukebox", List.of(open));
        store.assign(id, "alt+ctrl+SHIFT+j");
        store.deactivate("client", "jukebox");
        assertTrue(store.matching("ctrl+shift+alt+j").isEmpty());
        store.replace("client", "jukebox", List.of(new KeyAction("open", "Music", "Tools", "j")));
        assertEquals(id, store.matching("ctrl+shift+alt+j").getFirst().id());
        var restarted = new BindingStore(file);
        assertFalse(restarted.entries().getFirst().active());
        restarted.replace("client", "jukebox", List.of(open));
        assertEquals("ctrl+shift+alt+j", restarted.entries().getFirst().assigned());
        restarted.assign(id, "");
        assertTrue(new BindingStore(file).entries().getFirst().assigned().isEmpty());
    }

    @Test void ownersAndServersAreIndependentAndConflictsDoNotEraseActions() throws Exception {
        var store = new BindingStore(directory.resolve("keys.json"));
        store.replace("server:a", "module:a", List.of(open));
        store.replace("server:a", "module:b", List.of(open));
        store.replace("server:b", "module:a", List.of(open));
        store.assign(new BindingStore.Id("server:a", "module:a", "open"), "f8");
        assertEquals(2, store.matching("ctrl+h").size());
        store.deactivate("server:a", null);
        assertEquals(1, store.matching("ctrl+h").size());
        assertEquals("server:b", store.matching("ctrl+h").getFirst().id().scope());
    }

    @Test void inputAndDescriptionValidation() {
        assertEquals("ctrl+shift+k", KeyCombo.normalize("Shift+CONTROL+K"));
        assertEquals("ctrl+mouse1", KeyCombo.mouse(0, 2));
        assertThrows(IllegalArgumentException.class, () -> KeyCombo.normalize("ctrl+ctrl+k"));
        assertThrows(IllegalArgumentException.class, () -> KeyCombo.normalize("ctrl+k+j"));
        assertThrows(IllegalArgumentException.class, () -> new KeyAction("../open", "Open", "Tools", "h"));
        assertThrows(IllegalArgumentException.class, () -> new KeyAction("open", "Open\nOther", "Tools", "h"));
        var array = new com.google.gson.JsonArray(); array.add(open.json()); array.add(open.json());
        assertThrows(IllegalArgumentException.class, () -> KeyAction.parse(array));
    }
}
