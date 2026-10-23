package net.xmilon.craftpy.compat.jei;

import static net.xmilon.craftpy.compat.MachineRecipeLayout.*;

import java.util.Arrays;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawableAnimated;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.xmilon.craftpy.content.MachineRecipe;

/** One JEI page per machine block a project made, with its {@code machine_recipe(...)} recipes. */
final class MachineCategory extends AbstractRecipeCategory<RecipeHolder<MachineRecipe>> {
    private final IDrawableAnimated arrow;
    private final IDrawableStatic arrowEmpty;

    MachineCategory(ResourceLocation machine, IGuiHelper gui) {
        super(typeOf(machine), title(machine), gui.createDrawableItemLike(BuiltInRegistries.ITEM.get(machine)), WIDTH, HEIGHT);
        this.arrow = gui.createAnimatedRecipeArrow(40);
        this.arrowEmpty = gui.getRecipeArrow();
    }

    static RecipeType<RecipeHolder<MachineRecipe>> typeOf(ResourceLocation machine) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<RecipeHolder<MachineRecipe>> holder = (Class) RecipeHolder.class;
        return new RecipeType<>(ResourceLocation.fromNamespaceAndPath("craftpy", "machine/" + machine.getNamespace() + "/" + machine.getPath()), holder);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, RecipeHolder<MachineRecipe> holder, IFocusGroup focuses) {
        MachineRecipe recipe = holder.value();
        for (int i = 0; i < recipe.inputs().size(); i++) {
            var input = recipe.inputs().get(i);
            var stacks = Arrays.stream(input.ingredient().getItems()).map(stack -> stack.copyWithCount(input.count())).toList();
            builder.addSlot(RecipeIngredientRole.INPUT, slotX(INPUTS_X, i) + 1, slotY(i) + 1).setStandardSlotBackground().addItemStacks(stacks);
        }
        for (int i = 0; i < recipe.outputs().size(); i++) {
            var output = recipe.outputs().get(i);
            var slot = builder.addSlot(RecipeIngredientRole.OUTPUT, slotX(OUTPUTS_X, i) + 1, slotY(i) + 1)
                    .setStandardSlotBackground().addItemStack(output.stack());
            if (output.chance() < 1) slot.addRichTooltipCallback((view, tooltip) -> tooltip.add(chance(output.chance())));
        }
        recipe.fluidInput().ifPresent(liquid -> {
            var gauge = builder.addSlot(RecipeIngredientRole.INPUT, FLUID_IN_X, 0).setFluidRenderer(Math.max(1000, liquid.amount()), false, GAUGE_W, GAUGE_H);
            fluids(liquid).forEach(fluid -> gauge.addFluidStack(fluid, liquid.amount()));
        });
        recipe.fluidOutput().ifPresent(liquid -> {
            var gauge = builder.addSlot(RecipeIngredientRole.OUTPUT, FLUID_OUT_X, 0).setFluidRenderer(Math.max(1000, liquid.amount()), false, GAUGE_W, GAUGE_H);
            fluids(liquid).forEach(fluid -> gauge.addFluidStack(fluid, liquid.amount()));
        });
    }

    @Override
    public void draw(RecipeHolder<MachineRecipe> holder, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
        arrowEmpty.draw(graphics, ARROW_X, ARROW_Y);
        arrow.draw(graphics, ARROW_X, ARROW_Y);
        var font = Minecraft.getInstance().font;
        var cost = cost(holder.value());
        graphics.drawString(font, cost, ARROW_X + 12 - font.width(cost) / 2, TEXT_Y, 0xFF808080, false);
    }
}
