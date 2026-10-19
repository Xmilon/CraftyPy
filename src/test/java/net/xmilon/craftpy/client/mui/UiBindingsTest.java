package net.xmilon.craftpy.client.mui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiBindingsTest {
    @Test void sharedWorkerAndPreviewFixtures() throws Exception {
        var fixtures = JsonParser.parseString(Files.readString(Path.of("src/test/resources/ui-bindings.json"))).getAsJsonArray();
        for (var raw : fixtures) {
            var fixture = raw.getAsJsonObject(); String property = fixture.get("property").getAsString();
            JsonObject node = new JsonObject(); node.add("type", fixture.get("type")); node.addProperty(property, "fallback");
            JsonObject bind = new JsonObject(); bind.addProperty(property, "data_key"); node.add("bind", bind);
            JsonObject data = new JsonObject(); data.add("data_key", fixture.get("value"));
            var problems = new ArrayList<String>(); JsonObject resolved = UiBindings.node(node, data, problems);
            if (fixture.get("valid").getAsBoolean()) {
                assertTrue(problems.isEmpty(), fixture + ": " + problems);
                assertEquals(data.get("data_key"), resolved.get(property), fixture.toString());
            } else {
                assertFalse(problems.isEmpty(), fixture.toString()); assertEquals("fallback", resolved.get(property).getAsString());
            }
            assertEquals("fallback", node.get(property).getAsString(), "preview mutated source");
        }
    }

    @Test void missingPreviewKeysKeepStaticValuesAndChildrenKeepTheirIdentity() {
        var node = JsonParser.parseString("{\"type\":\"label\",\"text\":\"fallback\",\"bind\":{\"text\":\"missing\"},\"children\":[{}]}").getAsJsonObject();
        var problems = new ArrayList<String>(); var out = UiBindings.node(node, new JsonObject(), problems);
        assertEquals("fallback", out.get("text").getAsString());
        assertSame(node.get("children"), out.get("children"));
        assertTrue(problems.getFirst().contains("missing sample key 'missing'"));
    }
}
