package net.xmilon.craftpy.project;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleReadmeTest {

    @Test
    void writesWhatIsInside() {
        String code = String.join("\n",
                "from craftpy import *",
                "gem = Item(\"gem\", name=\"Gem\")",
                "ore = Block(\"gem_ore\")",
                "recipe(\"create:mixing\", ingredients=[{\"item\": \"mekanism:ingot_osmium\"}], results=[{\"id\": gem}])",
                "@on.block_break(block=ore)",
                "def mined(event): pass",
                "@command(\"gift\")",
                "def gift(ctx): pass",
                "@window(\"shop\", title=\"Shop\")",
                "class Shop(Window): pass",
                "x = \"minecraft:stone\"",
                "");
        String text = ModuleReadme.generate("gems", "Gems", "Shiny things.", "Alex", "1.2.0", "2.2", ModuleSide.BOTH,
                Map.of("code/main.py", code));
        assertTrue(text.startsWith("# Gems\n\nShiny things.\n"), text);
        assertTrue(text.contains("| Module id | `gems` |") && text.contains("| Works on | client and server |"), text);
        assertTrue(text.contains("**Items**: `gems:gem`") && text.contains("**Blocks**: `gems:gem_ore`"), text);
        assertTrue(text.contains("`/gems:gift`") && text.contains("**Reacts to**: block_break") && text.contains("**Windows**: shop"), text);
        assertTrue(text.contains("| Uses content of | create, mekanism |"), text);
        assertFalse(text.contains("minecraft,"), text);
    }

    @Test
    void theTemplateCountsAsNoReadme() {
        assertTrue(ModuleReadme.needsGenerating(null));
        assertTrue(ModuleReadme.needsGenerating("# X\n\n" + ModuleReadme.TEMPLATE_MARK + " This Markdown"));
        assertFalse(ModuleReadme.needsGenerating("# My mod\n\nIt does things."));
    }
}
