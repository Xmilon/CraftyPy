package net.xmilon.craftpy.project;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BundledModulesTest {
    @TempDir Path root;
    @Test void installPreservesExistingModulesStartsDisabledAndRespectsDeletion() throws Exception {
        String previous=System.getProperty("craftpy.home");
        try {
            System.setProperty("craftpy.home",root.toString()); PyForgePaths.ensureLayout(); ModuleLoader.useGlobalState();
            Path custom=PyForgePaths.modules().resolve("custom-jukebox.cpm");
            ModuleBuilder.build(Path.of("examples/jukebox"),custom); byte[] original=Files.readAllBytes(custom);
            BundledModules.install();
            assertArrayEquals(original,Files.readAllBytes(custom)); assertFalse(Files.exists(PyForgePaths.modules().resolve("jukebox.cpm")));
            assertFalse(Files.exists(PyForgePaths.projects().resolve("welcome")));
            assertFalse(ModuleLoader.list().stream().filter(m->!m.id.equals("jukebox")).anyMatch(m->m.enabled));
            Path removed=PyForgePaths.modules().resolve("waypoints.cpm"); assertTrue(Files.exists(removed));
            Files.delete(removed); BundledModules.install(); assertFalse(Files.exists(removed));
        } finally {
            if(previous==null) System.clearProperty("craftpy.home"); else System.setProperty("craftpy.home",previous);
            ModuleLoader.useGlobalState(); net.xmilon.craftpy.security.PermissionStore.forget();
        }
    }
}
