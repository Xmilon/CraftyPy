package net.xmilon.craftpy.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class JavaBridgeTest {
    public static class Fixture {
        public int count = 3;
        public static final String NAME = "fx";
        public final String locked = "x";
        public enum Color { RED, GREEN }

        public Fixture() {
        }

        public Fixture(int start, String label) {
            count = start;
        }

        public int add(int a, int b) {
            return a + b;
        }

        public double add(double a, double b) {
            return a + b;
        }

        public String paint(Color color) {
            return color.name();
        }

        public int sum(int[] values) {
            int total = 0;
            for (int v : values) {
                total += v;
            }
            return total;
        }

        public int size(List<Object> items) {
            return items.size();
        }

        public static Fixture make() {
            return new Fixture(9, "made");
        }
    }

    private static final String FX = "net.xmilon.craftpy.bridge.JavaBridgeTest.Fixture";

    @AfterEach
    void reset() {
        JavaAccess.configure(JavaAccess.Mode.RESTRICTED, List.of());
    }

    private static JsonObject call(String method, String json) {
        return JavaBridge.handle(method, JsonParser.parseString(json).getAsJsonObject());
    }

    private static JsonObject value(JsonObject reply) {
        assertTrue(reply.get("ok").getAsBoolean(), reply.toString());
        return reply.getAsJsonObject("value");
    }

    private static String newFixture() {
        int handle = value(call("new", "{name:'" + FX + "',args:[]}")).get("$h").getAsInt();
        return "{\"$h\":" + handle + "}";
    }

    @Test
    void overloadsAreChosenByTheArguments() {
        String target = newFixture();
        assertEquals(5, call("invoke", "{target:" + target + ",name:'add',args:[2,3]}").get("value").getAsInt());
        assertEquals(3.5, call("invoke", "{target:" + target + ",name:'add',args:[2.5,1]}").get("value").getAsDouble());
        assertEquals("GREEN", call("invoke", "{target:" + target + ",name:'paint',args:['green']}").get("value").getAsString());
        assertEquals(6, call("invoke", "{target:" + target + ",name:'sum',args:[[1,2,3]]}").get("value").getAsInt());
        assertEquals(2, call("invoke", "{target:" + target + ",name:'size',args:[['a',1]]}").get("value").getAsInt());
    }

    @Test
    void wholeNumberParametersRejectFractions() {
        String target = newFixture();
        JsonObject reply = call("invoke", "{target:" + target + ",name:'sum',args:[[1.5]]}");
        assertFalse(reply.get("ok").getAsBoolean(), reply.toString());
    }

    @Test
    void fieldsMethodsAndStatics() {
        String target = newFixture();
        assertEquals(3, call("get", "{target:" + target + ",name:'count'}").get("value").getAsInt());
        assertTrue(call("set", "{target:" + target + ",name:'count',value:11}").get("ok").getAsBoolean());
        assertEquals(11, call("get", "{target:" + target + ",name:'count'}").get("value").getAsInt());
        assertFalse(call("set", "{target:" + target + ",name:'locked',value:'y'}").get("ok").getAsBoolean(), "final fields stay final");
        assertEquals("add", value(call("get", "{target:" + target + ",name:'add'}")).get("$m").getAsString());
        assertEquals("fx", call("get", "{target:{'$c':'" + FX + "'},name:'NAME'}").get("value").getAsString());
        JsonObject made = value(call("invoke", "{target:{'$c':'" + FX + "'},name:'make',args:[]}"));
        assertEquals(9, call("get", "{target:{'$h':" + made.get("$h").getAsInt() + "},name:'count'}").get("value").getAsInt());
        assertEquals(FX.replace("Test.Fixture", "Test$Fixture"), made.get("$t").getAsString());
    }

    @Test
    void sameObjectKeepsItsHandleAndIsFreedByRelease() {
        String target = newFixture();
        int handle = JsonParser.parseString(target).getAsJsonObject().get("$h").getAsInt();
        int before = JavaBridge.liveHandles();
        call("release", "{release:[" + handle + "]}");
        assertEquals(before - 1, JavaBridge.liveHandles());
        assertFalse(call("get", "{target:" + target + ",name:'count'}").get("ok").getAsBoolean(), "released handles are gone");
    }

    @Test
    void dangerousClassesAndMethodsAreRefusedInEveryMode() {
        for (JavaAccess.Mode mode : new JavaAccess.Mode[]{JavaAccess.Mode.RESTRICTED, JavaAccess.Mode.FULL}) {
            JavaAccess.configure(mode, List.of());
            for (String name : List.of("java.lang.Runtime", "java.lang.System", "java.lang.ProcessBuilder", "java.io.File",
                    "java.nio.file.Files", "java.net.URL", "java.lang.Thread", "java.lang.Class", "java.lang.reflect.Method",
                    "java.lang.ClassLoader", "javax.script.ScriptEngine", "sun.misc.Unsafe", "net.neoforged.fml.loading.FMLLoader")) {
                assertFalse(call("class", "{name:'" + name + "'}").get("ok").getAsBoolean(), mode + " " + name);
            }
        }
        String target = newFixture();
        assertFalse(call("invoke", "{target:" + target + ",name:'getClass',args:[]}").get("ok").getAsBoolean());
    }

    @Test
    void restrictedModeKnowsPackagesAndOffModeNothing() {
        JavaAccess.configure(JavaAccess.Mode.RESTRICTED, List.of());
        assertFalse(call("class", "{name:'com.google.gson.JsonObject'}").get("ok").getAsBoolean());
        JavaAccess.configure(JavaAccess.Mode.RESTRICTED, List.of("com.google.gson."));
        assertTrue(call("class", "{name:'com.google.gson.JsonObject'}").get("ok").getAsBoolean());
        JavaAccess.configure(JavaAccess.Mode.FULL, List.of());
        assertTrue(call("class", "{name:'com.google.gson.JsonObject'}").get("ok").getAsBoolean());
        JavaAccess.configure(JavaAccess.Mode.OFF, List.of());
        assertFalse(call("class", "{name:'" + FX + "'}").get("ok").getAsBoolean());
    }

    @Test
    void membersListTheApi() {
        JsonObject members = value(call("members", "{target:{'$c':'" + FX + "'}}"));
        assertTrue(members.getAsJsonArray("fields").toString().contains("count"));
        assertTrue(members.getAsJsonArray("methods").toString().contains("add(int, int) -> int"));
        assertFalse(members.getAsJsonArray("methods").toString().contains("getClass"));
    }
}
