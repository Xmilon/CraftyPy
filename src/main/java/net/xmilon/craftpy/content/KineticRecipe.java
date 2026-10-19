package net.xmilon.craftpy.content;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public record KineticRecipe(Ingredient input, ItemStack output, int duration, float minimumSpeed) implements Recipe<SingleRecipeInput> {
    private static final DeferredRegister<RecipeType<?>> TYPES = DeferredRegister.create(Registries.RECIPE_TYPE, "craftpy");
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, "craftpy");
    public static final DeferredHolder<RecipeType<?>, RecipeType<KineticRecipe>> TYPE = TYPES.register("kinetic_processing", () -> new RecipeType<>() {
        @Override public String toString() { return "craftpy:kinetic_processing"; }
    });
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<KineticRecipe>> SERIALIZER = SERIALIZERS.register("kinetic_processing", Serializer::new);
    public KineticRecipe {
        if (input.isEmpty() || output.isEmpty() || output.getCount() > output.getMaxStackSize()) throw new IllegalArgumentException("Kinetic recipe needs one ingredient and a stack-sized output");
        if (duration < 1 || duration > 72000 || !Float.isFinite(minimumSpeed) || minimumSpeed < 0 || minimumSpeed > 256) throw new IllegalArgumentException("Invalid kinetic recipe duration or minimum speed");
        output = output.copy();
    }
    public static void register(IEventBus bus) { TYPES.register(bus); SERIALIZERS.register(bus); }
    @Override public boolean matches(SingleRecipeInput inventory, Level level) { return input.test(inventory.item()); }
    @Override public ItemStack assemble(SingleRecipeInput inventory, HolderLookup.Provider registries) { return output.copy(); }
    @Override public boolean canCraftInDimensions(int width, int height) { return true; }
    @Override public ItemStack getResultItem(HolderLookup.Provider registries) { return output.copy(); }
    @Override public NonNullList<Ingredient> getIngredients() { return NonNullList.of(Ingredient.EMPTY, input); }
    @Override public RecipeSerializer<?> getSerializer() { return SERIALIZER.get(); }
    @Override public RecipeType<?> getType() { return TYPE.get(); }
    @Override public boolean isSpecial() { return true; }
    private static final class Serializer implements RecipeSerializer<KineticRecipe> {
        private static final MapCodec<KineticRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(KineticRecipe::input),
                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(KineticRecipe::output),
                com.mojang.serialization.Codec.intRange(1, 72000).fieldOf("duration").forGetter(KineticRecipe::duration),
                com.mojang.serialization.Codec.floatRange(0, 256).fieldOf("minimum_speed").forGetter(KineticRecipe::minimumSpeed)
        ).apply(i, KineticRecipe::new));
        private static final StreamCodec<RegistryFriendlyByteBuf, KineticRecipe> STREAM = StreamCodec.composite(
                Ingredient.CONTENTS_STREAM_CODEC, KineticRecipe::input, ItemStack.STREAM_CODEC, KineticRecipe::output,
                ByteBufCodecs.VAR_INT, KineticRecipe::duration, ByteBufCodecs.FLOAT, KineticRecipe::minimumSpeed, KineticRecipe::new);
        @Override public MapCodec<KineticRecipe> codec() { return CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, KineticRecipe> streamCodec() { return STREAM; }
    }
}
