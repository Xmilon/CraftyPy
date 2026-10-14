package net.xmilon.craftpy.client.ide;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.xmilon.craftpy.client.screen.CodeEditor;

public class CodeEditorWidget extends AbstractWidget {
    private final CodeEditor editor;

    public CodeEditorWidget(CodeEditor editor) {
        super(0, 0, 1, 1, Component.literal("Code editor"));
        this.editor = editor;
    }

    public CodeEditor editor() {
        return this.editor;
    }

    private void syncBounds() {
        this.editor.setBounds(this.getX(), this.getY(), this.getWidth(), this.getHeight());
    }

    @Override
    public void setX(int x) {
        super.setX(x);
        this.syncBounds();
    }

    @Override
    public void setY(int y) {
        super.setY(y);
        this.syncBounds();
    }

    @Override
    public void setWidth(int width) {
        super.setWidth(width);
        this.syncBounds();
    }

    @Override
    public void setHeight(int height) {
        super.setHeight(height);
        this.syncBounds();
    }

    @Override
    public void setSize(int width, int height) {
        super.setSize(width, height);
        this.syncBounds();
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), IdeColors.BG);
        this.editor.render(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return this.visible && this.editor.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        this.editor.mouseDragged(mouseX, mouseY, button);
        return this.visible;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!this.visible) {
            return false;
        }
        if (net.minecraft.client.gui.screens.Screen.hasControlDown() && this.editor.isInside(mouseX, mouseY)) {
            return this.editor.zoomBy(scrollY);
        }
        return this.editor.isInside(mouseX, mouseY) && this.editor.mouseScrolled(scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return this.visible && this.editor.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return this.visible && this.editor.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean isFocused() {
        return this.editor.isFocused();
    }

    @Override
    public void setFocused(boolean focused) {
        this.editor.setFocused(focused);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
        this.defaultButtonNarrationText(narrationOutput);
    }
}