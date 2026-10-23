package net.xmilon.craftpy.compat.emi;

import static net.xmilon.craftpy.compat.MachineRecipeLayout.*;

import java.util.ArrayList;
import java.util.List;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.render.EmiTexture;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.xmilon.craftpy.content.MachineRecipe;

/**
 * Found and loaded by EMI only (never by CraftPy): the recipes of project machine blocks ({@code machine_recipe(...)}), one
 * category per machine with the block as its workstation, laid out like the JEI page.
 */
@EmiEntrypoint
public final class CraftPyEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        byMachine().forEach((machine, recipes) -> {
            EmiStack block = EmiStack.of(BuiltInRegistries.ITEM.get(machine));
            EmiRecipeCategory category = new EmiRecipeCategory(
                    ResourceLocation.fromNamespaceAndPath("craftpy", "machine/" + machine.getNamespace() + "/" + machine.getPath()), block) {
                @Override
                public net.minecraft.network.chat.Component getName() {
                    return title(machine);
                }
            };
            registry.addCategory(category);
            registry.addWorkstation(category, block);
            recipes.forEach(holder -> registry.addRecipe(new Recipe(category, holder)));
        });
    }

    private static final class Recipe implements EmiRecipe {
        private final EmiRecipeCategory category;
        private final ResourceLocation id;
        private final MachineRecipe recipe;
        private final List<EmiIngredient> inputs = new ArrayList<>();
        private final List<EmiStack> outputs = new ArrayList<>();
        private EmiIngredient fluidIn = EmiStack.EMPTY;
        private EmiStack fluidOut = EmiStack.EMPTY;

        Recipe(EmiRecipeCategory category, RecipeHolder<MachineRecipe> holder) {
            this.category = category;
            this.id = holder.id();
            this.recipe = holder.value();
            recipe.inputs().forEach(input -> inputs.add(EmiIngredient.of(input.ingredient(), input.count())));
            recipe.outputs().forEach(output -> outputs.add(EmiStack.of(output.stack()).setChance(output.chance())));
            recipe.fluidInput().ifPresent(liquid -> fluidIn = EmiIngredient.of(fluids(liquid).stream()
                    .map(fluid -> (EmiIngredient) EmiStack.of(fluid, liquid.amount())).toList(), liquid.amount()));
            recipe.fluidOutput().ifPresent(liquid -> fluids(liquid).stream().findFirst()
                    .ifPresent(fluid -> fluidOut = EmiStack.of(fluid, liquid.amount())));
        }

        @Override public EmiRecipeCategory getCategory() { return category; }
        @Override public ResourceLocation getId() { return id; }
        @Override public int getDisplayWidth() { return WIDTH; }
        @Override public int getDisplayHeight() { return HEIGHT; }

        @Override
        public List<EmiIngredient> getInputs() {
            List<EmiIngredient> all = new ArrayList<>(inputs);
            if (!fluidIn.isEmpty()) all.add(fluidIn);
            return all;
        }

        @Override
        public List<EmiStack> getOutputs() {
            List<EmiStack> all = new ArrayList<>(outputs);
            if (!fluidOut.isEmpty()) all.add(fluidOut);
            return all;
        }

        @Override
        public void addWidgets(WidgetHolder widgets) {
            if (!fluidIn.isEmpty()) widgets.addTank(fluidIn, FLUID_IN_X, 0, GAUGE_W, GAUGE_H, Math.max(1000, recipe.fluidInput().get().amount()));
            for (int i = 0; i < inputs.size(); i++) widgets.addSlot(inputs.get(i), slotX(INPUTS_X, i), slotY(i));
            widgets.addFillingArrow(ARROW_X, ARROW_Y, Math.max(1, recipe.time()) * 50);
            for (int i = 0; i < outputs.size(); i++) widgets.addSlot(outputs.get(i), slotX(OUTPUTS_X, i), slotY(i)).recipeContext(this);
            if (!fluidOut.isEmpty()) {
                widgets.addTank(fluidOut, FLUID_OUT_X, 0, GAUGE_W, GAUGE_H, Math.max(1000, recipe.fluidOutput().get().amount())).recipeContext(this);
            }
            var cost = cost(recipe);
            widgets.addText(cost, ARROW_X + 12 - net.minecraft.client.Minecraft.getInstance().font.width(cost) / 2, TEXT_Y, 0xFF808080, false);
        }
    }
}
