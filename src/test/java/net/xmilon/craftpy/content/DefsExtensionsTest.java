package net.xmilon.craftpy.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class DefsExtensionsTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void itemExtensionsAreParsed() {
        var def = Defs.ItemDef.from(json("""
                {"name":"Blade","max_stack":1,"burn_time":30,"ext":[
                 {"type":"tool","kind":"sword","level":3,"speed":8.0,"damage":7.0,"attack_speed":1.6},
                 {"type":"durability","uses":1561,"repair_with":"minecraft:diamond"},
                 {"type":"armor","slot":"head","defense":3,"toughness":1,"knockback_resistance":0.1},
                 {"type":"enchantable","value":10},{"type":"cooldown","seconds":2.5},
                 {"type":"tooltip","lines":["hot","very hot"],"color":"gold"}]}"""));
        assertEquals("sword", def.tool.kind());
        assertEquals(3, def.tool.level());
        assertEquals(7.0f, def.tool.damage());
        assertEquals(1561, def.maxDamage);
        assertEquals("minecraft:diamond", def.repairWith);
        assertEquals("head", def.armor.slot());
        assertEquals(10, def.enchantValue);
        assertEquals(2.5f, def.cooldownSeconds);
        assertEquals(2, def.tooltip.size());
        assertEquals("gold", def.tooltipColor);
        assertEquals(30, def.burnTime);
        assertNull(def.weapon);
    }

    @Test
    void blockExtensionsAreParsedAndClamped() {
        var def = Defs.BlockDef.from(json("""
                {"name":"Ore","ext":[{"type":"mineable","level":2},{"type":"redstone","power":99},
                 {"type":"flammable","chance":5,"spread":20},{"type":"friction","value":0.98},
                 {"type":"piston","reaction":"block"}]}"""));
        assertEquals(2, def.minLevel);
        assertEquals(15, def.redstone, "power is clamped to 15");
        assertEquals(5, def.burnChance);
        assertEquals(20, def.burnSpread);
        assertEquals(0.98f, def.friction);
        assertEquals("block", def.push);
    }

    @Test
    void definitionsWithoutExtensionsKeepTheirDefaults() {
        var item = Defs.ItemDef.from(json("{\"name\":\"Plain\"}"));
        assertNull(item.tool);
        assertEquals(0, item.maxDamage);
        assertEquals(-1, item.enchantValue);
        var block = Defs.BlockDef.from(json("{\"name\":\"Plain\"}"));
        assertEquals(-1, block.burnChance);
        assertTrue(block.friction < 0);
        assertEquals("", block.push);
    }

    @Test
    void unknownExtensionTypesAreIgnored() {
        var item = Defs.ItemDef.from(json("{\"ext\":[{\"type\":\"from_the_future\",\"x\":1},\"junk\",3]}"));
        assertNull(item.tool);
    }
}
