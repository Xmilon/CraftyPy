package net.xmilon.craftpy.content;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.Map;
import net.xmilon.craftpy.python.Declarations;
import net.xmilon.craftpy.registry.SlotRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeFamiliesTest {
    @Test void savedAliasesCannotBecomeSlottedContentAndSlotsCannotBecomeAliases(@TempDir Path home) {
        System.setProperty("craftpy.home", home.toString());
        try {
            var families = JsonParser.parseString("{\"block:demo:machine\":\"native_block\"}").getAsJsonObject();
            NativeContent.plan("demo", declarations("{}"), Map.of());
            NativeContent.commit(new NativeContent.Plan(Map.of(), families));
            assertThrows(IllegalStateException.class, () -> NativeContent.plan("demo", declarations("{\"blocks\":[{\"id\":\"machine\",\"definition\":{}}]}"), Map.of()));
            var oldSlot = Map.of("BLOCK:demo:other", new SlotRegistry.SlotRef(SlotRegistry.Kind.BLOCK, 7));
            assertThrows(IllegalStateException.class, () -> NativeContent.plan("demo", declarations("{\"blocks\":[{\"id\":\"other\",\"definition\":{\"native_base\":\"create:shaft\"}}]}"), oldSlot));
            assertEquals(7, oldSlot.get("BLOCK:demo:other").index());
        } finally { System.clearProperty("craftpy.home"); }
    }
    private static Declarations declarations(String content) {
        return Declarations.parse(JsonParser.parseString("{\"declarations\":" + content + "}").getAsJsonObject(), "main.py");
    }
}
