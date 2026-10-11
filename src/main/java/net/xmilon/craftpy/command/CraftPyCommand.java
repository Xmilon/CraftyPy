package net.xmilon.craftpy.command;

import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.xmilon.craftpy.registry.ModItems;
import net.xmilon.craftpy.registry.SlotRegistry;

/**
 * {@code /craftpy give <player> <project>:<id> <block|item> [count]}
 *
 * <p>Project content is only addressable through the project namespace (for
 * example {@code project_bb:ruby}), which never overlaps with registered item
 * ids: the id is resolved through the {@link SlotRegistry} content mapping, not
 * the item registry. Vanilla {@code /give} cannot hand out project content (see
 * {@link CommandGuard}); real registered items stay /give-able.</p>
 */
public class CraftPyCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(
                Commands.literal("craftpy")
                        .then(Commands.literal("give")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                                .then(Commands.literal("block")
                                                        .executes(ctx -> give(ctx, SlotRegistry.Kind.BLOCK, 1))
                                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                                .executes(ctx -> give(ctx, SlotRegistry.Kind.BLOCK,
                                                                        IntegerArgumentType.getInteger(ctx, "count")))))
                                                .then(Commands.literal("item")
                                                        .executes(ctx -> give(ctx, SlotRegistry.Kind.ITEM, 1))
                                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                                .executes(ctx -> give(ctx, SlotRegistry.Kind.ITEM,
                                                                        IntegerArgumentType.getInteger(ctx, "count")))))))));
    }

    private static int give(CommandContext<CommandSourceStack> ctx, SlotRegistry.Kind kind, int count)
            throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");

        String resolution = resolve(id, kind);
        if (resolution.startsWith("ERROR:")) {
            ctx.getSource().sendFailure(Component.literal(resolution.substring("ERROR:".length())));
            return 0;
        }

        int index = Integer.parseInt(resolution.substring(resolution.indexOf('#') + 1));
        Item item = kind == SlotRegistry.Kind.BLOCK
                ? ModItems.SLOT_BLOCK_ITEMS.get(index).get()
                : ModItems.SLOT_ITEMS.get(index).get();
        ItemStack stack = new ItemStack(item, Math.min(count, item.getDefaultMaxStackSize()));

        boolean added = target.getInventory().add(stack);
        if (!added) {
            target.drop(stack, false);
        }

        String holder = SlotRegistry.snapshotByHolder().entrySet().stream()
                .filter(entry -> entry.getValue().kind() == kind && entry.getValue().index() == index)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(id.toString());
        ctx.getSource().sendSuccess(() -> Component.literal("Gave " + stack.getCount() + " [" + holder + " ("
                + kind.name().toLowerCase(java.util.Locale.ROOT) + ")] to " + target.getName().getString()), true);
        return 1;
    }

    /**
     * Resolves a project content id to its slot. Accepts the exact mapping key
     * ({@code project:content}), a {@code project_}-prefixed alias
     * ({@code project_bb:ruby} for the {@code bb} project), or a bare content id.
     * Returns {@code "BLOCK#n"} / {@code "ITEM#n"} or {@code "ERROR:<message>"}.
     */
    static String resolve(ResourceLocation id, SlotRegistry.Kind kind) {
        return resolve(SlotRegistry.snapshotByHolder(), id.toString(), kind);
    }

    /** Pure resolution logic, separated for unit tests. */
    static String resolve(Map<String, SlotRegistry.SlotRef> mapping, String raw, SlotRegistry.Kind kind) {
        int colon = raw.indexOf(':');
        String ns = colon < 0 ? "" : raw.substring(0, colon);
        String path = colon < 0 ? raw : raw.substring(colon + 1);

        SlotRegistry.SlotRef ref = mapping.get(raw);
        if (ref == null && ns.startsWith("project_")) {
            ref = mapping.get(ns.substring("project_".length()) + ":" + path);
        }
        SlotRegistry.SlotRef byPath = null;
        boolean ambiguous = false;
        if (ref == null) {
            String suffix = ":" + path;
            for (Map.Entry<String, SlotRegistry.SlotRef> entry : mapping.entrySet()) {
                if (entry.getKey().endsWith(suffix) && entry.getValue().kind() == kind) {
                    if (byPath != null && byPath.index() != entry.getValue().index()) {
                        ambiguous = true;
                    }
                    byPath = entry.getValue();
                }
            }
            ref = byPath;
        }
        if (ambiguous) {
            return "ERROR:'" + raw + "' matches several projects - use <project>:<id>";
        }
        if (ref == null) {
            return "ERROR:Unknown project content '" + raw + "'. Use <project>:<id> as declared with @block/@item.";
        }
        if (ref.kind() != kind) {
            return "ERROR:'" + raw + "' is declared as "
                    + (ref.kind() == SlotRegistry.Kind.BLOCK ? "a block" : "an item") + ", not "
                    + (kind == SlotRegistry.Kind.BLOCK ? "a block" : "an item") + ".";
        }
        return ref.kind() + "#" + ref.index();
    }
}
