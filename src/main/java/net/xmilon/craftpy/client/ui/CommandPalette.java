package net.xmilon.craftpy.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Command Palette widget (Ctrl+Shift+P).
 */
public class CommandPalette extends Widget {
    public record Command(String label, Runnable action) {}

    private final EditBoxWidget search;
    private final List<Command> allCommands = new ArrayList<>();
    private final List<Command> filtered = new ArrayList<>();
    private int selectedIndex = 0;
    private Consumer<Command> onSelect;
    private Runnable onCancel;

    public CommandPalette() {
        this.search = new EditBoxWidget(Component.literal("Command Palette"));
        addChild(search);
        search.editBox().setResponder(s -> filter(s));
    }

    public void setOnSelect(Consumer<Command> onSelect) {
        this.onSelect = onSelect;
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    public void addCommand(String label, Runnable action) {
        allCommands.add(new Command(label, action));
        filter(search.editBox().getValue());
    }

    public void clearCommands() {
        allCommands.clear();
        filtered.clear();
        selectedIndex = 0;
    }

    private void filter(String query) {
        filtered.clear();
        String q = query.toLowerCase();
        for (Command c : allCommands) {
            if (c.label.toLowerCase().contains(q)) {
                filtered.add(c);
            }
        }
        if (selectedIndex >= filtered.size()) selectedIndex = Math.max(0, filtered.size() - 1);
    }

    @Override
    public void layout() {
        search.setBounds(x + 4, y + 4, width - 8, 16);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Theme theme = Theme.current();
        Font font = Minecraft.getInstance().font;
        
        // Background
        graphics.fill(x, y, x + width, y + height, theme.color(Theme.PANEL));
        // Border
        graphics.fill(x, y, x + width, y + 1, theme.color(Theme.BORDER));
        graphics.fill(x, y + height - 1, x + width, y + height, theme.color(Theme.BORDER));
        graphics.fill(x, y, x + 1, y + height, theme.color(Theme.BORDER));
        graphics.fill(x + width - 1, y, x + width, y + height, theme.color(Theme.BORDER));

        int ly = y + 24;
        for (int i = 0; i < filtered.size(); i++) {
            boolean selected = (i == selectedIndex);
            if (selected) {
                graphics.fill(x + 2, ly, x + width - 2, ly + 14, theme.color(Theme.SELECTED));
            }
            graphics.drawString(font, filtered.get(i).label, x + 6, ly + 3, selected ? theme.color(Theme.ACCENT) : theme.color(Theme.TEXT), false);
            ly += 14;
            if (ly + 14 > y + height) break;
        }
        
        if (filtered.isEmpty() && !search.editBox().getValue().isEmpty()) {
            graphics.drawString(font, "No matching commands", x + 6, y + 27, theme.color(Theme.MUTED), false);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_UP) {
            selectedIndex = Math.max(0, selectedIndex - 1);
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_DOWN) {
            selectedIndex = Math.min(filtered.size() - 1, selectedIndex + 1);
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (selectedIndex >= 0 && selectedIndex < filtered.size()) {
                Command cmd = filtered.get(selectedIndex);
                if (onSelect != null) onSelect.accept(cmd);
                cmd.action.run();
            }
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (onCancel != null) onCancel.run();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    public void focus() {
        search.editBox().setFocused(true);
    }
}
