package net.xmilon.craftpy.bridge;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.xmilon.craftpy.bridge.JavaBridge.BridgeException;

/**
 * Values between JSON (what Python sends) and Java objects.
 * <ul>
 *   <li>numbers, booleans, strings and null map to the parameter's own type (int, long, double, String...);</li>
 *   <li>{@code {"$h": n}} is a live Java object, {@code {"$c": name}} a class, {@code {"$fn": n}} a Python function
 *       (becomes a Runnable / Consumer / Function / any one-method interface);</li>
 *   <li>a string where Java wants an enum, UUID, ResourceLocation, Component, Block, Item, EntityType... is looked up;
 *       a list of numbers where Java wants a BlockPos / Vec3 is built;</li>
 *   <li>lists and dicts become ArrayList / LinkedHashMap, or arrays when the parameter is an array.</li>
 * </ul>
 */
final class JavaCodec {
    private JavaCodec() {
    }

    // ------------------------------------------------------------------
    // Java -> JSON
    // ------------------------------------------------------------------

    static JsonElement encode(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) ? new JsonPrimitive(d) : new JsonPrimitive(String.valueOf(d));
        }
        if (value instanceof String || value instanceof Character) {
            return new JsonPrimitive(value.toString());
        }
        JsonObject out = new JsonObject();
        if (value instanceof Class<?> type) {
            if (!JavaAccess.allowed(type)) {
                throw new BridgeException("Class " + type.getName() + " is not available to scripts");
            }
            out.addProperty("$c", type.getName());
            return out;
        }
        out.addProperty("$h", JavaBridge.handleOf(value));
        out.addProperty("$t", value.getClass().getName());
        String text;
        try {
            text = String.valueOf(value);
        } catch (RuntimeException e) {
            text = value.getClass().getSimpleName();
        }
        out.addProperty("$s", text.length() > 200 ? text.substring(0, 200) + "..." : text);
        return out;
    }

    // ------------------------------------------------------------------
    // JSON -> Java
    // ------------------------------------------------------------------

    /** The receiver of a member access: a handle, a class (static access), or a plain value. */
    static Object decodeTarget(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            throw new BridgeException("The Java object is None");
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("$h")) {
                return JavaBridge.object(object.get("$h").getAsInt());
            }
            if (object.has("$c")) {
                return JavaBridge.loadClass(object.get("$c").getAsString());
            }
        }
        return decode(element, Object.class);
    }

    static Object[] tryConvert(Class<?>[] types, JsonArray args) {
        Object[] out = new Object[types.length];
        try {
            for (int i = 0; i < types.length; i++) {
                out[i] = decode(args.get(i), types[i]);
            }
            return out;
        } catch (BridgeException | ClassCastException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Higher is a closer match: lets {@code f(int)} win over {@code f(double)} for a whole number. */
    static int score(Class<?>[] types, JsonArray args) {
        int total = 0;
        for (int i = 0; i < types.length; i++) {
            total += scoreOne(box(types[i]), args.get(i));
        }
        return total;
    }

    private static int scoreOne(Class<?> type, JsonElement e) {
        if (type == Object.class) {
            return 0;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            boolean whole = isWhole(e.getAsJsonPrimitive());
            if (type == Integer.class) {
                return whole ? 5 : 0;
            }
            if (type == Long.class) {
                return whole ? 4 : 0;
            }
            if (type == Double.class) {
                return whole ? 2 : 5;
            }
            if (type == Float.class) {
                return whole ? 1 : 4;
            }
            return 1;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            return type == String.class ? 5 : 1;
        }
        if (e.isJsonObject() && e.getAsJsonObject().has("$h")) {
            Object object = JavaBridge.object(e.getAsJsonObject().get("$h").getAsInt());
            return object.getClass() == type ? 5 : 3;
        }
        return 2;
    }

    static Object decode(JsonElement e, Class<?> target) {
        Class<?> type = box(target);
        if (e == null || e.isJsonNull()) {
            if (target.isPrimitive()) {
                throw new BridgeException("None is not a valid " + target.getName());
            }
            return null;
        }
        if (e.isJsonObject()) {
            return decodeObject(e.getAsJsonObject(), type);
        }
        if (e.isJsonArray()) {
            return decodeList(e.getAsJsonArray(), type);
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            if (type == Boolean.class || type == Object.class) {
                return p.getAsBoolean();
            }
            throw new BridgeException("A boolean is not a " + type.getSimpleName());
        }
        if (p.isNumber()) {
            return decodeNumber(p, type);
        }
        return decodeString(p.getAsString(), type);
    }

    private static Object decodeObject(JsonObject o, Class<?> type) {
        if (o.has("$h")) {
            Object value = JavaBridge.object(o.get("$h").getAsInt());
            if (type.isInstance(value)) {
                return value;
            }
            throw new BridgeException(value.getClass().getSimpleName() + " is not a " + type.getSimpleName());
        }
        if (o.has("$c")) {
            Class<?> cls = JavaBridge.loadClass(o.get("$c").getAsString());
            if (type == Class.class || type == Object.class) {
                return cls;
            }
            throw new BridgeException("A class is not a " + type.getSimpleName());
        }
        if (o.has("$fn")) {
            return JavaEvents.callback(JavaBridge.caller(), o.get("$fn").getAsInt(), type);
        }
        if (type.isAssignableFrom(LinkedHashMap.class)) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
                map.put(entry.getKey(), decode(entry.getValue(), Object.class));
            }
            return map;
        }
        throw new BridgeException("A dict is not a " + type.getSimpleName());
    }

    private static Object decodeList(JsonArray array, Class<?> type) {
        if (type.isArray()) {
            Class<?> component = type.getComponentType();
            Object out = Array.newInstance(component, array.size());
            for (int i = 0; i < array.size(); i++) {
                Array.set(out, i, decode(array.get(i), component));
            }
            return out;
        }
        if (Mc.wantsList(type)) {
            return Mc.fromList(array, type);
        }
        if (type.isAssignableFrom(ArrayList.class)) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : array) {
                list.add(decode(item, Object.class));
            }
            return list;
        }
        throw new BridgeException("A list is not a " + type.getSimpleName());
    }

    private static boolean isWhole(JsonPrimitive p) {
        double d = p.getAsDouble();
        return d == Math.rint(d) && Math.abs(d) < 9.0e15 && !p.getAsString().contains(".") && !p.getAsString().contains("e");
    }

    private static Object decodeNumber(JsonPrimitive p, Class<?> type) {
        if (type == Integer.class) {
            return exact(p, Integer.MIN_VALUE, Integer.MAX_VALUE).intValue();
        } else if (type == Long.class) {
            return exact(p, Long.MIN_VALUE, Long.MAX_VALUE).longValue();
        } else if (type == Short.class) {
            return exact(p, Short.MIN_VALUE, Short.MAX_VALUE).shortValue();
        } else if (type == Byte.class) {
            return exact(p, Byte.MIN_VALUE, Byte.MAX_VALUE).byteValue();
        } else if (type == Double.class) {
            return p.getAsDouble();
        } else if (type == Float.class) {
            return p.getAsFloat();
        } else if (type == Number.class || type == Object.class) {
            return isWhole(p) && Math.abs(p.getAsDouble()) <= Integer.MAX_VALUE ? (Object) p.getAsInt() : (Object) p.getAsDouble();
        }
        throw new BridgeException("A number is not a " + type.getSimpleName());
    }

    /** Whole numbers only: 2.5 must not silently become 2 when Java asks for an int. */
    private static Number exact(JsonPrimitive p, double min, double max) {
        double d = p.getAsDouble();
        if (d != Math.rint(d) || d < min || d > max) {
            throw new BridgeException(p.getAsString() + " does not fit the whole-number parameter");
        }
        return p.getAsLong();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object decodeString(String s, Class<?> type) {
        if (type == String.class || type == CharSequence.class || type == Object.class) {
            return s;
        }
        if (type == Character.class && s.length() == 1) {
            return s.charAt(0);
        }
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(s)) {
                    return constant;
                }
            }
            throw new BridgeException("'" + s + "' is not a " + type.getSimpleName() + " (" + enumNames(type) + ")");
        }
        if (type == UUID.class) {
            return UUID.fromString(s);
        }
        Object mc = Mc.fromString(s, type);
        if (mc != null) {
            return mc;
        }
        throw new BridgeException("A string is not a " + type.getSimpleName());
    }

    private static String enumNames(Class<?> type) {
        StringBuilder sb = new StringBuilder();
        for (Object constant : type.getEnumConstants()) {
            sb.append(sb.length() == 0 ? "" : ", ").append(((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "boolean" -> Boolean.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "char" -> Character.class;
            default -> type;
        };
    }

    /**
     * Minecraft-specific conversions. Kept in its own class so the bridge itself loads (and can be tested) without
     * the game on the class path; the JVM only loads this when a Minecraft type is really asked for.
     */
    private static final class Mc {
        private Mc() {
        }

        static boolean wantsList(Class<?> type) {
            String n = type.getName();
            return n.equals("net.minecraft.core.BlockPos") || n.equals("net.minecraft.world.phys.Vec3")
                    || n.equals("net.minecraft.core.Vec3i") || n.equals("net.minecraft.world.level.ChunkPos")
                    || n.equals("net.minecraft.world.phys.Vec2");
        }

        static Object fromList(JsonArray a, Class<?> type) {
            double[] v = new double[a.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = a.get(i).getAsDouble();
            }
            String n = type.getName();
            if (n.equals("net.minecraft.world.phys.Vec3") && v.length == 3) {
                return new net.minecraft.world.phys.Vec3(v[0], v[1], v[2]);
            }
            if (n.equals("net.minecraft.world.phys.Vec2") && v.length == 2) {
                return new net.minecraft.world.phys.Vec2((float) v[0], (float) v[1]);
            }
            if (n.equals("net.minecraft.world.level.ChunkPos") && v.length == 2) {
                return new net.minecraft.world.level.ChunkPos((int) v[0], (int) v[1]);
            }
            if (v.length == 3) {
                int x = (int) Math.floor(v[0]);
                int y = (int) Math.floor(v[1]);
                int z = (int) Math.floor(v[2]);
                return n.equals("net.minecraft.core.BlockPos") ? new net.minecraft.core.BlockPos(x, y, z)
                        : new net.minecraft.core.Vec3i(x, y, z);
            }
            throw new BridgeException("A " + type.getSimpleName() + " needs a list of " + (n.endsWith("ChunkPos") || n.endsWith("Vec2") ? 2 : 3) + " numbers");
        }

        static Object fromString(String s, Class<?> type) {
            String n = type.getName();
            if (n.equals("net.minecraft.resources.ResourceLocation")) {
                return net.minecraft.resources.ResourceLocation.parse(s);
            }
            if (n.equals("net.minecraft.network.chat.Component") || n.equals("net.minecraft.network.chat.MutableComponent")) {
                return net.minecraft.network.chat.Component.literal(s);
            }
            var registry = registryFor(n);
            if (registry != null) {
                var id = net.minecraft.resources.ResourceLocation.parse(s.contains(":") ? s : "minecraft:" + s);
                return registry.getOptional(id).orElseThrow(() -> new BridgeException("Unknown " + type.getSimpleName() + " '" + s + "'"));
            }
            return null;
        }

        private static net.minecraft.core.Registry<?> registryFor(String className) {
            return switch (className) {
                case "net.minecraft.world.level.block.Block" -> net.minecraft.core.registries.BuiltInRegistries.BLOCK;
                case "net.minecraft.world.item.Item" -> net.minecraft.core.registries.BuiltInRegistries.ITEM;
                case "net.minecraft.world.entity.EntityType" -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE;
                case "net.minecraft.world.level.material.Fluid" -> net.minecraft.core.registries.BuiltInRegistries.FLUID;
                case "net.minecraft.sounds.SoundEvent" -> net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT;
                case "net.minecraft.world.effect.MobEffect" -> net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT;
                case "net.minecraft.world.entity.ai.attributes.Attribute" -> net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE;
                case "net.minecraft.core.particles.ParticleType" -> net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE;
                case "net.minecraft.world.item.alchemy.Potion" -> net.minecraft.core.registries.BuiltInRegistries.POTION;
                case "net.minecraft.world.level.block.entity.BlockEntityType" -> net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE;
                default -> null;
            };
        }
    }
}
