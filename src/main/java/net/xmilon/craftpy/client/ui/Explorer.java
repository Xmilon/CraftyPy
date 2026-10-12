package net.xmilon.craftpy.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.xmilon.craftpy.client.ClientProjectStore;

import java.util.List;
import java.util.function.Consumer;

/**
 * Explorer widget showing project files.
 */
public class Explorer extends Widget {
    public interface ContextMenuCallback {
        void onContextMenu(String path, int x, int y);
    }

    private final Consumer<String> onSelect;
    private final ContextMenuCallback onContextMenu;
    private int scrollY;

    public Explorer(Consumer<String> onSelect, ContextMenuCallback onContextMenu) {
        this.onSelect = onSelect;
        this.onContextMenu = onContextMenu;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Theme theme = Theme.current();
        Font font = Minecraft.getInstance().font;
        ClientProjectStore store = ClientProjectStore.getInstance();
        List<String> files = store.files();
        String active = store.activeFile();
        
        // Headers
        graphics.drawString(font, "EXPLORER", x + 6, y + 6, theme.color(Theme.ACCENT), false);
        String activeProj = store.activeProjectId();
        graphics.drawString(font, activeProj == null ? "no project" : activeProj, x + 6, y + 18, theme.color(Theme.MUTED), false);
        
        int listTop = y + 40;
        graphics.drawString(font, "FILES", x + 6, listTop - 12, theme.color(Theme.ACCENT), false);
        
        int rowH = 16;
        int yOffset = 0;
        int listHeight = height - 40;
        
        graphics.enableScissor(x, listTop, x + width, y + height);
        
        if (files.isEmpty()) {
            graphics.drawString(font, "Project empty", x + 4, listTop + 4, theme.color(Theme.MUTED), false);
        } else {
            for (String file : files) {
                int rowY = listTop + yOffset - scrollY;
                if (rowY + rowH >= listTop && rowY < y + height) {
                    boolean isSelected = file.equals(active);
                    boolean isHovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowH;
                    
                    if (isSelected) {
                        graphics.fill(x, rowY, x + width, rowY + rowH, theme.color(Theme.SELECTED));
                    } else if (isHovered) {
                        graphics.fill(x, rowY, x + width, rowY + rowH, theme.color(Theme.PANEL_ALT));
                    }
                    
                    // Simple Icon Logic
                    int iconColor = theme.color(Theme.MUTED);
                    String icon = "•";
                    if (file.endsWith(".py")) { icon = "P"; iconColor = 0xFF4FC3F7; }
                    else if (file.endsWith(".json")) { icon = "{"; iconColor = 0xFFCE9178; }
                    else if (file.endsWith(".md")) { icon = "M"; iconColor = 0xFF569CD6; }
                    else if (file.startsWith("assets/")) { icon = "A"; iconColor = 0xFF81C784; }
                    
                    graphics.drawString(font, icon, x + 4, rowY + 3, iconColor, false);
                    
                    String label = file.startsWith("code/") ? file.substring(5) : file;
                    graphics.drawString(font, label, x + 16, rowY + 3, isSelected ? theme.color(Theme.ACCENT) : theme.color(Theme.TEXT), false);
                }
                yOffset += rowH;
            }
        }
        
        graphics.disableScissor();
        
        // Scrollbar
        int contentH = files.size() * rowH;
        if (contentH > height) {
            int sbW = 2;
            int sbH = (int) Math.max(10, ((float) height / contentH * height));
            int sbY = (int) ((float) scrollY / (contentH - height) * (height - sbH));
            graphics.fill(x + width - sbW - 1, y + sbY, x + width - 1, y + sbY + sbH, theme.color(Theme.MUTED));
        }
    }

    @Override
    protected boolean onClick(double mouseX, double mouseY, int button) {
        int listTop = y + 40;
        if (mouseY < listTop) return false;
        
        ClientProjectStore store = ClientProjectStore.getInstance();
        List<String> files = store.files();
        int rowH = 16;
        int index = (int) Math.floor((mouseY - listTop + scrollY) / rowH);
        
        if (index >= 0 && index < files.size()) {
            String file = files.get(index);
            if (button == 0) {
                onSelect.accept(file);
            } else if (button == 1) {
                onContextMenu.onContextMenu(file, (int)mouseX, (int)mouseY);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int listTop = y + 40;
        if (contains(mouseX, mouseY) && mouseY >= listTop) {
            List<String> files = ClientProjectStore.getInstance().files();
            int contentH = files.size() * 16;
            int listHeight = height - 40;
            if (contentH > listHeight) {
                scrollY = (int) Math.max(0, Math.min(scrollY - (int)(verticalAmount * 16), contentH - listHeight));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
