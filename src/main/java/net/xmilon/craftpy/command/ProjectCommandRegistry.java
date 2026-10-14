package net.xmilon.craftpy.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

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
import com.google.gson.JsonObject;

public final class ProjectCommandRegistry {
    private static final Map<String, Map<String, CommandBinding>> BINDINGS = new ConcurrentHashMap<>();
    private static CommandDispatcher<CommandSourceStack> dispatcher;
    private static CommandBuildContext buildContext;

    private ProjectCommandRegistry() {
    }

    public static void init(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx) {
        dispatcher = d;
        buildContext = ctx;
    }

    public static void register(String projectId, Declarations declarations) {
        if (dispatcher == null || declarations == null) {
            return;
        }
        unregister(projectId);
        Map<String, CommandBinding> map = new HashMap<>();
        for (Declarations.CommandDef def : declarations.commands()) {
            CommandBinding binding = buildBinding(projectId, def);
            if (binding != null) {
                map.put(binding.name, binding);
                try {
                    dispatcher.register(binding.builder);
                } catch (Exception e) {
                    // ignore duplicate registration issues
                }
            }
        }
        if (!map.isEmpty()) {
            BINDINGS.put(projectId, map);
        }
    }

    public static void unregister(String projectId) {
        Map<String, CommandBinding> map = BINDINGS.remove(projectId);
        if (map == null || dispatcher == null) {
            return;
        }
        // Brigadier doesn't support easy removal; to keep things simple and safe,
        // we just drop from our map. Dynamic removal is non-trivial in this setup.
        // If needed later, we could rebuild the root, but not required for now.
    }

    private static CommandBinding buildBinding(String projectId, Declarations.CommandDef def) {
        String name = def.name();
        if (name == null || name.isBlank()) {
            return null;
        }
        JsonObject params = def.params();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(projectId + ":" + name)
                .requires(s -> s.hasPermission(2));
        if (params == null || params.size() == 0) {
            root.executes(ctx -> dispatch(ctx, projectId, name, new JsonObject()));
            return new CommandBinding(name, root);
        }
        RequiredArgumentBuilder<CommandSourceStack, ?> current = null;
        List<RequiredArgumentBuilder<CommandSourceStack, ?>> chain = new ArrayList<>();
        // iterate in deterministic order by entry set
        for (var e : params.entrySet()) {
            String key = e.getKey();
            if (key.equals("name") || key.equals("description")) {
                continue;
            }
            String type = inferType(e.getValue());
            current = arg(key, type);
            chain.add(current);
        }
        if (chain.isEmpty()) {
            root.executes(ctx -> dispatch(ctx, projectId, name, new JsonObject()));
            return new CommandBinding(name, root);
        }
        // build chain: last executes; others then next
        RequiredArgumentBuilder<CommandSourceStack, ?> head = chain.get(0);
        RequiredArgumentBuilder<CommandSourceStack, ?> tail = head;
        for (int i = 1; i < chain.size(); i++) {
            RequiredArgumentBuilder<CommandSourceStack, ?> next = chain.get(i);
            tail.then(next);
            tail = next;
        }
        tail.executes(ctx -> dispatchWithArgs(ctx, projectId, name, chain));
        root.then(head);
        return new CommandBinding(name, root);
    }

    private static String inferType(com.google.gson.JsonElement v) {
        if (v == null || v.isJsonNull()) {
            return "str";
        }
        if (v.isJsonPrimitive()) {
            var p = v.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return "bool";
            }
            if (p.isNumber()) {
                // prefer int if no decimal? keep simple
                double d = p.getAsDouble();
                if (Math.floor(d) == d) {
                    return "int";
                }
                return "float";
            }
            if (p.isString()) {
                String s = p.getAsString().toLowerCase();
                if (s.equals("int") || s.equals("integer")) {
                    return "int";
                }
                if (s.equals("float") || s.equals("double")) {
                    return "float";
                }
                if (s.equals("bool") || s.equals("boolean")) {
                    return "bool";
                }
                if (s.equals("player")) {
                    return "player";
                }
                if (s.equals("block") || s.equals("item") || s.equals("id")) {
                    return "id";
                }
                return "str";
            }
        }
        return "str";
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> arg(String name, String type) {
        switch (type) {
            case "int" -> {
                return Commands.argument(name, IntegerArgumentType.integer());
            }
            case "float" -> {
                return Commands.argument(name, FloatArgumentType.floatArg());
            }
            case "double" -> {
                return Commands.argument(name, DoubleArgumentType.doubleArg());
            }
            case "bool" -> {
                return Commands.argument(name, BoolArgumentType.bool());
            }
            case "player" -> {
                return Commands.argument(name, EntityArgument.player());
            }
            case "id" -> {
                return Commands.argument(name, ResourceLocationArgument.id())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(SlotRegistry.snapshotByHolder().keySet(), builder));
            }
            default -> {
                return Commands.argument(name, StringArgumentType.string());
            }
        }
    }

    private static int dispatchWithArgs(CommandContext<CommandSourceStack> ctx, String projectId, String name, List<RequiredArgumentBuilder<CommandSourceStack, ?>> chain) {
        JsonObject args = new JsonObject();
        for (var rb : chain) {
            String key = rb.getName();
            var arg = ctx.getArgument(key, Object.class);
            if (arg instanceof Integer i) {
                args.addProperty(key, i);
            } else if (arg instanceof Float f) {
                args.addProperty(key, f);
            } else if (arg instanceof Double d) {
                args.addProperty(key, d);
            } else if (arg instanceof Boolean b) {
                args.addProperty(key, b);
            } else if (arg instanceof net.minecraft.server.level.ServerPlayer sp) {
                args.addProperty(key, sp.getName().getString());
            } else if (arg instanceof net.minecraft.resources.ResourceLocation rl) {
                args.addProperty(key, rl.toString());
            } else {
                args.addProperty(key, String.valueOf(arg));
            }
        }
        return dispatch(ctx, projectId, name, args);
    }

    private static int dispatch(CommandContext<CommandSourceStack> ctx, String projectId, String name, JsonObject args) {
        JsonObject context = new JsonObject();
        context.add("args", args);
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            JsonObject p = new JsonObject();
            p.addProperty("name", player.getName().getString());
            p.addProperty("uuid", player.getUUID().toString());
            context.add("player", p);
        }
        context.addProperty("source", ctx.getSource().getTextName());
        PyRuntime.get().triggerEvent("command_" + name, context).thenAccept(result -> {
            // send log lines back to source
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

    private record CommandBinding(String name, LiteralArgumentBuilder<CommandSourceStack> builder) {
    }
}