package net.xmilon.craftpy.catalog;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeInspectionTest {
    static boolean initialized, constructed;
    public static class Host { }
    public static class OtherHost { }
    public static class Compatible {
        static { initialized = true; }
        public Compatible(Host host, int priority) { constructed = true; }
        public void attack() { throw new AssertionError("inspection invoked API"); }
    }
    public static class Incompatible { public Incompatible(OtherHost host) { constructed = true; } }

    @Test void inspectionDoesNotInitializeOrConstructAndExplainsIncompatibleHosts() {
        var info = NativeInspection.inspect(Compatible.class, Host.class);
        assertFalse(initialized); assertFalse(constructed);
        assertFalse(info.get("verified").getAsBoolean());
        assertTrue(info.getAsJsonArray("constructors").get(0).getAsJsonObject().get("compatible").getAsBoolean());
        var rejected = NativeInspection.inspect(Incompatible.class, Host.class).getAsJsonArray("constructors").get(0).getAsJsonObject();
        assertFalse(rejected.get("compatible").getAsBoolean());
        assertTrue(rejected.get("reason").getAsString().contains(OtherHost.class.getName()));
        assertFalse(constructed);
    }
}
