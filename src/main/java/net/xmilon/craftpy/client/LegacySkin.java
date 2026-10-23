package net.xmilon.craftpy.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.xmilon.craftpy.menu.LegacyShapes;

/**
 * Draws the pieces of a Minecraft-style window with real game art: the panel, slots, arrow and flame of Minecraft's own
 * furnace screen, and, when Mekanism is installed, its energy bar, liquid gauges, slots, arrow and flame. Nothing is copied:
 * the textures are named by resource location and the game loads them from the installed jars (or a resource pack that
 * changes them). A window's {@code style} picks: {@code auto} (Minecraft's art, Mekanism's for energy and liquids when it is
 * there), {@code mekanism} (Mekanism's for everything it has) or {@code plain} (the drawn shapes of {@link LegacyShapes}).
 * Energy and liquids without Mekanism have no Minecraft art, so they are drawn.
 */
public final class LegacySkin {
    /** Where the pieces are drawn: the game's screen, or the window maker's preview (same pieces, same art). */
    public interface Target {
        void fill(int x1, int y1, int x2, int y2, int argb);

        /** Part (u, v, uWidth x vHeight) of a texture of size texW x texH, stretched to (x, y, width x height). */
        void blit(ResourceLocation texture, int x, int y, int width, int height, float u, float v, int uWidth, int vHeight,
                  int texW, int texH);

        /** A GUI sprite ({@code minecraft:container/slot}) stretched to the box. */
        void sprite(ResourceLocation sprite, int x, int y, int width, int height);

        /** True when Mekanism's art can be used. */
        boolean mekanism();
    }

    /** The game's screen: GuiGraphics, Mekanism when the mod is loaded. */
    public static Target screen(GuiGraphics graphics) {
        return new Target() {
            @Override public void fill(int x1, int y1, int x2, int y2, int argb) { graphics.fill(x1, y1, x2, y2, argb); }
            @Override public void blit(ResourceLocation texture, int x, int y, int width, int height, float u, float v,
                                       int uWidth, int vHeight, int texW, int texH) {
                graphics.blit(texture, x, y, width, height, u, v, uWidth, vHeight, texW, texH);
            }
            @Override public void sprite(ResourceLocation sprite, int x, int y, int width, int height) { graphics.blitSprite(sprite, x, y, width, height); }
            @Override public boolean mekanism() { return ModList.get() != null && ModList.get().isLoaded("mekanism"); }
        };
    }

    private static final ResourceLocation FURNACE = ResourceLocation.withDefaultNamespace("textures/gui/container/furnace.png");
    private static final ResourceLocation SLOT = ResourceLocation.withDefaultNamespace("container/slot");
    private static final ResourceLocation BURN = ResourceLocation.withDefaultNamespace("container/furnace/burn_progress");
    private static final ResourceLocation LIT = ResourceLocation.withDefaultNamespace("container/furnace/lit_progress");
    private static final ResourceLocation BUTTON = ResourceLocation.withDefaultNamespace("widget/button");
    private static final ResourceLocation BUTTON_HOVER = ResourceLocation.withDefaultNamespace("widget/button_highlighted");

    private static ResourceLocation mek(String path) {
        return ResourceLocation.fromNamespaceAndPath("mekanism", "gui/" + path + ".png");
    }

    private final Target g;
    private final int left, top;
    private final boolean mekanism, mekanismAll, plain;
    private final LegacyShapes.Surface surface;

    public LegacySkin(Target target, int left, int top, String style) {
        this.g = target;
        this.left = left;
        this.top = top;
        this.plain = "plain".equals(style);
        this.mekanism = !plain && target.mekanism();
        this.mekanismAll = mekanism && "mekanism".equals(style);
        this.surface = (x1, y1, x2, y2, argb) -> target.fill(left + x1, top + y1, left + x2, top + y2, argb);
    }

    public LegacyShapes.Surface surface() {
        return surface;
    }

    public void panel(int x, int y, int w, int h) {
        if (plain) {
            LegacyShapes.panel(surface, x, y, w, h);
        } else if (mekanismAll) {
            nineSlice(mek("base"), x, y, w, h, 4, 256, 256, 0, 0, 256, 256);
        } else {
            // the furnace screen's own frame, stretched: corners 4 x 4, its grey inside
            nineSlice(FURNACE, x, y, w, h, 4, 256, 256, 0, 0, 176, 166);
            surface.fill(x + 4, y + 4, x + w - 4, y + h - 4, 0xFFC6C6C6);
        }
    }

    /** The 18 x 18 box of a slot whose item is drawn at (itemX, itemY); {@code group} picks Mekanism's input/output colors. */
    public void slot(int itemX, int itemY, String group) {
        if (plain) {
            LegacyShapes.slotBox(surface, itemX, itemY);
        } else if (mekanismAll) {
            String kind = switch (group) {
                case "input" -> "slot/input";
                case "output" -> "slot/output";
                case "fuel", "energy_in", "energy_out" -> "slot/power";
                default -> "slot/normal";
            };
            g.blit(mek(kind), left + itemX - 1, top + itemY - 1, 18, 18, 0, 0, 18, 18, 18, 18);
        } else {
            g.sprite(SLOT, left + itemX - 1, top + itemY - 1, 18, 18);
        }
    }

    public void arrow(int x, int y, int w, int h, double fraction) {
        double f = clamp(fraction);
        if (plain) {
            LegacyShapes.arrow(surface, x, y, w, h, f);
        } else if (mekanismAll) {
            // right.png: the empty arrow above, the full one below, 32 x 12 each
            g.blit(mek("progress/right"), left + x, top + y, w, h, 0, 0, 32, 12, 32, 24);
            int done = (int) Math.round(w * f);
            if (done > 0) g.blit(mek("progress/right"), left + x, top + y, done, h, 0, 12, (int) Math.round(32 * f), 12, 32, 24);
        } else {
            g.blit(FURNACE, left + x, top + y, w, h, 79, 34, 24, 16, 256, 256);
            int done = (int) Math.round(w * f);
            if (done > 0) g.blit(texture(BURN), left + x, top + y, done, h, 0, 0, (int) Math.round(24 * f), 16, 24, 16);
        }
    }

    public void flame(int x, int y, int w, int h, double fraction) {
        double f = clamp(fraction);
        if (plain) {
            LegacyShapes.flame(surface, x, y, w, h, f);
            return;
        }
        int lit = (int) Math.round(h * f);
        if (mekanismAll) {
            // flame.png: the dark flame on the left, the burning one on the right, 13 x 13 each
            g.blit(mek("progress/flame"), left + x, top + y, w, h, 0, 0, 13, 13, 26, 13);
            if (lit > 0) {
                int from = 13 - (int) Math.round(13 * f);
                g.blit(mek("progress/flame"), left + x, top + y + h - lit, w, lit, 13, from, 13, 13 - from, 26, 13);
            }
        } else {
            g.blit(FURNACE, left + x, top + y, w, h, 56, 36, 14, 14, 256, 256);
            if (lit > 0) {
                int from = 14 - (int) Math.round(14 * f);
                g.blit(texture(LIT), left + x, top + y + h - lit, w, lit, 0, from, 14, 14 - from, 14, 14);
            }
        }
    }

    public void energy(int x, int y, int w, int h, double fraction) {
        double f = clamp(fraction);
        if (!mekanism) {
            LegacyShapes.energy(surface, x, y, w, h, f);
            return;
        }
        // Mekanism's power bar: its frame, and the green fill rising from the bottom
        nineSlice(mek("bar/base"), x - 1, y - 1, w + 2, h + 2, 1, 5, 5, 0, 0, 5, 5);
        int filled = (int) Math.round(h * f);
        if (filled > 0) {
            int from = 52 - (int) Math.round(52 * f);
            g.blit(mek("bar/vertical_power"), left + x, top + y + h - filled, w, filled, 0, from, 4, 52 - from, 4, 52);
        }
    }

    /** A liquid gauge: the frame and the dark inside, then {@code liquid} draws the liquid, then Mekanism's scale on top. */
    public void gauge(int x, int y, int w, int h, Runnable liquid) {
        if (!mekanism) {
            LegacyShapes.well(surface, x, y, w, h);
            liquid.run();
            return;
        }
        nineSlice(mek("gauge/normal"), x - 1, y - 1, w + 2, h + 2, 1, 5, 5, 0, 0, 5, 5);
        liquid.run();
        g.blit(mek("gauge/standard"), left + x, top + y, w, h, 0, 0, 16, 58, 16, 58);
    }

    public void bar(int x, int y, int w, int h, double fraction, int rgb) {
        if (!mekanism) {
            LegacyShapes.bar(surface, x, y, w, h, fraction, rgb);
            return;
        }
        nineSlice(mek("bar/base"), x - 1, y - 1, w + 2, h + 2, 1, 5, 5, 0, 0, 5, 5);
        double f = clamp(fraction);
        int color = 0xFF000000 | rgb;
        if (w >= h) {
            surface.fill(x, y, x + (int) Math.round(w * f), y + h, color);
        } else {
            int filled = (int) Math.round(h * f);
            surface.fill(x, y + h - filled, x + w, y + h, color);
        }
    }

    /** Minecraft's button, lit while the pointer is over it. */
    public void button(int x, int y, int w, int h, boolean hovered) {
        if (plain) {
            LegacyShapes.panel(surface, x, y, w, h);
        } else {
            g.sprite(hovered ? BUTTON_HOVER : BUTTON, left + x, top + y, w, h);
        }
    }

    /**
     * A picture of any texture of the game or a mod: a GUI sprite ({@code minecraft:container/slot}) or a texture file
     * ({@code mekanism:gui/energy.png}, optionally {@code #u,v,width,height,textureWidth,textureHeight}) scaled to the piece.
     */
    public void image(int x, int y, int w, int h, String ref) {
        int hash = ref.indexOf('#');
        String id = hash < 0 ? ref : ref.substring(0, hash);
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) return;
        if (!location.getPath().endsWith(".png")) {
            g.sprite(location, left + x, top + y, w, h);
            return;
        }
        int[] region = {0, 0, w, h, w, h};
        if (hash >= 0) {
            String[] parts = ref.substring(hash + 1).split(",");
            for (int i = 0; i < Math.min(6, parts.length); i++) {
                try {
                    region[i] = Integer.parseInt(parts[i].trim());
                } catch (NumberFormatException ignored) {
                    // the default for that number stays
                }
            }
        }
        g.blit(location, left + x, top + y, w, h, region[0], region[1], Math.max(1, region[2]), Math.max(1, region[3]),
                Math.max(1, region[4]), Math.max(1, region[5]));
    }

    /** A GUI sprite's file, for drawing part of it (blitSprite cannot crop by a fraction in every direction). */
    private static ResourceLocation texture(ResourceLocation sprite) {
        return sprite.withPath(path -> "textures/gui/sprites/" + path + ".png");
    }

    /** Stretches a framed region: the {@code border}-wide edges keep their size, the middle is stretched. */
    private void nineSlice(ResourceLocation texture, int x, int y, int w, int h, int border, int texW, int texH,
                           int u, int v, int regionW, int regionH) {
        int b = Math.min(border, Math.min(w, h) / 2);
        int innerW = w - 2 * b, innerH = h - 2 * b, srcW = regionW - 2 * border, srcH = regionH - 2 * border;
        int X = left + x, Y = top + y;
        // corners
        g.blit(texture, X, Y, b, b, u, v, b, b, texW, texH);
        g.blit(texture, X + w - b, Y, b, b, u + regionW - border, v, b, b, texW, texH);
        g.blit(texture, X, Y + h - b, b, b, u, v + regionH - border, b, b, texW, texH);
        g.blit(texture, X + w - b, Y + h - b, b, b, u + regionW - border, v + regionH - border, b, b, texW, texH);
        if (innerW > 0) {
            g.blit(texture, X + b, Y, innerW, b, u + border, v, srcW, b, texW, texH);
            g.blit(texture, X + b, Y + h - b, innerW, b, u + border, v + regionH - border, srcW, b, texW, texH);
        }
        if (innerH > 0) {
            g.blit(texture, X, Y + b, b, innerH, u, v + border, b, srcH, texW, texH);
            g.blit(texture, X + w - b, Y + b, b, innerH, u + regionW - border, v + border, b, srcH, texW, texH);
        }
        if (innerW > 0 && innerH > 0) {
            g.blit(texture, X + b, Y + b, innerW, innerH, u + border, v + border, srcW, srcH, texW, texH);
        }
    }

    private static double clamp(double fraction) {
        return Math.max(0, Math.min(1, fraction));
    }
}
