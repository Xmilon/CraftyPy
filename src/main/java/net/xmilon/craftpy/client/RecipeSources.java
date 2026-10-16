package net.xmilon.craftpy.client;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Where the Recipes & drops window gets recipe layouts. JEI classes are only touched after JEI is known to be
 * loaded, so CraftPy runs the same without it (the window then uses the server's generic recipe cards).
 */
public final class RecipeSources {
    private RecipeSources() {
    }

    /** True when JEI is installed and its runtime is up (in a world). */
    public static boolean jei() {
        return ModList.get() != null && ModList.get().isLoaded("jei") && net.xmilon.craftpy.compat.jei.JeiRecipes.ready();
    }

    public static List<RecipeCard> jeiCards(ItemStack stack, boolean usedIn, int max) {
        return jei() ? net.xmilon.craftpy.compat.jei.JeiRecipes.cards(stack, usedIn, max) : List.of();
    }
}
