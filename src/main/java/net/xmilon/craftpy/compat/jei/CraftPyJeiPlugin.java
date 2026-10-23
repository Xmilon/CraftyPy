package net.xmilon.craftpy.compat.jei;

import java.util.List;
import java.util.Map;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.compat.MachineRecipeLayout;
import net.xmilon.craftpy.content.MachineRecipe;

/**
 * Found and loaded by JEI only (never by CraftPy): hands JEI's runtime to the Recipes & drops window, and shows the recipes of
 * project machine blocks ({@code machine_recipe(...)}), one page per machine with the block as its catalyst.
 */
@JeiPlugin
public final class CraftPyJeiPlugin implements IModPlugin {
    private Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> machines = Map.of();

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(CraftPyLog.MODID, "ide");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        // JEI starts once the client has the server's recipes, so the machines are known here
        machines = MachineRecipeLayout.byMachine();
        var gui = registration.getJeiHelpers().getGuiHelper();
        machines.keySet().forEach(machine -> registration.addRecipeCategories(new MachineCategory(machine, gui)));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        machines.forEach((machine, recipes) -> registration.addRecipes(MachineCategory.typeOf(machine), recipes));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        machines.keySet().forEach(machine -> registration.addRecipeCatalysts(MachineCategory.typeOf(machine), BuiltInRegistries.ITEM.get(machine)));
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        JeiRecipes.runtime = runtime;
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiRecipes.runtime = null;
    }
}
