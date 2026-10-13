package net.xmilon.craftpy.client.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.xmilon.craftpy.client.ide.ScrollView.Span;

/** The preview panel and API tab render through this renderer. */
class MarkdownRendererTest {

    @Test
    void headingBecomesStyledRow() {
        List<ScrollView.Row> rows = MarkdownRenderer.render("# Hello World");
        assertTrue(rows.stream().anyMatch(r -> r.style() == ScrollView.Style.HEADING1));
        ScrollView.Row heading = rows.stream().filter(r -> r.style() == ScrollView.Style.HEADING1).findFirst().orElseThrow();
        assertTrue(heading.text().contains("Hello World"));
        assertTrue(heading.spans().stream().allMatch(Span::bold));
    }

    @Test
    void fencedCodeBlockIsStyledAsCode() {
        List<ScrollView.Row> rows = MarkdownRenderer.render("```python\nprint('hi')\n```");
        assertTrue(rows.stream().anyMatch(r -> r.style() == ScrollView.Style.CODE && r.text().contains("print")));
        // The fence lines themselves are never rendered as plain text.
        assertFalse(rows.stream().anyMatch(r -> r.text().contains("```")));
    }

    @Test
    void bulletsAndOrderedListsAreRecognized() {
        List<ScrollView.Row> bullets = MarkdownRenderer.render("- one\n- two");
        assertEquals(2, bullets.stream().filter(r -> r.style() == ScrollView.Style.BULLET).count());
        List<ScrollView.Row> ordered = MarkdownRenderer.render("1. first\n2. second");
        assertEquals(2, ordered.stream().filter(r -> r.style() == ScrollView.Style.ORDERED).count());
    }

    @Test
    void inlineBoldItalicAndCodeProduceMultipleSpans() {
        List<Span> spans = MarkdownRenderer.parseInline("a **b** *c* `d`", 0xFFFFFF, false);
        assertTrue(spans.stream().anyMatch(s -> s.text().equals("b") && s.bold()));
        assertTrue(spans.stream().anyMatch(s -> s.text().equals("c") && s.italic()));
        assertTrue(spans.stream().anyMatch(s -> s.text().equals("d")));
    }

    @Test
    void tablesBecomeAlignedRows() {
        List<ScrollView.Row> rows = MarkdownRenderer.render("| A | B |\n| --- | --- |\n| 1 | 2 |");
        assertTrue(rows.stream().anyMatch(r -> r.style() == ScrollView.Style.TABLE_HEADER));
        assertTrue(rows.stream().anyMatch(r -> r.style() == ScrollView.Style.TABLE_CELL));
        assertTrue(rows.stream().anyMatch(r -> r.style() == ScrollView.Style.RULE));
    }

    @Test
    void tableCellsParseInlineFormatting() {
        List<ScrollView.Row> rows = MarkdownRenderer.render("| Field | Notes |\n| --- | --- |\n| `name` | **bold** |");
        ScrollView.Row cell = rows.stream().filter(r -> r.style() == ScrollView.Style.TABLE_CELL)
                .findFirst().orElseThrow();
        assertFalse(cell.spans().stream().anyMatch(s -> s.text().contains("`")), "backticks stripped");
        assertTrue(cell.spans().stream().anyMatch(s -> s.text().equals("name")));
        assertTrue(cell.spans().stream().anyMatch(s -> s.text().equals("bold") && s.bold()));
    }

    @Test
    void horizontalRuleAndQuoteAreRecognized() {
        assertTrue(MarkdownRenderer.render("---").stream().anyMatch(r -> r.style() == ScrollView.Style.RULE));
        assertTrue(MarkdownRenderer.render("> note").stream().anyMatch(r -> r.style() == ScrollView.Style.QUOTE));
    }

    @Test
    void emptyInputStillProducesAPlaceholder() {
        assertEquals(1, MarkdownRenderer.render("").size());
    }
}
