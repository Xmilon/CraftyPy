package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Undo groups like IntelliJ: words, runs of Backspace, a keystroke with what the editor adds. */
class TextHistoryTest {
    private final TextHistory history = new TextHistory();
    private String text = "";
    private long now = 10_000;

    private void type(String chars) {
        for (char c : chars.toCharArray()) {
            int at = text.length();
            text = text + c;
            history.record(new TextHistory.Edit(at, "", String.valueOf(c)), at, at + 1, now += 100);
        }
    }

    private void backspace(int times) {
        for (int i = 0; i < times; i++) {
            int at = text.length() - 1;
            String removed = text.substring(at);
            text = text.substring(0, at);
            history.record(new TextHistory.Edit(at, removed, ""), at + 1, at, now += 100);
        }
    }

    private void undo() {
        text = TextHistory.undoText(text, history.undo());
    }

    private void redo() {
        text = TextHistory.redoText(text, history.redo());
    }

    @Test
    void wordsAreSteps() {
        type("hello world");
        undo();
        assertEquals("hello", text);
        undo();
        assertEquals("", text);
        assertFalse(history.canUndo());
        redo();
        redo();
        assertEquals("hello world", text);
        assertNull(history.redo());
    }

    @Test
    void backspaceRunsAndPausesAndNewLines() {
        type("abc");
        backspace(2);
        undo();
        assertEquals("abc", text);
        type("d");
        now += 5000; // a long pause starts a new step even inside a word
        type("e");
        undo();
        assertEquals("abcd", text);
        type("\nx");
        undo();
        assertEquals("abcd\n", text, "x after Enter is its own step");
    }

    @Test
    void whatTheEditorAddsBelongsToTheKeystroke() {
        type("f");
        type("(");
        // the editor closes the bracket within the same keystroke
        text = text + ")";
        history.record(new TextHistory.Edit(2, "", ")"), 2, 2, now + 5);
        undo();
        assertEquals("f", text, "the bracket pair goes away with the key that made it");
    }

    @Test
    void commandsAndNewEditsAfterUndo() {
        type("abc");
        history.breakGroup();
        text = "ABC";
        history.record(new TextHistory.Edit(0, "abc", "ABC"), 3, 3, now += 100);
        undo();
        assertEquals("abc", text);
        assertTrue(history.canRedo());
        type("d");
        assertFalse(history.canRedo(), "a new change drops the redo steps");
        assertEquals(1, history.undo().caretBefore() - 2, "caret before the typed d");
    }
}
