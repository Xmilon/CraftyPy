package net.xmilon.craftpy.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Duplicate-id detection shown as conflict badges on the Modules page. */
class ModuleLoaderDuplicatesTest {

    private static ModuleInfo module(String id, boolean enabled) {
        ModuleInfo info = new ModuleInfo();
        info.id = id;
        info.name = id;
        info.enabled = enabled;
        return info;
    }

    @Test
    void uniqueIdsAreNeverDuplicates() {
        ModuleInfo a = module("alpha", true);
        ModuleInfo b = module("beta", true);
        ModuleLoader.markDuplicates(List.of(a, b));
        assertFalse(a.duplicate);
        assertFalse(b.duplicate);
        assertTrue(a.primary);
        assertTrue(b.primary);
    }

    @Test
    void firstEnabledFileWinsAmongDuplicates() {
        ModuleInfo disabled = module("same", false);
        ModuleInfo enabledFirst = module("same", true);
        ModuleInfo enabledSecond = module("same", true);
        ModuleLoader.markDuplicates(List.of(disabled, enabledFirst, enabledSecond));
        assertTrue(enabledFirst.primary);
        assertFalse(enabledFirst.duplicate);
        assertTrue(enabledSecond.duplicate);
        assertFalse(enabledSecond.primary);
        assertTrue(disabled.duplicate);
    }

    @Test
    void allDisabledDuplicateStillPicksOnePrimary() {
        ModuleInfo a = module("same", false);
        ModuleInfo b = module("same", false);
        ModuleLoader.markDuplicates(List.of(a, b));
        assertTrue(a.primary);
        assertFalse(a.duplicate);
        assertTrue(b.duplicate);
    }
}
