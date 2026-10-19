package net.xmilon.craftpy.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import net.xmilon.craftpy.analysis.PyImports;

/** The Building Blocks catalog: every listed file is there, headers parse, new files get connected to main.py. */
class BuildingBlocksTest {

    @Test
    void everyFileIsListedAndEveryBlockIsComplete() throws Exception {
        Path root = Path.of("src/main/resources/craftpy_blocks");
        long files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".py")).count();
        }
        List<BuildingBlocks.Block> all = BuildingBlocks.all();
        assertEquals(files, all.size(), "index.txt must list every block file");
        for (BuildingBlocks.Block block : all) {
            assertFalse(block.title().isBlank(), block.file());
            assertFalse(block.about().isBlank(), block.file());
            assertTrue(block.module().matches("[a-z][a-z0-9_]*"), block.file());
            assertFalse(block.template().contains("#:"), block.file());
            assertTrue(block.docFile().endsWith(".md"), block.file());
        }
        assertTrue(BuildingBlocks.byCategory(all).containsKey("UI elements"));
    }

    @Test
    void fillSnippetAndFreeNames() {
        var block = BuildingBlocks.parse("x/demo.py", "#: title: Demo\n#: module: demo\n#: category: Items\n"
                + "from craftpy import *\n\n{{name}} = Item(\"{{name}}\")  # {{id}}\n");
        assertEquals("Demo", block.title());
        assertEquals("ruby = Item(\"ruby\")  # rr\n", block.snippet("ruby", "rr"));
        assertTrue(block.snippet("ruby", "rr", "").startsWith("from craftpy import *\n"));
        assertTrue(block.snippet("ruby", "rr", "from craftpy import Item\n").startsWith("from craftpy import *\n"));
        assertEquals(block.snippet("ruby", "rr"), block.snippet("ruby", "rr", "from craftpy import * # existing\n"));
        assertTrue(block.snippet("ruby", "rr", "\"\"\"\nfrom craftpy import *\n\"\"\"\n").startsWith("from craftpy import *\n"));
        assertTrue(block.fill("ruby", "rr").startsWith("from craftpy import *"));
        assertEquals("demo", BuildingBlocks.freeModule("demo", Set.of("code/main.py")));
        assertEquals("demo_3", BuildingBlocks.freeModule("demo", Set.of("code/demo.py", "code/demo_2.py")));
        assertEquals("part_1x", BuildingBlocks.freeModule("1x", Set.of()));
        assertEquals(1, BuildingBlocks.search(BuildingBlocks.all(), "lightning wand").size());
    }

    @Test
    void docSectionsEndAtTheNextHeadingOfTheSameLevel() {
        String md = "# Top\nintro\n## Windows\nwin text\n### `Label`\nlabel\n## Java\njava text\n";
        assertEquals("## Windows\nwin text\n### `Label`\nlabel\n", BuildingBlocks.section(md, "Windows"));
        assertEquals("### `Label`\nlabel\n", BuildingBlocks.section(md, "label"));
        assertEquals(md, BuildingBlocks.section(md, "nothing"));
    }

    @Test
    void importsAreAddedOnceAfterTheLastImport() {
        String main = "# hello\nfrom craftpy import *\nimport items\n\n\ndef main(ctx):\n    pass\n";
        String added = PyImports.addImport(main, "shop");
        assertEquals("# hello\nfrom craftpy import *\nimport items\nimport shop\n\n\ndef main(ctx):\n    pass\n", added);
        assertEquals(added, PyImports.addImport(added, "shop"));
        assertEquals(main, PyImports.addImport(main, "items"));
        assertEquals("# only a comment\nimport shop\nx = 1", PyImports.addImport("# only a comment\nx = 1", "shop"));
        assertEquals("import a, shop as s\n", PyImports.addImport("import a, shop as s\n", "shop"));
    }

    @Test
    void everyDocsLinkOpensASectionOfTheDocs() throws Exception {
        for (BuildingBlocks.Block block : BuildingBlocks.all()) {
            String markdown = Files.readString(Path.of("src/main/resources/assets/craftpy/docs", block.docFile()));
            String section = BuildingBlocks.section(markdown, block.docHeading());
            assertTrue(section.length() < markdown.length() / 2, block.file() + " -> " + block.doc() + " matches no heading");
        }
    }
}
