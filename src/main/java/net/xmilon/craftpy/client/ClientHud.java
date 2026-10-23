package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * What client-side Python draws on the screen: text, bars and item icons, kept here until Python replaces or removes them.
 * Drawing is every frame from this store, so Python is not asked 60 times a second. An owner (module or project) can have
 * {@link #PER_OWNER} elements, all owners together {@link #TOTAL}.
 */
public final class ClientHud {
    public static final int PER_OWNER = 200;
    public static final int TOTAL = 400;

    /** One thing on the screen; the fields a kind does not use stay at their defaults. */
    record Element(String kind, String anchor, double x, double y, double scale, String text, int color, boolean shadow,
                   double value, double max, int width, int height, int background, String label, String item, Integer count) {
    }

    private static final ConcurrentSkipListMap<String, Element> ELEMENTS = new ConcurrentSkipListMap<>();

    private ClientHud() {
    }

    /** Stores or replaces an element; false when it is malformed or a limit is reached. */
    public static boolean set(JsonObject p) {
        String owner = string(p, "owner", "");
        String id = string(p, "id", "");
        Element element = parse(p);
        if (owner.isEmpty() || id.isEmpty() || element == null) {
            return false;
        }
        String key = owner + "\u0000" + id;
        if (!ELEMENTS.containsKey(key)) {
            String prefix = owner + "\u0000";
            long owned = ELEMENTS.keySet().stream().filter(k -> k.startsWith(prefix)).count();
            if (owned >= PER_OWNER || ELEMENTS.size() >= TOTAL) {
                return false;
            }
        }
        ELEMENTS.put(key, element);
        return true;
    }

    static Element parse(JsonObject p) {
        String kind = string(p, "kind", "");
        String anchor = string(p, "anchor", "");
        if (!HudLayout.isAnchor(anchor) || !(kind.equals("text") || kind.equals("bar") || kind.equals("item"))) {
            return null;
        }
        double scale = clamp(number(p, "scale", 1), 0.25, 8);
        double x = clamp(number(p, "x", 0), -4000, 4000);
        double y = clamp(number(p, "y", 0), -4000, 4000);
        String label = p.has("label") && !p.get("label").isJsonNull() ? cut(string(p, "label", ""), 64) : null;
        Integer count = p.has("count") && p.get("count").isJsonPrimitive() && p.get("count").getAsJsonPrimitive().isNumber()
                ? (int) clamp(p.get("count").getAsDouble(), 0, 999) : null;
        return new Element(kind, anchor, x, y, scale, cut(string(p, "text", ""), 256),
                HudLayout.color(string(p, "color", "white"), 0xFFFFFF), !p.has("shadow") || p.get("shadow").getAsBoolean(),
                number(p, "value", 0), Math.max(1e-9, number(p, "max", 100)),
                (int) clamp(number(p, "width", 80), 1, 600), (int) clamp(number(p, "height", 6), 1, 60),
                HudLayout.color(string(p, "background", "dark_gray"), 0x555555), label, cut(string(p, "item", ""), 100), count);
    }

    public static void remove(String owner, String id) {
        ELEMENTS.remove(owner + "\u0000" + id);
    }

    public static void clear(String owner) {
        String prefix = owner + "\u0000";
        ELEMENTS.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public static void clearAll() {
        ELEMENTS.clear();
    }

    public static int size() {
        return ELEMENTS.size();
    }

    /** The GUI layer registered by CraftPyClient: draws every element above the rest of the HUD. */
    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        JoinOverview.render(graphics);
        Minecraft minecraft = Minecraft.getInstance();
        if (ELEMENTS.isEmpty() || minecraft.options.hideGui || minecraft.level == null) {
            return;
        }
        Font font = minecraft.font;
        int screenW = graphics.guiWidth();
        int screenH = graphics.guiHeight();
        for (Map.Entry<String, Element> entry : new ArrayList<>(ELEMENTS.entrySet())) {
            draw(graphics, font, entry.getValue(), screenW, screenH);
        }
    }

    private static void draw(GuiGraphics graphics, Font font, Element e, int screenW, int screenH) {
        double s = e.scale();
        switch (e.kind()) {
            case "text" -> {
                int[] at = HudLayout.position(e.anchor(), e.x(), e.y(), (int) Math.ceil(font.width(e.text()) * s),
                        (int) Math.ceil(font.lineHeight * s), screenW, screenH);
                graphics.pose().pushPose();
                graphics.pose().translate(at[0], at[1], 0);
                graphics.pose().scale((float) s, (float) s, 1f);
                graphics.drawString(font, e.text(), 0, 0, 0xFF000000 | e.color(), e.shadow());
                graphics.pose().popPose();
            }
            case "bar" -> {
                int w = (int) Math.ceil(e.width() * s);
                int h = (int) Math.ceil(e.height() * s);
                int[] at = HudLayout.position(e.anchor(), e.x(), e.y(), w, h, screenW, screenH);
                double fraction = Math.max(0, Math.min(1, e.value() / e.max()));
                graphics.fill(at[0], at[1], at[0] + w, at[1] + h, 0xFF000000 | e.background());
                graphics.fill(at[0], at[1], at[0] + (int) Math.round(w * fraction), at[1] + h, 0xFF000000 | e.color());
                if (e.label() != null && !e.label().isEmpty()) {
                    graphics.drawString(font, e.label(), at[0] + w + 4, at[1] + (h - font.lineHeight) / 2, 0xFFFFFFFF, true);
                }
            }
            case "item" -> {
                ItemStack stack = stackOf(e.item());
                int size = (int) Math.ceil(16 * s);
                int[] at = HudLayout.position(e.anchor(), e.x(), e.y(), size, size, screenW, screenH);
                graphics.pose().pushPose();
                graphics.pose().translate(at[0], at[1], 0);
                graphics.pose().scale((float) s, (float) s, 1f);
                graphics.renderItem(stack, 0, 0);
                if (e.count() != null) {
                    graphics.renderItemDecorations(font, stack, 0, 0, String.valueOf(e.count()));
                }
                graphics.pose().popPose();
            }
            default -> {
            }
        }
    }

    private static ItemStack stackOf(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? ItemStack.EMPTY
                : BuiltInRegistries.ITEM.getOptional(location).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    // -- small readers ------------------------------------------------------------------------------------------------

    private static String string(JsonObject p, String key, String fallback) {
        JsonElement value = p.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static double number(JsonObject p, String key, double fallback) {
        JsonElement value = p.get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            double number = value.getAsDouble();
            return Double.isFinite(number) ? number : fallback;
        }
        return fallback;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    /** For tests: how many elements one owner has. */
    static List<String> idsOf(String owner) {
        String prefix = owner + "\u0000";
        List<String> ids = new ArrayList<>();
        ELEMENTS.keySet().stream().filter(k -> k.startsWith(prefix)).forEach(k -> ids.add(k.substring(prefix.length())));
        return ids;
    }
}
