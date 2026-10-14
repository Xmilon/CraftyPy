package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression tests for the syntax-highlight tokenizer. The decorator branch
 * used to never advance its cursor, so any line containing '@' spun forever
 * appending empty tokens and exhausted the heap while rendering.
 */
class SyntaxTest {

    @Test
    @Timeout(5)
    void decoratorLineTerminates() {
        var tokens = Syntax.tokenize("@block(\"diamond_dirt\", hardness=5.0)");
        assertFalse(tokens.isEmpty());
    }

    @Test
    @Timeout(5)
    void loneDecoratorAndSpacedAtTerminate() {
        assertNotNull(Syntax.tokenize("@"));
        assertNotNull(Syntax.tokenize("a @ b"));
        assertNotNull(Syntax.tokenize("@@@"));
    }

    @Test
    @Timeout(5)
    void trailingBackslashInStringTerminates() {
        assertNotNull(Syntax.tokenize("x = \"abc\\"));
        assertNotNull(Syntax.tokenize("x = 'a\\"));
        assertNotNull(Syntax.tokenize("{\"a\\", "json"));
    }

    @Test
    @Timeout(5)
    void simpleDecoratorTokenCount() {
        assertEquals(1, Syntax.tokenize("@block").size());
    }

    /** The editor maps tokens back to offsets, so tokens must cover the line exactly. */
    @Test
    @Timeout(5)
    void tokensCoverTheWholeLine() {
        for (String lang : List.of("py", "json", "md")) {
            for (String line : List.of("def f(x): return x  # hi", "{\"k\": [1, -2.5, true, null]}",
                    "## Title", "see `code` and [link](url)", "x = 'a\\'b' + \"c\"", "@on(\"tick\")")) {
                String joined = String.join("", Syntax.tokenize(line, lang).stream().map(Syntax.Token::text).toList());
                assertEquals(line, joined, lang + ": " + line);
            }
        }
    }

    @Test
    void languageFromPath() {
        assertEquals("py", Syntax.language("code/main.py"));
        assertEquals("json", Syntax.language("data/x.JSON"));
        assertEquals("md", Syntax.language("README.md"));
        assertEquals("py", Syntax.language(null));
    }
}
