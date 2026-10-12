package net.xmilon.craftpy.client.screen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.client.assets.AssetIndex;

/**
 * The asset picker. Shows a searchable, namespace-filterable, paginated grid of
 * assets from every loaded pack/mod. Textures are drawn from their raw image
 * (assumed 16x16, the vanilla block/item convention); items and blocks are drawn
 * as rendered item icons. Sounds and models are listed as text.
 *
 * <p>Selection calls the supplied callback with the chosen resource id and closes.</p>
 */
public class AssetPickerScreen extends Screen {
    private static final int CELL = 26;
    private static final AssetKind[] TABS = {AssetKind.TEXTURE, AssetKind.ITEM, AssetKind.BLOCK,
            AssetKind.MODEL, AssetKind.SOUND};
    private static final int NS_ROW_H = 14;
    private static final int MAX_NS_ROWS = 12;

    private final Screen parent;
    private final Consumer<String> onPick;
    private final String projectId;
    private AssetKind kind;
    private EditBox search;
    private String namespace = "";
    private int page = 0;
    private int columns = 1;
    private List<String> filtered = List.of();
    private int hoveredIndex = -1;
    private int contentTop;
    private int contentBottom;
    private int contentLeft;
    private int contentRight;
    private Button namespaceButton;
    private boolean namespaceOpen;
    private List<String> namespaceList = List.of();

    public AssetPickerScreen(Screen parent, AssetKind kind, Consumer<String> onPick) {
        this(parent, kind, onPick, null);
    }

    public AssetPickerScreen(Screen parent, AssetKind kind, Consumer<String> onPick, String projectId) {
        super(Component.literal("Asset picker"));
        this.parent = parent;
        this.kind = kind;
        this.onPick = onPick;
        this.projectId = projectId;
    }

    @Override
    protected void init() {
        AssetIndex.get().ensureBuilt();
        contentLeft = 12;
        contentRight = width - 12;
        contentTop = 58;
        contentBottom = height - 20;
        columns = Math.max(1, (contentRight - contentLeft) / CELL);

        int tabWidth = 62;
        int tx = contentLeft;
        int tabIndex = 0;
        for (AssetKind tab : TABS) {
            final int index = tabIndex;
            Button button = Button.builder(Component.literal(label(tab)), b -> {
                kind = TABS[index];
                page = 0;
                refresh();
            }).bounds(tx, 30, tabWidth, 20).build();
            addRenderableWidget(button);
            tx += tabWidth + 2;
            tabIndex++;
        }

        search = new EditBox(font, contentLeft, 8, 180, 18, Component.literal("Search"));
        search.setHint(Component.literal("Search assets - \"#tag\" or \"@mod\""));
        search.setResponder(s -> {
            page = 0;
            refresh();
        });
        addRenderableWidget(search);

        namespaceButton = Button.builder(namespaceLabel(), b -> toggleNamespace()).bounds(contentLeft + 188, 8, 120, 18)
                .build();
        addRenderableWidget(namespaceButton);

        addRenderableWidget(Button.builder(Component.literal("< PgUp"), b -> {
            if (page > 0) {
                page--;
            }
        }).bounds(contentRight - 150, 8, 70, 18).build());
        addRenderableWidget(Button.builder(Component.literal("PgDn >"), b -> {
            if ((page + 1) * pageSize() < filtered.size()) {
                page++;
            }
        }).bounds(contentRight - 76, 8, 70, 18).build());

        int bottomY = height - 18;
        int bx = contentLeft;
        addRenderableWidget(Button.builder(Component.literal("Inspect..."), b -> inspectHovered())
                .bounds(bx, bottomY, 74, 16).build());
        bx += 76;
        addRenderableWidget(Button.builder(Component.literal("Insert"), b -> {
            if (hoveredIndex >= 0 && hoveredIndex < filtered.size()) {
                onPick.accept(filtered.get(hoveredIndex));
                onClose();
            }
        }).bounds(bx, bottomY, 48, 16).build());
        bx += 50;
        if (kind == AssetKind.SOUND) {
            addRenderableWidget(Button.builder(Component.literal("Play"), b -> playHovered(false))
                    .bounds(bx, bottomY, 46, 16).build());
            bx += 48;
            addRenderableWidget(Button.builder(Component.literal("Loop"), b -> playHovered(true))
                    .bounds(bx, bottomY, 46, 16).build());
            bx += 48;
            addRenderableWidget(Button.builder(Component.literal("Stop"), b -> {
                net.xmilon.craftpy.client.assets.SoundPreview.stop();
            }).bounds(bx, bottomY, 46, 16).build());
            bx += 48;
        }
        if (kind == AssetKind.TEXTURE) {
            addRenderableWidget(Button.builder(Component.literal("Edit..."), b -> editHovered())
                    .bounds(bx, bottomY, 60, 16).build());
            bx += 62;
            addRenderableWidget(Button.builder(Component.literal("New16"), b -> openEditor(net.xmilon.craftpy.client.screen.TextureEditorScreen.blank(this, projectId, 16)))
                    .bounds(bx, bottomY, 48, 16).build());
            bx += 50;
            addRenderableWidget(Button.builder(Component.literal("New32"), b -> openEditor(net.xmilon.craftpy.client.screen.TextureEditorScreen.blank(this, projectId, 32)))
                    .bounds(bx, bottomY, 48, 16).build());
            bx += 50;
            addRenderableWidget(Button.builder(Component.literal("New64"), b -> openEditor(net.xmilon.craftpy.client.screen.TextureEditorScreen.blank(this, projectId, 64)))
                    .bounds(bx, bottomY, 48, 16).build());
            bx += 50;
        }
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(contentRight - 60, bottomY, 60, 16).build());

        refresh();
    }

    private void inspectHovered() {
        if (hoveredIndex >= 0 && hoveredIndex < filtered.size()) {
            minecraft.setScreen(new InspectorScreen(this, kind, filtered.get(hoveredIndex)));
        }
    }

    private void playHovered(boolean loop) {
        if (hoveredIndex < 0 || hoveredIndex >= filtered.size()) {
            return;
        }
        String id = filtered.get(hoveredIndex);
        float pitch = 1.0f;
        float volume = 1.0f;
        net.xmilon.craftpy.client.assets.SoundPreview.setPitchVolume(pitch, volume);
        if (loop) {
            net.xmilon.craftpy.client.assets.SoundPreview.playLooping(id, pitch, volume);
        } else {
            net.xmilon.craftpy.client.assets.SoundPreview.play(id, pitch, volume);
        }
    }

    private void editHovered() {
        if (projectId == null) {
            return;
        }
        String id = (hoveredIndex >= 0 && hoveredIndex < filtered.size()) ? filtered.get(hoveredIndex) : null;
        if (id != null) {
            openEditor(net.xmilon.craftpy.client.screen.TextureEditorScreen.copyFrom(this, projectId, kind, id));
        }
    }

    private void openEditor(net.xmilon.craftpy.client.screen.TextureEditorScreen screen) {
        net.xmilon.craftpy.client.assets.SoundPreview.stop();
        minecraft.setScreen(screen);
    }

    private static String label(AssetKind k) {
        return switch (k) {
            case TEXTURE -> "Textures";
            case ITEM -> "Items";
            case BLOCK -> "Blocks";
            case MODEL -> "Models";
            case SOUND -> "Sounds";
        };
    }

    private Component namespaceLabel() {
        return Component.literal("NS: " + (namespace.isEmpty() ? "all" : namespace));
    }

    private void toggleNamespace() {
        namespaceOpen = !namespaceOpen;
        if (namespaceOpen) {
            List<String> ns = new ArrayList<>(AssetIndex.get().namespaces());
            Collections.sort(ns);
            ns.add(0, "");
            namespaceList = ns;
        }
    }

    private void selectNamespace(String value) {
        namespace = value;
        namespaceOpen = false;
        if (namespaceButton != null) {
            namespaceButton.setMessage(namespaceLabel());
        }
        page = 0;
        refresh();
    }

    private int dropdownY() {
        return 28;
    }

    private int pageSize() {
        int rows = Math.max(1, (contentBottom - contentTop) / CELL);
        return columns * rows;
    }

    private void refresh() {
        String query = search == null ? "" : search.getValue();
        String q = query == null ? "" : query.trim();
        if (q.startsWith("#")) {
            filtered = AssetIndex.get().byTag(kind, q.substring(1));
        } else if (q.startsWith("@")) {
            filtered = AssetIndex.get().filter(kind, "", q.substring(1).trim());
        } else {
            filtered = AssetIndex.get().filter(kind, q, namespace);
        }
        if (page * pageSize() > filtered.size() && page > 0) {
            page = 0;
        }
        namespaceOpen = false;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawString(font, "Asset picker - " + label(kind) + "  (" + filtered.size() + " results)",
                contentLeft, 44, 0xFFFFFF, false);

        // Active tab underline (all tabs stay clickable).
        for (int t = 0; t < TABS.length; t++) {
            if (TABS[t] == kind) {
                guiGraphics.fill(contentLeft + t * 64, 52, contentLeft + t * 64 + 62, 54, 0xFF4FC3F7);
            }
        }

        int rows = Math.max(1, (contentBottom - contentTop) / CELL);
        int start = page * pageSize();
        hoveredIndex = -1;
        for (int i = 0; i < pageSize() && start + i < filtered.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            int x = contentLeft + col * CELL;
            int y = contentTop + row * CELL;
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                hoveredIndex = start + i;
            }
            boolean hover = hoveredIndex == start + i;
            guiGraphics.fill(x, y, x + CELL, y + CELL, hover ? 0x80FFFFFF : 0x40000000);
            drawCell(guiGraphics, filtered.get(start + i), x + 1, y + 1, CELL - 2);
        }

        // Namespace dropdown, drawn above the grid.
        if (namespaceOpen) {
            int nsX = contentLeft + 188;
            int nsY = dropdownY();
            int listRows = Math.min(MAX_NS_ROWS, namespaceList.size());
            int listH = listRows * NS_ROW_H;
            guiGraphics.fill(nsX - 1, nsY - 1, nsX + 121, nsY + listH + 1, 0xFF000000);
            for (int i = 0; i < listRows; i++) {
                String entry = namespaceList.get(i);
                String text = entry.isEmpty() ? "all namespaces" : entry;
                boolean selected = entry.equals(namespace);
                int rowY = nsY + i * NS_ROW_H;
                if (selected) {
                    guiGraphics.fill(nsX, rowY, nsX + 120, rowY + NS_ROW_H, 0xFF2C5B7A);
                } else if (mouseX >= nsX && mouseX <= nsX + 120 && mouseY >= rowY && mouseY < rowY + NS_ROW_H) {
                    guiGraphics.fill(nsX, rowY, nsX + 120, rowY + NS_ROW_H, 0x60FFFFFF);
                }
                guiGraphics.drawString(font, text, nsX + 3, rowY + 3, selected ? 0xFFFFFFFF : 0xFFD7E1EA, false);
            }
        }

        if (hoveredIndex >= 0 && hoveredIndex < filtered.size()) {
            String id = filtered.get(hoveredIndex);
            String hint = switch (kind) {
                case SOUND -> "  [shift-click: insert]";
                case MODEL -> "  [shift-click: insert]";
                case TEXTURE -> projectId != null ? "  [shift-click: insert]" : "";
                default -> "";
            };
            guiGraphics.renderTooltip(font, Component.literal(id + hint), mouseX, mouseY);
        }
        if (start + pageSize() < filtered.size()) {
            guiGraphics.drawString(font, "more...", contentRight - 40, contentBottom - 10, 0xAAAAAA, false);
        }
    }

    private void drawCell(GuiGraphics guiGraphics, String id, int x, int y, int size) {
        if (kind == AssetKind.ITEM || kind == AssetKind.BLOCK) {
            ItemStack stack = stackFor(kind, id);
            if (!stack.isEmpty()) {
                guiGraphics.renderItem(stack, x + 4, y + 4);
            }
            return;
        }
        if (kind == AssetKind.TEXTURE) {
            ResourceLocation rl = resource(kind, id);
            if (rl != null) {
                // Vanilla block/item textures are 16x16; draw whole image scaled to cell.
                guiGraphics.blit(rl, x, y, size, size, 0f, 0f, 16, 16, 16, 16);
            }
            return;
        }
        if (kind == AssetKind.MODEL) {
            ResourceLocation rl = net.xmilon.craftpy.client.assets.AssetPreview.modelPreviewTexture(id);
            if (rl != null) {
                guiGraphics.blit(rl, x, y, size, size, 0f, 0f, 16, 16, 16, 16);
            } else {
                net.xmilon.craftpy.client.assets.AssetPreview.drawCube(guiGraphics, x, y, size);
            }
            return;
        }
        if (kind == AssetKind.SOUND) {
            net.xmilon.craftpy.client.assets.AssetPreview.drawSpeaker(guiGraphics, x, y, size);
        }
    }

    private static ItemStack stackFor(AssetKind kind, String id) {
        try {
            ResourceLocation rl = ResourceLocation.parse(id);
            if (kind == AssetKind.BLOCK) {
                Block block = BuiltInRegistries.BLOCK.get(rl);
                Item item = block.asItem();
                return item == null ? ItemStack.EMPTY : new ItemStack(item);
            }
            Item item = BuiltInRegistries.ITEM.get(rl);
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    private static ResourceLocation resource(AssetKind kind, String id) {
        try {
            String path = id.contains(":") ? id : "minecraft:" + id;
            String[] parts = path.split(":", 2);
            String prefix = switch (kind) {
                case TEXTURE -> "textures/";
                case MODEL -> "models/";
                default -> "";
            };
            String suffix = switch (kind) {
                case TEXTURE -> ".png";
                case MODEL -> ".json";
                default -> "";
            };
            return ResourceLocation.fromNamespaceAndPath(parts[0], prefix + parts[1] + suffix);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (namespaceOpen) {
            int nsX = contentLeft + 188;
            int nsY = dropdownY();
            int listRows = Math.min(MAX_NS_ROWS, namespaceList.size());
            int listH = listRows * NS_ROW_H;
            if (mouseX >= nsX && mouseX <= nsX + 120 && mouseY >= nsY && mouseY < nsY + listH) {
                int row = (int) ((mouseY - nsY) / NS_ROW_H);
                if (row >= 0 && row < namespaceList.size()) {
                    selectNamespace(namespaceList.get(row));
                    return true;
                }
            }
            // Click elsewhere closes the menu but still falls through to normal handling.
            boolean onNamespaceButton = mouseX >= contentLeft + 188 && mouseX <= contentLeft + 188 + 120
                    && mouseY >= 8 && mouseY <= 26;
            if (!onNamespaceButton) {
                namespaceOpen = false;
            }
        }
        if (hoveredIndex >= 0 && hoveredIndex < filtered.size()) {
            String id = filtered.get(hoveredIndex);
            if (hasShiftDown()) {
                onPick.accept(id);
                onClose();
                return true;
            }
            openViewer(id);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void openViewer(String id) {
        if (minecraft == null) {
            return;
        }
        net.xmilon.craftpy.client.assets.SoundPreview.stop();
        switch (kind) {
            case SOUND -> minecraft.setScreen(new SoundPlayerScreen(this, projectId, id));
            case MODEL -> minecraft.setScreen(new ModelViewerScreen(this, projectId, id));
            case TEXTURE -> {
                if (projectId != null) {
                    minecraft.setScreen(net.xmilon.craftpy.client.screen.TextureEditorScreen.copyFrom(this, projectId, kind, id));
                } else {
                    minecraft.setScreen(new InspectorScreen(this, kind, id));
                }
            }
            default -> minecraft.setScreen(new InspectorScreen(this, kind, id));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY < 0 && (page + 1) * pageSize() < filtered.size()) {
            page++;
            return true;
        }
        if (scrollY > 0 && page > 0) {
            page--;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void tick() {
        super.tick();
        net.xmilon.craftpy.client.assets.SoundPreview.tick();
    }

    @Override
    public void onClose() {
        net.xmilon.craftpy.client.assets.SoundPreview.stop();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}