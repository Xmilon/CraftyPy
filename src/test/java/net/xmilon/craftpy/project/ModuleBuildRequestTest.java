package net.xmilon.craftpy.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModuleBuildRequestTest {
    @Test
    void aNewModuleMovesItsOwnNamespace() {
        assertEquals("assets/my_pack/models/block/ruby.json",
                ModuleBuilder.entryName("assets", "ruby_rush/models/block/ruby.json", "ruby_rush", "my_pack"));
        assertEquals("data/my_pack/recipe/x.json", ModuleBuilder.entryName("data", "ruby_rush/recipe/x.json", "ruby_rush", "my_pack"));
        assertEquals("assets/textures/ruby.png", ModuleBuilder.entryName("assets", "textures/ruby.png", "ruby_rush", "my_pack"));
        assertEquals("assets/ruby_rush_extra/a.png", ModuleBuilder.entryName("assets", "ruby_rush_extra/a.png", "ruby_rush", "my_pack"));
        assertEquals("code/ruby_rush/a.py", ModuleBuilder.entryName("code", "ruby_rush/a.py", "ruby_rush", "my_pack"));
        assertEquals("assets/ruby_rush/a.png", ModuleBuilder.entryName("assets", "ruby_rush/a.png", null, "ruby_rush"));

        String code = "give(\"ruby_rush:ruby\")\nx = 'ruby_rush:ore'\ny = \"minecraft:stone\"  # ruby_rush:note\n";
        assertEquals("give(\"my_pack:ruby\")\nx = 'my_pack:ore'\ny = \"minecraft:stone\"  # ruby_rush:note\n",
                new String(ModuleBuilder.content("code/main.py", code.getBytes(), "ruby_rush", "my_pack")));
        byte[] png = {1, 2, 3};
        assertTrue(png == ModuleBuilder.content("assets/a.png", png, "ruby_rush", "my_pack"));
    }

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
