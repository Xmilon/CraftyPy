package net.xmilon.craftpy.mixin;

import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.registry.SlotRegistry;

/** Block counterpart of {@link ItemSuggestionMixin}. */
@Mixin(BlockStateParser.class)
public abstract class BlockSuggestionMixin {
    @ModifyArg(method = "suggestItem", index = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/SharedSuggestionProvider;suggestResource(Ljava/util/stream/Stream;"
                    + "Lcom/mojang/brigadier/suggestion/SuggestionsBuilder;)Ljava/util/concurrent/CompletableFuture;"))
    private Stream<ResourceLocation> craftpy$hideSlots(Stream<ResourceLocation> ids) {
        return ids.filter(id -> !SlotRegistry.isPlaceholder(id));
    }
}
