package net.xmilon.craftpy.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.xmilon.craftpy.block.PythonComputerBlock;
import net.xmilon.craftpy.menu.ComputerMenu;
import net.xmilon.craftpy.network.CraftPyNetwork;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.project.ModuleBuilder;
import net.xmilon.craftpy.project.ModuleLoader;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.project.ProjectSummary;
import net.xmilon.craftpy.python.PyRuntime;
import net.xmilon.craftpy.registry.ModItems;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.util.CraftPyExecutors;

/**
 * The {@code /craftpy} command tree (M9).
 *
 * <p>Every subtree is operator-gated through Brigadier and, for player
 * executors, additionally checked against the NeoForge PermissionAPI node
 * ({@link CraftPyPermissions}) so permission mods can override individual
 * players. Async work (project/module IO) replies through the server thread.
 */
public final class CraftPyCommand {

    private static final Pattern SEMVER = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    private CraftPyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        ProjectCommandRegistry.init(dispatcher, context);
        dispatcher.register(Commands.literal("craftpy")
                .executes(CraftPyCommand::rootHelp)
                .then(give())
                .then(summon())
                .then(place())
                .then(structure())
                .then(ide())
                .then(project())
                .then(moduleGroup())
                .then(py())
                .then(window())
                .then(debug())
                .then(game()));
    }

    // ------------------------------------------------------------------
    // Root help / command tree
    // ------------------------------------------------------------------

    private static int rootHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "CraftPy commands: give, ide open|close|project <id>, project list|new|delete|run|stop, "
                        + "module list|info|build|reload, py <code>, window open|close, debug list|slots."),
                false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /craftpy give <player> <project:content> [count]   (block/item is detected; "block" / "item" still force a kind)
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> give() {
        return Commands.literal("give")
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.get(net.xmilon.craftpy.Config.PERMISSION_LEVEL))))
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(contentIds(), builder))
                                .executes(ctx -> give(ctx, null, 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> give(ctx, null, IntegerArgumentType.getInteger(ctx, "amount"))))
                                .then(Commands.literal("block")
                                        .executes(ctx -> give(ctx, SlotRegistry.Kind.BLOCK, 1))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> give(ctx, SlotRegistry.Kind.BLOCK,
                                                        IntegerArgumentType.getInteger(ctx, "count")))))
                                .then(Commands.literal("item")
                                        .executes(ctx -> give(ctx, SlotRegistry.Kind.ITEM, 1))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> give(ctx, SlotRegistry.Kind.ITEM,
                                                        IntegerArgumentType.getInteger(ctx, "count")))))
                                .then(Commands.literal("egg")
                                        .executes(ctx -> giveEgg(ctx, 1))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> giveEgg(ctx, IntegerArgumentType.getInteger(ctx, "count")))))));
    }

    // /craftpy summon <project:mob> [pos]
    private static LiteralArgumentBuilder<CommandSourceStack> summon() {
        return Commands.literal("summon")
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.get(net.xmilon.craftpy.Config.PERMISSION_LEVEL))))
                .then(Commands.argument("id", ResourceLocationArgument.id())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(mobIds(), builder))
                        .executes(ctx -> summon(ctx, net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition())))
                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .executes(ctx -> summon(ctx, net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .getSpawnablePos(ctx, "pos")))));
    }

    // /craftpy place <template> [pos] [rotation]   (a project's data/structure/<name>.nbt or any template of the game)
    private static LiteralArgumentBuilder<CommandSourceStack> place() {
        return Commands.literal("place")
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.get(net.xmilon.craftpy.Config.PERMISSION_LEVEL))))
                .then(Commands.argument("template", ResourceLocationArgument.id())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
                                ctx.getSource().getLevel().getStructureManager().listTemplates(), builder))
                        .executes(ctx -> place(ctx, net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition()), 0))
                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .executes(ctx -> place(ctx, net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .getLoadedBlockPos(ctx, "pos"), 0))
                                .then(Commands.argument("rotation", IntegerArgumentType.integer(0, 270))
                                        .executes(ctx -> place(ctx, net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                                .getLoadedBlockPos(ctx, "pos"), IntegerArgumentType.getInteger(ctx, "rotation"))))));
    }

    private static int place(CommandContext<CommandSourceStack> ctx, net.minecraft.core.BlockPos pos, int rotation) {
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "template");
        String error = net.xmilon.craftpy.server.StructurePlacer.placeTemplate(ctx.getSource().getLevel(), id, pos, rotation, false, true);
        if (error != null) {
            fail(ctx, error);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Placed " + id + " at " + pos.toShortString()), true);
        return 1;
    }

    // /craftpy structure import <saved name> <project>   (what a structure block saved, into the project)
    private static LiteralArgumentBuilder<CommandSourceStack> structure() {
        return Commands.literal("structure")
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.get(net.xmilon.craftpy.Config.PERMISSION_LEVEL))))
                .then(Commands.literal("import")
                        .then(Commands.argument("name", ResourceLocationArgument.id())
                                .then(Commands.argument("project", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> {
                                            String name = ResourceLocationArgument.getId(ctx, "name").toString();
                                            String project = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "project");
                                            try {
                                                String message = net.xmilon.craftpy.server.StructurePlacer.importSaved(
                                                        ctx.getSource().getServer(), name, project);
                                                ctx.getSource().sendSuccess(() -> Component.literal(message), true);
                                                return 1;
                                            } catch (java.io.IOException e) {
                                                fail(ctx, "Could not import: " + e.getMessage());
                                                return 0;
                                            }
                                        }))));
    }

    /** The slot of a declared mob ({@code project:id} or a bare id), or null after telling the source why not. */
    private static SlotRegistry.SlotRef mobSlot(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
        String resolution = resolve(id, SlotRegistry.Kind.ENTITY);
        SlotRegistry.Kind kind = SlotRegistry.Kind.ENTITY;
        if (resolution.startsWith("ERROR:")) {
            String monster = resolve(id, SlotRegistry.Kind.MONSTER);
            if (!monster.startsWith("ERROR:")) {
                resolution = monster;
                kind = SlotRegistry.Kind.MONSTER;
            }
        }
        if (resolution.startsWith("ERROR:")) {
            ctx.getSource().sendFailure(Component.literal(resolution.substring("ERROR:".length())
                    .replace("@block/@item", "Mob(...)")));
            return null;
        }
        return new SlotRegistry.SlotRef(kind, Integer.parseInt(resolution.substring(resolution.indexOf('#') + 1)));
    }

    private static int summon(CommandContext<CommandSourceStack> ctx, net.minecraft.core.BlockPos pos) {
        if (!allowed(ctx, CraftPyPermissions.GIVE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.GIVE.getNodeName() + ".");
            return 0;
        }
        String requested = ResourceLocationArgument.getId(ctx, "id").toString();
        if (net.xmilon.craftpy.content.NativeContent.definition("entity", requested) != null) {
            if (net.xmilon.craftpy.content.NativeContent.spawn(requested, ctx.getSource().getLevel(), pos) == null) {
                fail(ctx, "Could not summon " + requested); return 0;
            }
            ctx.getSource().sendSuccess(() -> Component.literal("Summoned native variant " + requested), true); return 1;
        }
        SlotRegistry.SlotRef slot = mobSlot(ctx);
        if (slot == null) {
            return 0;
        }
        var mob = net.xmilon.craftpy.registry.ModEntities.type(slot).spawn(ctx.getSource().getLevel(), pos,
                net.minecraft.world.entity.MobSpawnType.COMMAND);
        if (mob == null) {
            fail(ctx, "Could not summon it there.");
            return 0;
        }
        String holder = SlotRegistry.holderFor(slot.kind(), slot.index());
        ctx.getSource().sendSuccess(() -> Component.literal("Summoned " + stripKind(holder)), true);
        return 1;
    }

    private static int giveEgg(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
        if (!allowed(ctx, CraftPyPermissions.GIVE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.GIVE.getNodeName() + ".");
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        SlotRegistry.SlotRef slot = mobSlot(ctx);
        if (slot == null) {
            return 0;
        }
        ItemStack stack = net.xmilon.craftpy.registry.ModEntities.egg(slot, SlotRegistry.entityDef(slot.kind(), slot.index()), count);
        if (!target.getInventory().add(stack)) {
            target.drop(stack, false);
        }
        String holder = SlotRegistry.holderFor(slot.kind(), slot.index());
        ctx.getSource().sendSuccess(() -> Component.literal("Gave " + count + " [" + stripKind(holder) + " (egg)] to "
                + target.getName().getString()), true);
        return 1;
    }

    private static Iterable<String> mobIds() {
        TreeSet<String> ids = new TreeSet<>();
        net.xmilon.craftpy.content.NativeContent.definitions("entity").forEach(def -> ids.add(def.id()));
        for (var entry : SlotRegistry.snapshotByHolder().entrySet()) {
            if (entry.getValue().kind() == SlotRegistry.Kind.ENTITY || entry.getValue().kind() == SlotRegistry.Kind.MONSTER) {
                ids.add(stripKind(entry.getKey()));
            }
        }
        return ids;
    }

    /**
     * Every project content id as {@code project:id}, offered as tab completions. Slot keys carry a
     * kind prefix ({@code BLOCK:project:id}) that is not a valid resource location, so it is stripped.
     */
    private static Iterable<String> contentIds() {
        TreeSet<String> ids = new TreeSet<>();
        net.xmilon.craftpy.content.NativeContent.definitions("block").forEach(def -> ids.add(def.id()));
        for (String key : SlotRegistry.snapshotByHolder().keySet()) {
            ids.add(stripKind(key));
        }
        return ids;
    }

    static String stripKind(String key) {
        for (SlotRegistry.Kind kind : SlotRegistry.Kind.values()) {
            String prefix = kind.name() + ":";
            if (key.startsWith(prefix)) {
                return key.substring(prefix.length());
            }
        }
        return key;
    }

    private static int give(CommandContext<CommandSourceStack> ctx, SlotRegistry.Kind kind, int count)
            throws CommandSyntaxException {
        if (!allowed(ctx, CraftPyPermissions.GIVE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.GIVE.getNodeName() + ".");
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");

        var nativeBlock = net.xmilon.craftpy.content.NativeContent.definition("block", id.toString());
        if (nativeBlock != null && (kind == null || kind == SlotRegistry.Kind.BLOCK)) {
            var item = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(ResourceLocation.parse(nativeBlock.base())).asItem();
            ItemStack stack = new ItemStack(item, Math.min(count, item.getDefaultMaxStackSize()));
            if (!target.getInventory().add(stack)) target.drop(stack, false);
            ctx.getSource().sendSuccess(() -> Component.literal("Gave native block " + id + " to " + target.getName().getString()), true); return 1;
        }
        String resolution;
        if (kind == null) {
            kind = SlotRegistry.Kind.BLOCK;
            resolution = resolve(id, kind);
            if (resolution.startsWith("ERROR:")) {
                String asItem = resolve(id, SlotRegistry.Kind.ITEM);
                if (!asItem.startsWith("ERROR:")) {
                    kind = SlotRegistry.Kind.ITEM;
                    resolution = asItem;
                }
            }
        } else {
            resolution = resolve(id, kind);
        }
        if (resolution.startsWith("ERROR:")) {
            ctx.getSource().sendFailure(Component.literal(resolution.substring("ERROR:".length())));
            return 0;
        }
        final SlotRegistry.Kind given = SlotRegistry.Kind.valueOf(resolution.substring(0, resolution.indexOf('#')));

        int index = Integer.parseInt(resolution.substring(resolution.indexOf('#') + 1));
        Item item = given == SlotRegistry.Kind.KINETIC ? net.xmilon.craftpy.registry.KineticSlots.BLOCKS.get(index).get().asItem() : kind == SlotRegistry.Kind.BLOCK
                ? ModItems.SLOT_BLOCK_ITEMS.get(index).get()
                : ModItems.SLOT_ITEMS.get(index).get();
        ItemStack stack = new ItemStack(item, Math.min(count, item.getDefaultMaxStackSize()));
        final int giveCount = stack.getCount(); // Inventory.add uses the stack up, so read the number first

        boolean added = target.getInventory().add(stack);
        if (!added) {
            target.drop(stack, false);
        }

        String holder = SlotRegistry.snapshotByHolder().entrySet().stream()
                .filter(entry -> entry.getValue().kind() == given && entry.getValue().index() == index)
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(id.toString());
        ctx.getSource().sendSuccess(() -> Component.literal("Gave " + giveCount + " [" + holder + " ("
                + given.name().toLowerCase(java.util.Locale.ROOT) + ")] to " + target.getName().getString()), true);
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

        // Live keys are kind-prefixed (BLOCK:project:id); older/plain keys are project:id.
        String kindPrefix = kind.name() + ":";
        SlotRegistry.SlotRef ref = mapping.get(kindPrefix + raw);
        if (ref == null && kind == SlotRegistry.Kind.BLOCK) ref = mapping.get("KINETIC:" + raw);
        if (ref == null) {
            ref = mapping.get(raw);
        }
        if (ref == null && ns.startsWith("project_")) {
            String alias = ns.substring("project_".length()) + ":" + path;
            ref = mapping.get(kindPrefix + alias);
            if (ref == null && kind == SlotRegistry.Kind.BLOCK) ref = mapping.get("KINETIC:" + alias);
            if (ref == null) {
                ref = mapping.get(alias);
            }
        }
        SlotRegistry.SlotRef byPath = null;
        boolean ambiguous = false;
        if (ref == null) {
            String suffix = ":" + path;
            for (Map.Entry<String, SlotRegistry.SlotRef> entry : mapping.entrySet()) {
                if (entry.getKey().endsWith(suffix) && (entry.getValue().kind() == kind || kind == SlotRegistry.Kind.BLOCK && entry.getValue().kind() == SlotRegistry.Kind.KINETIC)) {
                    if (byPath != null && !byPath.equals(entry.getValue())) {
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
        if (ref.kind() != kind && !(kind == SlotRegistry.Kind.BLOCK && ref.kind() == SlotRegistry.Kind.KINETIC)) {
            return "ERROR:'" + raw + "' is declared as "
                    + (ref.kind() == SlotRegistry.Kind.BLOCK ? "a block" : "an item") + ", not "
                    + (kind == SlotRegistry.Kind.BLOCK ? "a block" : "an item") + ".";
        }
        return ref.kind() + "#" + ref.index();
    }

    // ------------------------------------------------------------------
    // /craftpy ide open|close [player] | project <id> [player]
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> ide() {
        return Commands.literal("ide")
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.get(net.xmilon.craftpy.Config.PERMISSION_LEVEL))))
                .then(Commands.literal("open")
                        .executes(ctx -> ideOpen(ctx, null))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> ideOpen(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("close")
                        .executes(ctx -> ideClose(ctx, null))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> ideClose(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("project")
                        .then(Commands.argument("project", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(projectIds(), builder))
                                .executes(ctx -> ideProject(ctx, StringArgumentType.getString(ctx, "project"), null))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> ideProject(ctx, StringArgumentType.getString(ctx, "project"),
                                                EntityArgument.getPlayer(ctx, "player"))))));
    }

    private static int ideOpen(CommandContext<CommandSourceStack> ctx, ServerPlayer override) {
        if (!allowed(ctx, CraftPyPermissions.IDE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.IDE.getNodeName() + ".");
            return 0;
        }
        ServerPlayer target = override != null ? override : ctx.getSource().getPlayer();
        if (target == null) {
            fail(ctx, "Run as a player or pass a player argument.");
            return 0;
        }
        openIdeScreen(target, null);
        ctx.getSource().sendSuccess(() -> Component.literal("Opened CraftPy IDE for " + target.getName().getString()),
                true);
        return 1;
    }

    private static int ideClose(CommandContext<CommandSourceStack> ctx, ServerPlayer override) {
        ServerPlayer target = override != null ? override : ctx.getSource().getPlayer();
        if (target == null) {
            fail(ctx, "Run as a player or pass a player argument.");
            return 0;
        }
        target.closeContainer();
        ctx.getSource().sendSuccess(() -> Component.literal("Closed CraftPy IDE for " + target.getName().getString()),
                true);
        return 1;
    }

    private static int ideProject(CommandContext<CommandSourceStack> ctx, String id, ServerPlayer override) {
        if (!allowed(ctx, CraftPyPermissions.IDE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.IDE.getNodeName() + ".");
            return 0;
        }
        if (!ProjectManager.isValidId(id) || !projectExists(id)) {
            fail(ctx, "Unknown project '" + id + "'.");
            return 0;
        }
        ServerPlayer target = override != null ? override : ctx.getSource().getPlayer();
        if (target == null) {
            fail(ctx, "Run as a player or pass a player argument.");
            return 0;
        }
        openIdeScreen(target, id);
        ctx.getSource().sendSuccess(
                () -> Component.literal("Opened CraftPy IDE on project '" + id + "' for "
                        + target.getName().getString()),
                true);
        return 1;
    }

    /** Opens the IDE on the client; {@code project} (optional) makes it jump straight into the editor. */
    private static void openIdeScreen(ServerPlayer target, String project) {
        CraftPyNetwork.sendTo(target, ProjectAction.OPEN_IDE, true, "open",
                project == null ? "" : project);
    }

    // ------------------------------------------------------------------
    // /craftpy project list|new|delete|run|stop
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> project() {
        return Commands.literal("project")
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.get(net.xmilon.craftpy.Config.PERMISSION_LEVEL))))
                .then(Commands.literal("list").executes(CraftPyCommand::projectList))
                .then(Commands.literal("new")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> projectNew(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("run")
                        .then(Commands.argument("project", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(projectIds(), builder))
                                .executes(ctx -> projectRun(ctx, StringArgumentType.getString(ctx, "project")))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("project", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(projectIds(), builder))
                                .executes(ctx -> projectStop(ctx, StringArgumentType.getString(ctx, "project")))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("project", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(projectIds(), builder))
                                .executes(ctx -> projectDelete(ctx, StringArgumentType.getString(ctx, "project")))))
                .then(Commands.literal("trust")
                        .then(Commands.argument("project", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(projectIds(), builder))
                                .executes(ctx -> projectTrust(ctx, StringArgumentType.getString(ctx, "project")))));
    }

    /** A project that came from elsewhere (a module opened as a project) gets what it declares once an op trusts it. */
    private static int projectTrust(CommandContext<CommandSourceStack> ctx, String projectId) {
        if (!projectIds().contains(projectId)) {
            ctx.getSource().sendFailure(Component.literal("No project '" + projectId + "'"));
            return 0;
        }
        net.xmilon.craftpy.security.PermissionStore.trust(projectId);
        ctx.getSource().sendSuccess(() -> Component.literal("Project " + projectId
                + " is trusted: it gets what its project.json declares from its next run."), true);
        return 1;
    }

    private static int projectList(CommandContext<CommandSourceStack> ctx) {
        if (!allowed(ctx, CraftPyPermissions.PROJECT)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.PROJECT.getNodeName() + ".");
            return 0;
        }
        ProjectManager manager = ProjectManager.get();
        if (manager == null) {
            fail(ctx, "Project manager is not ready.");
            return 0;
        }
        manager.listProjects().thenAccept(list -> next(ctx, () -> {
            if (list.isEmpty()) {
                ctx.getSource().sendSuccess(
                        () -> Component.literal("No projects yet. Use /craftpy project new <name>."), false);
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (ProjectSummary summary : list) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(summary.id).append(" (").append(summary.name).append(')')
                        .append(summary.loaded ? " [loaded]" : "")
                        .append(summary.requiresRestart ? " [restart]" : "")
                        .append(summary.error != null ? " [error: " + summary.error + "]" : "");
            }
            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        }));
        return 1;
    }

    private static int projectNew(CommandContext<CommandSourceStack> ctx, String name) {
        if (!allowed(ctx, CraftPyPermissions.PROJECT)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.PROJECT.getNodeName() + ".");
            return 0;
        }
        ProjectManager manager = ProjectManager.get();
        if (manager == null) {
            fail(ctx, "Project manager is not ready.");
            return 0;
        }
        manager.createProject(name).thenAccept(result -> replyOp(ctx, result));
        return 1;
    }

    private static int projectRun(CommandContext<CommandSourceStack> ctx, String id) {
        if (!allowed(ctx, CraftPyPermissions.PROJECT)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.PROJECT.getNodeName() + ".");
            return 0;
        }
        if (!ProjectManager.isValidId(id) || !projectExists(id)) {
            fail(ctx, "Unknown project '" + id + "'.");
            return 0;
        }
        net.xmilon.craftpy.lifecycle.ProjectController.get().load(id, ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("Loading project '" + id + "'..."), true);
        return 1;
    }

    private static int projectStop(CommandContext<CommandSourceStack> ctx, String id) {
        if (!allowed(ctx, CraftPyPermissions.PROJECT)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.PROJECT.getNodeName() + ".");
            return 0;
        }
        if (!ProjectManager.isValidId(id) || !projectExists(id)) {
            fail(ctx, "Unknown project '" + id + "'.");
            return 0;
        }
        net.xmilon.craftpy.lifecycle.ProjectController.get().unload(id, ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("Unloaded project '" + id + "'."), true);
        return 1;
    }

    private static int projectDelete(CommandContext<CommandSourceStack> ctx, String id) {
        if (!allowed(ctx, CraftPyPermissions.PROJECT)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.PROJECT.getNodeName() + ".");
            return 0;
        }
        ProjectManager manager = ProjectManager.get();
        if (manager == null) {
            fail(ctx, "Project manager is not ready.");
            return 0;
        }
        manager.deleteProject(id).thenAccept(result -> replyOp(ctx, result));
        return 1;
    }

    // ------------------------------------------------------------------
    // /craftpy module list|info|build|reload
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> moduleGroup() {
        return Commands.literal("module")
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.get(net.xmilon.craftpy.Config.PERMISSION_LEVEL))))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayer();
                    if (player == null) { // the server console: no IDE to open
                        return moduleList(ctx);
                    }
                    openIdeScreen(player, null);
                    ctx.getSource().sendSuccess(() -> Component.literal("Opened CraftPy - see the Modules page."), false);
                    return 1;
                })
                .then(Commands.literal("list").executes(CraftPyCommand::moduleList))
                .then(Commands.literal("enable")
                        .then(Commands.argument("module", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(moduleIds(), builder))
                                .executes(ctx -> moduleSetEnabled(ctx, StringArgumentType.getString(ctx, "module"), true))))
                .then(Commands.literal("disable")
                        .then(Commands.argument("module", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(moduleIds(), builder))
                                .executes(ctx -> moduleSetEnabled(ctx, StringArgumentType.getString(ctx, "module"), false))))
                .then(Commands.literal("info")
                        .then(Commands.argument("module", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(moduleIds(), builder))
                                .executes(ctx -> moduleInfo(ctx, StringArgumentType.getString(ctx, "module"))))
                        .executes(CraftPyCommand::moduleList))
                .then(Commands.literal("build")
                        .then(Commands.argument("project", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(projectIds(), builder))
                                .executes(ctx -> moduleBuild(ctx, StringArgumentType.getString(ctx, "project"), null))
                                .then(Commands.argument("version", StringArgumentType.string())
                                        .executes(ctx -> moduleBuild(ctx, StringArgumentType.getString(ctx, "project"),
                                                StringArgumentType.getString(ctx, "version"))))))
                .then(Commands.literal("reload")
                        .executes(ctx -> moduleReloadAll(ctx))
                        .then(Commands.argument("module", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(moduleIds(), builder))
                                .executes(ctx -> moduleReload(ctx,
                                        StringArgumentType.getString(ctx, "module")))));
    }

    private static int moduleList(CommandContext<CommandSourceStack> ctx) {
        if (!allowed(ctx, CraftPyPermissions.MODULE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.MODULE.getNodeName() + ".");
            return 0;
        }
        List<net.xmilon.craftpy.project.ModuleInfo> modules = ModuleLoader.list();
        if (modules.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component
                    .literal("No modules installed. Drop .cpm files into pyforge/modules/."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        for (net.xmilon.craftpy.project.ModuleInfo module : modules) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String state = module.error != null ? "error"
                    : module.duplicate && !module.primary ? "duplicate"
                    : module.pendingRestart ? "disabled (restart to unload)"
                    : module.loaded ? "enabled" : module.enabled ? "enabled (not loaded)" : "disabled";
            sb.append(module.permissions.isEmpty() ? "  " : "⚠ ").append(module.id)
                    .append(" v").append(module.version == null || module.version.isBlank() ? "?" : module.version)
                    .append(" - ").append(state);
            if (!module.permissions.isEmpty()) {
                sb.append(" - can ").append(net.xmilon.craftpy.security.PermissionLabels.shortList(module.permissions));
            }
        }
        final String output = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(output), false);
        return 1;
    }

    private static int moduleInfo(CommandContext<CommandSourceStack> ctx, String id) {
        if (!allowed(ctx, CraftPyPermissions.MODULE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.MODULE.getNodeName() + ".");
            return 0;
        }
        ModuleBuilder.ModuleMetadata module = moduleById(id);
        if (module == null) {
            fail(ctx, "Unknown module '" + id + "'. Use /craftpy module list.");
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("id: ").append(module.id == null ? "?" : module.id).append('\n');
        sb.append("name: ").append(module.name == null ? "?" : module.name).append('\n');
        sb.append("version: ").append(module.version == null ? "?" : module.version).append('\n');
        sb.append("works on: ").append(net.xmilon.craftpy.project.ModuleSide.of(module.side).id()).append('\n');
        sb.append("Python API: ").append(module.apiVersion == null ? "not set (built before API versions)" : module.apiVersion)
                .append(" (this CraftPy: ").append(net.xmilon.craftpy.python.PyApi.version()).append(")\n");
        if (module.dependencies != null && module.dependencies.length > 0) {
            sb.append("dependencies: ").append(String.join(", ", module.dependencies)).append('\n');
        }
        final String output = sb.toString().trim();
        ctx.getSource().sendSuccess(() -> Component.literal(output), false);
        return 1;
    }

    private static int moduleBuild(CommandContext<CommandSourceStack> ctx, String projectId, String version) {
        if (!allowed(ctx, CraftPyPermissions.MODULE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.MODULE.getNodeName() + ".");
            return 0;
        }
        if (!ProjectManager.isValidId(projectId) || !projectExists(projectId)) {
            fail(ctx, "Unknown project '" + projectId + "'.");
            return 0;
        }
        if (version != null && !SEMVER.matcher(version).matches()) {
            fail(ctx, "Version must be semver (e.g. 1.2.0): got '" + version + "'.");
            return 0;
        }
        CraftPyExecutors.supplyAsync(() -> {
            try {
                ModuleBuilder.build(projectId, version);
                return "Built module from project '" + projectId + "' to "
                        + net.xmilon.craftpy.project.PyForgePaths.modules().resolve(projectId + ".cpm");
            } catch (java.io.IOException e) {
                return "Build failed: " + e.getMessage();
            }
        }, CraftPyExecutors.io()).thenAccept(message -> next(ctx, () -> {
            if (message.startsWith("Build failed")) {
                ctx.getSource().sendFailure(Component.literal(message));
            } else {
                ctx.getSource().sendSuccess(() -> Component.literal(message), true);
            }
        }));
        return 1;
    }

    private static int moduleSetEnabled(CommandContext<CommandSourceStack> ctx, String id, boolean enabled) {
        if (!allowed(ctx, CraftPyPermissions.MODULE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.MODULE.getNodeName() + ".");
            return 0;
        }
        if (ModuleLoader.findFileById(id) == null) {
            fail(ctx, "Unknown module '" + id + "'. Use /craftpy module list.");
            return 0;
        }
        long scope = ModuleLoader.stateRevision();
        CraftPyExecutors.runAsync(() -> ModuleLoader.setEnabled(id, enabled, scope), CraftPyExecutors.forProject("@module-state"))
                .thenRun(() -> next(ctx, () -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            (enabled ? "Will enable" : "Will disable") + " module '" + id + "' on RELOAD."), true);
                }));
        return 1;
    }

    private static int moduleReload(CommandContext<CommandSourceStack> ctx, String id) {
        if (!allowed(ctx, CraftPyPermissions.MODULE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.MODULE.getNodeName() + ".");
            return 0;
        }
        if (ModuleLoader.findFileById(id) == null) {
            fail(ctx, "Unknown module '" + id + "'.");
            return 0;
        }
        return moduleReloadAll(ctx);
    }

    private static int moduleReloadAll(CommandContext<CommandSourceStack> ctx) {
        if (!allowed(ctx, CraftPyPermissions.MODULE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.MODULE.getNodeName() + ".");
            return 0;
        }
        ModuleLoader.applyChanges().whenComplete((result, error) -> next(ctx, () -> {
            if (error != null) fail(ctx, "Reload failed: " + error.getMessage());
            else if (!result.success()) fail(ctx, result.summary());
            else ctx.getSource().sendSuccess(() -> Component.literal(result.summary()), true);
        }));
        return 1;
    }

    private static ModuleBuilder.ModuleMetadata moduleById(String id) {
        for (ModuleBuilder.ModuleMetadata module : ModuleLoader.loadedModules()) {
            if (id.equals(module.id)) {
                return module;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // /craftpy py <code>
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> py() {
        return Commands.literal("py")
                .requires(source -> source.hasPermission(3))
                .then(Commands.argument("code", StringArgumentType.greedyString())
                        .executes(ctx -> pyRun(ctx, StringArgumentType.getString(ctx, "code"))));
    }

    private static int pyRun(CommandContext<CommandSourceStack> ctx, String code) {
        if (!allowed(ctx, CraftPyPermissions.PY)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.PY.getNodeName() + ".");
            return 0;
        }
        if (!PyRuntime.available()) {
            fail(ctx, "No supported Python runtime is available.");
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        String session = "cmd:" + (player == null ? "console" : player.getStringUUID());
        PyRuntime.get().evaluate(null, code, session).thenAccept(result -> next(ctx, () -> {
            List<String> lines = new ArrayList<>();
            if (result.output() != null) {
                lines.addAll(result.output());
            }
            if (result.value() != null) {
                lines.add("=> " + result.value());
            }
            if (result.error() != null) {
                lines.add("ERROR: " + result.error());
            }
            if (lines.isEmpty()) {
                lines.add("(no output)");
            }
            if (player != null) {
                CraftPyNetwork.sendTo(player, ProjectAction.CONSOLE, true, String.join("\n", lines), "");
            } else {
                for (String line : lines) {
                    net.xmilon.craftpy.CraftPyLog.LOGGER.info("[craftpy py] {}", line);
                }
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
            }
        }));
        return 1;
    }

    // ------------------------------------------------------------------
    // /craftpy window open|close <module:window> [player]   (M13 pending)
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> window() {
        return Commands.literal("window")
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.get(net.xmilon.craftpy.Config.PERMISSION_LEVEL))))
                .then(Commands.literal("open")
                        .then(Commands.argument("module_window", StringArgumentType.string())
                                .executes(ctx -> windowNotAvailable(ctx))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> windowNotAvailable(ctx))))
                        .executes(ctx -> windowNotAvailable(ctx)))
                .then(Commands.literal("close")
                        .then(Commands.argument("module_window", StringArgumentType.string())
                                .executes(ctx -> windowNotAvailable(ctx))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> windowNotAvailable(ctx))))
                        .executes(ctx -> windowNotAvailable(ctx)));
    }

    /** Honest stub: the Python window toolkit is milestone M13, not this build. */
    private static int windowNotAvailable(CommandContext<CommandSourceStack> ctx) {
        if (!allowed(ctx, CraftPyPermissions.WINDOW)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.WINDOW.getNodeName() + ".");
            return 0;
        }
        fail(ctx, "Python windows are not available in this build yet (planned for milestone M13).");
        return 0;
    }

    // ------------------------------------------------------------------
    // /craftpy debug list|slots
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> debug() {
        return Commands.literal("debug")
                .requires(source -> source.hasPermission(3))
                .then(Commands.literal("list").executes(CraftPyCommand::debugList))
                .then(Commands.literal("slots").executes(CraftPyCommand::debugSlots));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> game() {
        return Commands.literal("game")
                .requires(source -> source.hasPermission(3))
                .then(Commands.literal("restart")
                        .executes(ctx -> gameRestart(ctx, 5))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 120))
                                .executes(ctx -> gameRestart(ctx, IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(Commands.literal("cancel")
                        .executes(ctx -> gameCancel(ctx)));
    }

    private static int gameRestart(CommandContext<CommandSourceStack> ctx, int seconds) {
        if (!allowed(ctx, CraftPyPermissions.DEBUG)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.DEBUG.getNodeName() + ".");
            return 0;
        }
        if (net.xmilon.craftpy.lifecycle.RestartManager.isScheduled()) {
            ctx.getSource().sendFailure(Component.literal("A restart is already scheduled."));
            return 0;
        }
        net.xmilon.craftpy.lifecycle.RestartManager.scheduleRestart(ctx.getSource().getServer(), seconds);
        ctx.getSource().sendSuccess(() -> Component.literal("Scheduled restart in " + seconds + "s (server will stop; relaunch required)"), true);
        return 1;
    }

    private static int gameCancel(CommandContext<CommandSourceStack> ctx) {
        if (!allowed(ctx, CraftPyPermissions.DEBUG)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.DEBUG.getNodeName() + ".");
            return 0;
        }
        if (!net.xmilon.craftpy.lifecycle.RestartManager.isScheduled()) {
            ctx.getSource().sendFailure(Component.literal("No restart is currently scheduled."));
            return 0;
        }
        net.xmilon.craftpy.lifecycle.RestartManager.cancel();
        ctx.getSource().sendSuccess(() -> Component.literal("Restart cancelled"), true);
        return 1;
    }

    private static int debugList(CommandContext<CommandSourceStack> ctx) {
        if (!allowed(ctx, CraftPyPermissions.DEBUG)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.DEBUG.getNodeName() + ".");
            return 0;
        }
        List<String> active = new ArrayList<>(SlotRegistry.activeProjectIds());
        int modules = ModuleLoader.loadedModules().size();
        String python = PyRuntime.available() ? "yes" : "no (no supported runtime)";
        final String output = "active projects: " + (active.isEmpty() ? "(none)" : String.join(", ", active))
                + "\nloaded modules: " + modules
                + "\npython runtime: " + python;
        ctx.getSource().sendSuccess(() -> Component.literal(output), false);
        return 1;
    }

    private static int debugSlots(CommandContext<CommandSourceStack> ctx) {
        if (!allowed(ctx, CraftPyPermissions.DEBUG)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.DEBUG.getNodeName() + ".");
            return 0;
        }
        List<String> holders = new ArrayList<>(new TreeSet<>(SlotRegistry.snapshotByHolder().keySet()));
        StringBuilder sb = new StringBuilder();
        sb.append("mapped content: ").append(holders.size()).append(" (")
                .append(SlotRegistry.Kind.values().length).append(" kinds)\n");
        for (int i = 0; i < Math.min(20, holders.size()); i++) {
            sb.append("  ").append(holders.get(i)).append('\n');
        }
        if (holders.size() > 20) {
            sb.append("  ... and ").append(holders.size() - 20).append(" more");
        }
        final String output = sb.toString().trim();
        ctx.getSource().sendSuccess(() -> Component.literal(output), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Checks the PermissionAPI node for a player executor; console sources fall back to op level. */
    private static boolean allowed(CommandContext<CommandSourceStack> ctx, PermissionNode<Boolean> node) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return node.getDefaultResolver().resolve(null, null);
        }
        try {
            return PermissionAPI.getPermission(player, node);
        } catch (net.neoforged.neoforge.server.permission.exceptions.UnregisteredPermissionException e) {
            return node.getDefaultResolver().resolve(player, player.getUUID());
        }
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal(message));
    }

    /** Runs {@code action} on the server thread (already the calling thread for command events). */
    private static void next(CommandContext<CommandSourceStack> ctx, Runnable action) {
        ctx.getSource().getServer().execute(action);
    }

    private static void replyOp(CommandContext<CommandSourceStack> ctx, ProjectManager.OpResult result) {
        next(ctx, () -> {
            if (result.success()) {
                ctx.getSource().sendSuccess(() -> Component.literal(result.message()), true);
            } else {
                ctx.getSource().sendFailure(Component.literal(result.message()));
            }
        });
    }

    private static boolean projectExists(String id) {
        ProjectManager manager = ProjectManager.get();
        if (manager == null) {
            return false;
        }
        return java.nio.file.Files.isDirectory(manager.projectsDir().resolve(id));
    }

    private static List<String> projectIds() {
        List<String> ids = new ArrayList<>();
        ProjectManager manager = ProjectManager.get();
        if (manager != null) {
            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(manager.projectsDir())) {
                stream.filter(java.nio.file.Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(ProjectManager::isValidId)
                        .forEach(ids::add);
            } catch (java.io.IOException ignored) {
                // suggestions are best-effort
            }
        }
        return ids;
    }

    private static volatile List<String> cachedModuleIds = List.of();
    private static volatile long cachedModuleIdsAt;

    /**
     * Every discovered module (loaded or not), so enable/info/reload can complete disabled ones too.
     * Cached for 5 s: listing opens every .cpm, and suggestions run on each keystroke.
     */
    private static Iterable<String> moduleIds() {
        long now = System.currentTimeMillis();
        if (now - cachedModuleIdsAt > 5000) {
            TreeSet<String> ids = new TreeSet<>();
            for (net.xmilon.craftpy.project.ModuleInfo module : ModuleLoader.list()) {
                if (module.id != null && !module.id.isBlank()) {
                    ids.add(module.id);
                }
            }
            cachedModuleIds = List.copyOf(ids);
            cachedModuleIdsAt = now;
        }
        return cachedModuleIds;
    }
}
