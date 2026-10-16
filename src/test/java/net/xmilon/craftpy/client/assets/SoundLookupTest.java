package net.xmilon.craftpy.client.assets;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SoundLookupTest {

    @Test
    void firstVariantNestedEventsAndReplace(@TempDir Path dir) throws IOException {
        Path low = pack(dir.resolve("low"), """
                {"ding": {"sounds": ["demo:ding_a", {"name": "demo:ding_b", "weight": 3}]},
                 "alias": {"sounds": [{"name": "demo:ding", "type": "event"}]},
                 "gone": {"sounds": ["demo:missing"]},
                 "swap": {"sounds": ["demo:ding_a"]}}
                """, "ding_a", "ding_b", "other");
        Path high = pack(dir.resolve("high"), """
                {"swap": {"replace": true, "sounds": ["demo:other"]}}
                """);
        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
                List.of(GameResources.pack("low", low), GameResources.pack("high", high)))) {
            assertEquals("demo:sounds/ding_a.ogg", AssetPreview.firstSound(manager, ResourceLocation.parse("demo:ding"), 0).toString());
            assertEquals("demo:sounds/ding_a.ogg", AssetPreview.firstSound(manager, ResourceLocation.parse("demo:alias"), 0).toString());
            assertEquals("demo:sounds/other.ogg", AssetPreview.firstSound(manager, ResourceLocation.parse("demo:swap"), 0).toString());
            assertNull(AssetPreview.firstSound(manager, ResourceLocation.parse("demo:gone"), 0));
            assertNull(AssetPreview.firstSound(manager, ResourceLocation.parse("demo:nothing"), 0));
        }
    }

    private static Path pack(Path root, String soundsJson, String... oggs) throws IOException {
        Path assets = Files.createDirectories(root.resolve("assets/demo/sounds"));
        Files.writeString(root.resolve("assets/demo/sounds.json"), soundsJson);
        for (String ogg : oggs) {
            Files.write(assets.resolve(ogg + ".ogg"), new byte[]{1});
        }
        return root;
    }
}
