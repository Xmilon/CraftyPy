package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PixelBufferTest {
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;

    @Test
    void strokeUndoRedo() {
        var buffer = new PixelBuffer(4, 4);
        buffer.checkpoint();
        buffer.set(1, 1, RED);
        buffer.set(9, 9, RED); // outside: ignored
        assertEquals(RED, buffer.get(1, 1));
        buffer.undo();
        assertEquals(0, buffer.get(1, 1));
        assertTrue(buffer.canRedo());
        buffer.redo();
        assertEquals(RED, buffer.get(1, 1));
        assertFalse(buffer.canRedo());
    }

    @Test
    void fillStaysInsideTheRegion() {
        var buffer = new PixelBuffer(3, 3);
        buffer.checkpoint();
        for (int y = 0; y < 3; y++) {
            buffer.set(1, y, BLUE); // a wall in the middle column
        }
        buffer.fill(0, 0, RED);
        assertEquals(RED, buffer.get(0, 2));
        assertEquals(BLUE, buffer.get(1, 1));
        assertEquals(0, buffer.get(2, 0));
        buffer.undo();
        assertEquals(0, buffer.get(0, 0));
    }

    @Test
    void linesAndRectangles() {
        var line = PixelBuffer.line(0, 0, 3, 3);
        assertEquals(4, line.size());
        assertArrayEquals(new int[]{3, 3}, line.get(3));
        assertEquals(8, PixelBuffer.rect(0, 0, 2, 2, false).size());
        assertEquals(9, PixelBuffer.rect(2, 2, 0, 0, true).size());
        assertEquals(1, PixelBuffer.line(2, 2, 2, 2).size());
    }

    @Test
    void flipsAndRotation() {
        var buffer = new PixelBuffer(2, 2, new int[]{1, 2, 3, 4});
        buffer.flipHorizontal();
        assertArrayEquals(new int[]{2, 1, 4, 3}, buffer.copy());
        buffer.flipVertical();
        assertArrayEquals(new int[]{4, 3, 2, 1}, buffer.copy());
        var turn = new PixelBuffer(2, 2, new int[]{1, 2, 3, 4});
        assertTrue(turn.rotate());
        assertArrayEquals(new int[]{3, 1, 4, 2}, turn.copy()); // clockwise
        assertFalse(new PixelBuffer(2, 3).rotate());
    }

    @Test
    void shiftWraps() {
        var buffer = new PixelBuffer(3, 1, new int[]{1, 2, 3});
        buffer.shift(1, 0);
        assertArrayEquals(new int[]{3, 1, 2}, buffer.copy());
        buffer.shift(-1, 0);
        assertArrayEquals(new int[]{1, 2, 3}, buffer.copy());
    }

    @Test
    void ellipsesAndBrushes() {
        assertEquals(1, PixelBuffer.ellipse(2, 2, 2, 2, true).size());
        assertEquals(21, PixelBuffer.ellipse(0, 0, 4, 4, true).size(), "a 5x5 disc has its four corners cut off");
        var outline = PixelBuffer.ellipse(0, 0, 6, 6, false);
        assertTrue(outline.stream().noneMatch(p -> p[0] == 3 && p[1] == 3), "the centre is not on the outline");
        assertTrue(outline.stream().anyMatch(p -> p[0] == 0 && p[1] == 3));
        assertEquals(1, PixelBuffer.brush(5, 5, 1).size());
        assertEquals(9, PixelBuffer.brush(5, 5, 3).size());
        assertArrayEquals(new int[]{4, 4}, PixelBuffer.brush(5, 5, 3).get(0));
        assertEquals(1024, PixelBuffer.brush(0, 0, 99).size(), "brush size is capped at 32");
    }

    @Test
    void toleranceFillAndRecolour() {
        var buffer = new PixelBuffer(3, 1, new int[]{0xFF100000, 0xFF180000, 0xFF000080});
        buffer.fill(0, 0, RED, 0);
        assertEquals(RED, buffer.get(0, 0));
        assertEquals(0xFF180000, buffer.get(1, 0), "exact fill leaves a near colour alone");
        buffer.undo();
        buffer.fill(0, 0, RED, 16);
        assertEquals(RED, buffer.get(1, 0));
        assertEquals(0xFF000080, buffer.get(2, 0), "far colours stay");
        assertEquals(2, buffer.replaceColor(RED, BLUE));
        assertEquals(BLUE, buffer.get(0, 0));
    }

    @Test
    void largeFillDoesNotOverflow() {
        var buffer = new PixelBuffer(512, 512);
        buffer.fill(0, 0, RED);
        assertEquals(RED, buffer.get(511, 511));
    }

    @Test
    void regionsAndPaste() {
        var buffer = new PixelBuffer(4, 4);
        buffer.set(1, 1, RED);
        buffer.set(2, 1, BLUE);
        var region = buffer.region(1, 1, 2, 1);
        assertEquals(2, region.width());
        assertEquals(1, region.height());
        buffer.clearRegion(1, 1, 2, 1);
        assertEquals(0, buffer.get(1, 1));
        buffer.paste(0, 3, region);
        assertEquals(RED, buffer.get(0, 3));
        assertEquals(BLUE, buffer.get(1, 3));
        buffer.paste(3, 3, region); // runs off the edge: the part outside is dropped
        assertEquals(RED, buffer.get(3, 3));
        assertNull(buffer.region(9, 9, 12, 12));
    }

    @Test
    void shadeKeepsAlphaAndSkipsTransparent() {
        var buffer = new PixelBuffer(2, 1, new int[]{0x80808080, 0});
        buffer.shade(0, 0, 0.5f);
        assertEquals(0x80C0C0C0, buffer.get(0, 0));
        buffer.shade(0, 0, -1f);
        assertEquals(0x80000000, buffer.get(0, 0));
        buffer.shade(1, 0, 0.5f);
        assertEquals(0, buffer.get(1, 0));
    }

    @Test
    void resizeAnchorsAndPaletteRanks() {
        var buffer = new PixelBuffer(2, 2, new int[]{RED, RED, BLUE, 0});
        var bigger = buffer.resized(4, 4, 1, 1);
        assertEquals(RED, bigger.get(1, 1));
        assertEquals(BLUE, bigger.get(1, 2));
        var smaller = buffer.resized(1, 1, 0, 0);
        assertEquals(RED, smaller.get(0, 0));
        assertEquals(List.of(RED, BLUE), buffer.palette(5));
        assertThrows(IllegalArgumentException.class, () -> buffer.resized(2000, 2, 0, 0));
    }

    @Test
    void colours() {
        assertEquals(0xFF112233, PixelBuffer.parseHex("#112233"));
        assertEquals(0x80112233, PixelBuffer.parseHex("80112233"));
        assertNull(PixelBuffer.parseHex("#12"));
        assertNull(PixelBuffer.parseHex("#GG1122"));
        assertEquals("#FF112233", PixelBuffer.hex(0xFF112233));
        assertEquals(0xFF332211, PixelBuffer.swapRedBlue(0xFF112233));
        assertEquals(0xFF112233, PixelBuffer.swapRedBlue(PixelBuffer.swapRedBlue(0xFF112233)));
    }
}
