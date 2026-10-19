package net.xmilon.craftpy.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import net.xmilon.craftpy.bridge.JavaAccess;

/** Reflection reads signatures, never constructors, static fields, getters or user methods. */
public final class NativeInspection {
    private NativeInspection() { }

    public static JsonObject inspect(Class<?> type, Class<?> host) {
        JsonObject out = new JsonObject();
        out.addProperty("class", type.getName());
        out.addProperty("base_class", type.getSuperclass() == null ? "" : type.getSuperclass().getName());
        out.addProperty("discovered", true); out.addProperty("verified", false);
        out.addProperty("bridge", "Public API calls require the owner's approved java permission and Java bridge class/method access.");
        JsonArray methods = new JsonArray();
        Arrays.stream(type.getMethods()).filter(m -> m.getDeclaringClass() != Object.class && JavaAccess.allowedMethod(m.getName()))
                .map(m -> m.getReturnType().getTypeName() + " " + m.getName() + "(" + parameters(m.getParameterTypes()) + ")")
                .distinct().sorted().limit(160).forEach(methods::add);
        out.add("public_methods", methods);
        JsonArray constructors = new JsonArray();
        for (var constructor : type.getConstructors()) {
            Class<?>[] params = constructor.getParameterTypes();
            boolean compatible = Modifier.isPublic(type.getModifiers()) && !Modifier.isAbstract(type.getModifiers())
                    && params.length > 0 && params[0].isAssignableFrom(host)
                    && Arrays.stream(params).skip(1).allMatch(NativeInspection::scalar);
            JsonObject entry = new JsonObject(); entry.addProperty("signature", type.getName() + "(" + parameters(params) + ")");
            entry.addProperty("compatible", compatible);
            entry.addProperty("reason", compatible ? "Host class matches; explicit scalar arguments and Java permission are still required."
                    : params.length == 0 ? "Constructor has no entity parameter."
                    : !params[0].isAssignableFrom(host) ? "Requires " + params[0].getName() + "; host is " + host.getName() + "."
                    : "Requires a public concrete class and scalar arguments; complex native internals need an explicit adapter.");
            constructors.add(entry);
        }
        out.add("constructors", constructors);
        return out;
    }

    public static boolean scalar(Class<?> type) {
        return type == String.class || type.isEnum() || type.isPrimitive() && type != void.class && type != char.class;
    }

    private static String parameters(Class<?>[] types) {
        return String.join(", ", Arrays.stream(types).map(Class::getTypeName).toList());
    }
}
