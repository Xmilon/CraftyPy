package net.xmilon.craftpy.client.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.xmilon.craftpy.assets.AssetKind;

class AssetDropTest {
    @Test
    void modifiedVanillaCopyGoesToTheModuleResources() {
        assertEquals("assets/welcome/textures/block/stone.png",
                AssetDrop.projectPath(AssetKind.TEXTURE, "minecraft:block/stone", "welcome", List.of()));
        assertEquals("assets/welcome/textures/block/stone.png",
                AssetDrop.projectPath(AssetKind.TEXTURE, "block/stone", "welcome", List.of()));
    }

    @Test
    void otherModsKeepTheirNamespaceInTheFileName() {
        assertEquals("assets/welcome/textures/create/block/gear.png",
                AssetDrop.projectPath(AssetKind.TEXTURE, "create:block/gear", "welcome", List.of()));
    }

    @Test
    void ownNamespaceUsesTheSimpleFolderWhenTheFileIsThere() {
        Set<String> files = Set.of("assets/textures/gem.png");
        assertEquals("assets/textures/gem.png", AssetDrop.projectPath(AssetKind.TEXTURE, "welcome:gem", "welcome", files));
        assertEquals("assets/welcome/textures/gem.png", AssetDrop.projectPath(AssetKind.TEXTURE, "welcome:gem", "welcome", Set.of()));
    }

    @Test
    void soundEventsBecomeFolders() {
        assertEquals("assets/welcome/sounds/block/bell/use.ogg",
                AssetDrop.projectPath(AssetKind.SOUND, "minecraft:block.bell.use", "welcome", List.of()));
        assertEquals("assets/sounds/bell_use.ogg", AssetDrop.intoFolder(AssetKind.SOUND, "minecraft:bell.use", "assets/sounds"));
    }

    @Test
    void registryKindsHaveNoFile() {
        assertNull(AssetDrop.projectPath(AssetKind.ITEM, "minecraft:stick", "welcome", List.of()));
        assertFalse(AssetDrop.isFile(AssetKind.BLOCK));
        assertTrue(AssetDrop.isFile(AssetKind.MODEL));
    }

    @Test
    void copyIntoAFolderKeepsOnlyTheLastSegment() {
        assertEquals("assets/textures/stone.png", AssetDrop.intoFolder(AssetKind.TEXTURE, "minecraft:block/stone", "assets/textures/"));
        assertEquals("stone.json", AssetDrop.intoFolder(AssetKind.MODEL, "block/stone", ""));
    }

    @Test
    void typedTargetsMustBeRealFilesUnderAssets() {
        assertTrue(AssetDrop.validTarget("assets/textures/block/ruby.png", ".png"));
        assertTrue(AssetDrop.validTarget("assets/s/a.wav", ".ogg", ".wav"));
        assertFalse(AssetDrop.validTarget("assets/.png", ".png"));
        assertFalse(AssetDrop.validTarget("assets/./x.png", ".png"));
        assertFalse(AssetDrop.validTarget("assets//x.png", ".png"));
        assertFalse(AssetDrop.validTarget("assets/../x.png", ".png"));
        assertFalse(AssetDrop.validTarget("code/x.png", ".png"));
        assertFalse(AssetDrop.validTarget("assets/x.PNG", ".png"));
        assertFalse(AssetDrop.validTarget("assets/x.ogg", ".png"));
        assertFalse(AssetDrop.validTarget(null, ".png"));
    }

    @Test
    void snippets() {
        assertEquals("\"minecraft:block/stone\"", AssetDrop.snippet(AssetKind.TEXTURE, "minecraft:block/stone"));
        assertEquals("event.player.play_sound(\"minecraft:block.bell.use\")",
                AssetDrop.snippet(AssetKind.SOUND, "minecraft:block.bell.use"));
    }
}
