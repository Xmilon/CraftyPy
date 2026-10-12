package net.xmilon.craftpy.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.xmilon.craftpy.client.ClientProjectStore;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A dedicated REPL console widget.
 */
public class ConsoleWidget extends Widget {
    private final String sessionId;
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private String historyDraft = "";
    
    private final EditBoxWidget input;
    private final ScrollView outputScroll;
    private final VBox outputLines;
    
    private int lastSize = -1;
    
    public ConsoleWidget(String sessionId) {
        this.sessionId = sessionId;
        this.outputLines = new VBox().padding(0).gap(1);
        this.outputScroll = new ScrollView();
        this.outputScroll.addChild(outputLines);
        
        this.input = new EditBoxWidget(Component.literal(">>>"));
        this.input.editBox().setMaxLength(1000);
        
        addChild(outputScroll);
        addChild(input);
    }

    @Override
    public void layout() {
        int inputH = 16;
        outputScroll.setBounds(x, y, width, height - inputH - 2);
        input.setBounds(x, y + height - inputH, width, inputH);
    }

    public void append(String line, int color) {
        Label label = new Label(line).color(colorToken(color));
        outputLines.addChild(label);
        outputLines.layout();
        // TODO: auto-scroll to bottom
    }
    
    private String colorToken(int color) {
        // Map common colors to theme tokens
        return Theme.TEXT;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (input.editBox().isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_C && Screen.hasControlDown()) {
                ClientProjectStore.getInstance().interrupt();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                String cmd = input.editBox().getValue();
                if (!cmd.isBlank()) {
                    execute(cmd);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP) {
                historyPrev();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                historyNext();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    
    private void execute(String cmd) {
        append(">>> " + cmd, 0);
        history.add(cmd);
        historyIndex = -1;
        input.editBox().setValue("");
        ClientProjectStore.getInstance().evaluate(cmd, sessionId);
    }
    
    private void historyPrev() {
        if (history.isEmpty()) return;
        if (historyIndex == -1) {
            historyDraft = input.editBox().getValue();
            historyIndex = history.size() - 1;
        } else if (historyIndex > 0) {
            historyIndex--;
        }
        input.editBox().setValue(history.get(historyIndex));
    }
    
    private void historyNext() {
        if (historyIndex == -1) return;
        if (historyIndex < history.size() - 1) {
            historyIndex++;
            input.editBox().setValue(history.get(historyIndex));
        } else {
            historyIndex = -1;
            input.editBox().setValue(historyDraft);
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refreshFromStore();
    }

    private void refreshFromStore() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        List<String> log = store.log(sessionId);
        if (log.size() != lastSize) {
            outputLines.children.clear();
            for (String line : log) {
                int color = line.contains("ERROR") ? 0xFFFF6B6B : 0xFFD7E1EA;
                outputLines.addChild(new Label(line).color(colorToken(color)));
            }
            lastSize = log.size();
            outputLines.layout();
        }
    }

    public void focus() {
        input.editBox().setFocused(true);
    }
}
