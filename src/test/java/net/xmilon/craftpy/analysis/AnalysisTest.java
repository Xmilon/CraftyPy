package net.xmilon.craftpy.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AnalysisTest {
    private static final String SAMPLE = """
            # demo
            from craftpy import *
            import random

            RUBY_LIMIT = 10
            ruby = Item("ruby", texture="ruby.png")
            ruby_ore = Block("ruby_ore", texture="ruby_ore.png", drops=(ruby, 2))


            class Counter:
                \"\"\"Counts.\"\"\"

                def bump(self, amount: int = 1) -> int:
                    return amount


            @on.block_break(block=ruby_ore)
            def mined(event):
                text = \"\"\"
            def fake():
            \"\"\"
                event.player.say(f"{ruby_ore} {random.random()}")


            @command("heal")
            def heal(ctx):
                pass
            """;

    @Test
    void lexSeparatesStringsAndComments() {
        String text = "x = 'a#b' # c\ny = \"\"\"q\n#r\n\"\"\"\n";
        String masked = PyLex.maskNonCode(text);
        assertEquals(text.length(), masked.length());
        assertFalse(masked.contains("#"));
        assertTrue(masked.startsWith("x = "));
    }

    @Test
    void outlineFindsSymbols() {
        List<PyOutline.Symbol> symbols = PyOutline.parse(SAMPLE);
        Map<String, PyOutline.Symbol> byName = new LinkedHashMap<>();
        symbols.forEach(s -> byName.put(s.name(), s));
        assertEquals(PyOutline.Kind.CONSTANT, byName.get("RUBY_LIMIT").kind());
        assertEquals(PyOutline.Kind.CONTENT, byName.get("ruby_ore").kind());
        assertEquals("ruby_ore", byName.get("ruby_ore").contentId());
        assertEquals(PyOutline.Kind.CLASS, byName.get("Counter").kind());
        assertEquals(PyOutline.Kind.METHOD, byName.get("bump").kind());
        assertEquals("Counter", byName.get("bump").parent());
        assertEquals(PyOutline.Kind.HANDLER, byName.get("mined").kind());
        assertEquals(PyOutline.Kind.COMMAND, byName.get("heal").kind());
        assertFalse(byName.containsKey("fake"), "def inside a string is not a symbol");
        PyOutline.Symbol mined = byName.get("mined");
        assertTrue(mined.endLine() >= mined.line() + 4);
        List<PyOutline.Symbol> chain = PyOutline.chain(symbols, byName.get("bump").line() + 1);
        assertEquals(List.of("Counter", "bump"), chain.stream().map(PyOutline.Symbol::name).toList());
    }

    @Test
    void organiseSortsMergesAndDropsUnused() {
        String text = "import random\nimport time\nfrom craftpy import *\nfrom typing import List, Dict\nimport math\n\nprint(random.random(), Dict)\n";
        PyImports.Result result = PyImports.organise(text);
        String organised = result.replacement().applyTo(text);
        assertEquals("import random\nfrom typing import Dict\n\nfrom craftpy import *\n\nprint(random.random(), Dict)\n", organised);
        assertEquals(3, result.removed().size());
    }

    @Test
    void organiseKeepsNamesUsedInFStrings() {
        String text = "import random\nfrom craftpy import *\n\nx = f\"{random.random()}\"\n";
        PyImports.Result result = PyImports.organise(text);
        assertTrue(result.removed().isEmpty(), result.removed().toString());
    }

    @Test
    void missingImportsFindsStdlibModules() {
        assertEquals(List.of("math", "random"), PyImports.missing("from craftpy import *\nx = random.random() + math.pi\n"));
        assertEquals(List.of(), PyImports.missing("import random\nx = random.random()\n"));
        assertEquals(List.of(), PyImports.missing("def f(random):\n    return random.x\n"));
        String source = "from craftpy import *\nx = random.random()\n";
        PyImports.Result add = PyImports.organise(source, List.of("random"), false);
        assertEquals("import random\n\nfrom craftpy import *\nx = random.random()\n", add.replacement().applyTo(source));
    }

    @Test
    void docstringIsGenerated() {
        String text = "class A:\n    def go(self, speed: int = 3, *names):\n        if speed:\n            raise ValueError('x')\n        return speed\n";
        PyDocstring.Result result = PyDocstring.generate(text, 2);
        assertNotNull(result.replacement());
        String out = result.replacement().applyTo(text);
        assertTrue(out.contains("        \"\"\"Go.\n"), out);
        assertTrue(out.contains("speed (int): The speed."), out);
        assertTrue(out.contains("names: The names."), out);
        assertTrue(out.contains("Returns:"), out);
        assertTrue(out.contains("ValueError: When it fails."), out);
        assertNull(PyDocstring.generate(out, 2).replacement(), "second run: already has one");
    }

    @Test
    void assetUsageAndMissing() {
        List<String> paths = List.of("code/main.py", "assets/textures/ruby.png", "assets/textures/unused.png");
        Map<String, String> texts = Map.of("code/main.py",
                "a = Item('ruby', texture='ruby.png')\nb = Item('x', texture='ghost.png')\nc = tex('minecraft:dirt.png')\n");
        ProjectScan.UsageReport report = ProjectScan.assetUsage(paths, texts);
        assertEquals(1, report.assets().get(0).references().size());
        assertEquals(0, report.assets().get(1).references().size());
        assertEquals(1, report.missing().size());
        assertEquals("ghost.png", report.missing().get(0).value());
    }

    @Test
    void renameIdTouchesCodeAssetsAndFiles() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("code/main.py", SAMPLE);
        texts.put("assets/models/ruby_ore.json", "{\"textures\": {\"all\": \"demo:block/ruby_ore\"}}");
        List<String> paths = List.of("code/main.py", "assets/models/ruby_ore.json", "assets/textures/ruby_ore.png");
        ProjectScan.RenamePlan plan = ProjectScan.renameId(paths, texts, "ruby_ore", "garnet_ore");
        assertTrue(plan.ok(), plan.error());
        String py = plan.newTexts().get("code/main.py");
        assertTrue(py.contains("garnet_ore = Block(\"garnet_ore\", texture=\"garnet_ore.png\""), py);
        assertTrue(py.contains("@on.block_break(block=garnet_ore)"), py);
        assertTrue(py.contains("f\"{garnet_ore} {random.random()}\""), py);
        assertTrue(plan.newTexts().get("assets/models/ruby_ore.json").contains("demo:block/garnet_ore"));
        assertEquals(2, plan.moves().size());
        assertEquals("assets/textures/garnet_ore.png", plan.moves().get(1).to());
        assertFalse(ProjectScan.renameId(paths, texts, "ruby_ore", "ruby").ok(), "ruby is declared");
        assertFalse(ProjectScan.renameId(paths, texts, "ruby_ore", "Bad Id").ok());
        assertFalse(ProjectScan.renameId(paths, texts, "nope", "fine").ok());
    }

    @Test
    void renameStringOnlyMatchesWholeReferences() {
        assertEquals("demo:garnet", ProjectScan.renameString("demo:ruby", "ruby", "garnet"));
        assertEquals("block/garnet.png", ProjectScan.renameString("block/ruby.png", "ruby", "garnet"));
        assertEquals("block.demo.garnet", ProjectScan.renameString("block.demo.ruby", "ruby", "garnet"));
        assertEquals("You found a ruby!", ProjectScan.renameString("You found a ruby!", "ruby", "garnet"));
        assertEquals("ruby_ore", ProjectScan.renameString("ruby_ore", "ruby", "garnet"));
    }

    @Test
    void moduleGraphShowsMissingAndCycles() {
        var a = new ModuleGraph.Node("a", true, true, List.of("b>=1.0", "ghost"));
        var b = new ModuleGraph.Node("b", true, true, List.of());
        var rows = ModuleGraph.tree(List.of(a, b));
        assertEquals("a", rows.get(0).id());
        assertEquals(ModuleGraph.Status.MISSING, rows.get(2).status());
        assertEquals(1, ModuleGraph.problems(List.of(a, b)).size());
        var x = new ModuleGraph.Node("x", true, false, List.of("y"));
        var y = new ModuleGraph.Node("y", true, false, List.of("x"));
        assertTrue(ModuleGraph.problems(List.of(x, y)).stream().anyMatch(p -> p.contains("circular")));
    }
}
