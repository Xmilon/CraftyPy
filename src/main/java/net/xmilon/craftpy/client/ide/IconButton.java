package net.xmilon.craftpy.client.ide;

import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

public class IconButton extends AbstractWidget {
    public enum Icon {
        PLAY(
            "..........",
            "..X.......",
            "..XXX.....",
            "..XXXXX...",
            "..XXXXXXX.",
            "..XXXXXXXX",
            "..XXXXXXX.",
            "..XXXXX...",
            "..XXX.....",
            "..X......."
        ),
        STOP(
            "..........",
            "..XXXXXX..",
            ".XXXXXXXX.",
            ".XXXXXXXX.",
            ".XXXXXXXX.",
            ".XXXXXXXX.",
            ".XXXXXXXX.",
            ".XXXXXXXX.",
            "..XXXXXX..",
            ".........."
        ),
        SAVE(
            "..........",
            "..X.......",
            "..X.......",
            "..X.......",
            "..X.......",
            "..X.......",
            ".XXXXXXX..",
            "..........",
            "XXXXXXXXXX",
            ".........."
        ),
        BUILD(
            "..........",
            "XXXXXXXXX.",
            "XXXXXXXXX.",
            "....X.....",
            "....X.....",
            "....X.....",
            "....X.....",
            "....X.....",
            "..XXX.....",
            ".........."
        ),
        VALIDATE(
            "..........",
            ".........X",
            "........XX",
            ".......XX.",
            "X....XX...",
            "XX..XX....",
            ".XXXX.....",
            "..XX......",
            "..........",
            ".........."
        ),
        ASSETS(
            "..........",
            "..XX..XX..",
            "..XX..XX..",
            "..XX..XX..",
            "..........",
            "..XX..XX..",
            "..XX..XX..",
            "..XX..XX..",
            "..........",
            ".........."
        ),
        NEW_FILE(
            "..........",
            ".XXXXXXXX.",
            ".X......X.",
            ".X......X.",
            ".X..XX..X.",
            ".X..XX..X.",
            ".X..XX..X.",
            ".X..XX..X.",
            ".XXXXXXXX.",
            ".........."
        ),
        NEW_FOLDER(
            "..........",
            ".XXXXXXXX.",
            ".XXXXXXXX.",
            ".XX.XX.XX.",
            ".XX.XX.XX.",
            ".XX.XX.XX.",
            ".XX.XX.XX.",
            ".XX.XX.XX.",
            ".XXXXXXXX.",
            ".........."
        ),
        REFRESH(
            "..........",
            ".XXXXXX...",
            "XX....XX..",
            "X......XX.",
            "X......XX.",
            "X......XX.",
            "XX....XX..",
            ".XXXXXX...",
            "..........",
            ".........."
        ),
        FIND(
            "..........",
            "..XXXX....",
            ".XX..XX...",
            "X..XX..X..",
            "X..XX..X..",
            "X..XX..X..",
            ".XX..XX...",
            "..XXXX.XX.",
            ".......XX.",
            "........X."
        ),
        FORMAT(
            "..........",
            "..XXXX....",
            ".XX..XX..X",
            ".XX....X..",
            ".XX..XX...",
            "..XXXX....",
            ".XX..XX...",
            ".XX....X..",
            ".XX..XX..X",
            "..XXXX...."
        ),
        CLOSE(
            "..........",
            "..........",
            ".XX....XX.",
            "..XX..XX..",
            "...XXXX...",
            "....XX....",
            "...XXXX...",
            "..XX..XX..",
            ".XX....XX.",
            ".........."
        ),
        CHEVRON(
            "..........",
            "..........",
            "..........",
            "..........",
            "....XX....",
            "...XXXX...",
            "..XXXXXX..",
            ".XXXXXXXX.",
            "..........",
            ".........."
        );

        private final boolean[][] cells = new boolean[10][10];

        Icon(String... rows) {
            for (int y = 0; y < rows.length && y < 10; y++) {
                String row = rows[y];
                for (int x = 0; x < row.length() && x < 10; x++) {
                    if (row.charAt(x) == 'X') {
                        this.cells[y][x] = true;
                    }
                }
            }
        }
    }

    public static boolean cell(Icon icon, int y, int x) {
        return icon.cells[y][x];
    }

    private final Icon icon;
    private final Runnable action;
    private final String label;
    private boolean disabled;

    public IconButton(Icon icon, Component tooltip, Runnable action) {
        this(icon, null, tooltip, action);
    }

    public IconButton(Icon icon, @Nullable String label, Component tooltip, Runnable action) {
        super(0, 0, label == null || label.isEmpty() ? 24 : 20 + 8 + (label.length() * 6 + 2), 22, tooltip);
        this.icon = icon;
        this.label = label;
        this.action = action;
        this.setTooltip(Tooltip.create(tooltip));
    }

    public IconButton disabled(boolean disabled) {
        this.disabled = disabled;
        return this;
    }

    public boolean isDisabled() {
        return this.disabled;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = this.isHoveredOrFocused() && !this.disabled;
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        graphics.fill(x, y, x + w, y + h, this.disabled ? IdeColors.PANEL : (hovered ? IdeColors.PANEL_HOVER : IdeColors.PANEL));
        graphics.fill(x, y, x + w, y + 1, hovered ? IdeColors.ACCENT : IdeColors.BORDER);
        graphics.fill(x, y + h - 1, x + w, y + h, IdeColors.BORDER);
        int iconColor = this.disabled ? IdeColors.MUTED : IdeColors.ACCENT;
        if (this.label != null && !this.label.isEmpty()) {
            for (int j = 0; j < 9; j++) {
                for (int i = 0; i < 9; i++) {
                    if (this.icon.cells[j][i]) {
                        graphics.fill(x + 7 + i, y + (h - 9) / 2 + 1 + j, x + 8 + i, y + (h - 9) / 2 + 2 + j, iconColor);
                    }
                }
            }
            graphics.drawString(Minecraft.getInstance().font, this.label, x + 17, y + (h - 8) / 2, iconColor, false);
        } else {
            for (int j = 0; j < 9; j++) {
                for (int i = 0; i < 9; i++) {
                    if (this.icon.cells[j][i]) {
                        graphics.fill(x + (w - 9) / 2 + i, y + (h - 9) / 2 + j, x + (w - 9) / 2 + i + 1, y + (h - 9) / 2 + j + 1, iconColor);
                    }
                }
            }
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
        this.defaultButtonNarrationText(narrationOutput);
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        if (button == 0 && !this.disabled && this.action != null) {
            this.action.run();
        }
    }
}