package net.xmilon.craftpy.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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

    /** The kinds of piece, as the UI maker names them. */
    public static final Set<String> TYPES = Set.of("lg_panel", "lg_slot", "lg_player", "lg_energy", "lg_fluid", "lg_arrow",
            "lg_flame", "lg_label", "lg_icon");
    /** What a bar, arrow or flame can show. */
    public static final Set<String> BINDINGS = Set.of("energy", "fluid", "progress");

    /**
     * One piece. {@code slot} is "input" or "output" for {@code lg_slot}; {@code bind} says which number a bar, arrow or flame
     * shows; {@code text} is a label's text or an icon's item id; {@code color} is a label's color (RGB).
     */
    public record Piece(String type, int x, int y, int width, int height, String slot, String bind, String text, int color) {
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
            children.add(node);
        }
        JsonObject root = new JsonObject();
        root.addProperty("type", "canvas");
        root.add("children", children);
        out.add("root", root);
        return out;
    }

    /** One piece from a node of the UI maker (the preview draws pieces one by one); null for an unknown type. */
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
        int width = switch (type) {
            case "lg_slot" -> 16;
            case "lg_player" -> 162;
            case "lg_icon" -> 16;
            case "lg_flame" -> 14;
            default -> clamp(number(node, "width", defaultWidth(type)), 1, MAX_SIZE);
        };
        int height = switch (type) {
            case "lg_slot" -> 16;
            case "lg_player" -> 76;
            case "lg_icon" -> 16;
            case "lg_flame" -> 14;
            default -> clamp(number(node, "height", defaultHeight(type)), 1, MAX_SIZE);
        };
        String slot = type.equals("lg_slot") ? ("output".equals(string(node, "slot")) ? "output" : "input") : "";
        String bind = string(node, "bind");
        if (!BINDINGS.contains(bind)) {
            bind = switch (type) {
                case "lg_energy" -> "energy";
                case "lg_fluid" -> "fluid";
                case "lg_arrow", "lg_flame" -> "progress";
                default -> "";
            };
        }
        if (!(type.equals("lg_energy") || type.equals("lg_fluid") || type.equals("lg_arrow") || type.equals("lg_flame"))) {
            bind = "";
        }
        String text = type.equals("lg_label") || type.equals("lg_icon") ? truncate(string(node, "text").isEmpty()
                ? string(node, "item") : string(node, "text"), 120) : "";
        int color = type.equals("lg_label") ? color(string(node, "color")) : 0;
        return new Piece(type, x, y, width, height, slot, bind, text, color);
    }

    private static int defaultWidth(String type) {
        return switch (type) {
            case "lg_panel" -> 176;
            case "lg_arrow" -> 22;
            case "lg_label" -> 80;
            default -> 16;
        };
    }

    private static int defaultHeight(String type) {
        return switch (type) {
            case "lg_panel" -> 166;
            case "lg_arrow" -> 15;
            case "lg_label" -> 9;
            default -> 54;
        };
    }

    private static int color(String text) {
        String hex = text.startsWith("#") ? text.substring(1) : text;
        try {
            return hex.length() == 6 ? Integer.parseInt(hex, 16) : 0x404040;
        } catch (NumberFormatException bad) {
            return 0x404040;
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
