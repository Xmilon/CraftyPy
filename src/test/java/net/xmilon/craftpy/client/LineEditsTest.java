package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The IntelliJ line commands of the editor. */
class LineEditsTest {

    private static String apply(String text, TextOps.Edit edit) {
        return text.substring(0, edit.start()) + edit.text() + text.substring(edit.end());
    }

    @Test
    void toggleCommentAddsAtTheCommonIndentAndRemovesAgain() {
        String code = "def f():\n    a = 1\n        b = 2\n";
        String commented = apply(code, LineEdits.toggleComment(code, 10, 30));
        assertEquals("def f():\n    # a = 1\n    #     b = 2\n", commented);
        assertEquals(code, apply(commented, LineEdits.toggleComment(commented, 10, 34)));
        assertNull(LineEdits.toggleComment("\n\n", 0, 1));
        var single = LineEdits.toggleComment("x = 1", 2, 2);
        assertEquals("# x = 1", single.text());
        assertEquals(4, single.cursor());
    }

    @Test
    void duplicateDeleteAndMove() {
        String code = "a\nb\nc";
        assertEquals("a\nb\nb\nc", apply(code, LineEdits.duplicate(code, 2, 2)));
        assertEquals(4, LineEdits.duplicate(code, 2, 2).cursor());
        assertEquals("abb\nb\nc", apply(code.replace("a\n", "ab\n"), LineEdits.duplicate("ab\nb\nc", 1, 2)));
        assertEquals("a\nc", apply(code, LineEdits.deleteLines(code, 2, 2)));
        assertEquals("a\nb", apply(code, LineEdits.deleteLines(code, 4, 4)));
        assertEquals("b\na\nc", apply(code, LineEdits.moveLines(code, 2, 2, true)));
        assertEquals("a\nc\nb", apply(code, LineEdits.moveLines(code, 2, 2, false)));
        assertNull(LineEdits.moveLines(code, 0, 0, true));
        assertNull(LineEdits.moveLines(code, 4, 4, false));
        assertEquals(0, LineEdits.moveLines(code, 2, 2, true).cursor());
    }

    @Test
    void reformatOnlyTouchesWhitespace() {
        String code = "import x\ndef f():\n\treturn 1   \n\n\n\n\nclass A:\n    s = '''keep   \n\tthis'''\n@on.chat\ndef g(e):\n    pass\n\n";
        String expected = "import x\n\n\ndef f():\n    return 1\n\n\nclass A:\n    s = '''keep   \n\tthis'''\n\n\n@on.chat\n"
                + "def g(e):\n    pass\n";
        assertEquals(expected, LineEdits.reformat(code));
        assertEquals(expected, LineEdits.reformat(expected));
        assertEquals("# header\ndef f():\n    pass\n", LineEdits.reformat("# header\ndef f():\n    pass"));
    }

    @Test
    void smartBackspaceAndTemplates() {
        assertEquals(4, LineEdits.smartBackspace("        x", 8).start());
        assertEquals(4, LineEdits.smartBackspace("      ", 6).start());
        assertNull(LineEdits.smartBackspace("    x", 5));
        var edit = LineEdits.expandTemplate("    onj", 7);
        assertEquals("@on.player_join\n    def greet(event):\n        ", edit.text());
        assertEquals(4 + edit.text().length(), edit.cursor());
        assertNull(LineEdits.expandTemplate("x = onj", 7));
        assertNull(LineEdits.expandTemplate("nothing", 7));
    }

    @Test
    void renameInFileSkipsStringsCommentsAndAttributes() {
        String code = "gem = 1  # gem\nprint(gem, 'gem', other.gem, gems)\n";
        var edit = LineEdits.renameInFile(code, 1, "ruby");
        assertEquals("ruby = 1  # gem\nprint(ruby, 'gem', other.gem, gems)\n", edit.text());
        assertNull(LineEdits.renameInFile(code, 1, "not valid"));
        assertNull(LineEdits.renameInFile(code, 1, "gem"));
    }
}
