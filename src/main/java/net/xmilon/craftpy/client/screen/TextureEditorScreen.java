package net.xmilon.craftpy.client.screen;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Optional;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.client.ClientProjectStore;

/**
 * A small but complete pixel editor for project textures. Supports pencil,
 * eraser, fill and eyedropper, a fixed palette plus an HSV wheel with an alpha
 * slider (paint-style), undo/redo, zoom, grid, mirror, flip/rotate, blank
 * textures in 16/32/64 px, and "copy an existing texture into the project and
 * edit" (the source is never modified). Saving uploads the PNG into the current
 * project's {@code assets/textures/} folder.
 */
public class TextureEditorScreen extends Screen {
    private enum Tool {
        PENCIL("Pencil", 'B'), ERASER("Eraser", 'E'), FILL("Fill", 'G'), EYEDROPPER("Picker", 'I'),
        LINE("Line", 'L'), RECT("Rect", 'R');

        final String label;
        final char key;

        Tool(String label, char key) {
            this.label = label;
            this.key = key;
        }
    }

    private static final int[] PALETTE = {
            0x00000000, 0xFF000000, 0xFFFFFFFF, 0xFF9E9E9E, 0xFF795548,
            0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00, 0xFF76FF03, 0xFF00C853,
            0xFF00E5FF, 0xFF2962FF, 0xFF6A1B9A, 0xFFE040FB, 0xFFFF4081,
            0xFF3E2723};

    private static final int SV_SIZE = 40;
    private static final int BAR_W = 6;
    private static final int BAR_H = 40;

    private final Screen parent;
    private final String projectId;
    private int texWidth;
    private int texHeight;
    private int[] pixels;
    private int zoom = 16;
    private boolean grid = true;
    private boolean mirrorX;
    private boolean mirrorY;
    private Tool tool = Tool.PENCIL;
    private int color = 0xFF000000;
    private float hue;
    private float sat = 1f;
    private float val = 1f;
    private boolean colorDrag;
    private int svX;
    private int svY;
    private int hueX;
    private int hueY;
    private int alphaX;
    private int alphaY;

    private final ArrayDeque<int[]> undo = new ArrayDeque<>();
    private final ArrayDeque<int[]> redo = new ArrayDeque<>();

    private EditBox nameBox;
    private int canvasX;
    private int canvasY;
    private String status = "";
    private boolean painting;
    private int lastPaintX = -1;
    private int lastPaintY = -1;
    private final java.util.List<int[]> paletteRects = new java.util.ArrayList<>();
    /** Colors painted with most recently, newest first. */
    private final java.util.List<Integer> recentColors = new java.util.ArrayList<>();
    private EditBox hexBox;
    private int shownHexColor = 1;
    /** Line/rect tools: start pixel of the drag and the current end pixel. */
    private int shapeStartX = -1;
    private int shapeStartY = -1;
    private int shapeEndX = -1;
    private int shapeEndY = -1;
    private int shapeButton;
    private int hoverX = -1;
    private int hoverY = -1;

    private TextureEditorScreen(Screen parent, String projectId, int w, int h, int[] initial, String name) {
        super(Component.literal("Texture editor"));
        this.parent = parent;
        this.projectId = projectId;
        this.texWidth = w;
        this.texHeight = h;
        this.pixels = initial != null ? initial : new int[w * h];
        this.defaultName = name;
        setColorArgb(this.color);
    }

    private String defaultName;

    public static TextureEditorScreen blank(Screen parent, String projectId, int size) {
        return new TextureEditorScreen(parent, projectId, size, size, null, "texture_" + size);
    }

    public static TextureEditorScreen copyFrom(Screen parent, String projectId, AssetKind kind, String sourceId) {
        NativeImage image = loadSource(kind, sourceId);
        if (image == null) {
            return new TextureEditorScreen(parent, projectId, 16, 16, null, sanitize(sourceId));
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = image.getPixelRGBA(x, y);
                int a = (v >>> 24) & 0xFF;
                int b = (v >>> 16) & 0xFF;
                int g = (v >>> 8) & 0xFF;
                int r = v & 0xFF;
                px[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        image.close();
        return new TextureEditorScreen(parent, projectId, w, h, px, sanitize(sourceId));
    }

    /** Opens the editor pre-filled from an already decoded image (e.g. the asset viewer). */
    public static TextureEditorScreen fromImage(Screen parent, String projectId, NativeImage image, String name) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = image.getPixelRGBA(x, y);
                int a = (v >>> 24) & 0xFF;
                int b = (v >>> 16) & 0xFF;
                int g = (v >>> 8) & 0xFF;
                int r = v & 0xFF;
                px[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return new TextureEditorScreen(parent, projectId, w, h, px, sanitize(name));
    }

    private static NativeImage loadSource(AssetKind kind, String id) {
        try {
            String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(ns, "textures/" + path + ".png");
            Optional<Resource> res = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(rl);
            if (res.isPresent()) {
                try (InputStream in = res.get().open()) {
                    return NativeImage.read(in);
                }
            }
        } catch (Exception e) {
            CraftPy.LOGGER.warn("Failed to load texture source {}", id, e);
        }
        return null;
    }

    @Override
    protected void init() {
        int top = 28;
        int bx = 8;
        for (Tool t : Tool.values()) {
            Tool toolRef = t;
            Button b = Button.builder(Component.literal(t.label), x -> tool = toolRef).bounds(bx, top, 50, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(t.label + " (" + t.key + ")")))
                    .build();
            addRenderableWidget(b);
            bx += 52;
        }
        bx += 8;
        addRenderableWidget(Button.builder(Component.literal("Undo"), b -> doUndo()).bounds(bx, top, 50, 16).build());
        bx += 52;
        addRenderableWidget(Button.builder(Component.literal("Redo"), b -> doRedo()).bounds(bx, top, 50, 16).build());
        bx += 52;
        addRenderableWidget(Button.builder(Component.literal("Zoom+"), b -> zoom = Math.min(40, zoom + 2))
                .bounds(bx, top, 50, 16).build());
        bx += 52;
        addRenderableWidget(Button.builder(Component.literal("Zoom-"), b -> zoom = Math.max(2, zoom - 2))
                .bounds(bx, top, 50, 16).build());
        bx += 52;
        addRenderableWidget(Button.builder(Component.literal("Grid"), b -> grid = !grid).bounds(bx, top, 50, 16).build());
        bx += 52;
        addRenderableWidget(Button.builder(Component.literal("MirrorX"), b -> mirrorX = !mirrorX)
                .bounds(bx, top, 56, 16).build());
        bx += 58;
        addRenderableWidget(Button.builder(Component.literal("MirrorY"), b -> mirrorY = !mirrorY)
                .bounds(bx, top, 56, 16).build());

        int bottom = height - 26;
        nameBox = new EditBox(font, 100, bottom, 150, 16, Component.literal("name"));
        nameBox.setValue(defaultName);
        nameBox.setMaxLength(48);
        nameBox.setHint(Component.literal("texture name"));
        addRenderableWidget(nameBox);
        hexBox = new EditBox(font, svX(), 58 + SV_SIZE + 14, 70, 14, Component.literal("hex"));
        hexBox.setMaxLength(9);
        hexBox.setHint(Component.literal("#RRGGBB"));
        hexBox.setResponder(this::onHexTyped);
        addRenderableWidget(hexBox);
        shownHexColor = color + 1;
        addRenderableWidget(Button.builder(Component.literal("Flip H"), b -> flipHorizontally())
                .bounds(width - 340, bottom, 56, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Flip V"), b -> flipVertically())
                .bounds(width - 280, bottom, 56, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Rotate"), b -> rotate())
                .bounds(width - 220, bottom, 56, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Save to project"), b -> save())
                .bounds(width - 158, bottom, 100, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(width - 54, bottom, 48, 16).build());

        centerCanvas();
    }

    private void centerCanvas() {
        canvasX = (width - texWidth * zoom) / 2;
        canvasY = 70 + (Math.max(0, height - 100 - texHeight * zoom)) / 2;
    }

    // ------------------------------------------------------------------
    // Color helpers (HSV wheel + alpha, like a paint program)
    // ------------------------------------------------------------------

    private int svX() {
        return width - 154;
    }

    /** "#RRGGBB" or "#AARRGGBB" typed in the hex box. */
    private void onHexTyped(String value) {
        String hex = value.trim().startsWith("#") ? value.trim().substring(1) : value.trim();
        if (!hex.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) {
            return;
        }
        long parsed = Long.parseLong(hex, 16);
        int argb = hex.length() == 6 ? 0xFF000000 | (int) parsed : (int) parsed;
        setColorArgb(argb);
        shownHexColor = argb;
        if (tool == Tool.ERASER) {
            tool = Tool.PENCIL;
        }
    }

    private static String hex(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        return alpha == 0xFF ? String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF)
                : String.format(Locale.ROOT, "#%08X", argb);
    }

    private void rememberColor(int argb) {
        if (((argb >>> 24) & 0xFF) == 0) {
            return;
        }
        recentColors.remove((Integer) argb);
        recentColors.add(0, argb);
        while (recentColors.size() > 10) {
            recentColors.remove(recentColors.size() - 1);
        }
    }

    private void initWheelGeometry() {
        svX = width - 154;
        svY = 58;
        hueX = svX + SV_SIZE + 4;
        hueY = svY;
        alphaX = hueX + BAR_W + 4;
        alphaY = svY;
    }

    /** Re-derives hue/saturation/value/alpha from a packed ARGB color. */
    private void setColorArgb(int argb) {
        this.color = argb;
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        float[] hsv = rgbToHsv(r, g, b);
        this.hue = hsv[0];
        this.sat = hsv[1];
        this.val = hsv[2] < 0.02f ? 1f : hsv[2];
    }

    /** Current pick color: current hue/sat/val RGB with the selected alpha. */
    private int currentColorArgb() {
        int a = (color >>> 24) & 0xFF;
        return (a << 24) | (hsvToRgb(hue, sat, val) & 0xFFFFFF);
    }

    private static int hsvToRgb(float h, float s, float v) {
        int i = (int) (h * 6) % 6;
        float f = h * 6 - i;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        float r = 0f, g = 0f, b = 0f;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float d = max - min;
        float h = 0f;
        if (d != 0f) {
            if (max == rf) {
                h = ((gf - bf) / d) % 6f;
            } else if (max == gf) {
                h = (bf - rf) / d + 2f;
            } else {
                h = (rf - gf) / d + 4f;
            }
            h *= 60f;
            if (h < 0f) {
                h += 360f;
            }
            h /= 360f;
        }
        float s = max == 0f ? 0f : d / max;
        return new float[] { h, s, max };
    }

    /** Returns true when the click/drag landed on the SV box, hue bar or alpha bar. */
    private boolean applyWheelAt(double mouseX, double mouseY) {
        if (mouseX >= svX && mouseX < svX + SV_SIZE && mouseY >= svY && mouseY < svY + SV_SIZE) {
            sat = clamp01((float) (mouseX - svX) / SV_SIZE);
            val = 1f - clamp01((float) (mouseY - svY) / SV_SIZE);
            color = currentColorArgb();
            return true;
        }
        if (mouseX >= hueX && mouseX < hueX + BAR_W && mouseY >= hueY && mouseY < hueY + BAR_H) {
            hue = clamp01((float) (mouseY - hueY) / BAR_H);
            color = currentColorArgb();
            return true;
        }
        if (mouseX >= alphaX && mouseX < alphaX + BAR_W && mouseY >= alphaY && mouseY < alphaY + BAR_H) {
            int a = (int) (255 * clamp01((float) (mouseY - alphaY) / BAR_H));
            color = (color & 0x00FFFFFF) | (a << 24);
            return true;
        }
        return false;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Paints the color wheel in the top-right corner. */
    private void renderColorWheel(GuiGraphics g) {
        initWheelGeometry();
        // Saturation/value square (opaque RGB in the current hue).
        for (int iy = 0; iy < SV_SIZE; iy += 2) {
            for (int ix = 0; ix < SV_SIZE; ix += 2) {
                float s = ix / (float) SV_SIZE;
                float v = 1f - iy / (float) SV_SIZE;
                g.fill(svX + ix, svY + iy, svX + ix + 2, svY + iy + 2, 0xFF000000 | hsvToRgb(hue, s, v));
            }
        }
        g.fill(svX - 1, svY - 1, svX + SV_SIZE + 1, svY + SV_SIZE + 1, 0xFF666666);
        int mx = svX + (int) (sat * (SV_SIZE - 2)) + 1;
        int my = svY + (int) ((1f - val) * (SV_SIZE - 2)) + 1;
        g.fill(mx - 2, my - 2, mx + 3, my + 3, 0xFFFFFFFF);
        g.fill(mx - 2, my - 2, mx + 3, my + 3, 0xFF000000);
        g.fill(mx - 1, my - 1, mx + 2, my + 2, 0xFFFFFFFF);

        // Hue bar.
        for (int i = 0; i < BAR_H; i += 2) {
            g.fill(hueX, hueY + i, hueX + BAR_W, hueY + i + 2, 0xFF000000 | hsvToRgb(i / (float) BAR_H, 1f, 1f));
        }
        int hy = hueY + (int) (hue * (BAR_H - 2));
        g.fill(hueX - 1, hy - 1, hueX + BAR_W + 1, hy + 1, 0xFFFFFFFF);

        // Alpha bar: the selected RGB at varying alpha over a dark backdrop.
        int rgb = hsvToRgb(hue, sat, val);
        for (int i = 0; i < BAR_H; i += 2) {
            int a = (int) (255 * Math.max(0.08f, i / (float) BAR_H));
            int blended = 0xFF000000 | blend(rgb, 0xFF202020, a);
            g.fill(alphaX, alphaY + i, alphaX + BAR_W, alphaY + i + 2, blended);
        }
        int ai = alphaY + (int) ((((color >>> 24) & 0xFF) / 255f) * (BAR_H - 2));
        g.fill(alphaX - 1, ai - 1, alphaX + BAR_W + 1, ai + 1, 0xFFFFFFFF);
    }

    private static int blend(int rgb, int bg, int alpha) {
        int r = ((rgb >>> 16) & 0xFF) * alpha / 255 + ((bg >>> 16) & 0xFF) * (255 - alpha) / 255;
        int g = ((rgb >>> 8) & 0xFF) * alpha / 255 + ((bg >>> 8) & 0xFF) * (255 - alpha) / 255;
        int b = (rgb & 0xFF) * alpha / 255 + (bg & 0xFF) * (255 - alpha) / 255;
        return (r << 16) | (g << 8) | b;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        String cursor = hoverX >= 0 ? "   x" + hoverX + " y" + hoverY + "  " + hex(pixels[hoverY * texWidth + hoverX]) : "";
        graphics.drawString(font, "Texture editor  " + texWidth + "x" + texHeight + "  " + tool.label
                + (mirrorX ? "  mirrorX" : "") + (mirrorY ? "  mirrorY" : "") + cursor, 8, 10, 0x4FC3F7, false);
        if (!hexBox.isFocused() && shownHexColor != color) {
            shownHexColor = color;
            hexBox.setValue(hex(color));
        }

        centerCanvas();
        // checkerboard backdrop
        for (int y = 0; y < texHeight; y++) {
            for (int x = 0; x < texWidth; x++) {
                int px = canvasX + x * zoom;
                int py = canvasY + y * zoom;
                int bg = ((x + y) & 1) == 0 ? 0xFF3A3A3A : 0xFF2A2A2A;
                graphics.fill(px, py, px + zoom, py + zoom, bg);
                int c = pixels[y * texWidth + x];
                if (((c >>> 24) & 0xFF) != 0) {
                    graphics.fill(px, py, px + zoom, py + zoom, c);
                }
            }
        }
        if (grid && zoom >= 4) {
            for (int x = 0; x <= texWidth; x++) {
                int px = canvasX + x * zoom;
                graphics.fill(px, canvasY, px + 1, canvasY + texHeight * zoom, 0x30000000);
            }
            for (int y = 0; y <= texHeight; y++) {
                int py = canvasY + y * zoom;
                graphics.fill(canvasX, py, canvasX + texWidth * zoom, py + 1, 0x30000000);
            }
        }

        // palette
        paletteRects.clear();
        int px = 8;
        int py = height - 52;
        for (int c : PALETTE) {
            graphics.fill(px, py, px + 18, py + 18, ((c >>> 24) & 0xFF) == 0 ? 0xFF888888 : c);
            graphics.fill(px, py, px + 18, py + 1, 0xFF000000);
            graphics.fill(px, py + 17, px + 18, py + 18, 0xFF000000);
            paletteRects.add(new int[] { px, py, 18, 18, c });
            px += 20;
        }
        graphics.fill(px + 4, py, px + 22, py + 18, ((color >>> 24) & 0xFF) == 0 ? 0xFF888888 : color);
        graphics.drawString(font, "current", px + 28, py + 5, 0xD7E1EA, false);
        graphics.drawString(font, "A:" + ((color >>> 24) & 0xFF), px + 28, py + 17, 0xD7E1EA, false);
        // recently used colors, under the palette
        int rx = 8;
        int ry = py - 14;
        if (!recentColors.isEmpty()) {
            graphics.drawString(font, "recent", rx, ry - 10, 0x8A97A4, false);
        }
        for (int c : recentColors) {
            graphics.fill(rx, ry, rx + 12, ry + 12, c);
            paletteRects.add(new int[] { rx, ry, 12, 12, c });
            rx += 14;
        }
        if (shapeStartX >= 0 && shapeEndX >= 0) {
            int previewColor = shapeButton == 1 || tool == Tool.ERASER ? 0x80FF00FF : color;
            for (int[] point : shapePoints(shapeStartX, shapeStartY, shapeEndX, shapeEndY)) {
                int sx = canvasX + point[0] * zoom;
                int sy = canvasY + point[1] * zoom;
                graphics.fill(sx, sy, sx + zoom, sy + zoom, previewColor);
            }
        }

        renderColorWheel(graphics);
        graphics.drawString(font, "S/V  Hue  Alpha", svX, svY + SV_SIZE + 2, 0xD7E1EA, false);
        graphics.drawString(font, "B E G I L R tools, Ctrl+Z/Y, +/- zoom, H grid", 8, 48, 0x8A97A4, false);

        graphics.drawString(font, "Name:", 62, height - 22, 0xD7E1EA, false);
        if (!status.isEmpty()) {
            graphics.drawString(font, status, 8, height - 40, 0xFFB86C, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // color wheel (S/V square, hue bar, alpha bar)
        if (button == 0 && applyWheelAt(mouseX, mouseY)) {
            colorDrag = true;
            return true;
        }
        // palette
        for (int[] r : paletteRects) {
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                setColorArgb(r[4]);
                if (tool == Tool.ERASER) {
                    tool = Tool.PENCIL;
                }
                return true;
            }
        }
        if (insideCanvas(mouseX, mouseY)) {
            setFocused(null);
            if (tool == Tool.LINE || tool == Tool.RECT) {
                shapeStartX = shapeEndX = (int) ((mouseX - canvasX) / zoom);
                shapeStartY = shapeEndY = (int) ((mouseY - canvasY) / zoom);
                shapeButton = button;
                return true;
            }
            painting = true;
            lastSnapshotPending = true;
            if (tool == Tool.PENCIL && button == 0) {
                rememberColor(color);
            }
            paintAt(mouseX, mouseY, button);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (colorDrag) {
            applyWheelAt(mouseX, mouseY);
            return true;
        }
        if (shapeStartX >= 0) {
            shapeEndX = Math.max(0, Math.min(texWidth - 1, (int) ((mouseX - canvasX) / zoom)));
            shapeEndY = Math.max(0, Math.min(texHeight - 1, (int) ((mouseY - canvasY) / zoom)));
            return true;
        }
        if (painting && insideCanvas(mouseX, mouseY)) {
            paintAt(mouseX, mouseY, button);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (shapeStartX >= 0 && shapeEndX >= 0) {
            pushUndo();
            int c = shapeButton == 1 || tool == Tool.ERASER ? 0 : color;
            if (c != 0) {
                rememberColor(c);
            }
            for (int[] point : shapePoints(shapeStartX, shapeStartY, shapeEndX, shapeEndY)) {
                plot(point[0], point[1], c);
            }
        }
        shapeStartX = shapeStartY = shapeEndX = shapeEndY = -1;
        prevStrokeX = prevStrokeY = -1;
        // The next stroke gets its own undo step.
        lastSnapshotPending = true;
        painting = false;
        colorDrag = false;
        lastPaintX = -1;
        lastPaintY = -1;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (Screen.hasControlDown() || insideCanvas(mouseX, mouseY)) {
            zoom = Math.max(2, Math.min(40, zoom + (int) Math.signum(scrollY) * 2));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (insideCanvas(mouseX, mouseY)) {
            hoverX = (int) ((mouseX - canvasX) / zoom);
            hoverY = (int) ((mouseY - canvasY) / zoom);
        } else {
            hoverX = hoverY = -1;
        }
        super.mouseMoved(mouseX, mouseY);
    }

    /** Pixels of a line (Bresenham) or a rectangle outline, depending on the tool. */
    private java.util.List<int[]> shapePoints(int x0, int y0, int x1, int y1) {
        java.util.List<int[]> points = new java.util.ArrayList<>();
        if (tool == Tool.RECT) {
            int left = Math.min(x0, x1), right = Math.max(x0, x1), top = Math.min(y0, y1), bottom = Math.max(y0, y1);
            for (int x = left; x <= right; x++) {
                points.add(new int[] { x, top });
                points.add(new int[] { x, bottom });
            }
            for (int y = top + 1; y < bottom; y++) {
                points.add(new int[] { left, y });
                points.add(new int[] { right, y });
            }
            return points;
        }
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0, y = y0;
        while (true) {
            points.add(new int[] { x, y });
            if (x == x1 && y == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
        return points;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() instanceof EditBox box && box.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        boolean ctrl = Screen.hasControlDown();
        if (ctrl && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Z) {
            doUndo();
            return true;
        }
        if (ctrl && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Y) {
            doRedo();
            return true;
        }
        if (!ctrl) {
            for (Tool t : Tool.values()) {
                if (keyCode == t.key) {
                    tool = t;
                    return true;
                }
            }
            switch (keyCode) {
                case org.lwjgl.glfw.GLFW.GLFW_KEY_H -> {
                    grid = !grid;
                    return true;
                }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD -> {
                    zoom = Math.min(40, zoom + 2);
                    return true;
                }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT -> {
                    zoom = Math.max(2, zoom - 2);
                    return true;
                }
                default -> {
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean insideCanvas(double mouseX, double mouseY) {
        return mouseX >= canvasX && mouseX < canvasX + texWidth * zoom && mouseY >= canvasY
                && mouseY < canvasY + texHeight * zoom;
    }

    private void paintAt(double mouseX, double mouseY, int button) {
        int x = (int) ((mouseX - canvasX) / zoom);
        int y = (int) ((mouseY - canvasY) / zoom);
        if (x < 0 || y < 0 || x >= texWidth || y >= texHeight) {
            return;
        }
        if (tool != Tool.PENCIL && tool != Tool.ERASER && (lastPaintX == x && lastPaintY == y)) {
            return;
        }
        lastPaintX = x;
        lastPaintY = y;
        switch (tool) {
            case FILL -> floodFill(x, y, currentPaintColor(button));
            case EYEDROPPER -> setColorArgb(pixels[y * texWidth + x]);
            default -> {
                if (lastSnapshotPending) {
                    pushUndo();
                    lastSnapshotPending = false;
                }
                int c = currentPaintColor(button);
                if (prevStrokeX >= 0) {
                    Tool shape = tool;
                    tool = Tool.LINE;
                    for (int[] point : shapePoints(prevStrokeX, prevStrokeY, x, y)) {
                        plot(point[0], point[1], c);
                    }
                    tool = shape;
                } else {
                    plot(x, y, c);
                }
                prevStrokeX = x;
                prevStrokeY = y;
            }
        }
    }

    private boolean lastSnapshotPending = true;
    private int prevStrokeX = -1;
    private int prevStrokeY = -1;

    private int currentPaintColor(int button) {
        if (tool == Tool.ERASER || button == 1) {
            return 0x00000000;
        }
        return color;
    }

    private void plot(int x, int y, int c) {
        setPixel(x, y, c);
        if (mirrorX) {
            setPixel(texWidth - 1 - x, y, c);
        }
        if (mirrorY) {
            setPixel(x, texHeight - 1 - y, c);
        }
        if (mirrorX && mirrorY) {
            setPixel(texWidth - 1 - x, texHeight - 1 - y, c);
        }
    }

    private void setPixel(int x, int y, int c) {
        if (x >= 0 && y >= 0 && x < texWidth && y < texHeight) {
            pixels[y * texWidth + x] = c;
        }
    }

    private void floodFill(int sx, int sy, int newColor) {
        int target = pixels[sy * texWidth + sx];
        if (target == newColor) {
            return;
        }
        pushUndo();
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] { sx, sy });
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int x = p[0];
            int y = p[1];
            if (x < 0 || y < 0 || x >= texWidth || y >= texHeight) {
                continue;
            }
            if (pixels[y * texWidth + x] != target) {
                continue;
            }
            pixels[y * texWidth + x] = newColor;
            stack.push(new int[] { x + 1, y });
            stack.push(new int[] { x - 1, y });
            stack.push(new int[] { x, y + 1 });
            stack.push(new int[] { x, y - 1 });
        }
    }

    private void pushUndo() {
        undo.push(pixels.clone());
        while (undo.size() > 60) {
            undo.removeLast();
        }
        redo.clear();
    }

    private void doUndo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.push(pixels.clone());
        pixels = undo.pop();
        lastSnapshotPending = true;
    }

    private void doRedo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.push(pixels.clone());
        pixels = redo.pop();
        lastSnapshotPending = true;
    }

    private void flipHorizontally() {
        pushUndo();
        for (int y = 0; y < texHeight; y++) {
            for (int x = 0; x < texWidth / 2; x++) {
                int a = y * texWidth + x;
                int b = y * texWidth + (texWidth - 1 - x);
                int tmp = pixels[a];
                pixels[a] = pixels[b];
                pixels[b] = tmp;
            }
        }
    }

    private void flipVertically() {
        pushUndo();
        for (int y = 0; y < texHeight / 2; y++) {
            for (int x = 0; x < texWidth; x++) {
                int a = y * texWidth + x;
                int b = (texHeight - 1 - y) * texWidth + x;
                int tmp = pixels[a];
                pixels[a] = pixels[b];
                pixels[b] = tmp;
            }
        }
    }

    private void rotate() {
        pushUndo();
        int[] rotated = new int[pixels.length];
        for (int y = 0; y < texHeight; y++) {
            for (int x = 0; x < texWidth; x++) {
                int nx = texHeight - 1 - y;
                int ny = x;
                if (texWidth == texHeight) {
                    rotated[ny * texWidth + nx] = pixels[y * texWidth + x];
                }
            }
        }
        if (texWidth == texHeight) {
            pixels = rotated;
        } else {
            status = "Rotate only supported for square textures";
        }
    }

    private void save() {
        String name = sanitize(nameBox.getValue());
        if (name.isBlank()) {
            status = "Enter a valid name [a-z0-9_/.-]";
            return;
        }
        try {
            NativeImage image = new NativeImage(texWidth, texHeight, false);
            for (int y = 0; y < texHeight; y++) {
                for (int x = 0; x < texWidth; x++) {
                    int argb = pixels[y * texWidth + x];
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    image.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            byte[] png = image.asByteArray();
            image.close();
            String path = "assets/textures/" + name + ".png";
            ClientProjectStore.getInstance().uploadAsset(projectId, path, png);
            status = "Saved " + path + " (" + png.length + " bytes)";
        } catch (Exception e) {
            status = "Save failed: " + e.getMessage();
        }
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_/.-]", "_");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
