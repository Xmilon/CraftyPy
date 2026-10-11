package net.xmilon.craftpy.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AssetReferencesTest {

    @Test
    void parsesHelperCalls() {
        List<AssetReference> refs = AssetReferences.parse("    texture = tex(\"minecraft:block/dirt\")");
        assertEquals(1, refs.size());
        AssetReference ref = refs.get(0);
        assertEquals(AssetKind.TEXTURE, ref.kind());
        assertEquals("minecraft:block/dirt", ref.value());
        assertTrue(ref.call());
        // span points inside the quotes
        assertEquals("minecraft:block/dirt", "    texture = tex(\"minecraft:block/dirt\")".substring(ref.start(), ref.end()));
    }

    @Test
    void parsesSoundAndModelHelpers() {
        List<AssetReference> refs = AssetReferences.parse(
                "sound = snd('minecraft:block.stone.break'); model = mdl(\"minecraft:block/cube_all\")");
        assertEquals(2, refs.size());
        assertEquals(AssetKind.SOUND, refs.get(0).kind());
        assertEquals("minecraft:block.stone.break", refs.get(0).value());
        assertEquals(AssetKind.MODEL, refs.get(1).kind());
        assertEquals("minecraft:block/cube_all", refs.get(1).value());
    }

    @Test
    void parsesKeyForm() {
        List<AssetReference> refs = AssetReferences.parse("texture0: minecraft:block/stone, icon: minecraft:diamond");
        assertEquals(2, refs.size());
        assertEquals(AssetKind.TEXTURE, refs.get(0).kind());
        assertEquals("minecraft:block/stone", refs.get(0).value());
        assertEquals(AssetKind.ITEM, refs.get(1).kind());
        assertEquals("minecraft:diamond", refs.get(1).value());
    }

    @Test
    void ignoresPlainStrings() {
        List<AssetReference> refs = AssetReferences.parse("name = \"Ruby Block\"");
        assertTrue(refs.isEmpty());
    }

    @Test
    void handlesNullAndEmpty() {
        assertTrue(AssetReferences.parse(null).isEmpty());
        assertTrue(AssetReferences.parse("").isEmpty());
    }

    @Test
    void multipleOnSameLineSorted() {
        List<AssetReference> refs = AssetReferences.parse(
                "a = tex(\"minecraft:block/a\"); b = snd(\"minecraft:block/b\")");
        assertEquals(2, refs.size());
        assertTrue(refs.get(0).start() < refs.get(1).start());
    }
}