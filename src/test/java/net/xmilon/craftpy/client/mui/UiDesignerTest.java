package net.xmilon.craftpy.client.mui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class UiDesignerTest {
    private static final String WINDOW = """
            {"id": "w", "root": {"type": "vbox", "children": [
              {"type": "label", "text": "a"},
              {"type": "hbox", "children": [{"type": "button", "text": "b"}]},
              {"type": "label", "text": "c"}
            ]}}""";

    private static JsonObject root() {
        return JsonParser.parseString(WINDOW).getAsJsonObject().getAsJsonObject("root");
    }

    @Test
    void widgetsMoveBeforeAfterAndIntoBoxes() {
        JsonObject root = root();
        JsonObject a = UiDesigner.nodeAt(root, List.of(0));
        JsonObject hbox = UiDesigner.nodeAt(root, List.of(1));
        JsonObject b = UiDesigner.nodeAt(root, List.of(1, 0));
        JsonObject c = UiDesigner.nodeAt(root, List.of(2));
        assertTrue(UiDesigner.moveNode(root, a, hbox, UiDesigner.Drop.INTO)); // a into the row, after b
        assertEquals(List.of(0, 1), UiDesigner.pathOf(root, a)); // the row moved up, a left the root
        assertTrue(UiDesigner.moveNode(root, c, b, UiDesigner.Drop.BEFORE)); // c into the row too, before b
        assertEquals(List.of(0, 0), UiDesigner.pathOf(root, c));
        assertTrue(UiDesigner.moveNode(root, b, hbox, UiDesigner.Drop.AFTER)); // b out of the row, after it
        assertEquals(List.of(1), UiDesigner.pathOf(root, b));
        assertEquals(2, root.getAsJsonArray("children").size());
        // within the same box: down past its neighbour
        JsonObject first = UiDesigner.nodeAt(root, List.of(0, 0));
        JsonObject second = UiDesigner.nodeAt(root, List.of(0, 1));
        assertTrue(UiDesigner.moveNode(root, first, second, UiDesigner.Drop.AFTER));
        assertEquals(List.of(0, 1), UiDesigner.pathOf(root, first));
    }

    @Test
    void impossibleMovesChangeNothing() {
        JsonObject root = root();
        String before = root.toString();
        JsonObject a = UiDesigner.nodeAt(root, List.of(0));
        JsonObject hbox = UiDesigner.nodeAt(root, List.of(1));
        JsonObject b = UiDesigner.nodeAt(root, List.of(1, 0));
        assertFalse(UiDesigner.moveNode(root, root, a, UiDesigner.Drop.BEFORE)); // the root
        assertFalse(UiDesigner.moveNode(root, hbox, b, UiDesigner.Drop.BEFORE)); // into its own subtree
        assertFalse(UiDesigner.moveNode(root, hbox, hbox, UiDesigner.Drop.INTO)); // onto itself
        assertFalse(UiDesigner.moveNode(root, b, a, UiDesigner.Drop.INTO)); // a label is not a box
        assertFalse(UiDesigner.moveNode(root, a, root, UiDesigner.Drop.AFTER)); // beside the root
        assertEquals(before, root.toString());
        assertTrue(UiDesigner.moveNode(root, a, root, UiDesigner.Drop.INTO)); // into the root: to its end
        assertEquals(List.of(2), UiDesigner.pathOf(root, a));
    }

    @Test
    void dropZonesAndPaths() {
        JsonObject root = root();
        JsonObject box = UiDesigner.nodeAt(root, List.of(1));
        JsonObject label = UiDesigner.nodeAt(root, List.of(0));
        assertEquals(UiDesigner.Drop.BEFORE, UiDesigner.zone(box, 0.1f));
        assertEquals(UiDesigner.Drop.INTO, UiDesigner.zone(box, 0.5f));
        assertEquals(UiDesigner.Drop.AFTER, UiDesigner.zone(box, 0.9f));
        assertEquals(UiDesigner.Drop.BEFORE, UiDesigner.zone(label, 0.4f));
        assertEquals(UiDesigner.Drop.AFTER, UiDesigner.zone(label, 0.6f));
        assertNull(UiDesigner.nodeAt(root, List.of(9)));
        assertNotNull(UiDesigner.nodeAt(root, List.of()));
        assertEquals(List.of(), UiDesigner.pathOf(root, new JsonObject()));
    }

    @Test
    void brokenFilesAreReportedNotReplaced() {
        assertNull(UiDesigner.problem(""));
        assertNull(UiDesigner.problem(WINDOW));
        assertNotNull(UiDesigner.problem("{\"root\": [1, 2"));
        assertNotNull(UiDesigner.problem("[1, 2]"));
        assertNotNull(UiDesigner.problem("{\"root\": 5}"));
    }
}
