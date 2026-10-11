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
 * eraser, fill and eyedropper, a fixed palette plus live color, undo/redo,
 * zoom, grid, mirror, flip/rotate, blank textures in 16/32/64 px, and
 * "copy an existing texture into the project and edit" (the source is never
 * modified). Saving uploads the PNG into the current project's
 * {@code assets/textures/} folder.
 */
public class TextureEditorScreen extends Screen {
    private enum Tool {PENCIL, ERASER, FILL, EYEDROPPER}

    private static final int[] PALETTE = {
            0x00000000, 0xFF000000, 0xFFFFFFFF, 0xFF9E9E9E, 0xFF795548,
            0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00, 0xFF76FF03, 0xFF00C853,
            0xFF00E5FF, 0xFF2962FF, 0xFF6A1B9A, 0xFFE040FB, 0xFFFF4081,
            0xFF3E2723};

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

    private TextureEditorScreen(Screen parent, String projectId, int w, int h, int[] initial, String name) {
        super(Component.literal("Texture editor"));
        this.parent = parent;
        this.projectId = projectId;
        this.texWidth = w;
        this.texHeight = h;
        this.pixels = initial != null ? initial : new int[w * h];
        this.defaultName = name;
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
            Button b = Button.builder(Component.literal(t.name().substring(0, 1) + t.name().substring(1).toLowerCase(Locale.ROOT)),
                    x -> tool = toolRef).bounds(bx, top, 66, 16).build();
            addRenderableWidget(b);
            bx += 68;
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, "Texture editor  " + texWidth + "x" + texHeight + "  (" + (mirrorX ? "mirrorX " : "")
                + (mirrorY ? "mirrorY" : "") + ")", 8, 10, 0x4FC3F7, false);

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

        graphics.drawString(font, "Name:", 62, height - 22, 0xD7E1EA, false);
        if (!status.isEmpty()) {
            graphics.drawString(font, status, 8, height - 40, 0xFFB86C, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // palette
        for (int[] r : paletteRects) {
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                color = r[4];
                if (tool == Tool.ERASER) {
                    tool = Tool.PENCIL;
                }
                return true;
            }
        }
        if (insideCanvas(mouseX, mouseY)) {
            painting = true;
            paintAt(mouseX, mouseY, button);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (painting && insideCanvas(mouseX, mouseY)) {
            paintAt(mouseX, mouseY, button);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        painting = false;
        lastPaintX = -1;
        lastPaintY = -1;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (insideCanvas(mouseX, mouseY)) {
            zoom = Math.max(2, Math.min(40, zoom + (int) Math.signum(scrollY) * 2));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
            case EYEDROPPER -> color = pixels[y * texWidth + x];
            default -> {
                if (undo.isEmpty() || lastSnapshotPending) {
                    pushUndo();
                    lastSnapshotPending = false;
                }
                plot(x, y, currentPaintColor(button));
            }
        }
    }

    private boolean lastSnapshotPending = true;

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
