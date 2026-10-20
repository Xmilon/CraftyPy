package net.xmilon.craftpy.client.mui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class HelpPanelTest {
    @Test
    void headingsSkipCodeBlocksAndMarks() {
        String md = """
                # Guide
                text
                ## The `ui` window
                ```python
                # not a heading
                ```
                ### [Links](#x) and **bold**
                #### too deep
                #nospace
                """;
        assertEquals(List.of(new HelpPanel.Heading(1, "Guide"), new HelpPanel.Heading(2, "The ui window"),
                new HelpPanel.Heading(3, "Links and bold")), HelpPanel.headings(md));
    }
}
