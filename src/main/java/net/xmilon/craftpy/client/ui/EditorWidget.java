package net.xmilon.craftpy.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.xmilon.craftpy.client.screen.CodeEditor;

/**
 * A widget wrapper for the CodeEditor.
 */
public class EditorWidget extends Widget {
    private final CodeEditor editor;

    public EditorWidget(CodeEditor editor) {
        this.editor = editor;
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        editor.setBounds(x, y, width, height);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        editor.render(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return editor.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return editor.mouseScrolled(horizontalAmount, verticalAmount);
    }
}
