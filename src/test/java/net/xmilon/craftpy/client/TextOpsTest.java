package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;

import org.junit.jupiter.api.Test;

class TextOpsTest {

    private static String apply(String text, TextOps.Edit edit) {
        return text.substring(0, edit.start()) + edit.text() + text.substring(edit.end());
    }

    @Test
    void smartHomeTogglesBetweenIndentAndColumnZero() {
        String text = "x\n    foo()";
        int end = text.length();
        assertEquals(6, TextOps.smartHome(text, end));
        assertEquals(2, TextOps.smartHome(text, 6));
        assertEquals(6, TextOps.smartHome(text, 2));
    }

    @Test
    void sortAndDedupeWholeDocumentOrSelection() {
        String text = "b\na\nb\nc";
        assertEquals("a\nb\nb\nc", apply(text, TextOps.sortLines(text, 0, 0)));
        assertEquals("b\na\nc", apply(text, TextOps.removeDuplicateLines(text, 0, 0)));
        // Only lines 1-2 ("a", "b") selected.
        assertEquals("b\na\nb\nc", apply(text, TextOps.sortLines(text, 2, 5)));
    }

    @Test
    void joinAndSplitLines() {
        String text = "x = (1,\n     2)\ny";
        TextOps.Edit join = TextOps.joinLines(text, 3, 3);
        assertEquals("x = (1, 2)\ny", apply(text, join));
        assertNull(TextOps.joinLines("only", 2, 2));

        String code = "    call(a, b)";
        TextOps.Edit split = TextOps.splitLine(code, 12);
        assertEquals("    call(a, \n    b)", apply(code, split));
        assertEquals(12, split.cursor());
    }

    @Test
    void trimForSave() {
        assertEquals("a\n  b\n", TextOps.trimForSave("a   \n  b\t\n\n\n"));
        assertEquals("x\n", TextOps.trimForSave("x"));
        assertEquals("", TextOps.trimForSave(""));
    }

    @Test
    void autoPairAndSurround() {
        // "(" typed at 3 in "abc(" -> add ")"
        String text = "abc(";
        assertEquals("abc()", apply(text, TextOps.afterTyped(text, 3, '(', "")));
        // typed before a word: no pair
        assertNull(TextOps.afterTyped("(word", 0, '(', ""));
        // apostrophe inside a word: no pair
        assertNull(TextOps.afterTyped("it'", 2, '\'', ""));
        // surround: selection "name" replaced by '"' at 4 -> '"name"'
        String typed = "x = \"";
        TextOps.Edit surround = TextOps.afterTyped(typed, 4, '"', "name");
        assertEquals("x = \"name\"", apply(typed, surround));
        assertEquals(5, surround.cursor());
        assertEquals(9, surround.selectionEnd());
        // closing quote of an open string is not doubled
        assertNull(TextOps.afterTyped("s = \"abc\"", 8, '"', ""));
        assertEquals(true, TextOps.shouldSkipCloser("f()", 2, ')'));
    }

    @Test
    void dedentElseToItsIf() {
        String text = "if a:\n    x = 1\n    else:";
        TextOps.Edit edit = TextOps.dedentBlockKeyword(text, text.length());
        assertEquals("if a:\n    x = 1\nelse:", apply(text, edit));
        String nested = "def f():\n    try:\n        pass\n        except ValueError:";
        assertEquals("def f():\n    try:\n        pass\n    except ValueError:",
                apply(nested, TextOps.dedentBlockKeyword(nested, nested.length())));
        assertNull(TextOps.dedentBlockKeyword("if a:\n    x = 1\nelse:", 20));
        assertNull(TextOps.dedentBlockKeyword("x = 1\n    else:", 15));
    }

    @Test
    void findHelpers() {
        String text = "ruby rubyx Ruby ruby";
        assertEquals(4, TextOps.findAll(text, "ruby", false, 0, text.length()).size());
        assertEquals(1, TextOps.findAll(text, "Ruby", true, 0, text.length()).size());
        assertEquals(1, TextOps.findAll(text, "ruby", false, 6, 15).size()); // "in selection": only "Ruby"
        assertEquals(2, TextOps.findWord(text, "ruby", 10).size());
        int[] word = TextOps.wordAt("foo bar_baz", 6);
        assertEquals(4, word[0]);
        assertEquals(11, word[1]);
    }

    @Test
    void bookmarksFollowLineChanges() {
        assertEquals(Set.of(1, 6), TextOps.shiftLines(Set.of(1, 4), 2, 0, 2));
        assertEquals(Set.of(1, 2, 3), TextOps.shiftLines(Set.of(1, 3, 6), 2, 3, 0));
    }
}
