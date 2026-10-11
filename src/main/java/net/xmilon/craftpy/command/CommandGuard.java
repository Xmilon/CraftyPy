package net.xmilon.craftpy.command;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.xmilon.craftpy.block.SlottedBlock;
import net.xmilon.craftpy.block.SlottedItem;

/**
 * Namespace isolation for project content: vanilla {@code /give} may hand out
 * any real registered item (vanilla or modded) but never the generic slot items
 * backing project blocks/items. Project content is exclusively obtained through
 * {@code /craftpy give <player> <project>:<id> <block|item>}.
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
        if (input == null || !isProjectContent(input.getItem())) {
            return;
        }
        event.setCanceled(true);
        CommandSourceStack source = parse.getContext().getSource();
        if (source != null) {
            source.sendFailure(Component.literal(
                    "Project content cannot be given with /give. Use /craftpy give <player> <project>:<id> <block|item>."));
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
}
