package net.xmilon.craftpy.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.xmilon.craftpy.util.Json;

class ModuleWorldStateTest {
    @Test
    void aNewWorldStartsWithEveryModuleOffAndKeepsItsOwnList(@TempDir Path dir) throws Exception {
        Path world = dir.resolve("world/craftpy/modules.json");
        try {
            ModuleLoader.useStateFile(world, true);
            assertTrue(Files.isRegularFile(world));
            assertEquals(List.of(), Json.read(world, ModuleLoader.ModuleState.class).enabled);
            assertTrue(ModuleLoader.usesWorldState());

            ModuleLoader.setEnabled("some_module", true);
            assertEquals(List.of("some_module"), Json.read(world, ModuleLoader.ModuleState.class).enabled);

            Path other = dir.resolve("other/craftpy/modules.json");
            ModuleLoader.useStateFile(other, true);
            assertEquals(List.of(), Json.read(other, ModuleLoader.ModuleState.class).enabled, "worlds do not share the list");

            ModuleLoader.useStateFile(world, true);
            ModuleLoader.setEnabled("some_module", false);
            assertFalse(Json.read(world, ModuleLoader.ModuleState.class).enabled.contains("some_module"));
        } finally {
            ModuleLoader.useGlobalState();
        }
        assertFalse(ModuleLoader.usesWorldState());
    }
}
