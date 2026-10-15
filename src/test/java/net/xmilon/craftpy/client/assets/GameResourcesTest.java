package net.xmilon.craftpy.client.assets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

/** Without the game (the standalone IDE, like these tests) the asset browser reads the vanilla assets jar. */
class GameResourcesTest {

    @Test
    void vanillaAssetsAreReadableWithoutTheGame() throws Exception {
        assertFalse(GameResources.inGame());
        var manager = GameResources.manager();
        assertNotNull(manager, "the vanilla assets jar is on the classpath");
        var stone = manager.getResource(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/stone.png"));
        assertTrue(stone.isPresent());
        try (var in = stone.get().open()) {
            assertTrue(in.readAllBytes().length > 0);
        }
        assertNotNull(ModelResources.model("minecraft:block/stone"));

        AssetIndex.get().rebuild(manager);
        assertTrue(AssetIndex.get().ids(net.xmilon.craftpy.assets.AssetKind.TEXTURE).contains("minecraft:block/stone"));
        assertTrue(AssetIndex.get().ids(net.xmilon.craftpy.assets.AssetKind.MODEL).contains("minecraft:block/stone"));
        assertFalse(SoundPreview.play("minecraft:block.stone.break", 1, 1), "no sound engine without the game");
    }
}
