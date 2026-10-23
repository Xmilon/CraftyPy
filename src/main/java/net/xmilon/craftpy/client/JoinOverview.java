package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The small panel that appears in the top right corner when you join a server from another computer: the modules it runs,
 * one line each. It stays for a few seconds, then fades; {@code /craftpy_client server_modules} shows them again.
 */
public final class JoinOverview {
    private static final long SHOWN_MS = 12_000, FADE_MS = 1_500;
    private static final int MAX_LINES = 12;

    private record Line(String text, boolean flagged) {
    }

    private static volatile List<Line> lines = List.of();
    private static volatile long shownAt;

    private JoinOverview() {
    }

    /** The {@code overview} of the server's info: every module it runs (id, name, version, description, flagged). */
    static void show(JsonObject info) {
        List<Line> out = new ArrayList<>();
        if (info != null && info.has("overview") && info.get("overview").isJsonArray()) {
            for (JsonElement element : info.getAsJsonArray("overview")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject module = element.getAsJsonObject();
                String name = module.has("name") ? module.get("name").getAsString() : "?";
                String version = module.has("version") ? module.get("version").getAsString() : "";
                String note = module.has("description") ? module.get("description").getAsString() : "";
                boolean flagged = module.has("flagged") && module.get("flagged").getAsBoolean();
                out.add(new Line(name + (version.isEmpty() ? "" : " v" + version) + (note.isEmpty() ? "" : " - " + note), flagged));
            }
        }
        lines = List.copyOf(out);
        shownAt = 0; // the clock starts when the world is on screen
    }

    public static void clear() {
        lines = List.of();
    }

    public static void render(GuiGraphics graphics) {
        List<Line> shown = lines;
        Minecraft minecraft = Minecraft.getInstance();
        if (shown.isEmpty() || minecraft.options.hideGui || minecraft.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (shownAt == 0) {
            shownAt = now;
        }
        long age = now - shownAt;
        if (age > SHOWN_MS + FADE_MS) {
            lines = List.of();
            return;
        }
        int alpha = age <= SHOWN_MS ? 255 : (int) (255 * (1 - (age - SHOWN_MS) / (double) FADE_MS));
        if (alpha < 8) {
            return;
        }
        Font font = minecraft.font;
        int rows = Math.min(shown.size(), MAX_LINES);
        int width = font.width("Server modules (" + shown.size() + ")");
        for (int i = 0; i < rows; i++) {
            width = Math.max(width, Math.min(260, font.width(shown.get(i).text())));
        }
        int x = graphics.guiWidth() - width - 12, y = 8, height = (rows + 1 + (shown.size() > rows ? 1 : 0)) * 11 + 6;
        graphics.fill(x - 5, y - 4, x + width + 5, y - 4 + height, alpha / 2 << 24 | 0x101418);
        graphics.drawString(font, "Server modules (" + shown.size() + ")", x, y, alpha << 24 | 0x36B37E, false);
        for (int i = 0; i < rows; i++) {
            Line line = shown.get(i);
            graphics.drawString(font, font.plainSubstrByWidth(line.text(), 260), x, y + 11 * (i + 1),
                    alpha << 24 | (line.flagged() ? 0xF2C55C : 0xDFE1E5), false);
        }
        if (shown.size() > rows) {
            graphics.drawString(font, "+" + (shown.size() - rows) + " more: /craftpy_client server_modules", x,
                    y + 11 * (rows + 1), alpha << 24 | 0x9DA0A8, false);
        }
    }
}
