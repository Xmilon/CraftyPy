package net.xmilon.craftpy.command;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.xmilon.craftpy.block.SlottedBlock;
import net.xmilon.craftpy.block.SlottedItem;

/**
 * Namespace isolation: vanilla {@code /give} may hand out any real registered
 * item (vanilla or modded) but never:
 * <ul>
 *   <li>Generic slot items backing project blocks/items (project content)</li>
 *   <li>Items/blocks registered by the CraftPy mod itself (namespace {@code craftpy:})</li>
 * </ul>
 * Project content is exclusively obtained through
 * {@code /craftpy give <player> <project>:<id> <block|item>}.
 * Mod-internal items (like the computer block) are simply not obtainable via /give.
 *
 * <p>Because {@code /execute run} redirects into the same parse tree, scanning
 * every parsed node (not just the root command) also covers
 * {@code /execute run give ...}.</p>
 */
public final class CommandGuard {

    private CommandGuard() {
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        ParseResults<CommandSourceStack> parse = event.getParseResults();
        if (parse == null) {
            return;
        }
        boolean isGive = false;
        for (ParsedCommandNode<CommandSourceStack> node : parse.getContext().getNodes()) {
            if ("give".equals(node.getNode().getName())) {
                isGive = true;
                break;
            }
        }
        if (!isGive) {
            return;
        }
        ItemInput input = itemArgument(parse);
        if (input == null) {
            return;
        }
        Item item = input.getItem();
        if (!isProjectContent(item) && !isModInternal(item)) {
            return;
        }
        event.setCanceled(true);
        CommandSourceStack source = parse.getContext().getSource();
        if (source != null) {
            String msg = isModInternal(item)
                    ? "This item/block is part of the CraftPy mod and cannot be obtained with /give."
                    : "Project content cannot be given with /give. Use /craftpy give <player> <project>:<id> <block|item>.";
            source.sendFailure(Component.literal(msg));
        }
    }

    @SuppressWarnings("unchecked")
    private static ItemInput itemArgument(ParseResults<CommandSourceStack> parse) {
        for (ParsedCommandNode<CommandSourceStack> node : parse.getContext().getNodes()) {
            if (node.getNode() instanceof ArgumentCommandNode<?, ?> argument
                    && "item".equals(argument.getName())) {
                ParsedArgument<CommandSourceStack, ?> parsed = parse.getContext().getArguments().get("item");
                if (parsed != null && parsed.getResult() instanceof ItemInput input) {
                    return input;
                }
            }
        }
        return null;
    }

    private static boolean isProjectContent(Item item) {
        return item instanceof SlottedItem
                || (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof SlottedBlock);
    }

    /** True for items/blocks registered under the CraftPy mod namespace. */
    private static boolean isModInternal(Item item) {
        if (item == null) {
            return false;
        }
        ResourceKey<Item> key = BuiltInRegistries.ITEM.getResourceKey(item).orElse(null);
        if (key != null && "craftpy".equals(key.location().getNamespace())) {
            return true;
        }
        if (item instanceof BlockItem blockItem) {
            ResourceKey<Block> bKey = BuiltInRegistries.BLOCK.getResourceKey(blockItem.getBlock()).orElse(null);
            if (bKey != null && "craftpy".equals(bKey.location().getNamespace())) {
                return true;
            }
        }
        return false;
    }
}
