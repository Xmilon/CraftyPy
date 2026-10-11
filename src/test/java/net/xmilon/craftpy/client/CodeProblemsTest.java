package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class CodeProblemsTest {

    private static boolean hasError(String content, String fragment) {
        CodeProblems.basicCheck("main.py", content);
        return CodeProblems.snapshot().stream()
                .anyMatch(p -> p.severity() == CodeProblems.Severity.ERROR && p.message().contains(fragment));
    }

    @Test
    void multiLineBracketsAreNotFlagged() {
        String content = String.join("\n",
                "from craftpy import *",
                "",
                "def main(ctx):",
                "    print(",
                "        \"hello\"",
                "    )",
                "    ctx.log(\"ok\")");
        CodeProblems.basicCheck("main.py", content);
        boolean bracketError = CodeProblems.snapshot().stream()
                .anyMatch(p -> p.severity() == CodeProblems.Severity.ERROR);
        assertFalse(bracketError, "valid multi-line brackets must not be flagged");
    }

    @Test
    void tripleQuotedStringsHideBrackets() {
        String content = String.join("\n",
                "def main(ctx):",
                "    \"\"\"Doc mentioning ) and ( and ] and [\"\"\"",
                "    ctx.log(\"ok\")");
        CodeProblems.basicCheck("main.py", content);
        assertFalse(CodeProblems.snapshot().stream()
                .anyMatch(p -> p.severity() == CodeProblems.Severity.ERROR));
    }

    @Test
    void bracketsInsideCommentsAreIgnored() {
        String content = String.join("\n",
                "def main(ctx):  # trailing )",
                "    ctx.log(\"ok\")  # ( unbalanced");
        CodeProblems.basicCheck("main.py", content);
        assertFalse(CodeProblems.snapshot().stream()
                .anyMatch(p -> p.severity() == CodeProblems.Severity.ERROR));
    }

    @Test
    void realUnclosedBracketIsFlagged() {
        assertTrue(hasError("def main(ctx):\n    ctx.log(\"x\"\n", "Unclosed"));
    }

    @Test
    void strayClosingBracketIsFlagged() {
        assertTrue(hasError("def main(ctx):\n    ctx.log(\"x\"))\n", "Unexpected ')'"));
    }
}
