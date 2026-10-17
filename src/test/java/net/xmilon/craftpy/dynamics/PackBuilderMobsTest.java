package net.xmilon.craftpy.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.xmilon.craftpy.content.Defs;

class PackBuilderMobsTest {
    @Test
    void biomeModifierAddsTheSlotTypeToTheBiomes() {
        var single = PackBuilder.biomeModifier("pcmonster_2", new Defs.EntityDef.Spawn(List.of("#minecraft:is_overworld"), 20, 1, 4));
        assertEquals("{\"type\":\"neoforge:add_spawns\",\"biomes\":\"#minecraft:is_overworld\",\"spawners\":"
                + "{\"type\":\"craftpy:pcmonster_2\",\"weight\":20,\"minCount\":1,\"maxCount\":4}}", single.toString());
        var several = PackBuilder.biomeModifier("pcentity_0",
                new Defs.EntityDef.Spawn(List.of("minecraft:plains", "minecraft:forest"), 5, 2, 3));
        assertEquals("[\"minecraft:plains\",\"minecraft:forest\"]", several.get("biomes").toString());
    }

    @Test
    void geoAndAnimationFoldersBelongToTheProject() {
        assertEquals("zoo", PackBuilder.assetNamespace("zoo", "geo", true));
        assertEquals("zoo", PackBuilder.assetNamespace("zoo", "animations", true));
    }
}
