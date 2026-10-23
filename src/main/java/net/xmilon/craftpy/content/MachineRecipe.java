package net.xmilon.craftpy.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * A recipe of a machine block a project made ({@code machine_recipe(grinder, inputs=..., outputs=...)}): items (with counts,
 * tags allowed) and optionally a liquid go in, items (each with a chance) and optionally a liquid come out, at a cost in energy
 * and time. It is an ordinary data-pack recipe, so it reaches every player's game and JEI and EMI show it under the machine.
 * The machine's Python decides when it runs (energy, timing); {@link #matches} and {@link #finish} do the item work so tags,
 * counts and stacking are always right.
 */
public record MachineRecipe(ResourceLocation machine, List<Input> inputs, List<Output> outputs, Optional<LiquidAmount> fluidInput,
                            Optional<LiquidAmount> fluidOutput, int energy, int time) implements Recipe<RecipeInput> {
    private static final DeferredRegister<RecipeType<?>> TYPES = DeferredRegister.create(Registries.RECIPE_TYPE, "craftpy");
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, "craftpy");
    public static final DeferredHolder<RecipeType<?>, RecipeType<MachineRecipe>> TYPE = TYPES.register("machine", () -> new RecipeType<>() {
        @Override public String toString() { return "craftpy:machine"; }
    });
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<MachineRecipe>> SERIALIZER = SERIALIZERS.register("machine", Serializer::new);

    /** {@code count} items that match {@code ingredient} (an item or a tag). */
    public record Input(Ingredient ingredient, int count) {
        static final Codec<Input> CODEC = RecordCodecBuilder.create(i -> i.group(
                Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(Input::ingredient),
                Codec.intRange(1, 64).optionalFieldOf("count", 1).forGetter(Input::count)).apply(i, Input::new));
        static final StreamCodec<RegistryFriendlyByteBuf, Input> STREAM = StreamCodec.composite(
                Ingredient.CONTENTS_STREAM_CODEC, Input::ingredient, ByteBufCodecs.VAR_INT, Input::count, Input::new);
    }

    /** A stack that comes out with {@code chance} (0..1]. */
    public record Output(ItemStack stack, float chance) {
        static final Codec<Output> CODEC = RecordCodecBuilder.create(i -> i.group(
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(Output::stack),
                Codec.floatRange(0.0001f, 1).optionalFieldOf("chance", 1f).forGetter(Output::chance)).apply(i, Output::new));
        static final StreamCodec<RegistryFriendlyByteBuf, Output> STREAM = StreamCodec.composite(
                ItemStack.STREAM_CODEC, Output::stack, ByteBufCodecs.FLOAT, Output::chance, Output::new);
    }

    /** A liquid by id ({@code minecraft:lava}) or tag ({@code #c:lava}) and an amount in mB. */
    public record LiquidAmount(String fluid, int amount) {
        static final Codec<LiquidAmount> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("fluid").forGetter(LiquidAmount::fluid),
                Codec.intRange(1, 1_000_000).fieldOf("amount").forGetter(LiquidAmount::amount)).apply(i, LiquidAmount::new));
        static final StreamCodec<RegistryFriendlyByteBuf, LiquidAmount> STREAM = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, LiquidAmount::fluid, ByteBufCodecs.VAR_INT, LiquidAmount::amount, LiquidAmount::new);

        public boolean test(Fluid candidate) {
            if (fluid.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(fluid.substring(1));
                return tag != null && candidate.defaultFluidState().is(TagKey.create(Registries.FLUID, tag));
            }
            return BuiltInRegistries.FLUID.getKey(candidate).toString().equals(fluid);
        }

        /** The liquid an output names (a tag gives nothing). */
        public FluidStack stack() {
            ResourceLocation id = fluid.startsWith("#") ? null : ResourceLocation.tryParse(fluid);
            Fluid found = id == null ? null : BuiltInRegistries.FLUID.get(id);
            return found == null ? FluidStack.EMPTY : new FluidStack(found, amount);
        }
    }

    public MachineRecipe {
        if (inputs.isEmpty() && fluidInput.isEmpty()) throw new IllegalArgumentException("A machine recipe needs something going in");
        if (outputs.isEmpty() && fluidOutput.isEmpty()) throw new IllegalArgumentException("A machine recipe needs something coming out");
        if (inputs.size() > 9 || outputs.size() > 9) throw new IllegalArgumentException("A machine recipe has at most 9 inputs and 9 outputs");
        if (energy < 0 || time < 1 || time > 72000) throw new IllegalArgumentException("Machine recipe energy or time out of range");
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        SERIALIZERS.register(bus);
    }

    /** The machine's slots of these roles hold enough of every input, and its tanks the liquid. */
    public boolean matches(KineticInventory inventory, IFluidHandler tanks) {
        List<ItemStack> available = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.roleOf(slot).equals("input")) available.add(inventory.getStackInSlot(slot).copy());
        }
        for (Input input : inputs) {
            int needed = input.count();
            for (ItemStack stack : available) {
                if (needed > 0 && !stack.isEmpty() && input.ingredient().test(stack)) {
                    int used = Math.min(needed, stack.getCount());
                    stack.shrink(used);
                    needed -= used;
                }
            }
            if (needed > 0) return false;
        }
        return fluidInput.isEmpty() || drainable(tanks, fluidInput.get());
    }

    private static boolean drainable(IFluidHandler tanks, LiquidAmount wanted) {
        if (tanks == null) return false;
        for (int i = 0; i < tanks.getTanks(); i++) {
            FluidStack in = tanks.getFluidInTank(i);
            if (!in.isEmpty() && wanted.test(in.getFluid()) && in.getAmount() >= wanted.amount()) return true;
        }
        return false;
    }

    /**
     * Takes the inputs and liquid and puts the outputs (each rolled against its chance) into the output slots, all or nothing:
     * false when they would not fit (nothing changes then).
     */
    public boolean finish(KineticInventory inventory, IFluidHandler tanks, RandomSource random) {
        if (!matches(inventory, tanks)) return false;
        List<ItemStack> made = new ArrayList<>();
        for (Output output : outputs) {
            if (output.chance() >= 1 || random.nextFloat() < output.chance()) made.add(output.stack().copy());
        }
        // try the outputs on a copy first
        ItemStack[] copy = new ItemStack[inventory.getSlots()];
        for (int slot = 0; slot < copy.length; slot++) copy[slot] = inventory.getStackInSlot(slot).copy();
        for (ItemStack stack : made) {
            ItemStack rest = stack.copy();
            for (int slot = 0; slot < copy.length && !rest.isEmpty(); slot++) {
                if (!inventory.roleOf(slot).equals("output")) continue;
                int limit = Math.min(inventory.getSlotLimit(slot), rest.getMaxStackSize());
                if (copy[slot].isEmpty()) {
                    int put = Math.min(limit, rest.getCount());
                    copy[slot] = rest.copyWithCount(put);
                    rest.shrink(put);
                } else if (ItemStack.isSameItemSameComponents(copy[slot], rest)) {
                    int put = Math.min(limit - copy[slot].getCount(), rest.getCount());
                    if (put > 0) {
                        copy[slot].grow(put);
                        rest.shrink(put);
                    }
                }
            }
            if (!rest.isEmpty()) return false;
        }
        FluidStack liquidOut = fluidOutput.map(LiquidAmount::stack).orElse(FluidStack.EMPTY);
        if (!liquidOut.isEmpty() && (tanks == null || tanks.fill(liquidOut, IFluidHandler.FluidAction.SIMULATE) < liquidOut.getAmount())) {
            return false;
        }
        // take the inputs from the input slots of the copy
        for (Input input : inputs) {
            int needed = input.count();
            for (int slot = 0; slot < copy.length && needed > 0; slot++) {
                if (inventory.roleOf(slot).equals("input") && !copy[slot].isEmpty() && input.ingredient().test(copy[slot])) {
                    int used = Math.min(needed, copy[slot].getCount());
                    copy[slot].shrink(used);
                    needed -= used;
                }
            }
        }
        for (int slot = 0; slot < copy.length; slot++) {
            if (!ItemStack.matches(copy[slot], inventory.getStackInSlot(slot))) inventory.setStackInSlot(slot, copy[slot]);
        }
        if (fluidInput.isPresent()) {
            for (int i = 0; i < tanks.getTanks(); i++) {
                FluidStack in = tanks.getFluidInTank(i);
                if (!in.isEmpty() && fluidInput.get().test(in.getFluid()) && in.getAmount() >= fluidInput.get().amount()) {
                    tanks.drain(in.copyWithAmount(fluidInput.get().amount()), IFluidHandler.FluidAction.EXECUTE);
                    break;
                }
            }
        }
        if (!liquidOut.isEmpty()) tanks.fill(liquidOut, IFluidHandler.FluidAction.EXECUTE);
        return true;
    }

    /** The recipes of the machine block {@code machine}, in the order the data pack has them. */
    public static List<net.minecraft.world.item.crafting.RecipeHolder<MachineRecipe>> of(Level level, ResourceLocation machine) {
        return level.getRecipeManager().getAllRecipesFor(TYPE.get()).stream().filter(holder -> holder.value().machine().equals(machine)).toList();
    }

    @Override public boolean matches(RecipeInput input, Level level) { return false; } // machines match through matches(inventory, tanks)
    @Override public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) { return getResultItem(registries); }
    @Override public boolean canCraftInDimensions(int width, int height) { return true; }
    @Override public ItemStack getResultItem(HolderLookup.Provider registries) { return outputs.isEmpty() ? ItemStack.EMPTY : outputs.get(0).stack().copy(); }
    @Override public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> out = NonNullList.create();
        inputs.forEach(input -> out.add(input.ingredient()));
        return out;
    }
    @Override public RecipeSerializer<?> getSerializer() { return SERIALIZER.get(); }
    @Override public RecipeType<?> getType() { return TYPE.get(); }
    @Override public boolean isSpecial() { return true; } // not in the recipe book

    /** The recipe file's layout (what PackBuilder writes for machine_recipe). */
    public static final MapCodec<MachineRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ResourceLocation.CODEC.fieldOf("machine").forGetter(MachineRecipe::machine),
            Input.CODEC.listOf().optionalFieldOf("inputs", List.of()).forGetter(MachineRecipe::inputs),
            Output.CODEC.listOf().optionalFieldOf("outputs", List.of()).forGetter(MachineRecipe::outputs),
            LiquidAmount.CODEC.optionalFieldOf("fluid_input").forGetter(MachineRecipe::fluidInput),
            LiquidAmount.CODEC.optionalFieldOf("fluid_output").forGetter(MachineRecipe::fluidOutput),
            Codec.intRange(0, 1_000_000_000).optionalFieldOf("energy", 0).forGetter(MachineRecipe::energy),
            Codec.intRange(1, 72000).optionalFieldOf("time", 20).forGetter(MachineRecipe::time)
    ).apply(i, MachineRecipe::new));

    private static final class Serializer implements RecipeSerializer<MachineRecipe> {
        private static final StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> STREAM = StreamCodec.of(
                (buf, recipe) -> {
                    ResourceLocation.STREAM_CODEC.encode(buf, recipe.machine());
                    Input.STREAM.apply(ByteBufCodecs.list()).encode(buf, recipe.inputs());
                    Output.STREAM.apply(ByteBufCodecs.list()).encode(buf, recipe.outputs());
                    ByteBufCodecs.optional(LiquidAmount.STREAM).encode(buf, recipe.fluidInput());
                    ByteBufCodecs.optional(LiquidAmount.STREAM).encode(buf, recipe.fluidOutput());
                    buf.writeVarInt(recipe.energy());
                    buf.writeVarInt(recipe.time());
                },
                buf -> new MachineRecipe(ResourceLocation.STREAM_CODEC.decode(buf), Input.STREAM.apply(ByteBufCodecs.list()).decode(buf),
                        Output.STREAM.apply(ByteBufCodecs.list()).decode(buf), ByteBufCodecs.optional(LiquidAmount.STREAM).decode(buf),
                        ByteBufCodecs.optional(LiquidAmount.STREAM).decode(buf), buf.readVarInt(), buf.readVarInt()));

        @Override public MapCodec<MachineRecipe> codec() { return CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> streamCodec() { return STREAM; }
    }
}
