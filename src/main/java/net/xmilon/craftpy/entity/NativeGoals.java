package net.xmilon.craftpy.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.ai.goal.Goal;
import net.xmilon.craftpy.bridge.JavaAccess;
import net.xmilon.craftpy.catalog.NativeInspection;
import net.xmilon.craftpy.security.PermissionStore;

/** Only inspected, compatible public constructors with explicit scalar arguments can be attached. */
public final class NativeGoals {
    private NativeGoals() { }
    private record Match(Constructor<?> constructor, JsonArray args) { }

    public static void validate(JsonArray goals, String owner) {
        if (goals.size() > 8) throw new IllegalArgumentException("At most eight native goals per mob");
        for (JsonElement raw : goals) resolve(raw.getAsJsonObject(), owner);
    }

    private static Match resolve(JsonObject spec, String owner) {
        JavaAccess.refreshFromConfig();
        if (!PermissionStore.javaAllowed(owner)) throw new IllegalArgumentException("Native AI goals require this owner's approved java permission");
        if (!java.util.Set.of("class", "args", "priority", "target", "parameters").containsAll(spec.keySet()))
            throw new IllegalArgumentException("Unknown native goal option");
        String name = spec.get("class").getAsString();
        if (name.length() > 256) throw new IllegalArgumentException("Native goal class name exceeds 256 characters");
        if (spec.has("target") && (!spec.get("target").isJsonPrimitive() || !spec.get("target").getAsJsonPrimitive().isBoolean()))
            throw new IllegalArgumentException("Goal target must be a boolean");
        try {
            Class<?> type = Class.forName(name, false, NativeGoals.class.getClassLoader());
            if (!JavaAccess.allowed(type) || !Goal.class.isAssignableFrom(type) || !java.lang.reflect.Modifier.isPublic(type.getModifiers())
                    || java.lang.reflect.Modifier.isAbstract(type.getModifiers()))
                throw new IllegalArgumentException("Goal class is inaccessible, abstract or not a native Goal: " + name);
            JsonArray args = spec.has("args") ? spec.getAsJsonArray("args") : new JsonArray();
            if (args.size() > 8) throw new IllegalArgumentException("A goal accepts at most eight scalar arguments");
            String signature = spec.has("parameters") ? spec.get("parameters").toString() : "";
            List<Match> matches = new ArrayList<>();
            for (Constructor<?> constructor : type.getConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length != args.size() + 1 || !params[0].isAssignableFrom(SlottedEntity.class)) continue;
                JsonArray names = new JsonArray(); for (Class<?> param : params) names.add(param.getName());
                if (!signature.isEmpty() && !signature.equals(names.toString())) continue;
                boolean valid = true;
                for (int i = 1; i < params.length; i++) if (!NativeInspection.scalar(params[i]) || !JavaAccess.allowed(params[i]) || !accepts(args.get(i - 1), params[i])) valid = false;
                if (valid) matches.add(new Match(constructor, args));
            }
            if (matches.size() != 1) throw new IllegalArgumentException(name + (matches.isEmpty()
                    ? ": no compatible public constructor for SlottedEntity and the supplied scalar arguments. Inspect the catalog for required entity classes."
                    : ": ambiguous constructor; specify parameters as the exact list of Java type names from the catalog."));
            int priority = spec.has("priority") ? spec.get("priority").getAsInt() : 3;
            if (priority < 0 || priority > 20 || spec.has("priority") && spec.get("priority").getAsDouble() != priority)
                throw new IllegalArgumentException("Goal priority must be an integer from 0 to 20");
            return matches.getFirst();
        } catch (ClassNotFoundException | LinkageError error) { throw new IllegalArgumentException("Cannot inspect native goal " + name + ": " + error, error); }
    }

    private static boolean accepts(JsonElement value, Class<?> type) {
        if (!value.isJsonPrimitive()) return false;
        var p = value.getAsJsonPrimitive();
        if (type == String.class) return p.isString() && p.getAsString().length() <= 256;
        if (type == boolean.class) return p.isBoolean();
        if (type.isEnum()) return p.isString() && java.util.Arrays.stream(type.getFields()).anyMatch(f -> f.isEnumConstant() && f.getName().equals(p.getAsString()));
        if (!p.isNumber() || !Double.isFinite(p.getAsDouble()) || Math.abs(p.getAsDouble()) > 1e6) return false;
        double n = p.getAsDouble();
        return type == double.class || type == float.class || n == Math.rint(n)
                && (type == int.class || type == long.class || type == short.class && n >= Short.MIN_VALUE && n <= Short.MAX_VALUE
                || type == byte.class && n >= Byte.MIN_VALUE && n <= Byte.MAX_VALUE);
    }

    public static void attach(SlottedEntity mob, JsonObject spec, String owner) {
        Match match = resolve(spec, owner);
        Class<?>[] types = match.constructor().getParameterTypes(); Object[] args = new Object[types.length]; args[0] = mob;
        for (int i = 1; i < types.length; i++) args[i] = value(match.args().get(i - 1), types[i]);
        try {
            Goal goal = (Goal) match.constructor().newInstance(args);
            mob.addNativeGoal(spec.has("priority") ? spec.get("priority").getAsInt() : 3,
                    spec.has("target") && spec.get("target").getAsBoolean(), goal);
        } catch (ReflectiveOperationException error) { throw new IllegalArgumentException("Native goal constructor failed: " + match.constructor(), error); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object value(JsonElement value, Class<?> type) {
        if (type == String.class) return value.getAsString();
        if (type == boolean.class) return value.getAsBoolean();
        if (type.isEnum()) return Enum.valueOf((Class) type, value.getAsString());
        if (type == int.class) return value.getAsInt(); if (type == long.class) return value.getAsLong();
        if (type == short.class) return value.getAsShort(); if (type == byte.class) return value.getAsByte();
        return type == float.class ? (Object) value.getAsFloat() : value.getAsDouble();
    }
}
