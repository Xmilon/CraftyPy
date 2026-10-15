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
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.PERMISSION_LEVEL.get())))
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
                                                        IntegerArgumentType.getInteger(ctx, "count")))))));
    }

    /**
     * Every project content id as {@code project:id}, offered as tab completions. Slot keys carry a
     * kind prefix ({@code BLOCK:project:id}) that is not a valid resource location, so it is stripped.
     */
    private static Iterable<String> contentIds() {
        TreeSet<String> ids = new TreeSet<>();
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
        final SlotRegistry.Kind given = kind;

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
                .filter(entry -> entry.getValue().kind() == given && entry.getValue().index() == index)
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(id.toString());
        ctx.getSource().sendSuccess(() -> Component.literal("Gave " + stack.getCount() + " [" + holder + " ("
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
        if (ref == null) {
            ref = mapping.get(raw);
        }
        if (ref == null && ns.startsWith("project_")) {
            String alias = ns.substring("project_".length()) + ":" + path;
            ref = mapping.get(kindPrefix + alias);
            if (ref == null) {
                ref = mapping.get(alias);
            }
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

    // ------------------------------------------------------------------
    // /craftpy ide open|close [player] | project <id> [player]
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> ide() {
        return Commands.literal("ide")
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.PERMISSION_LEVEL.get())))
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

    /** Opens the container menu; {@code project} (optional) makes the client jump straight into the IDE. */
    private static void openIdeScreen(ServerPlayer target, String project) {
        target.openMenu(new SimpleMenuProvider(
                (containerId, inventory, player) -> new ComputerMenu(containerId, inventory),
                PythonComputerBlock.TITLE));
        CraftPyNetwork.sendTo(target, ProjectAction.OPEN_IDE, true, "",
                project == null ? "" : project);
    }

    // ------------------------------------------------------------------
    // /craftpy project list|new|delete|run|stop
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> project() {
        return Commands.literal("project")
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.PERMISSION_LEVEL.get())))
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
                                .executes(ctx -> projectDelete(ctx, StringArgumentType.getString(ctx, "project")))));
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
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.PERMISSION_LEVEL.get())))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
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
        List<ModuleBuilder.ModuleMetadata> modules = new ArrayList<>(ModuleLoader.loadedModules());
        if (modules.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component
                    .literal("No modules loaded. Drop .cpm files into pyforge/modules/ or pyforge/imports/."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        for (ModuleBuilder.ModuleMetadata module : modules) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(module.id).append(" v").append(module.version == null ? "?" : module.version).append(" ")
                    .append(module.name == null ? "" : "(" + module.name + ")");
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
                        + net.xmilon.craftpy.project.PyForgePaths.exports().resolve(
                                projectId + (version == null ? "" : "-" + version) + ".cpm");
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
        CraftPyExecutors.runAsync(() -> ModuleLoader.setEnabled(id, enabled), CraftPyExecutors.io())
                .thenRun(() -> next(ctx, () -> ctx.getSource().sendSuccess(() -> Component.literal(
                        (enabled ? "Enabled" : "Disabled (fully unloads after a restart)") + " module '" + id + "'."), true)));
        return 1;
    }

    private static int moduleReload(CommandContext<CommandSourceStack> ctx, String id) {
        if (!allowed(ctx, CraftPyPermissions.MODULE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.MODULE.getNodeName() + ".");
            return 0;
        }
        CraftPyExecutors.supplyAsync(() -> {
            try {
                return ModuleLoader.reload(id);
            } catch (java.io.IOException e) {
                return null;
            }
        }, CraftPyExecutors.io()).thenAccept(reloaded -> next(ctx, () -> {
            if (reloaded == null) {
                ctx.getSource().sendFailure(Component.literal("Reload failed for '" + id + "'."));
            } else if (Boolean.TRUE.equals(reloaded)) {
                ctx.getSource().sendSuccess(() -> Component.literal("Reloaded module '" + id + "'."), true);
            } else {
                ctx.getSource().sendFailure(Component.literal("Module '" + id + "' is not file-backed; cannot reload."));
            }
        }));
        return 1;
    }

    private static int moduleReloadAll(CommandContext<CommandSourceStack> ctx) {
        if (!allowed(ctx, CraftPyPermissions.MODULE)) {
            fail(ctx, "Missing permission " + CraftPyPermissions.MODULE.getNodeName() + ".");
            return 0;
        }
        List<String> ids = new ArrayList<>();
        for (ModuleBuilder.ModuleMetadata module : ModuleLoader.loadedModules()) {
            ids.add(module.id);
        }
        ctx.getSource().sendSuccess(
                () -> Component.literal("Reloading " + ids.size() + " module(s): " + String.join(", ", ids) + "..."),
                true);
        for (String id : ids) {
            moduleReload(ctx, id);
        }
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
                    net.xmilon.craftpy.CraftPy.LOGGER.info("[craftpy py] {}", line);
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
                .requires(source -> source.hasPermission(Math.max(2, net.xmilon.craftpy.Config.PERMISSION_LEVEL.get())))
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