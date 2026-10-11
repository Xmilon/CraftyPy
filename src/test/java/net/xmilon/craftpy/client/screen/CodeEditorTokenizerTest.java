package net.xmilon.craftpy.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression tests for the syntax-highlight tokenizer. The decorator branch
 * used to never advance its cursor, so any line containing '@' spun forever
 * appending empty tokens and exhausted the heap while rendering.
 */
class CodeEditorTokenizerTest {

    @Test
    @Timeout(5)
    void decoratorLineTerminates() {
        var tokens = CodeEditor.tokenize("@block(\"diamond_dirt\", hardness=5.0)");
        assertFalse(tokens.isEmpty());
    }

    @Test
    @Timeout(5)
    void loneDecoratorAndSpacedAtTerminate() {
        assertNotNull(CodeEditor.tokenize("@"));
        assertNotNull(CodeEditor.tokenize("a @ b"));
        assertNotNull(CodeEditor.tokenize("@@@"));
    }

    @Test
    @Timeout(5)
    void trailingBackslashInStringTerminates() {
        assertNotNull(CodeEditor.tokenize("x = \"abc\\"));
        assertNotNull(CodeEditor.tokenize("x = 'a\\"));
    }

    @Test
    @Timeout(5)
    void simpleDecoratorTokenCount() {
        assertEquals(1, CodeEditor.tokenize("@block").size());
    }
}
