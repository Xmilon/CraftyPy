package net.xmilon.craftpy.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

class StructureFilesTest {
    private static CompoundTag state(String name) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", name);
        CompoundTag properties = new CompoundTag();
        properties.putString("craftpy_light", "0");
        state.put("Properties", properties);
        return state;
    }

    @Test
    void projectBlocksInPalettesAreRenamedAndSurviveAFile(@TempDir Path dir) throws Exception {
        CompoundTag template = new CompoundTag();
        ListTag palette = new ListTag();
        palette.add(state("craftpy:pcblock_7"));
        palette.add(state("minecraft:stone"));
        template.put("palette", palette);
        ListTag variants = new ListTag();
        ListTag second = new ListTag();
        second.add(state("craftpy:pcblock_7"));
        variants.add(second);
        template.put("palettes", variants);

        StructureFiles.renameBlocks(template, name -> name.equals("craftpy:pcblock_7")
                ? StructureFiles.contentName("BLOCK:ruins:mossy_brick") : name);
        assertEquals("craftpy_content:ruins/mossy_brick", template.getList("palette", 10).getCompound(0).getString("Name"));
        assertFalse(template.getList("palette", 10).getCompound(0).contains("Properties"), "slot properties are dropped");
        assertEquals("minecraft:stone", template.getList("palette", 10).getCompound(1).getString("Name"));
        assertEquals("craftpy_content:ruins/mossy_brick", template.getList("palettes", 9).getList(0).getCompound(0).getString("Name"));

        Path file = dir.resolve("data/structure/hall.nbt");
        StructureFiles.write(template, file);
        assertEquals(template, StructureFiles.read(file));
    }

    @Test
    void dataFoldersBelongToTheProjectAndOtherFoldersAreNamespaces() {
        assertEquals("ruins", PackBuilder.dataNamespace("ruins", "structure", true));
        assertEquals("ruins", PackBuilder.dataNamespace("ruins", "worldgen", true));
        assertEquals("minecraft", PackBuilder.dataNamespace("ruins", "minecraft", true));
        assertNull(PackBuilder.dataNamespace("ruins", "Bad Name", true));
    }
}
