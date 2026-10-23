package net.xmilon.craftpy.menu;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The edits of the Minecraft-style window maker on the window JSON ({@code ui/<id>.json} with {@code "legacy": true}): a size in
 * GUI units and a flat list of pieces in {@code root.children}, drawn in list order (the last is on top). Every edit keeps the
 * window valid for {@link ScreenLayout}: sizes between {@link ScreenLayout#MIN_SIZE} and {@link ScreenLayout#MAX_SIZE}, every piece
 * inside the window, at most {@link ScreenLayout#MAX_PIECES} pieces. Pure JSON, no UI, so it is tested on its own.
 */
public final class LegacyEdit {
    /** The pieces in toolbox order. */
    public static final List<String> TYPES = List.of("lg_panel", "lg_slot", "lg_slots", "lg_player", "lg_energy", "lg_fluid",
            "lg_arrow", "lg_flame", "lg_bar", "lg_label", "lg_icon");
    /** Slots sit 18 units apart in every vanilla window. */
    public static final int SLOT_PITCH = 18;
    /** How close (in units) a slot must come to the 18-unit row or column of another slot to snap onto it. */
    static final int SNAP = 3;

    private LegacyEdit() {
    }

    /** A new window: the size, and one panel that fills it. */
    public static JsonObject create(String id, String title, int width, int height) {
        JsonObject window = new JsonObject();
        window.addProperty("id", id);
        window.addProperty("title", title);
        window.addProperty("legacy", true);
        width = clampSize(width);
        height = clampSize(height);
        window.add("size", size(width, height));
        JsonObject root = new JsonObject();
        root.addProperty("type", "canvas");
        JsonArray children = new JsonArray();
        JsonObject panel = newPiece("lg_panel");
        panel.addProperty("width", width);
        panel.addProperty("height", height);
        children.add(panel);
        root.add("children", children);
        window.add("root", root);
        return window;
    }

    /** True for a window of the Minecraft look. */
    public static boolean isLegacy(JsonObject window) {
        return window.has("legacy") && window.get("legacy").isJsonPrimitive() && window.get("legacy").getAsBoolean();
    }

    /** The size of the window in GUI units, clamped like the game clamps it. */
    public static int[] size(JsonObject window) {
        int width = 176, height = 166;
        if (window.has("size") && window.get("size").isJsonArray() && window.getAsJsonArray("size").size() >= 2) {
            width = number(window.getAsJsonArray("size").get(0), width);
            height = number(window.getAsJsonArray("size").get(1), height);
        }
        return new int[]{clampSize(width), clampSize(height)};
    }

    /** The pieces, created when missing so edits always have a list to work on. */
    public static JsonArray pieces(JsonObject window) {
        if (!window.has("root") || !window.get("root").isJsonObject()) {
            JsonObject root = new JsonObject();
            root.addProperty("type", "canvas");
            window.add("root", root);
        }
        JsonObject root = window.getAsJsonObject("root");
        if (!root.has("children") || !root.get("children").isJsonArray()) {
            root.add("children", new JsonArray());
        }
        return root.getAsJsonArray("children");
    }

    /** A piece of {@code type} at (0, 0) with the values the toolbox gives it. */
    public static JsonObject newPiece(String type) {
        JsonObject node = new JsonObject();
        node.addProperty("type", type);
        node.addProperty("x", 0);
        node.addProperty("y", 0);
        switch (type) {
            case "lg_panel" -> {
                node.addProperty("width", 176);
                node.addProperty("height", 166);
            }
            case "lg_slot" -> node.addProperty("slot", "input.0");
            case "lg_slots" -> {
                node.addProperty("slot", "storage");
                node.addProperty("columns", 3);
                node.addProperty("rows", 3);
            }
            case "lg_energy" -> {
                node.addProperty("width", 16);
                node.addProperty("height", 54);
                node.addProperty("bind", "energy");
            }
            case "lg_fluid" -> {
                node.addProperty("width", 16);
                node.addProperty("height", 54);
                node.addProperty("bind", "fluid");
            }
            case "lg_arrow" -> {
                node.addProperty("width", 22);
                node.addProperty("height", 15);
                node.addProperty("bind", "progress");
            }
            case "lg_flame" -> node.addProperty("bind", "progress");
            case "lg_bar" -> {
                node.addProperty("width", 50);
                node.addProperty("height", 8);
                node.addProperty("bind", "value:level");
                node.addProperty("max", 100);
                node.addProperty("color", "#3C9A3C");
            }
            case "lg_label" -> {
                node.addProperty("text", "Text");
                node.addProperty("width", 80);
                node.addProperty("height", 9);
                node.addProperty("color", "#404040");
            }
            case "lg_icon" -> node.addProperty("item", "minecraft:diamond");
            case "lg_player" -> {
                // always 162 x 76: nine columns, three rows and the hotbar
            }
            default -> throw new IllegalArgumentException("not a piece: " + type);
        }
        return node;
    }

    /** The piece as the game reads it (sizes of slots, grids and the player inventory are fixed by their type). */
    public static ScreenLayout.Piece read(JsonObject node, JsonObject window) {
        int[] size = size(window);
        return ScreenLayout.pieceOf(node, size[0], size[1]);
    }

    /** Slots, grids and the player inventory are drawn with a one unit frame around their items. */
    public static int frame(String type) {
        return type.equals("lg_slot") || type.equals("lg_slots") || type.equals("lg_player") ? 1 : 0;
    }

    /** The box a piece covers on screen, frame included: {x1, y1, x2, y2} in units. */
    public static int[] box(JsonObject node, JsonObject window) {
        ScreenLayout.Piece piece = read(node, window);
        int f = frame(piece.type());
        return new int[]{piece.x() - f, piece.y() - f, piece.x() + piece.width() + f, piece.y() + piece.height() + f};
    }

    /** True when the width and height of this type can be changed (the others have a fixed size or are sized by rows). */
    public static boolean resizable(String type) {
        return switch (type) {
            case "lg_panel", "lg_energy", "lg_fluid", "lg_arrow", "lg_bar", "lg_label", "lg_slots" -> true;
            default -> false;
        };
    }

    /** The topmost piece under (x, y) in units, or null. */
    public static JsonObject pieceAt(JsonObject window, int x, int y) {
        JsonArray pieces = pieces(window);
        for (int i = pieces.size() - 1; i >= 0; i--) {
            if (!pieces.get(i).isJsonObject()) continue;
            JsonObject node = pieces.get(i).getAsJsonObject();
            int[] box = box(node, window);
            if (x >= box[0] && x < box[2] && y >= box[1] && y < box[3]) {
                return node;
            }
        }
        return null;
    }

    /** Adds a piece at (x, y), kept inside the window; null when the window already has the most pieces it may have. */
    public static JsonObject add(JsonObject window, String type, int x, int y) {
        JsonArray pieces = pieces(window);
        if (pieces.size() >= ScreenLayout.MAX_PIECES) {
            return null;
        }
        JsonObject node = newPiece(type);
        if (type.equals("lg_panel")) {
            int[] size = size(window);
            node.addProperty("width", size[0]);
            node.addProperty("height", size[1]);
            x = 0;
            y = 0;
        }
        pieces.add(node);
        moveTo(window, node, x, y);
        return node;
    }

    /** Moves a piece so its top left item corner is at (x, y), kept inside the window. */
    public static void moveTo(JsonObject window, JsonObject node, int x, int y) {
        int[] size = size(window);
        ScreenLayout.Piece piece = read(node, window);
        int f = frame(piece.type());
        node.addProperty("x", clamp(x, f, Math.max(f, size[0] - piece.width() - f)));
        node.addProperty("y", clamp(y, f, Math.max(f, size[1] - piece.height() - f)));
    }

    /**
     * Snaps a slot or slot grid at (x, y) onto the 18-unit rows and columns of the other slots when it is within
     * {@link #SNAP} units of one, so grids line up like in vanilla windows. Other pieces come back unchanged.
     */
    public static int[] snap(JsonObject window, JsonObject node, int x, int y) {
        String type = type(node);
        if (!type.equals("lg_slot") && !type.equals("lg_slots")) {
            return new int[]{x, y};
        }
        int bestX = x, bestY = y, dx = SNAP + 1, dy = SNAP + 1;
        for (JsonElement element : pieces(window)) {
            if (!element.isJsonObject() || element == node) continue;
            JsonObject other = element.getAsJsonObject();
            String otherType = type(other);
            if (!otherType.equals("lg_slot") && !otherType.equals("lg_slots") && !otherType.equals("lg_player")) continue;
            ScreenLayout.Piece piece = read(other, window);
            int ox = Math.floorMod(x - piece.x(), SLOT_PITCH), oy = Math.floorMod(y - piece.y(), SLOT_PITCH);
            int nearX = ox <= SLOT_PITCH / 2 ? -ox : SLOT_PITCH - ox, nearY = oy <= SLOT_PITCH / 2 ? -oy : SLOT_PITCH - oy;
            if (Math.abs(nearX) < dx) {
                dx = Math.abs(nearX);
                bestX = x + nearX;
            }
            if (Math.abs(nearY) < dy) {
                dy = Math.abs(nearY);
                bestY = y + nearY;
            }
        }
        return new int[]{dx <= SNAP ? bestX : x, dy <= SNAP ? bestY : y};
    }

    /**
     * Gives a resizable piece a new size, kept inside the window. A slot grid takes the columns and rows that fit the size; a
     * piece with a fixed size is left alone.
     */
    public static void resize(JsonObject window, JsonObject node, int width, int height) {
        String type = type(node);
        if (!resizable(type)) {
            return;
        }
        int[] size = size(window);
        ScreenLayout.Piece piece = read(node, window);
        int f = frame(type);
        int maxW = size[0] - piece.x() - f, maxH = size[1] - piece.y() - f;
        if (type.equals("lg_slots")) {
            int columns = clamp((Math.min(width, maxW) + 2) / SLOT_PITCH, 1, 9);
            int rows = clamp((Math.min(height, maxH) + 2) / SLOT_PITCH, 1, 6);
            while (columns * rows > net.xmilon.craftpy.content.Defs.InventorySpec.MAX_SLOTS) rows--;
            node.addProperty("columns", columns);
            node.addProperty("rows", rows);
            return;
        }
        node.addProperty("width", clamp(width, 1, Math.max(1, maxW)));
        node.addProperty("height", clamp(height, 1, Math.max(1, maxH)));
    }

    /**
     * Changes the window size. A panel that filled the old window fills the new one, and every piece is moved (and a
     * resizable piece shrunk) so it stays inside.
     */
    public static void resizeWindow(JsonObject window, int width, int height) {
        int[] old = size(window);
        width = clampSize(width);
        height = clampSize(height);
        window.add("size", size(width, height));
        for (JsonElement element : pieces(window)) {
            if (!element.isJsonObject()) continue;
            JsonObject node = element.getAsJsonObject();
            ScreenLayout.Piece piece = ScreenLayout.pieceOf(node, old[0], old[1]);
            if (piece.type().equals("lg_panel") && piece.x() == 0 && piece.y() == 0 && piece.width() == old[0] && piece.height() == old[1]) {
                node.addProperty("width", width);
                node.addProperty("height", height);
                continue;
            }
            // moved in first (as far as it fits), then a piece still too big is shrunk to the room left
            moveTo(window, node, piece.x(), piece.y());
            if (resizable(piece.type())) {
                resize(window, node, piece.width(), piece.height());
            }
        }
    }

    /** Moves a piece {@code by} places in the drawing order (negative: further back); false when it is already at the end. */
    public static boolean reorder(JsonObject window, JsonObject node, int by) {
        JsonArray pieces = pieces(window);
        int index = indexOf(pieces, node);
        int target = index + by;
        if (index < 0 || target < 0 || target >= pieces.size()) {
            return false;
        }
        pieces.remove(index);
        JsonArray reordered = new JsonArray();
        for (int i = 0; i < pieces.size(); i++) {
            if (i == target) reordered.add(node);
            reordered.add(pieces.get(i));
        }
        if (target >= pieces.size()) reordered.add(node);
        window.getAsJsonObject("root").add("children", reordered);
        return true;
    }

    /** A copy of the piece placed 4 units further, or null when the window is full. */
    public static JsonObject duplicate(JsonObject window, JsonObject node) {
        JsonArray pieces = pieces(window);
        if (pieces.size() >= ScreenLayout.MAX_PIECES || indexOf(pieces, node) < 0) {
            return null;
        }
        JsonObject copy = node.deepCopy();
        pieces.add(copy);
        ScreenLayout.Piece piece = read(node, window);
        moveTo(window, copy, piece.x() + 4, piece.y() + 4);
        return copy;
    }

    public static boolean remove(JsonObject window, JsonObject node) {
        JsonArray pieces = pieces(window);
        int index = indexOf(pieces, node);
        if (index < 0) {
            return false;
        }
        pieces.remove(index);
        return true;
    }

    /** Replaces every piece and the size with those of {@code layout} (the standard window of a machine). */
    public static void fill(JsonObject window, ScreenLayout layout) {
        JsonObject json = layout.toJson();
        window.add("size", json.get("size"));
        window.getAsJsonObject("root").add("children", json.getAsJsonObject("root").getAsJsonArray("children"));
    }

    public static int indexOf(JsonArray array, JsonObject node) {
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i) == node) {
                return i;
            }
        }
        return -1;
    }

    private static JsonArray size(int width, int height) {
        JsonArray size = new JsonArray();
        size.add(width);
        size.add(height);
        return size;
    }

    private static int clampSize(int value) {
        return clamp(value, ScreenLayout.MIN_SIZE, ScreenLayout.MAX_SIZE);
    }

    private static int number(JsonElement element, int fallback) {
        try {
            return element.isJsonPrimitive() ? (int) Math.round(element.getAsDouble()) : fallback;
        } catch (NumberFormatException | UnsupportedOperationException bad) {
            return fallback;
        }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static String type(JsonObject node) {
        return node.has("type") && node.get("type").isJsonPrimitive() ? node.get("type").getAsString() : "";
    }
}
