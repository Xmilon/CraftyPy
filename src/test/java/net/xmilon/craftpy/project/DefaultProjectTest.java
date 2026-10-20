package net.xmilon.craftpy.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.registry.SlotRegistry;

class DefaultProjectTest {
    private static String resource(String path) throws IOException {
        try (InputStream in = DefaultProjectTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyFileOfTheManifestExistsAndTheProjectIsComplete() throws IOException {
        List<String> files = resource("/craftpy_default/files.txt").lines().map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
        assertTrue(files.contains("welcome/project.json"));
        assertTrue(files.contains("welcome/code/main.py"));
        for (String file : files) {
            try (InputStream in = DefaultProjectTest.class.getResourceAsStream("/craftpy_default/" + file)) {
                assertNotNull(in, file);
            }
        }
        JsonObject meta = JsonParser.parseString(resource("/craftpy_default/welcome/project.json")).getAsJsonObject();
        assertEquals("welcome", meta.get("id").getAsString());
        assertTrue(ProjectManager.isValidId(meta.get("id").getAsString()));
    }

    @Test
    void theBundledWindowFileIsAValidTree() throws IOException {
        JsonObject window = JsonParser.parseString(resource("/craftpy_default/welcome/ui/settings.json")).getAsJsonObject();
        assertEquals("settings", window.get("id").getAsString());
        assertEquals("vbox", window.getAsJsonObject("root").get("type").getAsString());
    }

    @Test
    void placeholderSlotsAreRecognisedButProjectContentIsNot() {
        assertTrue(SlotRegistry.isPlaceholder(ResourceLocation.fromNamespaceAndPath("craftpy", "pcblock_17")));
        assertTrue(SlotRegistry.isPlaceholder(ResourceLocation.fromNamespaceAndPath("craftpy", "pcitem_0")));
        assertFalse(SlotRegistry.isPlaceholder(ResourceLocation.fromNamespaceAndPath("craftpy", "python_computer")));
        assertFalse(SlotRegistry.isPlaceholder(ResourceLocation.fromNamespaceAndPath("minecraft", "pcblock_1")));
    }
}
