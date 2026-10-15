package net.xmilon.craftpy.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModuleBuildRequestTest {
    @Test
    void aBareVersionIsTheOldExportOnlyRequest() {
        var request = ModuleBuilder.Request.parse("1.4.0");
        assertEquals("1.4.0", request.version());
        assertEquals(ModuleBuilder.Request.UPDATE, request.mode());
        assertFalse(request.install());
        assertNull(ModuleBuilder.Request.parse("").version());
        assertNull(ModuleBuilder.Request.parse(null).version());
    }

    @Test
    void theDialogSendsJson() {
        var request = ModuleBuilder.Request.parse(
                "{\"version\":\"2.0\",\"mode\":\"new\",\"newId\":\"my_pack\",\"newName\":\"My Pack\",\"install\":true}");
        assertEquals("2.0", request.version());
        assertEquals(ModuleBuilder.Request.NEW, request.mode());
        assertEquals("my_pack", request.newId());
        assertEquals("My Pack", request.newName());
        assertTrue(request.install());
    }

    @Test
    void unknownModesFallBackToUpdate() {
        assertEquals(ModuleBuilder.Request.UPDATE, ModuleBuilder.Request.parse("{\"mode\":\"destroy\"}").mode());
    }
}
