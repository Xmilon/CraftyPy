package net.xmilon.craftpy.client.mui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    @Test
    void canvasPositionsSnapAndStayInside() {
        assertEquals(12, UiDesigner.snap(13.2f));
        assertEquals(24, UiDesigner.moved(8, 17f, 200)); // 25 -> 24 on the grid
        assertEquals(0, UiDesigner.moved(8, -40f, 200)); // never left of the canvas
        assertEquals(100, UiDesigner.moved(8, 500f, 100)); // never past its right edge
        assertEquals(UiDesigner.MIN_SIZE, UiDesigner.resized(40, -200f));
        assertEquals(64, UiDesigner.resized(40, 23f));
    }

    @Test
    void newWindowsAreCanvasesAndNewWidgetsGoBelowTheOthers() {
        JsonObject window = UiDesigner.parse("shop", "");
        JsonObject root = window.getAsJsonObject("root");
        assertEquals("canvas", root.get("type").getAsString());
        JsonObject first = UiDesigner.newNode("button");
        UiDesigner.place(root, first);
        root.getAsJsonArray("children").add(first);
        JsonObject second = UiDesigner.newNode("text_input");
        UiDesigner.place(root, second);
        assertEquals(8, first.get("x").getAsInt());
        assertEquals(8, first.get("y").getAsInt());
        assertEquals(96, first.get("width").getAsInt());
        assertEquals(40, second.get("y").getAsInt()); // below the button (8 + 24) and 8 more
        assertTrue(UiDesigner.isContainer("canvas"));
    }

    @Test
    void paletteWidgetsInsertIntoContainersAndBetweenSiblings() {
        JsonObject root = root();
        JsonObject hbox = UiDesigner.nodeAt(root, List.of(1));
        JsonObject button = UiDesigner.newNode("button");
        assertTrue(UiDesigner.insertNode(root, button, hbox, UiDesigner.Drop.INTO));
        assertEquals(List.of(1, 1), UiDesigner.pathOf(root, button));
        JsonObject label = UiDesigner.newNode("label");
        assertTrue(UiDesigner.insertNode(root, label, button, UiDesigner.Drop.BEFORE));
        assertEquals(List.of(1, 1), UiDesigner.pathOf(root, label));
        assertEquals(List.of(1, 2), UiDesigner.pathOf(root, button));
    }

    @Test
    void invalidPaletteDropsAndDetachedMoveTargetsPreserveTheTree() {
        JsonObject root = root();
        JsonObject a = UiDesigner.nodeAt(root, List.of(0));
        JsonObject detached = UiDesigner.newNode("canvas");
        String before = root.toString();
        assertFalse(UiDesigner.insertNode(root, UiDesigner.newNode("button"), a, UiDesigner.Drop.INTO));
        assertFalse(UiDesigner.insertNode(root, UiDesigner.newNode("button"), root, UiDesigner.Drop.BEFORE));
        assertFalse(UiDesigner.insertNode(root, a, root, UiDesigner.Drop.INTO));
        assertFalse(UiDesigner.moveNode(root, a, detached, UiDesigner.Drop.INTO));
        assertEquals(before, root.toString());
    }

    @Test
    void edgeResizingKeepsTheOppositeEdgeAndOtherAxisFixed() {
        assertArrayEquals(new int[] {0, 20, 110, 40},
                UiDesigner.resizeBounds(10, 20, 100, 40, -200, 0, UiDesigner.LEFT, 300, 200, true));
        assertArrayEquals(new int[] {10, 44, 100, 16},
                UiDesigner.resizeBounds(10, 20, 100, 40, 0, 200, UiDesigner.TOP, 300, 200, true));
        assertArrayEquals(new int[] {12, 20, 108, 40},
                UiDesigner.resizeBounds(12, 20, 100, 40, 7, 200, UiDesigner.RIGHT, 300, 200, true));
        assertArrayEquals(new int[] {10, 20, 290, 180},
                UiDesigner.resizeBounds(10, 20, 100, 40, 900, 900,
                        UiDesigner.RIGHT | UiDesigner.BOTTOM, 300, 200, true));
        assertArrayEquals(new int[] {10, 20, 120, 40},
                UiDesigner.resizeBounds(10, 20, 100, 40, -20, 0, UiDesigner.LEFT, 300, 200, false));
    }

    @Test
    void cancelledGesturesRestoreWindowSizesPositionsAndProperties() {
        JsonObject window = UiDesigner.parse("shop", "");
        JsonObject button = UiDesigner.newNode("button");
        JsonObject root = window.getAsJsonObject("root");
        assertTrue(UiDesigner.insertNode(root, button, root, UiDesigner.Drop.INTO));
        JsonObject before = window.deepCopy();
        button.addProperty("x", 72);
        button.addProperty("width", 200);
        window.getAsJsonArray("size").set(0, new com.google.gson.JsonPrimitive(600));
        window.addProperty("temporary", true);
        UiDesigner.restore(window, before);
        assertEquals(before, window);
        assertFalse(UiDesigner.nodeAt(window.getAsJsonObject("root"), List.of(0)).has("x"));
    }

    @Test
    void designerRecognizesOnlyWindowFilesAndRejectsMissingRoots() {
        assertTrue(UiDesigner.isWindowFile("ui/shop.json"));
        assertTrue(UiDesigner.isWindowFile("ui/menus/shop.json"));
        assertFalse(UiDesigner.isWindowFile("assets/shop.json"));
        assertFalse(UiDesigner.isWindowFile("code/shop.py"));
        assertFalse(UiDesigner.isWindowFile(null));
        assertNotNull(UiDesigner.problem("{\"title\":\"Preserve me\"}"));
    }

    @Test
    void toolboxSearchUsesFriendlyNamesAndInspectorSeparatesEvents() {
        assertEquals(List.of("canvas", "vbox", "hbox", "grid", "tabs"),
                UiDesigner.toolboxTypes("layout"));
        assertEquals(List.of("text_input"), UiDesigner.toolboxTypes(" TEXT FIELD "));
        assertEquals(List.of(), UiDesigner.toolboxTypes("nonexistent"));
        assertEquals("Events", UiDesigner.propertyGroup("on_click"));
        assertEquals("Layout", UiDesigner.propertyGroup("width"));
        assertEquals("Identity", UiDesigner.propertyGroup("id"));
        assertEquals("Appearance & values", UiDesigner.propertyGroup("checked"));
    }

    @Test
    void newInputsNeverReuseAnExistingInputId() {
        JsonObject root = JsonParser.parseString("""
                {"type":"vbox","children":[{"type":"text_input","id":"field"},
                  {"type":"hbox","children":[{"type":"text_input","id":"field2"}]}]}
                """).getAsJsonObject();
        JsonObject node = UiDesigner.newControl(root, "text_area");
        assertEquals("field3", node.get("id").getAsString());
        assertTrue(UiDesigner.insertNode(root, node, root, UiDesigner.Drop.INTO));
        assertEquals("field4", UiDesigner.newControl(root, "text_input").get("id").getAsString());
        assertEquals("field", UiDesigner.nodeAt(root, List.of(0)).get("id").getAsString());
    }

    @Test
    void duplicationPreservesActionsAndCustomDataButAssignsUniqueInputIds() {
        JsonObject root = JsonParser.parseString("""
                {"type":"canvas","children":[
                  {"type":"vbox","id":"form","x":12,"y":16,"custom":{"keep":true},"children":[
                    {"type":"text_input","id":"name","value":"Player"},
                    {"type":"button","on_click":"submit","text":"Submit"}]},
                  {"type":"text_input","id":"name_copy"}]}
                """).getAsJsonObject();
        JsonObject original = UiDesigner.nodeAt(root, List.of(0));
        JsonObject copy = UiDesigner.duplicateNode(root, original);
        assertNotNull(copy);
        assertEquals("form_copy", copy.get("id").getAsString());
        assertEquals(20, copy.get("x").getAsInt());
        assertEquals("name_copy2", UiDesigner.nodeAt(copy, List.of(0)).get("id").getAsString());
        assertEquals("submit", UiDesigner.nodeAt(copy, List.of(1)).get("on_click").getAsString());
        assertEquals(original.get("custom"), copy.get("custom"));
        assertEquals("name", UiDesigner.nodeAt(original, List.of(0)).get("id").getAsString());
        assertNull(UiDesigner.duplicateNode(root, root));
        assertNull(UiDesigner.duplicateNode(root, new JsonObject()));
    }

    @Test
    void existingLayoutActionsAndUnknownPropertiesSurviveDesignChangesAndUndo() {
        JsonObject window = UiDesigner.parse("shop", """
                {"id":"shop","title":"Shop","size":[400,260],"custom_window":{"keep":42},
                 "root":{"type":"vbox","spacing":7,"children":[
                   {"type":"button","text":"Buy","on_click":"buy","custom_widget":[1,2,3]},
                   {"type":"text_input","id":"quantity","on_submit":"purchase"}]}}
                """);
        String before = window.toString();
        JsonObject root = window.getAsJsonObject("root");
        JsonObject button = UiDesigner.nodeAt(root, List.of(0));
        JsonObject input = UiDesigner.nodeAt(root, List.of(1));
        assertTrue(UiDesigner.moveNode(root, button, input, UiDesigner.Drop.AFTER));
        button.addProperty("width", 120);
        String after = window.toString();
        assertEquals("vbox", root.get("type").getAsString());
        assertEquals(7, root.get("spacing").getAsInt());
        assertEquals("buy", button.get("on_click").getAsString());
        assertEquals(42, window.getAsJsonObject("custom_window").get("keep").getAsInt());
        assertEquals("[1,2,3]", button.get("custom_widget").toString());
        var history = new net.xmilon.craftpy.client.TextHistory();
        history.breakGroup();
        history.record(new net.xmilon.craftpy.client.TextHistory.Edit(0, before, after), 0, 0, 100);
        var step = history.undo();
        assertEquals(before, net.xmilon.craftpy.client.TextHistory.undoText(after, step));
        assertEquals(after, net.xmilon.craftpy.client.TextHistory.redoText(before, history.redo()));
    }
}
