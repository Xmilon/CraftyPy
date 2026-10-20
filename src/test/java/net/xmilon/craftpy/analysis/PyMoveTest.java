package net.xmilon.craftpy.analysis;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyMoveTest {

    private static final String REWARDS = String.join("\n",
            "from craftpy import *",
            "import random",
            "import math",
            "",
            "BONUS = 3",
            "",
            "",
            "# how much a block pays",
            "def reward_for(block):",
            "    return random.randint(1, 3) * 2",
            "",
            "",
            "def total(blocks):",
            "    return sum(reward_for(b) for b in blocks)",
            "");

    @Test
    void movesToANewFileWithTheImportsItUses() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("code/rewards.py", REWARDS);
        texts.put("code/main.py", "from rewards import total, reward_for\nimport rewards\nprint(rewards.reward_for('x'))\n");
        PyMove.Plan plan = PyMove.move(texts, "code/rewards.py", "reward_for", "code/tools/pay.py");
        assertTrue(plan.ok(), plan.error());

        String target = plan.newTexts().get("code/tools/pay.py");
        assertEquals("from craftpy import *\nimport random\n\n\n# how much a block pays\ndef reward_for(block):\n"
                + "    return random.randint(1, 3) * 2\n", target);

        String source = plan.newTexts().get("code/rewards.py");
        assertFalse(source.contains("def reward_for"));
        assertTrue(source.contains("from tools.pay import reward_for"), source); // total() still calls it
        assertTrue(source.contains("def total(blocks):"));

        String main = plan.newTexts().get("code/main.py");
        assertTrue(main.contains("from rewards import total\nfrom tools.pay import reward_for"), main);
        assertTrue(main.contains("print(tools.pay.reward_for('x'))") && main.contains("import tools.pay"), main);
    }

    @Test
    void namesThatStayBehindAreImportedFromTheSource() {
        String code = "BONUS = 3\n\n\ndef bonus(x):\n    return x + BONUS\n";
        PyMove.Plan plan = PyMove.move(Map.of("code/a.py", code), "code/a.py", "bonus", "code/b.py");
        assertTrue(plan.ok(), plan.error());
        assertTrue(plan.newTexts().get("code/b.py").startsWith("from a import BONUS\n"), plan.newTexts().get("code/b.py"));
        assertFalse(plan.newTexts().get("code/a.py").contains("import"), plan.newTexts().get("code/a.py"));
    }

    @Test
    void filesThatWouldImportEachOtherAreRefused() {
        String code = "BONUS = 3\n\n\ndef bonus(x):\n    return x + BONUS\n\n\nprint(bonus(1))\n";
        PyMove.Plan plan = PyMove.move(Map.of("code/a.py", code), "code/a.py", "bonus", "code/b.py");
        assertFalse(plan.ok());
        assertTrue(plan.error().contains("import each other"), plan.error());
    }

    @Test
    void aMovedHandlerStillRuns() {
        String code = "from craftpy import *\n\n\n@on(\"player_join\")\ndef hello(event):\n    say(\"hi\")\n";
        PyMove.Plan plan = PyMove.move(Map.of("code/events.py", code), "code/events.py", "hello", "code/greet.py");
        assertTrue(plan.ok(), plan.error());
        assertTrue(plan.newTexts().get("code/greet.py").startsWith("from craftpy import *\n\n\n@on(\"player_join\")\ndef hello"));
        assertTrue(plan.newTexts().get("code/events.py").contains("import greet"), plan.newTexts().get("code/events.py"));
    }

    @Test
    void appendsToAnExistingFileAndDropsItsOldImport() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("code/a.py", "def f():\n    return 1\n");
        texts.put("code/b.py", "from a import f\n\n\ndef g():\n    return f()\n");
        PyMove.Plan plan = PyMove.move(texts, "code/a.py", "f", "code/b.py");
        assertTrue(plan.ok(), plan.error());
        assertEquals("\n\ndef g():\n    return f()\n\n\ndef f():\n    return 1\n", plan.newTexts().get("code/b.py"));
        assertEquals("", plan.newTexts().get("code/a.py"));
    }

    @Test
    void refusals() {
        Map<String, String> texts = Map.of("code/a.py", "def f():\n    pass\n", "code/b.py", "def f():\n    pass\n");
        assertTrue(PyMove.move(texts, "code/a.py", "f", "code/main.py").error().contains("main.py"));
        assertTrue(PyMove.move(texts, "code/a.py", "g", "code/c.py").error().contains("No top-level"));
        assertTrue(PyMove.move(texts, "code/a.py", "f", "code/b.py").error().contains("already defines"));
        assertTrue(PyMove.move(texts, "code/a.py", "f", "assets/x.py").error().contains("not a Python file"));
        assertEquals("tools.pay", PyMove.moduleOf("code/tools/pay.py"));
        assertEquals("tools", PyMove.moduleOf("code/tools/__init__.py"));
        assertNull(PyMove.moduleOf("code/my-file.py"));
    }
}
