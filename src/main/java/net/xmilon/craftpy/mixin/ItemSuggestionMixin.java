package net.xmilon.craftpy.mixin;

import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.registry.SlotRegistry;

/**
 * Keeps the pre-registered {@code craftpy:pcblock_N} / {@code pcitem_N} placeholders out of the tab
 * completion of /give, /setblock, /fill ... They stay valid when typed; project content is handed out
 * with {@code /craftpy give} instead.
 */
@Mixin(targets = "net.minecraft.commands.arguments.item.ItemParser$State")
public abstract class ItemSuggestionMixin {
    @ModifyArg(method = "suggestItem", index = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/SharedSuggestionProvider;suggestResource(Ljava/util/stream/Stream;"
                    + "Lcom/mojang/brigadier/suggestion/SuggestionsBuilder;)Ljava/util/concurrent/CompletableFuture;"))
    private Stream<ResourceLocation> craftpy$hideSlots(Stream<ResourceLocation> ids) {
        return ids.filter(id -> !SlotRegistry.isPlaceholder(id));
    }
}
