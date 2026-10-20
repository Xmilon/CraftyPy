package net.xmilon.craftpy.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.xmilon.craftpy.client.TextFormat;

class RefactorTest {
    @Test
    void extractVariableInsertsAboveTheStatement() {
        String text = "def f(p):\n    if p:\n        say(p.name + \"!\", 3)\n";
        int start = text.indexOf("p.name + \"!\"");
        int end = start + "p.name + \"!\"".length();
        PyRefactor.Result result = PyRefactor.extractVariable(text, start, end, "greeting");
        assertNotNull(result.replacement(), result.message());
        assertEquals("def f(p):\n    if p:\n        greeting = p.name + \"!\"\n        say(greeting, 3)\n",
                result.replacement().applyTo(text));
    }

    @Test
    void extractVariableHandlesContinuationLinesAndRefusals() {
        String text = "shaped(a,\n       \"RR\",\n       R=ruby)\n";
        int start = text.indexOf("ruby");
        PyRefactor.Result result = PyRefactor.extractVariable(text, start, start + 4, "gem");
        assertEquals("gem = ruby\nshaped(a,\n       \"RR\",\n       R=gem)\n", result.replacement().applyTo(text));
        assertNull(PyRefactor.extractVariable(text, 0, 0, "x").replacement());
        assertNull(PyRefactor.extractVariable(text, text.indexOf("RR") + 1, text.indexOf("RR") + 2, "x").replacement(),
                "inside a string");
        assertNull(PyRefactor.extractVariable("x = (1 + 2)\n", 4, 7, "y").replacement(), "unbalanced");
        assertNull(PyRefactor.extractVariable("x = 1\n", 4, 5, "class").replacement(), "keyword name");
    }

    @Test
    void extractFunctionInfersParametersAndReturnValues() {
        String text = """
                import random


                def start(player, rounds):
                    total = 0
                    for i in range(rounds):
                        roll = random.randint(1, 6)
                        total += roll
                        player.say(f"roll {roll}")
                    print(total)
                """;
        PyRefactor.Result result = PyRefactor.extractFunction(text, 5, 8, "play_rounds");
        assertNotNull(result.replacement(), result.message());
        String out = result.replacement().applyTo(text);
        assertEquals("""
                import random


                def play_rounds(player, rounds, total):
                    for i in range(rounds):
                        roll = random.randint(1, 6)
                        total += roll
                        player.say(f"roll {roll}")
                    return total


                def start(player, rounds):
                    total = 0
                    total = play_rounds(player, rounds, total)
                    print(total)
                """, out);
    }

    @Test
    void extractFunctionRefusesReturnAndLoopFragments() {
        String text = "def f(x):\n    if x:\n        return 1\n    for i in x:\n        break\n";
        assertNull(PyRefactor.extractFunction(text, 2, 2, "g").replacement());
        assertNull(PyRefactor.extractFunction(text, 4, 4, "g").replacement());
        assertNull(PyRefactor.extractFunction(text, 0, 1, "g").replacement(), "includes the def header");
    }

    @Test
    void textFormatDetectsAndConverts() {
        assertEquals(TextFormat.LineEnding.LF, TextFormat.detect("a\nb\n"));
        assertEquals(TextFormat.LineEnding.CRLF, TextFormat.detect("a\r\nb\r\n"));
        assertEquals(TextFormat.LineEnding.MIXED, TextFormat.detect("a\r\nb\n"));
        assertEquals(TextFormat.LineEnding.NONE, TextFormat.detect("abc"));
        assertEquals("a\r\nb\r\n", TextFormat.convert("a\nb\r\n", true));
        assertEquals("a\nb\n", TextFormat.convert("a\r\nb\r\n", false));
        assertTrue(TextFormat.hasBom("\uFEFFx"));
        assertEquals("x", TextFormat.stripBom("\uFEFFx"));
        assertEquals("UTF-8 BOM (1 invalid)", TextFormat.encodingLabel("\uFEFFa\uFFFD"));
    }
}
