package net.xmilon.craftpy.content;

import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** Pure settings; this class loads even when Create is absent. Stress values are per RPM, as in Create. */
public record KineticDef(String role, Direction.Axis axis, float speed, float capacity, float impact, String model) {
    public static KineticDef from(JsonObject json) {
        String role = Defs.s(json, "role", "shaft");
        if (!java.util.Set.of("generator", "shaft", "processor").contains(role)) throw new IllegalArgumentException("Kinetic role must be generator, shaft or processor");
        Direction.Axis axis;
        try { axis = Direction.Axis.valueOf(Defs.s(json, "axis", "y").toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Kinetic axis must be x, y or z"); }
        float speed = bounded(json, "speed", 64, -256, 256), capacity = bounded(json, "capacity", 32, 0, 65536);
        float impact = bounded(json, "impact", role.equals("processor") ? 8 : 0, 0, 65536);
        String model = Defs.s(json, "rotating_model", "craftpy:block/machine_rotor");
        if (ResourceLocation.tryParse(model) == null) throw new IllegalArgumentException("Invalid rotating model resource ID");
        return new KineticDef(role, axis, role.equals("generator") ? speed : 0, role.equals("generator") ? capacity : 0, impact, model);
    }
    private static float bounded(JsonObject json, String key, float fallback, float low, float high) {
        float value = Defs.f(json, key, fallback);
        if (!Float.isFinite(value) || value < low || value > high) throw new IllegalArgumentException("Kinetic " + key + " must be " + low + "-" + high);
        return value;
    }
}
