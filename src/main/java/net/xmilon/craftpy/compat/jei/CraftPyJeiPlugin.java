package net.xmilon.craftpy.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.CraftPyLog;

/** Found and loaded by JEI only (never by CraftPy): hands JEI's runtime to the Recipes & drops window. */
@JeiPlugin
public final class CraftPyJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(CraftPyLog.MODID, "ide");
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
