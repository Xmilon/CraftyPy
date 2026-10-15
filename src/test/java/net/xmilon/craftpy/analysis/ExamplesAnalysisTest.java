package net.xmilon.craftpy.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The analyzers against the real example project (several files), so they are not only tested on toy strings. */
class ExamplesAnalysisTest {
    private static final List<String> RUBY_FILES = List.of("main.py", "items.py", "blocks.py", "recipes.py",
            "surge.py", "events.py", "commands.py");

    private static String read(String project, String file) throws Exception {
        Path path = Path.of("examples", project, "code", file);
        assumeTrue(Files.isRegularFile(path), "examples not found");
        return Files.readString(path);
    }

    private static Map<String, String> rubyTexts() throws Exception {
        Map<String, String> texts = new LinkedHashMap<>();
        for (String file : RUBY_FILES) {
            texts.put("code/" + file, read("ruby_rush", file));
        }
        return texts;
    }

    @Test
    void rubyRushOutline() throws Exception {
        var ids = new ArrayList<String>();
        for (String file : List.of("items.py", "blocks.py")) {
            PyOutline.parse(read("ruby_rush", file)).stream()
                    .filter(s -> s.kind() == PyOutline.Kind.CONTENT).forEach(s -> ids.add(s.contentId()));
        }
        assertEquals(List.of("ruby", "ruby_sword", "ruby_apple", "ruby_ore", "ruby_block"), ids);
        assertTrue(PyOutline.parse(read("ruby_rush", "surge.py")).stream()
                .anyMatch(s -> s.name().equals("start_surge") && s.kind() == PyOutline.Kind.FUNCTION));
        assertTrue(PyOutline.parse(read("ruby_rush", "commands.py")).stream()
                .anyMatch(s -> s.name().equals("surge_command") && s.kind() == PyOutline.Kind.COMMAND));
        assertTrue(PyOutline.parse(read("ruby_rush", "events.py")).stream()
                .anyMatch(s -> s.name().equals("mined_ruby") && s.kind() == PyOutline.Kind.HANDLER));
    }

    @Test
    void rubyRushImportsAreAlreadyFineAndNothingIsMissing() throws Exception {
        for (String file : RUBY_FILES) {
            String text = read("ruby_rush", file);
            assertEquals(List.of(), PyImports.missing(text), file);
            PyImports.Result result = PyImports.organise(text);
            assertTrue(result.removed().isEmpty(), file + ": " + result.removed());
        }
        String events = read("ruby_rush", "events.py");
        PyImports.Result result = PyImports.organise(events);
        String organised = result.replacement() == null ? events : result.replacement().applyTo(events);
        assertTrue(organised.contains("import random\n\nfrom craftpy import *"), organised.substring(0, 200));
    }

    @Test
    void renamingRubyInRubyRushKeepsLongerNamesAlone() throws Exception {
        Map<String, String> texts = rubyTexts();
        List<String> paths = new ArrayList<>(texts.keySet());
        paths.add("assets/textures/ruby.png");
        paths.add("assets/textures/ruby_ore.png");
        ProjectScan.RenamePlan plan = ProjectScan.renameId(paths, texts, "ruby", "garnet");
        assertTrue(plan.ok(), plan.error());
        String items = plan.newTexts().get("code/items.py");
        assertTrue(items.contains("garnet = Item(\"garnet\", texture=\"garnet.png\")"), items);
        String blocks = plan.newTexts().get("code/blocks.py");
        assertTrue(blocks.contains("drops=(garnet, 2)"), blocks);
        assertTrue(blocks.contains("from items import garnet"), blocks);
        assertTrue(blocks.contains("ruby_ore = Block(\"ruby_ore\", texture=\"ruby_ore.png\""), "longer ids are untouched");
        assertFalse(plan.newTexts().getOrDefault("code/recipes.py", texts.get("code/recipes.py"))
                .contains("tag(\"ruby_gems\", [ruby"), "variable inside the list was renamed");
        assertTrue(plan.moves().contains(new ProjectScan.Move("assets/textures/ruby.png", "assets/textures/garnet.png")));
        assertEquals(1, plan.moves().size());
    }

    @Test
    void docstringForARubyRushFunction() throws Exception {
        String text = read("ruby_rush", "surge.py");
        int line = 0;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("def start_surge")) {
                line = i + 1;
            }
        }
        var result = PyDocstring.generate(text, line);
        assertTrue(result.replacement() != null, result.message());
        String out = result.replacement().applyTo(text);
        assertTrue(out.contains("def start_surge(miner):\n    \"\"\"Start surge.\n\n    Args:\n        miner: The miner.\n    \"\"\"\n"), out);
    }
}
