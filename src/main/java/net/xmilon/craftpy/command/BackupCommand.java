package net.xmilon.craftpy.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.xmilon.craftpy.content.MachineAccess;
import net.xmilon.craftpy.content.MachineState;
import net.xmilon.craftpy.lifecycle.StorageData;
import net.xmilon.craftpy.project.PyForgePaths;
import net.xmilon.craftpy.server.Backups;
import net.xmilon.craftpy.util.CraftPyExecutors;
import net.xmilon.craftpy.util.Json;

/**
 * {@code /craftpy backup create [project]}, {@code list} and {@code restore <file> [project]}: every module's and project's own
 * data ({@code storage}, {@code files.*}, loaded machines) as JSON in {@code <world>/craftpy_backups/} (see {@link Backups}).
 * Operators only (level 3). A restore first writes a backup of what it replaces, so it can be undone with the same command.
 */
final class BackupCommand {
    private BackupCommand() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("backup")
                .requires(source -> source.hasPermission(3))
                .then(Commands.literal("create")
                        .executes(ctx -> create(ctx, null))
                        .then(Commands.argument("project", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(owners(), builder))
                                .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "project")))))
                .then(Commands.literal("list").executes(BackupCommand::list))
                .then(Commands.literal("restore")
                        .then(Commands.argument("file", StringArgumentType.string())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(names(ctx.getSource().getServer()), builder))
                                .executes(ctx -> restore(ctx, StringArgumentType.getString(ctx, "file"), null))
                                .then(Commands.argument("project", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(owners(), builder))
                                        .executes(ctx -> restore(ctx, StringArgumentType.getString(ctx, "file"),
                                                StringArgumentType.getString(ctx, "project"))))));
    }

    static Path folder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(Backups.FOLDER);
    }

    private static Path dataRoot() {
        return PyForgePaths.root().resolve("data");
    }

    /** The projects and modules that have storage values (for completion). */
    private static List<String> owners() {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (String key : StorageData.VALUES.keySet()) {
            int slash = key.indexOf('/');
            String owner = slash < 0 ? key : key.substring(0, slash);
            out.add(owner.startsWith("module:") ? owner.substring("module:".length()) : owner);
        }
        return new ArrayList<>(out);
    }

    private static List<String> names(MinecraftServer server) {
        try {
            return Backups.list(folder(server));
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    /** The loaded CraftPy machines (of {@code project}, or all), with their saved state. Server thread. */
    private static List<Backups.Machine> machines(MinecraftServer server, String project) {
        List<Backups.Machine> out = new ArrayList<>();
        for (BlockEntity entity : MachineState.loaded()) {
            if (!(entity instanceof MachineAccess machine) || !(entity.getLevel() instanceof ServerLevel level) || level.getServer() != server) {
                continue;
            }
            String owner = ownerOf(entity);
            if (project != null && !project.equals(owner)) continue;
            CompoundTag state = new CompoundTag();
            machine.machineState().save(state, level.registryAccess());
            BlockPos pos = entity.getBlockPos();
            out.add(new Backups.Machine(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(),
                    BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString(), owner, state.toString()));
        }
        return out;
    }

    /** The project a machine block belongs to ({@code bank} of {@code bank:vault}), or "" when it is not filled. */
    private static String ownerOf(BlockEntity entity) {
        String holder = net.xmilon.craftpy.registry.SlotRegistry.holderFor(net.xmilon.craftpy.registry.SlotRegistry.Kind.KINETIC,
                net.xmilon.craftpy.registry.KineticSlots.index(entity.getBlockState().getBlock()));
        if (holder == null) return "";
        String content = net.xmilon.craftpy.lifecycle.EventBridge.projectId(holder);
        int colon = content.indexOf(':');
        return colon > 0 ? content.substring(0, colon) : content;
    }

    private static int create(CommandContext<CommandSourceStack> ctx, String project) {
        MinecraftServer server = ctx.getSource().getServer();
        // read on the server thread, written to disk off it
        var storage = new java.util.HashMap<>(StorageData.VALUES);
        var machines = machines(server, project);
        Instant now = Instant.now();
        String world = server.getWorldData().getLevelName();
        Path folder = folder(server);
        CraftPyExecutors.supplyAsync(() -> {
            try {
                JsonObject backup = Backups.build(storage, dataRoot(), machines, project, now, world);
                Files.createDirectories(folder);
                Path file = folder.resolve(Backups.fileName(now, project));
                byte[] bytes = Json.GSON.toJson(backup).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                net.neoforged.neoforge.common.IOUtilities.atomicWrite(file, out -> out.write(bytes));
                int values = backup.getAsJsonObject("storage").entrySet().stream().mapToInt(e -> e.getValue().getAsJsonObject().size()).sum();
                int files = backup.getAsJsonObject("files").entrySet().stream().mapToInt(e -> e.getValue().getAsJsonObject().size()).sum();
                return "Backup " + file.getFileName() + ": " + values + " storage values, " + files + " files, " + machines.size()
                        + " loaded machines" + (project == null ? "" : " of " + project) + " (" + bytes.length / 1024 + " KB, in "
                        + Backups.FOLDER + "/ of the world)";
            } catch (java.io.IOException | RuntimeException e) {
                return "!Backup failed: " + e.getMessage();
            }
        }, CraftPyExecutors.io()).thenAccept(message -> server.execute(() -> reply(ctx, message)));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<String> found = names(ctx.getSource().getServer());
        if (found.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No backups yet. /craftpy backup create makes one."), false);
            return 0;
        }
        String shown = String.join("\n  ", found.subList(0, Math.min(20, found.size())));
        ctx.getSource().sendSuccess(() -> Component.literal("Backups (newest first):\n  " + shown
                + (found.size() > 20 ? "\n  ... and " + (found.size() - 20) + " older" : "")), false);
        return found.size();
    }

    private static int restore(CommandContext<CommandSourceStack> ctx, String name, String project) {
        MinecraftServer server = ctx.getSource().getServer();
        Path file;
        try {
            file = Backups.resolve(folder(server), name);
        } catch (java.io.IOException bad) {
            ctx.getSource().sendFailure(Component.literal(bad.getMessage()));
            return 0;
        }
        if (!Files.isRegularFile(file)) {
            ctx.getSource().sendFailure(Component.literal("There is no backup " + file.getFileName() + " (see /craftpy backup list)"));
            return 0;
        }
        // what is replaced is kept first, so a restore can itself be undone
        var storage = new java.util.HashMap<>(StorageData.VALUES);
        var machines = machines(server, project);
        Instant now = Instant.now();
        String world = server.getWorldData().getLevelName();
        CraftPyExecutors.supplyAsync(() -> {
            try {
                JsonObject before = Backups.build(storage, dataRoot(), machines, project, now, world);
                Path undo = folder(server).resolve(Backups.fileName(now, "before_restore" + (project == null ? "" : "_" + project)));
                byte[] bytes = Json.GSON.toJson(before).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                net.neoforged.neoforge.common.IOUtilities.atomicWrite(undo, out -> out.write(bytes));
                return new Object[]{JsonParser.parseString(Files.readString(file)).getAsJsonObject(), undo.getFileName().toString()};
            } catch (java.io.IOException | RuntimeException e) {
                return new Object[]{null, e.getMessage()};
            }
        }, CraftPyExecutors.io()).thenAccept(read -> server.execute(() -> {
            if (read[0] == null) {
                reply(ctx, "!Restore failed: " + read[1]);
                return;
            }
            try {
                var done = Backups.restore((JsonObject) read[0], StorageData.VALUES, dataRoot(), machine -> putBack(server, machine), project);
                StorageData.changed();
                StringBuilder message = new StringBuilder("Restored " + done.values() + " storage values, " + done.files() + " files, "
                        + done.machines() + " machines from " + file.getFileName() + ". What it replaced is in " + read[1]
                        + ". Run or reload the projects so their code sees it.");
                for (String skipped : done.skipped().subList(0, Math.min(8, done.skipped().size()))) {
                    message.append("\n  not restored: ").append(skipped);
                }
                reply(ctx, message.toString());
            } catch (java.io.IOException | RuntimeException e) {
                reply(ctx, "!Restore failed: " + e.getMessage());
            }
        }));
        return 1;
    }

    /** Puts a machine's saved state back where the same machine block is loaded. Server thread. */
    private static boolean putBack(MinecraftServer server, Backups.Machine machine) {
        ResourceLocation dimension = ResourceLocation.tryParse(machine.dimension());
        if (dimension == null) return false;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        BlockPos pos = new BlockPos(machine.x(), machine.y(), machine.z());
        if (level == null || !level.isLoaded(pos)) return false;
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof MachineAccess access)
                || !BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString().equals(machine.block())) {
            return false;
        }
        try {
            access.machineState().load(TagParser.parseTag(machine.state()), level.registryAccess());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException broken) {
            return false;
        }
        entity.setChanged();
        access.syncMachine();
        return true;
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String message) {
        if (message.startsWith("!")) {
            ctx.getSource().sendFailure(Component.literal(message.substring(1)));
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(message), true);
        }
    }
}
