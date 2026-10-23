package net.xmilon.craftpy.menu;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.xmilon.craftpy.content.Defs;

/**
 * The layout of a Minecraft-style machine window ("legacy" window of the UI maker): a size and a flat list of pieces at fixed
 * pixel positions (GUI units), like vanilla container screens. It comes from the Python side as JSON, so everything is
 * checked here: unknown pieces are dropped, numbers are clamped, and there are at most {@link #MAX_PIECES} pieces. The server
 * builds the slots from it, and the same JSON goes to the client, which draws it.
 */
public final class ScreenLayout {
    public static final int MAX_PIECES = 64;
    public static final int MAX_JSON = 24_000;
    public static final int MIN_SIZE = 64, MAX_SIZE = 256;
    private static final Pattern SLOT = Pattern.compile("([a-z][a-z0-9_]{0,24})(?:\\.([0-9]{1,2}))?");

    /** The kinds of piece, as the UI maker names them. */
    public static final Set<String> TYPES = Set.of("lg_panel", "lg_slot", "lg_slots", "lg_player", "lg_energy", "lg_fluid",
            "lg_arrow", "lg_flame", "lg_bar", "lg_label", "lg_icon");
    /** What a bar, arrow or flame can show: the machine's own numbers, or {@code value:key} set by Python. */
    public static final Set<String> BINDINGS = Set.of("energy", "fluid", "progress");
    private static final Pattern VALUE_BIND = Pattern.compile("value:[a-z][a-z0-9_]{0,24}");

    /**
     * One piece. {@code slot} is "group.n" for {@code lg_slot} (just "group" is slot 0 of it) and the group for
     * {@code lg_slots}, a grid of {@code columns} x {@code rows} slots counting from 0; {@code bind} says which number a bar,
     * arrow or flame shows; {@code text} is a label's text or an icon's item id; {@code color} is a label's color (RGB).
     */
    public record Piece(String type, int x, int y, int width, int height, String slot, String bind, String text, int color,
                        int columns, int rows, double max) {
        public Piece(String type, int x, int y, int width, int height, String slot, String bind, String text, int color) {
            this(type, x, y, width, height, slot, bind, text, color, 1, 1, 100);
        }

        public Piece(String type, int x, int y, int width, int height, String slot, String bind, String text, int color,
                     int columns, int rows) {
            this(type, x, y, width, height, slot, bind, text, color, columns, rows, 100);
        }
    }

    /** One slot of the window: the machine slot {@code group.n} at (x, y). */
    public record SlotRef(String group, int n, int x, int y) {
    }

    public final int width, height;
    public final List<Piece> pieces;

    private ScreenLayout(int width, int height, List<Piece> pieces) {
        this.width = width;
        this.height = height;
        this.pieces = List.copyOf(pieces);
    }

    /** True when some piece is of {@code type}. */
    public boolean has(String type) {
        return pieces.stream().anyMatch(piece -> piece.type().equals(type));
    }

    /** Every machine slot of the window in drawing order, without repeats (the first piece for a slot wins). */
    public List<SlotRef> slotRefs() {
        List<SlotRef> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Piece piece : pieces) {
            if (piece.type().equals("lg_slot")) {
                Matcher m = SLOT.matcher(piece.slot());
                if (m.matches()) {
                    String group = m.group(1);
                    int n = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                    if (seen.add(group + "." + n)) {
                        out.add(new SlotRef(group, n, piece.x(), piece.y()));
                    }
                }
            } else if (piece.type().equals("lg_slots")) {
                for (int row = 0; row < piece.rows(); row++) {
                    for (int column = 0; column < piece.columns(); column++) {
                        int n = row * piece.columns() + column;
                        if (seen.add(piece.slot() + "." + n)) {
                            out.add(new SlotRef(piece.slot(), n, piece.x() + column * 18, piece.y() + row * 18));
                        }
                    }
                }
            }
        }
        return out.size() > Defs.InventorySpec.MAX_SLOTS ? out.subList(0, Defs.InventorySpec.MAX_SLOTS) : out;
    }

    /** The layout every machine window has when it names none: two slots, the gauges that fit, the player inventory. */
    public static ScreenLayout defaults(boolean slots, boolean energy, boolean fluid) {
        List<Piece> list = new ArrayList<>();
        list.add(new Piece("lg_panel", 0, 0, 176, 166, "", "", "", 0));
        if (slots) {
            list.add(new Piece("lg_slot", 26, 20, 16, 16, "input", "", "", 0));
            list.add(new Piece("lg_arrow", 30, 40, 10, 14, "", "progress", "", 0));
            list.add(new Piece("lg_slot", 26, 56, 16, 16, "output", "", "", 0));
        }
        if (fluid) {
            list.add(new Piece("lg_fluid", 86, 16, 16, 54, "", "fluid", "", 0));
        }
        if (energy) {
            list.add(new Piece("lg_energy", 152, 16, 16, 54, "", "energy", "", 0));
        }
        list.add(new Piece("lg_player", 8, 84, 162, 76, "", "", "", 0));
        list.add(new Piece("lg_label", 8, 6, 160, 9, "", "", "{title}", 0x404040));
        list.add(new Piece("lg_label", 8, 72, 100, 9, "", "", "{inventory}", 0x404040));
        return new ScreenLayout(176, 166, list);
    }

    /**
     * The standard window of a machine with declared slots: one grid per group (at most 5 columns, a new row for each
     * group), the gauges on the right, the player inventory below. The window grows downward to fit.
     */
    public static ScreenLayout forInventory(Defs.InventorySpec spec, boolean energy, boolean fluid) {
        List<Piece> list = new ArrayList<>();
        int y = 18;
        for (Defs.InventorySpec.Group group : spec.groups) {
            int columns = Math.min(5, group.count()), rows = (group.count() + columns - 1) / columns;
            int fullRows = group.count() / columns;
            if (fullRows > 0) {
                list.add(new Piece("lg_slots", 8, y, columns * 18 - 2, fullRows * 18 - 2, group.name(), "", "", 0, columns, fullRows));
            }
            // a last row that is not full is drawn as single slots
            for (int n = fullRows * columns; n < group.count(); n++) {
                list.add(new Piece("lg_slot", 8 + (n - fullRows * columns) * 18, y + fullRows * 18, 16, 16, group.name() + "." + n, "", "", 0));
            }
            y += rows * 18 + 4;
        }
        int playerY = Math.max(84, y + 12);
        int height = Math.min(MAX_SIZE, playerY + 82);
        list.add(0, new Piece("lg_panel", 0, 0, 176, height, "", "", "", 0));
        if (fluid) {
            list.add(new Piece("lg_fluid", 110, 16, 16, 54, "", "fluid", "", 0));
        }
        if (energy) {
            list.add(new Piece("lg_energy", 152, 16, 16, 54, "", "energy", "", 0));
        }
        list.add(new Piece("lg_player", 8, playerY, 162, 76, "", "", "", 0));
        list.add(new Piece("lg_label", 8, 6, 160, 9, "", "", "{title}", 0x404040));
        list.add(new Piece("lg_label", 8, playerY - 12, 100, 9, "", "", "{inventory}", 0x404040));
        return new ScreenLayout(176, height, list);
    }

    /** From the JSON of a window drawn in the UI maker; falls back to {@code fallback} when it holds nothing usable. */
    public static ScreenLayout parse(JsonObject window, ScreenLayout fallback) {
        try {
            int width = 176, height = 166;
            if (window.has("size") && window.get("size").isJsonArray() && window.getAsJsonArray("size").size() >= 2) {
                width = clamp(window.getAsJsonArray("size").get(0).getAsInt(), MIN_SIZE, MAX_SIZE);
                height = clamp(window.getAsJsonArray("size").get(1).getAsInt(), MIN_SIZE, MAX_SIZE);
            }
            JsonObject root = window.has("root") && window.get("root").isJsonObject() ? window.getAsJsonObject("root") : window;
            List<Piece> list = new ArrayList<>();
            if (root.has("children") && root.get("children").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("children")) {
                    if (list.size() >= MAX_PIECES || !element.isJsonObject()) {
                        continue;
                    }
                    Piece piece = piece(element.getAsJsonObject(), width, height);
                    if (piece != null) {
                        list.add(piece);
                    }
                }
            }
            return list.isEmpty() ? fallback : new ScreenLayout(width, height, list);
        } catch (RuntimeException broken) {
            return fallback;
        }
    }

    /** The layout as the JSON that is sent to the client and read again by {@link #parse}. */
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        JsonArray size = new JsonArray();
        size.add(width);
        size.add(height);
        out.add("size", size);
        JsonArray children = new JsonArray();
        for (Piece piece : pieces) {
            JsonObject node = new JsonObject();
            node.addProperty("type", piece.type());
            node.addProperty("x", piece.x());
            node.addProperty("y", piece.y());
            node.addProperty("width", piece.width());
            node.addProperty("height", piece.height());
            if (!piece.slot().isEmpty()) node.addProperty("slot", piece.slot());
            if (!piece.bind().isEmpty()) node.addProperty("bind", piece.bind());
            if (!piece.text().isEmpty()) node.addProperty("text", piece.text());
            if (piece.color() != 0) node.addProperty("color", String.format("#%06x", piece.color() & 0xFFFFFF));
            if (piece.type().equals("lg_slots")) {
                node.addProperty("columns", piece.columns());
                node.addProperty("rows", piece.rows());
            }
            if (piece.bind().startsWith("value:") || piece.type().equals("lg_bar")) {
                node.addProperty("max", piece.max());
            }
            children.add(node);
        }
        JsonObject root = new JsonObject();
        root.addProperty("type", "canvas");
        root.add("children", children);
        out.add("root", root);
        return out;
    }

    /** One piece from a node of the UI maker (the preview draws pieces one by one); a slot for an unknown type. */
    public static Piece pieceOf(JsonObject node, int windowWidth, int windowHeight) {
        Piece piece = piece(node, windowWidth, windowHeight);
        return piece != null ? piece : new Piece("lg_slot", 0, 0, 16, 16, "input", "", "", 0);
    }

    private static Piece piece(JsonObject node, int windowWidth, int windowHeight) {
        String type = string(node, "type");
        if (!TYPES.contains(type)) {
            return null;
        }
        int x = clamp(number(node, "x", 0), 0, windowWidth), y = clamp(number(node, "y", 0), 0, windowHeight);
        int columns = 1, rows = 1;
        if (type.equals("lg_slots")) {
            columns = clamp(number(node, "columns", 3), 1, 9);
            rows = clamp(number(node, "rows", 1), 1, 6);
            while (columns * rows > Defs.InventorySpec.MAX_SLOTS) rows--;
        }
        int width = switch (type) {
            case "lg_slot" -> 16;
            case "lg_slots" -> columns * 18 - 2;
            case "lg_player" -> 162;
            case "lg_icon" -> 16;
            case "lg_flame" -> 14;
            default -> clamp(number(node, "width", defaultWidth(type)), 1, MAX_SIZE);
        };
        int height = switch (type) {
            case "lg_slot" -> 16;
            case "lg_slots" -> rows * 18 - 2;
            case "lg_player" -> 76;
            case "lg_icon" -> 16;
            case "lg_flame" -> 14;
            default -> clamp(number(node, "height", defaultHeight(type)), 1, MAX_SIZE);
        };
        String slot = "";
        if (type.equals("lg_slot") || type.equals("lg_slots")) {
            Matcher m = SLOT.matcher(string(node, "slot"));
            slot = m.matches() ? (type.equals("lg_slots") ? m.group(1) : m.group(1) + (m.group(2) == null ? "" : "." + m.group(2))) : "input";
        }
        String bind = string(node, "bind");
        if (!BINDINGS.contains(bind) && !VALUE_BIND.matcher(bind).matches()) {
            bind = switch (type) {
                case "lg_energy" -> "energy";
                case "lg_fluid" -> "fluid";
                case "lg_arrow", "lg_flame" -> "progress";
                case "lg_bar" -> "value:level";
                default -> "";
            };
        }
        if (!(type.equals("lg_energy") || type.equals("lg_fluid") || type.equals("lg_arrow") || type.equals("lg_flame") || type.equals("lg_bar"))) {
            bind = "";
        }
        double max = 100;
        try {
            max = node.has("max") && node.get("max").isJsonPrimitive() ? node.get("max").getAsDouble() : 100;
        } catch (NumberFormatException | UnsupportedOperationException bad) {
            max = 100;
        }
        if (!Double.isFinite(max) || max <= 0) max = 100;
        String text = type.equals("lg_label") || type.equals("lg_icon") ? truncate(string(node, "text").isEmpty()
                ? string(node, "item") : string(node, "text"), 120) : "";
        int color = type.equals("lg_label") ? color(string(node, "color"), 0x404040)
                : type.equals("lg_bar") ? color(string(node, "color"), 0x3C9A3C) : 0;
        return new Piece(type, x, y, width, height, slot, bind, text, color, columns, rows, max);
    }

    private static int defaultWidth(String type) {
        return switch (type) {
            case "lg_panel" -> 176;
            case "lg_arrow" -> 22;
            case "lg_bar" -> 50;
            case "lg_label" -> 80;
            default -> 16;
        };
    }

    private static int defaultHeight(String type) {
        return switch (type) {
            case "lg_panel" -> 166;
            case "lg_arrow" -> 15;
            case "lg_bar" -> 8;
            case "lg_label" -> 9;
            default -> 54;
        };
    }

    private static int color(String text, int fallback) {
        String hex = text.startsWith("#") ? text.substring(1) : text;
        try {
            return hex.length() == 6 ? Integer.parseInt(hex, 16) : fallback;
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    private static String string(JsonObject node, String key) {
        return node.has(key) && node.get(key).isJsonPrimitive() ? node.get(key).getAsString() : "";
    }

    private static int number(JsonObject node, String key, int fallback) {
        try {
            return node.has(key) && node.get(key).isJsonPrimitive() ? (int) Math.round(node.get(key).getAsDouble()) : fallback;
        } catch (NumberFormatException | UnsupportedOperationException bad) {
            return fallback;
        }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
