package net.xmilon.craftpy.registry;

import com.google.gson.JsonParser;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.content.KineticDef;
import net.minecraft.core.Direction;
import net.xmilon.craftpy.registry.SlotPlan;
import net.xmilon.craftpy.registry.SlotRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class KineticTest {
    @Test void settingsValidateOnTheHostAndPreserveReversedRotation() {
        var def = Defs.BlockDef.from(JsonParser.parseString("{\"ext\":[{\"type\":\"kinetic\",\"role\":\"generator\",\"axis\":\"x\",\"speed\":-64}]}").getAsJsonObject());
        assertEquals(Direction.Axis.X, def.kinetic.axis()); assertEquals(-64, def.kinetic.speed());
        assertEquals(32, def.kinetic.capacity());
        for (String invalid : List.of("{\"role\":\"fake\"}", "{\"axis\":\"north\"}", "{\"speed\":257}", "{\"capacity\":-1}", "{\"impact\":1e40}", "{\"rotating_model\":\"BAD:name\"}"))
            assertThrows(IllegalArgumentException.class, () -> KineticDef.from(JsonParser.parseString(invalid).getAsJsonObject()));
        assertEquals(0, KineticDef.from(JsonParser.parseString("{\"role\":\"shaft\"}").getAsJsonObject()).speed());
    }
    @Test void independentPoolKeepsOldSlotsAndExhaustionIsAtomic() {
        var old = Map.of("BLOCK:old:machine", new SlotRegistry.SlotRef(SlotRegistry.Kind.BLOCK, 0));
        var next = SlotPlan.allocate(old, "foundry", Map.of(SlotRegistry.Kind.KINETIC, List.of("motor", "mill")), Map.of(SlotRegistry.Kind.KINETIC, 2));
        assertEquals(old.get("BLOCK:old:machine"), next.get("BLOCK:old:machine"));
        assertEquals("pckinetic_0", SlotRegistry.slotName(next.get("KINETIC:foundry:motor")));
        assertThrows(IllegalStateException.class, () -> SlotPlan.allocate(next, "other", Map.of(SlotRegistry.Kind.KINETIC, List.of("new")), Map.of(SlotRegistry.Kind.KINETIC, 2)));
        assertEquals(3, next.size()); assertEquals(1, old.size());
    }
    @Test void existingGenericBlockCannotBeReassignedToKineticFamily() {
        SlotRegistry.clearAll();
        try {
            var old = SlotRegistry.applyBlock("family", "machine", new com.google.gson.JsonObject());
            var json = JsonParser.parseString("{\"ext\":[{\"type\":\"kinetic\",\"role\":\"shaft\"}]}").getAsJsonObject();
            assertThrows(IllegalStateException.class, () -> SlotRegistry.applyBlock("family", "machine", json));
            assertEquals(old, SlotRegistry.snapshotByHolder().get("BLOCK:family:machine"));
            assertNull(SlotRegistry.kineticDef(0));
        } finally { SlotRegistry.clearAll(); }
    }
}
