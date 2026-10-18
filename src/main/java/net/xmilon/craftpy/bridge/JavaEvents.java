package net.xmilon.craftpy.bridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.bridge.JavaBridge.BridgeException;
import net.xmilon.craftpy.python.PyRuntime;
import net.xmilon.craftpy.registry.SlotRegistry;

/**
 * Events and callbacks across the bridge: any NeoForge event class can be subscribed to by name, and a Python
 * function can be handed to Java wherever it wants a one-method interface. Python runs while the server thread waits
 * (still serving the bridge calls the script makes), so a handler can cancel or change the event.
 */
final class JavaEvents {
    private static final long WAIT_NANOS = 1_000_000_000L;
    private static final Map<String, Consumer<Event>> LISTENERS = new ConcurrentHashMap<>();

    private JavaEvents() {
    }

    static JsonElement listen(JsonObject params) {
        String name = params.get("name").getAsString();
        Class<?> type = JavaBridge.loadClass(name);
        if (!Event.class.isAssignableFrom(type)) {
            throw new BridgeException(type.getSimpleName() + " is not a NeoForge event");
        }
        EventPriority priority;
        try {
            priority = EventPriority.valueOf(params.has("priority") ? params.get("priority").getAsString().toUpperCase() : "NORMAL");
        } catch (IllegalArgumentException e) {
            throw new BridgeException("priority must be highest, high, normal, low or lowest");
        }
        boolean receiveCancelled = params.has("receive_cancelled") && params.get("receive_cancelled").getAsBoolean();
        String key = type.getName() + "|" + priority + "|" + receiveCancelled;
        LISTENERS.computeIfAbsent(key, k -> {
            Consumer<Event> consumer = event -> dispatch(type.getName(), event);
            addListener(priority, receiveCancelled, type, consumer);
            return consumer;
        });
        return new JsonPrimitive(key);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addListener(EventPriority priority, boolean receiveCancelled, Class<?> type, Consumer<Event> consumer) {
        NeoForge.EVENT_BUS.addListener(priority, receiveCancelled, (Class) type, (Consumer) consumer);
    }

    static JsonElement unlisten(JsonObject params) {
        Consumer<Event> consumer = LISTENERS.remove(params.get("key").getAsString());
        if (consumer != null) {
            NeoForge.EVENT_BUS.unregister(consumer);
        }
        return JsonNull.INSTANCE;
    }

    private static void dispatch(String eventClass, Event event) {
        String name = "java:" + eventClass;
        if (!SlotRegistry.handlesEvent(name)) {
            return; // nobody scripted this event: cost is one map lookup
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        JsonObject context = new JsonObject();
        context.add("java", JavaCodec.encode(event));
        int handle = context.getAsJsonObject("java").get("$h").getAsInt();
        try {
            CompletableFuture<JsonObject> done = PyRuntime.get().triggerEvent(name, context);
            await(server, done);
        } catch (RuntimeException e) {
            CraftPyLog.LOGGER.warn("Python handler for {} failed: {}", eventClass, e.toString());
        } finally {
            JavaBridge.release(handle);
        }
    }

    /** Waits for the worker while still running server tasks, so the script's own bridge calls can complete. */
    private static void await(MinecraftServer server, CompletableFuture<?> future) {
        if (!server.isSameThread()) {
            return;
        }
        long deadline = System.nanoTime() + WAIT_NANOS;
        server.managedBlock(() -> future.isDone() || System.nanoTime() > deadline);
    }

    // ------------------------------------------------------------------
    // Python functions as Java interfaces
    // ------------------------------------------------------------------

    /** A Java interface that calls Python function {@code functionId} in the worker of {@code owner}, who handed it over. */
    static Object callback(String owner, int functionId, Class<?> type) {
        if (!type.isInterface() || !JavaAccess.allowed(type)) {
            throw new BridgeException("A Python function can only be passed where Java expects an interface"
                    + " (Runnable, Consumer, Function, Predicate, Supplier...), not " + type.getSimpleName());
        }
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> "python function " + functionId;
                };
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return callPython(owner, functionId, method, args);
        };
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object callPython(String owner, int functionId, Method method, Object[] args) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        JsonArray encoded = new JsonArray();
        if (args != null) {
            for (Object arg : args) {
                encoded.add(JavaCodec.encode(arg));
            }
        }
        CompletableFuture<JsonObject> future = PyRuntime.get().callFunction(owner, functionId, encoded);
        if (server != null && server.isSameThread()) {
            await(server, future);
        }
        JsonObject result;
        try {
            result = future.getNow(null);
        } catch (RuntimeException e) {
            throw new BridgeException("Python function failed: " + e.getMessage());
        }
        if (result == null) {
            throw new BridgeException("Python function " + functionId + " did not answer in time");
        }
        if (result.has("error")) {
            throw new BridgeException("Python function failed: " + result.get("error").getAsString());
        }
        Class<?> returns = method.getReturnType();
        return returns == void.class ? null : JavaCodec.decode(result.get("value"), returns);
    }
}
