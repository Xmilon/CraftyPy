package net.xmilon.craftpy.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import net.xmilon.craftpy.content.Defs;

class MobDefsTest {
    @Test void profilesAndNamedAnimationsValidateAndRoundTrip() {
        var json = JsonParser.parseString("{\"model\":\"guardian.geo.json\",\"behavior\":{\"profile\":\"guard\",\"radius\":12},"
                + "\"animations\":{\"idle\":\"guardian.idle\",\"attack\":\"guardian.strike\",\"death\":\"guardian.fall\"}}").getAsJsonObject();
        var def = Defs.EntityDef.from(json); var look = SlottedEntity.Look.of(def, "foundry");
        assertEquals("guardian.strike", SlottedEntity.Look.parse(look.json()).clips().get("attack").getAsString());
        json.getAsJsonObject("behavior").addProperty("radius", 1000);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Defs.EntityDef.from(json));
    }
    @Test
    void lookTurnsProjectFilesIntoIdsOfTheProjectNamespace() {
        Defs.EntityDef def = Defs.EntityDef.from(JsonParser.parseString(
                "{\"name\":\"Goblin\",\"texture\":\"goblin.png\",\"model\":\"goblin.geo.json\",\"scale\":1.5}").getAsJsonObject());
        SlottedEntity.Look look = SlottedEntity.Look.of(def, "zoo");
        assertEquals("zoo:textures/goblin.png", look.texture());
        assertEquals("zoo:geo/goblin.geo.json", look.model());
        assertEquals("zoo:animations/goblin.animation.json", look.animation());
        assertTrue(look.geckoModel());
        assertEquals(look, SlottedEntity.Look.parse(look.json()), "the synced string reads back the same");

        Defs.EntityDef pig = Defs.EntityDef.from(JsonParser.parseString(
                "{\"model\":\"pig\",\"texture\":\"minecraft:entity/pig/pig\"}").getAsJsonObject());
        SlottedEntity.Look pigLook = SlottedEntity.Look.of(pig, "zoo");
        assertEquals("minecraft:textures/entity/pig/pig.png", pigLook.texture());
        assertEquals("pig", pigLook.model());
        assertEquals("", pigLook.animation());
    }

    @Test
    void definitionsReadSpawnsEggsAndEffectAttributes() {
        Defs.EntityDef def = Defs.EntityDef.from(JsonParser.parseString("{\"base\":\"hostile\",\"egg_color\":\"#336633\","
                + "\"spawns\":[{\"biomes\":[\"#minecraft:is_forest\"],\"weight\":8,\"min\":3,\"max\":1}]}").getAsJsonObject());
        assertTrue(def.hostile());
        assertEquals(0x336633, def.eggColor);
        assertEquals(List.of(new Defs.EntityDef.Spawn(List.of("#minecraft:is_forest"), 8, 3, 3)), def.spawns,
                "max is never below min");

        Defs.EffectDef effect = Defs.EffectDef.from(JsonParser.parseString("{\"category\":\"Harmful\",\"attributes\":"
                + "[{\"attribute\":\"minecraft:generic.armor\",\"amount\":-2,\"operation\":\"add_value\"}]}").getAsJsonObject());
        assertEquals("harmful", effect.category);
        assertEquals(-2.0, effect.attributes.get(0).amount());
    }
}
