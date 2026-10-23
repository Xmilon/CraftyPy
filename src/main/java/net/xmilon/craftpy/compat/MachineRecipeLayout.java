package net.xmilon.craftpy.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.xmilon.craftpy.content.MachineRecipe;

/**
 * Where JEI and EMI put the parts of a {@link MachineRecipe} (the same picture in both): the liquid going in, up to 3 x 3
 * inputs, an arrow, up to 3 x 3 outputs and the liquid coming out, with the energy and time below the arrow.
 */
public final class MachineRecipeLayout {
    public static final int WIDTH = 170, HEIGHT = 66;
    public static final int FLUID_IN_X = 0, INPUTS_X = 14, ARROW_X = 72, ARROW_Y = 19, OUTPUTS_X = 100, FLUID_OUT_X = 158;
    public static final int GAUGE_W = 12, GAUGE_H = 54, TEXT_Y = 57;

    private MachineRecipeLayout() {
    }

    public static int slotX(int base, int index) { return base + (index % 3) * 18; }
    public static int slotY(int index) { return (index / 3) * 18; }

    /** The client's machine recipes by machine block, in data-pack order (empty before the client has recipes). */
    public static Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> byMachine() {
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> out = new LinkedHashMap<>();
        var level = Minecraft.getInstance().level;
        if (level == null) return out;
        for (var holder : level.getRecipeManager().getAllRecipesFor(MachineRecipe.TYPE.get())) {
            out.computeIfAbsent(holder.value().machine(), key -> new ArrayList<>()).add(holder);
        }
        return out;
    }

    /** The liquids a recipe names: one, or every still liquid of a {@code #tag}. */
    public static List<Fluid> fluids(MachineRecipe.LiquidAmount liquid) {
        List<Fluid> out = new ArrayList<>();
        if (liquid.fluid().startsWith("#")) {
            ResourceLocation tag = ResourceLocation.tryParse(liquid.fluid().substring(1));
            if (tag != null) {
                BuiltInRegistries.FLUID.getTagOrEmpty(TagKey.create(Registries.FLUID, tag)).forEach(holder -> {
                    if (holder.value().isSource(holder.value().defaultFluidState())) out.add(holder.value());
                });
            }
        } else {
            ResourceLocation id = ResourceLocation.tryParse(liquid.fluid());
            Fluid fluid = id == null ? Fluids.EMPTY : BuiltInRegistries.FLUID.get(id);
            if (fluid != Fluids.EMPTY) out.add(fluid);
        }
        return out;
    }

    /** "400 FE · 4 s" below the arrow. */
    public static Component cost(MachineRecipe recipe) {
        String seconds = recipe.time() % 20 == 0 ? String.valueOf(recipe.time() / 20) : String.format(Locale.ROOT, "%.2f", recipe.time() / 20.0);
        return Component.literal((recipe.energy() > 0 ? recipe.energy() + " FE · " : "") + seconds + " s");
    }

    public static Component chance(float chance) {
        return Component.literal(String.format(Locale.ROOT, "%.0f%% chance", chance * 100)).withStyle(ChatFormatting.GOLD);
    }

    /** The machine block's name, the page title. */
    public static Component title(ResourceLocation machine) {
        return BuiltInRegistries.BLOCK.get(machine).getName();
    }
}
