package net.xmilon.craftpy.bridge;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The translation layer between Python and the JVM: Python holds <em>handles</em> to Java objects and asks the
 * host to read fields, call methods, build objects and subscribe to events, all by name. Because it works on
 * whatever classes are loaded, it covers Minecraft, NeoForge and any other mod without per-API wrapper code.
 * <p>
 * Everything here runs on the server thread (the caller schedules it). Access rules are in {@link JavaAccess};
 * values are converted by {@link JavaCodec}.
 */
public final class JavaBridge {
    private static final int MAX_HANDLES = 100_000;

    private static final class Entry {
        final Object object;
        int refs;

        Entry(Object object) {
            this.object = object;
        }
    }

    private static final Map<Integer, Entry> HANDLES = new ConcurrentHashMap<>();
    private static final Map<Object, Integer> IDS = new IdentityHashMap<>();
    private static int next = 1;

    private JavaBridge() {
    }

    /** Thrown for anything the script did wrong; the message goes back to Python as an error. */
    public static final class BridgeException extends RuntimeException {
        public BridgeException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // Handles
    // ------------------------------------------------------------------

    static synchronized int handleOf(Object object) {
        Integer existing = IDS.get(object);
        if (existing != null) {
            HANDLES.get(existing).refs++;
            return existing;
        }
        if (HANDLES.size() >= MAX_HANDLES) {
            throw new BridgeException("Too many live Java objects (" + MAX_HANDLES + "): release some or restart the script");
        }
        int id = next++;
        Entry entry = new Entry(object);
        entry.refs = 1;
        HANDLES.put(id, entry);
        IDS.put(object, id);
        return id;
    }

    static Object object(int handle) {
        Entry entry = HANDLES.get(handle);
        if (entry == null) {
            throw new BridgeException("Java object " + handle + " was already released");
        }
        return entry.object;
    }

    public static synchronized void release(int handle) {
        Entry entry = HANDLES.get(handle);
        if (entry != null && --entry.refs <= 0) {
            HANDLES.remove(handle);
            IDS.remove(entry.object);
        }
    }

    public static int liveHandles() {
        return HANDLES.size();
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /** Handles {@code java.<method>}; never throws, errors come back as {@code {ok:false,error}}. */
    public static JsonObject handle(String method, JsonObject params) {
        JavaAccess.refreshFromConfig();
        JsonObject out = new JsonObject();
        try {
            if (JavaAccess.mode() == JavaAccess.Mode.OFF) {
                throw new BridgeException("The Java bridge is switched off (config javaBridge.mode)");
            }
            if (params.has("release") && params.get("release").isJsonArray()) {
                params.getAsJsonArray("release").forEach(e -> release(e.getAsInt()));
            }
            JsonElement value = switch (method) {
                case "class" -> JavaCodec.encode(loadClass(str(params, "name")));
                case "new" -> construct(params);
                case "get" -> getMember(params);
                case "set" -> setField(params);
                case "invoke" -> invoke(params);
                case "str" -> new JsonPrimitive(String.valueOf(JavaCodec.decodeTarget(params.get("target"))));
                case "items" -> items(params);
                case "members" -> members(params);
                case "mods" -> mods();
                case "server" -> JavaCodec.encode(server());
                case "level" -> JavaCodec.encode(level(params));
                case "entity" -> JavaCodec.encode(entity(params));
                case "release" -> JsonNull.INSTANCE;
                case "listen" -> JavaEvents.listen(params);
                case "unlisten" -> JavaEvents.unlisten(params);
                default -> throw new BridgeException("Unknown Java bridge call: " + method);
            };
            out.addProperty("ok", true);
            out.add("value", value);
        } catch (BridgeException e) {
            out.addProperty("ok", false);
            out.addProperty("error", e.getMessage());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            out.addProperty("ok", false);
            out.addProperty("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (RuntimeException | ReflectiveOperationException | LinkageError e) {
            out.addProperty("ok", false);
            out.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    private static String str(JsonObject params, String key) {
        if (!params.has(key) || !params.get(key).isJsonPrimitive()) {
            throw new BridgeException("Missing '" + key + "'");
        }
        return params.get(key).getAsString();
    }

    private static JsonArray args(JsonObject params) {
        return params.has("args") && params.get("args").isJsonArray() ? params.getAsJsonArray("args") : new JsonArray();
    }

    // ------------------------------------------------------------------
    // Classes
    // ------------------------------------------------------------------

    static Class<?> loadClass(String name) {
        ClassLoader loader = JavaBridge.class.getClassLoader();
        String candidate = name;
        while (true) {
            try {
                Class<?> type = Class.forName(candidate, false, loader);
                if (!JavaAccess.allowed(type)) {
                    throw new BridgeException("Class " + name + " is not available to scripts (config javaBridge.mode / extraPackages)");
                }
                return type;
            } catch (ClassNotFoundException | LinkageError e) {
                int dot = candidate.lastIndexOf('.');
                if (dot < 0) {
                    throw new BridgeException("Class not found: " + name);
                }
                // "a.b.Outer.Inner" is the nested class a.b.Outer$Inner
                candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
            }
        }
    }

    private static JsonElement construct(JsonObject params) throws ReflectiveOperationException {
        Class<?> type = loadClass(str(params, "name"));
        JsonArray given = args(params);
        Constructor<?> best = null;
        Object[] bestArgs = null;
        int bestScore = -1;
        for (Constructor<?> constructor : type.getConstructors()) {
            if (constructor.getParameterCount() != given.size()) {
                continue;
            }
            Object[] converted = JavaCodec.tryConvert(constructor.getParameterTypes(), given);
            int score = converted == null ? -1 : JavaCodec.score(constructor.getParameterTypes(), given);
            if (score > bestScore) {
                best = constructor;
                bestArgs = converted;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new BridgeException("No constructor of " + type.getSimpleName() + " takes these " + given.size() + " argument(s)");
        }
        return JavaCodec.encode(best.newInstance(bestArgs));
    }

    // ------------------------------------------------------------------
    // Members
    // ------------------------------------------------------------------

    /** {@code target} is a handle, or a class ({@code {"$c": name}}) for static members. */
    private static JsonElement getMember(JsonObject params) throws ReflectiveOperationException {
        Object target = JavaCodec.decodeTarget(params.get("target"));
        String name = str(params, "name");
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        boolean isStatic = target instanceof Class<?>;
        Object holder = isStatic ? null : target;
        check(type, name);
        Field field = findField(type, name, isStatic);
        if (field != null) {
            return JavaCodec.encode(field.get(holder));
        }
        if (hasMethod(type, name, isStatic)) {
            JsonObject marker = new JsonObject();
            marker.addProperty("$m", name);
            return marker;
        }
        if (!isStatic && type.isArray() && name.equals("length")) {
            return new JsonPrimitive(Array.getLength(target));
        }
        throw new BridgeException(type.getSimpleName() + " has no public field or method '" + name + "'");
    }

    private static JsonElement setField(JsonObject params) throws ReflectiveOperationException {
        Object target = JavaCodec.decodeTarget(params.get("target"));
        String name = str(params, "name");
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        boolean isStatic = target instanceof Class<?>;
        check(type, name);
        Field field = findField(type, name, isStatic);
        if (field == null || Modifier.isFinal(field.getModifiers())) {
            throw new BridgeException(type.getSimpleName() + " has no assignable public field '" + name + "'");
        }
        field.set(isStatic ? null : target, JavaCodec.decode(params.get("value"), field.getType()));
        return JsonNull.INSTANCE;
    }

    private static JsonElement invoke(JsonObject params) throws ReflectiveOperationException {
        Object target = JavaCodec.decodeTarget(params.get("target"));
        String name = str(params, "name");
        boolean isStatic = target instanceof Class<?>;
        Class<?> type = isStatic ? (Class<?>) target : target.getClass();
        check(type, name);
        JsonArray given = args(params);
        Method best = null;
        Object[] bestArgs = null;
        int bestScore = -1;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != given.size()
                    || (isStatic && !Modifier.isStatic(method.getModifiers()))) {
                continue;
            }
            Method callable = accessible(method);
            if (callable == null || !JavaAccess.allowed(callable.getDeclaringClass())) {
                continue;
            }
            Object[] converted = JavaCodec.tryConvert(callable.getParameterTypes(), given);
            int score = converted == null ? -1 : JavaCodec.score(callable.getParameterTypes(), given);
            if (score > bestScore) {
                best = callable;
                bestArgs = converted;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new BridgeException("No method " + type.getSimpleName() + "." + name + " takes these "
                    + given.size() + " argument(s)");
        }
        Object result = best.invoke(isStatic ? null : target, bestArgs);
        return best.getReturnType() == void.class ? JsonNull.INSTANCE : JavaCodec.encode(result);
    }

    private static void check(Class<?> type, String member) {
        if (!JavaAccess.allowed(type)) {
            throw new BridgeException("Class " + type.getName() + " is not available to scripts");
        }
        if (!JavaAccess.allowedMethod(member)) {
            throw new BridgeException("'" + member + "' is not available to scripts");
        }
    }

    private static Field findField(Class<?> type, String name, boolean wantStatic) {
        try {
            Field field = type.getField(name);
            if (Modifier.isStatic(field.getModifiers()) == wantStatic || !wantStatic) {
                return field;
            }
        } catch (NoSuchFieldException ignored) {
            // not a field
        }
        return null;
    }

    private static boolean hasMethod(Class<?> type, String name, boolean wantStatic) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && (!wantStatic || Modifier.isStatic(method.getModifiers()))) {
                return true;
            }
        }
        return false;
    }

    /** A public method declared on a public type (a public method of a hidden class is found on its interface). */
    private static Method accessible(Method method) {
        if (Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            return method;
        }
        return findPublic(method.getDeclaringClass(), method);
    }

    private static Method findPublic(Class<?> from, Method wanted) {
        List<Class<?>> candidates = new ArrayList<>();
        for (Class<?> iface : from.getInterfaces()) {
            candidates.add(iface);
        }
        if (from.getSuperclass() != null) {
            candidates.add(from.getSuperclass());
        }
        for (Class<?> candidate : candidates) {
            if (Modifier.isPublic(candidate.getModifiers())) {
                try {
                    return candidate.getMethod(wanted.getName(), wanted.getParameterTypes());
                } catch (NoSuchMethodException ignored) {
                    // keep looking
                }
            }
            Method deeper = findPublic(candidate, wanted);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Iteration and reflection helpers for the docs / completion
    // ------------------------------------------------------------------

    private static JsonElement items(JsonObject params) {
        Object target = JavaCodec.decodeTarget(params.get("target"));
        JsonArray out = new JsonArray();
        int limit = 100_000;
        if (target instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (limit-- <= 0) {
                    break;
                }
                JsonArray pair = new JsonArray();
                pair.add(JavaCodec.encode(entry.getKey()));
                pair.add(JavaCodec.encode(entry.getValue()));
                out.add(pair);
            }
        } else if (target instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (limit-- <= 0) {
                    break;
                }
                out.add(JavaCodec.encode(item));
            }
        } else if (target != null && target.getClass().isArray()) {
            for (int i = 0; i < Math.min(Array.getLength(target), limit); i++) {
                out.add(JavaCodec.encode(Array.get(target, i)));
            }
        } else if (target instanceof java.util.stream.Stream<?> stream) {
            stream.limit(limit).forEach(item -> out.add(JavaCodec.encode(item)));
        } else if (target instanceof java.util.Optional<?> optional) {
            optional.ifPresent(item -> out.add(JavaCodec.encode(item)));
        } else {
            throw new BridgeException("Not iterable: " + (target == null ? "null" : target.getClass().getSimpleName()));
        }
        return out;
    }

    private static net.minecraft.server.MinecraftServer server() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new BridgeException("No server is running");
        }
        return server;
    }

    private static net.minecraft.server.level.ServerLevel level(JsonObject params) {
        String id = params.has("dimension") ? params.get("dimension").getAsString() : "minecraft:overworld";
        var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(id.contains(":") ? id : "minecraft:" + id));
        var level = server().getLevel(key);
        if (level == null) {
            throw new BridgeException("No dimension " + id);
        }
        return level;
    }

    private static Object entity(JsonObject params) {
        java.util.UUID uuid = java.util.UUID.fromString(str(params, "uuid"));
        var player = server().getPlayerList().getPlayer(uuid);
        if (player != null) {
            return player;
        }
        for (var level : server().getAllLevels()) {
            var found = level.getEntity(uuid);
            if (found != null) {
                return found;
            }
        }
        throw new BridgeException("No entity with that uuid is loaded");
    }

    private static JsonElement mods() {
        JsonArray out = new JsonArray();
        for (var mod : net.neoforged.fml.ModList.get().getMods()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", mod.getModId());
            entry.addProperty("name", mod.getDisplayName());
            entry.addProperty("version", mod.getVersion().toString());
            out.add(entry);
        }
        return out;
    }

    /** Public fields and methods of a class or object, for completion and for "what can I call on this". */
    private static JsonElement members(JsonObject params) {
        Object target = JavaCodec.decodeTarget(params.get("target"));
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        if (!JavaAccess.allowed(type)) {
            throw new BridgeException("Class " + type.getName() + " is not available to scripts");
        }
        JsonObject out = new JsonObject();
        out.addProperty("class", type.getName());
        TreeSet<String> fields = new TreeSet<>();
        for (Field field : type.getFields()) {
            fields.add(field.getName());
        }
        JsonArray fieldList = new JsonArray();
        fields.forEach(fieldList::add);
        out.add("fields", fieldList);
        TreeSet<String> methods = new TreeSet<>();
        for (Method method : type.getMethods()) {
            if (JavaAccess.allowedMethod(method.getName())) {
                StringBuilder sb = new StringBuilder(method.getName()).append('(');
                Class<?>[] parameters = method.getParameterTypes();
                for (int i = 0; i < parameters.length; i++) {
                    sb.append(i == 0 ? "" : ", ").append(parameters[i].getSimpleName());
                }
                methods.add(sb.append(") -> ").append(method.getReturnType().getSimpleName()).toString());
            }
        }
        JsonArray methodList = new JsonArray();
        methods.forEach(methodList::add);
        out.add("methods", methodList);
        return out;
    }
}
