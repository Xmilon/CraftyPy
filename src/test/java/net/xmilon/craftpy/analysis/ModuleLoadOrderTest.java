package net.xmilon.craftpy.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ModuleLoadOrderTest {
    @Test
    void dependenciesLoadFirstUnknownOnesAreSkippedCyclesDoNotHang() {
        Map<String, List<String>> deps = new LinkedHashMap<>();
        deps.put("shop", List.of("economy>=1.0", "missing"));
        deps.put("economy", List.of("core"));
        deps.put("core", List.of());
        deps.put("a", List.of("b"));
        deps.put("b", List.of("a"));
        assertEquals(List.of("core", "economy", "shop", "b", "a"), ModuleGraph.loadOrder(deps));
    }
}
