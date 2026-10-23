package net.xmilon.craftpy.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.xmilon.craftpy.python.Declarations;
import net.xmilon.craftpy.python.PyRuntime;
import net.xmilon.craftpy.registry.SlotRegistry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The commands projects and modules declare with {@code @command("name", level=...)}: {@code /<id>:<name>}. Each runs only in
 * the worker of the project or module that declared it, may be used by players with the declared permission level (0: every
 * player), and is hidden as soon as its project stops, until it runs again. The commands are put into every new command tree
 * ({@code /reload} makes one), and a declaration that changed replaces the old command instead of being merged into it.
 */
public final class ProjectCommandRegistry {
    /** id -> command name -> binding; the commands of one project or module. */
    private static final Map<String, Map<String, CommandBinding>> BINDINGS = new ConcurrentHashMap<>();
    private static CommandDispatcher<CommandSourceStack> dispatcher;

    private ProjectCommandRegistry() {
    }

    /** A new command tree (the server starts, or /reload): every running project's commands go into it again. */
    public static void init(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx) {
        dispatcher = d;
        for (Map<String, CommandBinding> commands : BINDINGS.values()) {
            for (CommandBinding binding : commands.values()) {
                dispatcher.register(binding.builder());
            }
        }
    }

    /** Puts the commands of a project ({@code owner} = its id) or module ({@code owner} = {@code module:<id>}) into the tree. */
    public static void register(String id, String owner, Declarations declarations) {
        if (dispatcher == null || declarations == null) {
            return;
        }
        Map<String, CommandBinding> previous = BINDINGS.remove(id);
        Map<String, CommandBinding> map = new LinkedHashMap<>();
        for (Declarations.CommandDef def : declarations.commands()) {
            CommandBinding binding = buildBinding(id, owner, def);
            if (binding != null) {
                map.put(binding.name(), binding);
            }
        }
        // a command whose arguments or level changed would otherwise be merged into the old one
        if (previous != null) previous.keySet().forEach(name -> removeRoot(id + ":" + name));
        map.keySet().forEach(name -> removeRoot(id + ":" + name));
        for (CommandBinding binding : map.values()) {
            dispatcher.register(binding.builder());
        }
        if (!map.isEmpty()) {
            BINDINGS.put(id, map);
        }
        resendCommandTrees();
    }

    /** The project stopped: its commands disappear from the players' completion and refuse to run. */
    public static void unregister(String id) {
        Map<String, CommandBinding> map = BINDINGS.remove(id);
        if (map == null || dispatcher == null) {
            return;
        }
        map.keySet().forEach(name -> removeRoot(id + ":" + name));
        resendCommandTrees();
    }

    /** The commands a project or module has now, with their levels, for the help and the IDE. */
    public static Map<String, Integer> commandsOf(String id) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Map<String, CommandBinding> map = BINDINGS.get(id);
        if (map != null) map.values().forEach(binding -> out.put(binding.name(), binding.level()));
        return out;
    }

    /**
     * Brigadier has no removal: the root's three child maps are reached by reflection. If that ever fails, the command's
     * {@code requires} still hides and refuses it because it checks {@link #BINDINGS}.
     */
    @SuppressWarnings("unchecked")
    private static void removeRoot(String literal) {
        CommandNode<CommandSourceStack> root = dispatcher.getRoot();
        try {
            for (String field : new String[]{"children", "literals", "arguments"}) {
                var map = CommandNode.class.getDeclaredField(field);
                map.setAccessible(true);
                ((Map<String, ?>) map.get(root)).remove(literal);
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // requires(...) below keeps a dropped command unusable
        }
    }

    /** Clients only know the command tree they got on join; resend it so new commands tab-complete and old ones vanish. */
    private static void resendCommandTrees() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                server.getCommands().sendCommands(player);
            }
        });
    }

    private static boolean active(String id, String name, CommandBinding binding) {
        Map<String, CommandBinding> map = BINDINGS.get(id);
        return map != null && map.get(name) == binding;
    }

    private static CommandBinding buildBinding(String id, String owner, Declarations.CommandDef def) {
        String name = def.name();
        if (name == null || !name.matches("[a-z0-9_.-]{1,40}")) {
            return null;
        }
        JsonObject entry = def.params();
        // the entry is the whole declaration: {"name", "params": {...}, "level", "optional": [...]}
        JsonObject params = entry.has("params") && entry.get("params").isJsonObject() ? entry.getAsJsonObject("params") : entry;
        int level = entry.has("level") && entry.get("level").isJsonPrimitive() ? Math.clamp(entry.get("level").getAsInt(), 0, 4) : 2;
        Set<String> optional = new java.util.HashSet<>();
        if (entry.has("optional") && entry.get("optional").isJsonArray()) {
            entry.getAsJsonArray("optional").forEach(e -> optional.add(e.getAsString()));
        }
        CommandBinding[] self = new CommandBinding[1];
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(id + ":" + name)
                .requires(source -> source.hasPermission(level) && active(id, name, self[0]));
        List<RequiredArgumentBuilder<CommandSourceStack, ?>> chain = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : params.entrySet()) {
            String key = e.getKey();
            if (key.equals("name") || key.equals("description") || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,40}")) {
                continue;
            }
            chain.add(arg(key, inferType(e.getValue())));
            names.add(key);
        }
        // the command runs after its last required argument, and after every optional one that follows
        int required = 0;
        while (required < names.size() && !optional.contains(names.get(required))) required++;
        if (required == 0) {
            root.executes(ctx -> dispatch(ctx, owner, name, args(ctx, List.of())));
        }
        for (int i = 0; i < chain.size(); i++) {
            List<String> given = List.copyOf(names.subList(0, i + 1));
            if (i + 1 >= required) {
                chain.get(i).executes(ctx -> dispatch(ctx, owner, name, args(ctx, given)));
            }
        }
        for (int i = chain.size() - 1; i > 0; i--) {
            chain.get(i - 1).then(chain.get(i));
        }
        if (!chain.isEmpty()) {
            root.then(chain.get(0));
        }
        self[0] = new CommandBinding(name, level, root);
        return self[0];
    }

    private static String inferType(JsonElement v) {
        if (v == null || !v.isJsonPrimitive()) {
            return "str";
        }
        var p = v.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return "bool";
        }
        if (p.isNumber()) {
            return Math.floor(p.getAsDouble()) == p.getAsDouble() ? "int" : "float";
        }
        return switch (p.getAsString().toLowerCase(java.util.Locale.ROOT)) {
            case "int", "integer" -> "int";
            case "float", "double" -> "float";
            case "bool", "boolean" -> "bool";
            case "player" -> "player";
            case "block", "item", "id" -> "id";
            default -> "str";
        };
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> arg(String name, String type) {
        return switch (type) {
            case "int" -> Commands.argument(name, IntegerArgumentType.integer());
            case "float" -> Commands.argument(name, FloatArgumentType.floatArg());
            case "bool" -> Commands.argument(name, BoolArgumentType.bool());
            case "player" -> Commands.argument(name, EntityArgument.player());
            case "id" -> Commands.argument(name, ResourceLocationArgument.id())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(SlotRegistry.snapshotByHolder().keySet(), builder));
            default -> Commands.argument(name, StringArgumentType.string());
        };
    }

    private static JsonObject args(CommandContext<CommandSourceStack> ctx, List<String> given) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        JsonObject args = new JsonObject();
        for (String key : given) {
            Object arg = ctx.getArgument(key, Object.class);
            if (arg instanceof Integer i) {
                args.addProperty(key, i);
            } else if (arg instanceof Float f) {
                args.addProperty(key, f);
            } else if (arg instanceof Boolean b) {
                args.addProperty(key, b);
            } else if (arg instanceof net.minecraft.commands.arguments.selector.EntitySelector) {
                args.addProperty(key, EntityArgument.getPlayer(ctx, key).getName().getString());
            } else if (arg instanceof net.minecraft.resources.ResourceLocation rl) {
                args.addProperty(key, rl.toString());
            } else {
                args.addProperty(key, String.valueOf(arg));
            }
        }
        return args;
    }

    private static int dispatch(CommandContext<CommandSourceStack> ctx, String owner, String name, JsonObject args) {
        JsonObject context = new JsonObject();
        context.add("args", args);
        context.addProperty("owner", owner); // only the worker that declared it runs it
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            JsonObject p = new JsonObject();
            p.addProperty("name", player.getName().getString());
            p.addProperty("uuid", player.getUUID().toString());
            context.add("player", p);
        }
        context.addProperty("source", ctx.getSource().getTextName());
        PyRuntime.get().triggerEvent("command_" + name, context).thenAccept(result -> {
            if (result.has("log")) {
                JsonArray log = result.getAsJsonArray("log");
                for (int i = 0; i < log.size(); i++) {
                    String line = log.get(i).getAsString();
                    if (player != null) {
                        player.sendSystemMessage(Component.literal(line));
                    } else {
                        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                }
            }
        });
        return 1;
    }

    private record CommandBinding(String name, int level, LiteralArgumentBuilder<CommandSourceStack> builder) {
    }
}
