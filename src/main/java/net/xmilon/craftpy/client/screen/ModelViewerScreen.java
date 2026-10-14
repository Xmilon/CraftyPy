package net.xmilon.craftpy.client.screen;

import java.nio.charset.StandardCharsets;
import java.util.List;

import net.minecraft.client.Minecraft;
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
    /** The block/item that uses this model, rendered in 3D; EMPTY when none matches. */
    private net.minecraft.world.item.ItemStack stack = net.minecraft.world.item.ItemStack.EMPTY;
    private float rotX = 25;
    private float rotY = -35;
    private float modelScale = 1;
    private boolean rotating;
    private static final int BOX_X = 12;
    private static final int BOX_Y = 48;
    private static final int BOX_SIZE = 128;

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
        stack = stackFor(modelId);

        int y = height - 26;
        if (projectId != null) {
            addRenderableWidget(Button.builder(Component.literal("Copy to project"), b -> save())
                    .bounds(width - 226, y, 120, 18).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 102, y, 90, 18).build());
    }

    /** "minecraft:block/stone" / "minecraft:item/diamond" / "minecraft:stone" -> that item, if it exists. */
    private static net.minecraft.world.item.ItemStack stackFor(String id) {
        try {
            ResourceLocation rl = ResourceLocation.parse(id);
            String path = rl.getPath();
            for (String prefix : new String[] { "models/", "block/", "item/" }) {
                if (path.startsWith(prefix)) {
                    path = path.substring(prefix.length());
                }
            }
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getOptional(ResourceLocation.fromNamespaceAndPath(rl.getNamespace(), path));
            return item.map(net.minecraft.world.item.ItemStack::new)
                    .filter(s -> !s.isEmpty()).orElse(net.minecraft.world.item.ItemStack.EMPTY);
        } catch (RuntimeException e) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }

    private boolean insideBox(double mouseX, double mouseY) {
        return mouseX >= BOX_X && mouseX < BOX_X + BOX_SIZE && mouseY >= BOX_Y && mouseY < BOX_Y + BOX_SIZE;
    }

    /** Renders the item model rotated around its center (drag to rotate, wheel to zoom). */
    private void renderModel(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(BOX_X + BOX_SIZE / 2f, BOX_Y + BOX_SIZE / 2f, 150);
        float scale = BOX_SIZE * 0.55f * modelScale;
        pose.scale(scale, -scale, scale);
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(rotX));
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rotY));
        pose.translate(-0.5f, -0.5f, -0.5f);
        var renderer = mc.getItemRenderer();
        var model = renderer.getModel(stack, mc.level, null, 0);
        com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
        renderer.render(stack, net.minecraft.world.item.ItemDisplayContext.NONE, false, pose, graphics.bufferSource(),
                0xF000F0, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, model);
        graphics.flush();
        pose.popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!stack.isEmpty() && insideBox(mouseX, mouseY)) {
            rotating = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (rotating) {
            rotY += (float) dragX * 2;
            rotX = Math.max(-90, Math.min(90, rotX + (float) dragY * 2));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        rotating = false;
        return super.mouseReleased(mouseX, mouseY, button);
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

        int boxX = BOX_X;
        int boxY = BOX_Y;
        int boxSize = BOX_SIZE;
        graphics.fill(boxX - 1, boxY - 1, boxX + boxSize + 1, boxY + boxSize + 1, 0xFF000000);
        graphics.fill(boxX, boxY, boxX + boxSize, boxY + boxSize, 0xFF202830);
        if (!stack.isEmpty()) {
            graphics.enableScissor(boxX, boxY, boxX + boxSize, boxY + boxSize);
            renderModel(graphics);
            graphics.disableScissor();
            graphics.drawString(font, "drag: rotate, wheel: zoom", boxX, boxY + boxSize + 2, 0x8A97A4, false);
        } else if (preview != null) {
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
            graphics.drawString(font, "Open a project to copy this model into it.", 12, boxY + boxSize + 14, 0xFFB86C,
                    false);
        } else {
            graphics.drawString(font, "Copy to project, then edit the JSON in the Editor.", 12, boxY + boxSize + 14,
                    0x8A97A4, false);
        }
        if (!status.isEmpty()) {
            graphics.drawString(font, status, 12, boxY + boxSize + 26, 0xFFB86C, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!stack.isEmpty() && insideBox(mouseX, mouseY)) {
            modelScale = Math.max(0.3f, Math.min(3f, modelScale * (scrollY > 0 ? 1.1f : 1 / 1.1f)));
            return true;
        }
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
