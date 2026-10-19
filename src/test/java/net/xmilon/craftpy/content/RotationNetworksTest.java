package net.xmilon.craftpy.content;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RotationNetworksTest {
    private KineticDef generator(float speed, float capacity) { return new KineticDef("generator", Direction.Axis.X, speed, capacity, 0, "craftpy:block/machine_rotor"); }
    private KineticDef load(float impact) { return new KineticDef("processor", Direction.Axis.X, 0, 0, impact, "craftpy:block/machine_rotor"); }
    @Test void signedSourcesCombineCapacityAndStopOnConflict() {
        var combined = RotationNetworks.solve(List.of(generator(-64, 4), generator(-64, 4), load(8)), 4096);
        assertEquals(-64, combined.speed()); assertEquals("ready", combined.state());
        assertEquals("overstressed", RotationNetworks.solve(List.of(generator(-64, 7), load(8)), 4096).state());
        assertEquals("conflict", RotationNetworks.solve(List.of(generator(-64, 9), generator(64, 9)), 4096).state());
        assertEquals("conflict", RotationNetworks.solve(List.of(generator(32, 9), generator(64, 9)), 4096).state());
        assertEquals("unpowered", RotationNetworks.solve(List.of(load(1)), 4096).state());
        assertEquals("network_limit", RotationNetworks.solve(List.of(generator(32, 9), load(1)), 1).state());
    }
    @Test void resourceDeclarationsRejectFractionalAndMalformedLimits() {
        var json = com.google.gson.JsonParser.parseString("{\"ext\":[{\"type\":\"energy_storage\",\"capacity\":1000,\"max_receive\":10,\"max_extract\":20},{\"type\":\"fluid_tank\",\"capacity\":4000,\"allowed\":[\"minecraft:water\"]}]}").getAsJsonObject();
        var def = Defs.BlockDef.from(json); assertTrue(def.machine()); assertNull(def.kinetic);
        for (String value : List.of("0", "-1", "1.5", "1e40", "true")) {
            var invalid = def.energy.deepCopy(); invalid.add("capacity", com.google.gson.JsonParser.parseString(value));
            assertThrows(IllegalArgumentException.class, () -> MachineState.validateEnergy(invalid));
        }
        var invalid = def.fluid.deepCopy(); invalid.add("allowed", com.google.gson.JsonParser.parseString("[\"BAD fluid\"]"));
        assertThrows(IllegalArgumentException.class, () -> MachineState.validateFluid(invalid));
    }
}
