package net.xmilon.craftpy.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * One recipe as the Recipes & drops window draws it: either JEI's own layout (when JEI is installed) or CraftPy's
 * generic card. Coordinates are GUI units; render thread.
 */
public interface RecipeCard {
    /** The category ("Crafting", "Mixing"...). */
    String title();

    /** A machine or block that makes it (JEI's catalyst), or null. */
    ItemStack station();

    int width();

    int height();

    void draw(GuiGraphics g, int x, int y, int mouseX, int mouseY);

    /** Tooltips and the like, drawn after every card. */
    default void drawOverlays(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
    }

    /** The item under the mouse, or null. */
    ItemStack stackAt(int x, int y, int mouseX, int mouseY);
}
