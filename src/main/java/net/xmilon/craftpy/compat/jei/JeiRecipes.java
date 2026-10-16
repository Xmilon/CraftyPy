package net.xmilon.craftpy.compat.jei;

import java.util.ArrayList;
import java.util.List;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.xmilon.craftpy.client.RecipeCard;

/**
 * Recipes through JEI's own runtime, the way JEI's recipe screen finds them ({@code RecipesGui}: categories limited
 * to the focus, then the recipes of each category limited to the focus) and drawn with JEI's own layouts, so every
 * category a mod registers (Create's mixing and pressing, Mekanism's machines...) looks exactly as in JEI.
 * Only touched through {@link net.xmilon.craftpy.client.RecipeSources} after it checked that JEI is loaded.
 */
public final class JeiRecipes {
    static volatile IJeiRuntime runtime;

    private JeiRecipes() {
    }

    public static boolean ready() {
        return runtime != null;
    }

    /** Recipes that make ({@code usedIn} false) or use the stack, at most {@code max}. Render / client thread. */
    public static List<RecipeCard> cards(ItemStack stack, boolean usedIn, int max) {
        IJeiRuntime jei = runtime;
        List<RecipeCard> out = new ArrayList<>();
        if (jei == null || stack.isEmpty()) {
            return out;
        }
        IRecipeManager recipes = jei.getRecipeManager();
        IFocus<ItemStack> focus = jei.getJeiHelpers().getFocusFactory()
                .createFocus(usedIn ? RecipeIngredientRole.INPUT : RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack);
        IFocusGroup group = jei.getJeiHelpers().getFocusFactory().createFocusGroup(List.of(focus));
        for (IRecipeCategory<?> category : recipes.createRecipeCategoryLookup().limitFocus(List.of(focus)).get().toList()) {
            if (out.size() >= max) {
                break;
            }
            addCategory(recipes, category, focus, group, max, out);
        }
        return out;
    }

    private static <T> void addCategory(IRecipeManager recipes, IRecipeCategory<T> category, IFocus<ItemStack> focus,
                                        IFocusGroup group, int max, List<RecipeCard> out) {
        ItemStack station = recipes.createRecipeCatalystLookup(category.getRecipeType()).get()
                .flatMap(ingredient -> ingredient.getItemStack().stream()).findFirst().orElse(null);
        String title = category.getTitle().getString();
        for (T recipe : recipes.createRecipeLookup(category.getRecipeType()).limitFocus(List.of(focus)).get().toList()) {
            if (out.size() >= max) {
                return;
            }
            recipes.createRecipeLayoutDrawable(category, recipe, group).ifPresent(layout -> out.add(new Card<>(title, station, layout)));
        }
    }

    private static final class Card<T> implements RecipeCard {
        private final String title;
        private final ItemStack station;
        private final IRecipeLayoutDrawable<T> layout;
        private long lastTick;

        Card(String title, ItemStack station, IRecipeLayoutDrawable<T> layout) {
            this.title = title;
            this.station = station;
            this.layout = layout;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public ItemStack station() {
            return station;
        }

        @Override
        public int width() {
            return layout.getRectWithBorder().getWidth();
        }

        @Override
        public int height() {
            return layout.getRectWithBorder().getHeight();
        }

        /** The layout's own rect sits inside its border: place it so the border starts at (x, y). */
        private void place(int x, int y) {
            Rect2i rect = layout.getRect();
            Rect2i border = layout.getRectWithBorder();
            layout.setPosition(x + (rect.getX() - border.getX()), y + (rect.getY() - border.getY()));
        }

        @Override
        public void draw(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
            long now = System.currentTimeMillis();
            if (now - lastTick >= 50) { // JEI ticks layouts 20 times a second (cycling ingredients, animations)
                lastTick = now;
                layout.tick();
            }
            place(x, y);
            layout.drawRecipe(g, mouseX, mouseY);
        }

        @Override
        public void drawOverlays(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
            place(x, y);
            layout.drawOverlays(g, mouseX, mouseY);
        }

        @Override
        public ItemStack stackAt(int x, int y, int mouseX, int mouseY) {
            place(x, y);
            return layout.getItemStackUnderMouse(mouseX, mouseY).orElse(null);
        }
    }
}
