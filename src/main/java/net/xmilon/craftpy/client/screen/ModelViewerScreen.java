package net.xmilon.craftpy.client.screen;

import java.nio.charset.StandardCharsets;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.client.assets.AssetPreview;

/**
 * Viewer for a single model JSON. Shows a resolved texture thumbnail (falling
 * back to a cube glyph) next to the raw JSON, and can copy the model into the
 * current project's {@code assets/models/} folder.
 */
public class ModelViewerScreen extends Screen {
    private final Screen parent;
    private final String projectId;
    private final String modelId;

    private String json = "";
    private List<String> lines = List.of();
    private int scroll;
    private String status = "";
    private ResourceLocation preview;

    public ModelViewerScreen(Screen parent, String projectId, String modelId) {
        super(Component.literal("Model viewer"));
        this.parent = parent;
        this.projectId = projectId;
        this.modelId = modelId;
    }

    @Override
    protected void init() {
        json = AssetPreview.modelJson(modelId);
        lines = json.isBlank() ? List.of("(model JSON not found)") : List.of(json.split("\n", -1));
        preview = AssetPreview.modelPreviewTexture(modelId);

        int y = height - 26;
        if (projectId != null) {
            addRenderableWidget(Button.builder(Component.literal("Copy to project"), b -> save())
                    .bounds(width - 226, y, 120, 18).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 102, y, 90, 18).build());
    }

    private void save() {
        if (json.isBlank()) {
            status = "Nothing to copy: model JSON not found";
            return;
        }
        String rel = modelId.contains(":") ? modelId.substring(modelId.indexOf(':') + 1) : modelId;
        if (rel.startsWith("models/")) {
            rel = rel.substring("models/".length());
        }
        String path = "assets/models/" + rel + ".json";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ClientProjectStore.getInstance().uploadAsset(projectId, path, bytes);
        status = "Copied " + path + " (" + bytes.length + " bytes)";
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, "Model viewer", 12, 12, 0x4FC3F7, false);
        graphics.drawString(font, modelId + (preview == null ? "  (no texture)" : ""), 12, 30, 0xFFFFFF, false);

        int boxX = 12;
        int boxY = 48;
        int boxSize = 96;
        graphics.fill(boxX - 1, boxY - 1, boxX + boxSize + 1, boxY + boxSize + 1, 0xFF000000);
        graphics.fill(boxX, boxY, boxX + boxSize, boxY + boxSize, 0xFF202830);
        if (preview != null) {
            graphics.blit(preview, boxX + 16, boxY + 16, boxSize - 32, boxSize - 32, 0f, 0f, 16, 16, 16, 16);
        } else {
            AssetPreview.drawCube(graphics, boxX + 24, boxY + 24, boxSize - 48);
        }

        int textX = boxX + boxSize + 12;
        int textY = 48;
        int textBottom = height - 34;
        graphics.enableScissor(textX, textY, width - 8, textBottom);
        int lineH = 10;
        int visible = Math.max(1, (textBottom - textY) / lineH);
        int maxScroll = Math.max(0, lines.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        for (int i = 0; i < visible && scroll + i < lines.size(); i++) {
            graphics.drawString(font, lines.get(scroll + i), textX, textY + i * lineH, 0xD7E1EA, false);
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            graphics.drawString(font, "scroll: " + scroll + "/" + maxScroll, textX, textBottom + 2, 0x8A97A4, false);
        }

        if (projectId == null) {
            graphics.drawString(font, "Open a project to copy this model into it.", 12, boxY + boxSize + 6, 0xFFB86C,
                    false);
        }
        if (!status.isEmpty()) {
            graphics.drawString(font, status, 12, boxY + boxSize + (projectId == null ? 18 : 6), 0xFFB86C, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY) * 2;
        return true;
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
