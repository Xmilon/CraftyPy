package net.xmilon.craftpy.dynamics;

import net.xmilon.craftpy.project.ModuleBuilder;
import net.xmilon.craftpy.project.ModuleInfo;
import net.xmilon.craftpy.project.ModuleSide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientModulePackTest {

    @Test
    void sides() {
        assertEquals(ModuleSide.BOTH, ModuleSide.of(null));
        assertEquals(ModuleSide.BOTH, ModuleSide.of("weird"));
        assertEquals(ModuleSide.CLIENT, ModuleSide.of(" Client "));
        assertEquals(ModuleSide.SERVER, ModuleSide.of("server"));
        assertFalse(ModuleSide.CLIENT.runsOnServer());
        assertFalse(ModuleSide.SERVER.hasAssets());
        assertTrue(ModuleSide.CLIENT.packs("assets") && !ModuleSide.CLIENT.packs("code") && !ModuleSide.CLIENT.packs("data"));
        assertTrue(ModuleSide.SERVER.packs("code") && ModuleSide.SERVER.packs("data") && !ModuleSide.SERVER.packs("assets"));
        assertTrue(ModuleSide.BOTH.packs("assets") && ModuleSide.BOTH.packs("code"));
        assertEquals(ModuleSide.BOTH, ModuleBuilder.Request.parse("1.0").side());
    }

    @Test
    void entriesGoWhereAProjectsWould(@TempDir Path dir) {
        Path root = dir.toAbsolutePath().normalize();
        assertEquals(root.resolve("assets/shiny/textures/gem.png"), ClientModulePack.target(root, "shiny", "assets/textures/gem.png"));
        assertEquals(root.resolve("assets/minecraft/textures/block/stone.png"),
                ClientModulePack.target(root, "shiny", "assets/minecraft/textures/block/stone.png"));
        assertEquals(root.resolve("assets/shiny/sounds.json"), ClientModulePack.target(root, "shiny", "assets/sounds.json"));
        assertNull(ClientModulePack.target(root, "shiny", "code/main.py"));
        assertNull(ClientModulePack.target(root, "shiny", "assets/minecraft/../../../evil.png"));
        assertNull(ClientModulePack.target(root, "shiny", "assets/Bad Name/x.png"));
    }

    @Test
    void buildsAPackFromTheModuleFiles(@TempDir Path dir) throws IOException {
        Path cpm = dir.resolve("shiny.cpm");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cpm))) {
            put(zip, "module.json", "{\"id\":\"shiny\",\"side\":\"client\"}");
            put(zip, "assets/minecraft/textures/block/stone.png", "png");
            put(zip, "assets/lang/en_us.json", "{}");
            put(zip, "code/main.py", "print('never runs')");
        }
        ModuleInfo info = new ModuleInfo();
        info.id = "shiny";
        info.path = cpm.toString();
        Path root = dir.resolve("pack");
        ClientModulePack.build(root, List.of(info));
        assertTrue(Files.readString(root.resolve("pack.mcmeta")).contains("pack_format"));
        assertEquals("png", Files.readString(root.resolve("assets/minecraft/textures/block/stone.png")));
        assertTrue(Files.isRegularFile(root.resolve("assets/shiny/lang/en_us.json")));
        assertFalse(Files.exists(root.resolve("code")));
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
