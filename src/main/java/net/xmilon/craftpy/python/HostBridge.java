package net.xmilon.craftpy.python;

import net.xmilon.craftpy.CraftPyLog;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Executes reverse calls issued by project Python code. Every handler that
 * touches world state is scheduled on the server thread (so it is safe) and the
 * caller blocks until it completes. Unknown methods return an {@code ok:false}
 * result instead of throwing, so older hosts degrade gracefully.
 */
public final class HostBridge {

    private static final Map<String, JsonElement> STORAGE = new ConcurrentHashMap<>();
    private static final long TIMEOUT_SECONDS = 8;

    private HostBridge() {
    }

    public static JsonObject handle(String method, JsonObject params) {
        if (method.startsWith("java.")) {
            // the generic Java bridge: Minecraft/NeoForge/mod classes by name (see bridge.JavaBridge)
            return onServer(server -> net.xmilon.craftpy.bridge.JavaBridge.handle(method.substring(5), params));
        }
        try {
            return switch (method) {
                case "world.get_block" -> onServer(server -> getBlock(server, params));
                case "world.set_block" -> onServer(server -> setBlock(server, params));
                case "world.get_time" -> onServer(HostBridge::getTime);
                case "world.set_time" -> onServer(server -> setTime(server, params));
                case "world.play_sound" -> onServer(server -> playSound(server, params));
                case "world.spawn" -> onServer(server -> spawn(server, params));
                case "world.particle" -> onServer(server -> particle(server, params));
                case "world.set_weather" -> onServer(server -> setWeather(server, params));
                case "world.blocks_in_box" -> onServer(server -> blocksInBox(server, params));
                case "world.entities_in_box" -> onServer(server -> entitiesInBox(server, params));
                case "player.send_message" -> onServer(server2 -> sendMessage(server2, params));
                case "player.teleport" -> onServer(server2 -> teleport(server2, params));
                case "player.give" -> onServer(server2 -> give(server2, params));
                case "players.all" -> onServer(HostBridge::playersAll);
                case "players.get" -> onServer(server2 -> playersGet(server2, params));
                case "server.broadcast" -> onServer(server2 -> broadcast(server2, params));
                case "server.command" -> onServer(server2 -> command(server2, params));
                case "world.fill" -> onServer(server -> fill(server, params));
                case "world.explode" -> onServer(server -> explode(server, params));
                case "world.lightning" -> onServer(server -> lightning(server, params));
                case "world.drop_item" -> onServer(server -> dropItem(server, params));
                case "world.info" -> onServer(server -> worldInfo(server, params));
                case "entity.get" -> onServer(server -> entityGet(server, params));
                case "entity.kill" -> onServer(server -> entityKill(server, params));
                case "entity.teleport" -> onServer(server -> entityTeleport(server, params));
                case "entity.set_name" -> onServer(server -> entityName(server, params));
                case "player.heal" -> onServer(server -> withPlayer(server, params, HostBridge::heal));
                case "player.set_health" -> onServer(server -> withPlayer(server, params, HostBridge::setHealth));
                case "player.feed" -> onServer(server -> withPlayer(server, params, HostBridge::feed));
                case "player.effect" -> onServer(server -> withPlayer(server, params, HostBridge::effect));
                case "player.clear_effects" -> onServer(server -> withPlayer(server, params, (pl, q) -> {
                    pl.removeAllEffects();
                    return ok();
                }));
                case "player.gamemode" -> onServer(server -> withPlayer(server, params, HostBridge::gamemode));
                case "player.add_xp" -> onServer(server -> withPlayer(server, params, HostBridge::addXp));
                case "player.title" -> onServer(server -> withPlayer(server, params, HostBridge::title));
                case "player.actionbar" -> onServer(server -> withPlayer(server, params, (pl, q) -> {
                    pl.displayClientMessage(Component.literal(string(q, "message", "")), true);
                    return ok();
                }));
                case "player.sound" -> onServer(server -> withPlayer(server, params, HostBridge::playerSound));
                case "player.kill" -> onServer(server -> withPlayer(server, params, (pl, q) -> {
                    pl.kill();
                    return ok();
                }));
                case "player.set_fire" -> onServer(server -> withPlayer(server, params, (pl, q) -> {
                    pl.setRemainingFireTicks(Math.max(0, (int) (number(q, "seconds", 3) * 20)));
                    return ok();
                }));
                case "player.inventory" -> onServer(server -> withPlayer(server, params, HostBridge::inventory));
                case "player.count_item" -> onServer(server -> withPlayer(server, params, HostBridge::countItem));
                case "player.take_item" -> onServer(server -> withPlayer(server, params, HostBridge::takeItem));
                case "storage.get" -> {
                    JsonObject result = ok();
                    JsonElement stored = STORAGE.get(storageKey(params));
                    if (stored != null) {
                        result.add("value", stored);
                    }
                    yield result;
                }
                case "storage.set" -> {
                    JsonElement stored = params.get("value");
                    STORAGE.put(storageKey(params), stored == null ? new JsonObject() : stored);
                    yield ok();
                }
                case "storage.clear" -> {
                    if (params.has("key") && !string(params, "key", "").isBlank()) {
                        STORAGE.remove(storageKey(params));
                    } else {
                        STORAGE.clear();
                    }
                    yield ok();
                }
                case "server.log" -> {
                    CraftPyLog.LOGGER.info("[module] {}", params.has("message") ? params.get("message").getAsString() : "");
                    yield ok();
                }
                case "capabilities" -> ok();
                case "ui.open" -> onServer(server -> uiSend(server, params, "open"));
                case "ui.close" -> onServer(server -> uiSend(server, params, "close"));
                default -> unsupported(method);
            };
        } catch (Exception e) {
            CraftPyLog.LOGGER.warn("Host call '{}' failed: {}", method, e.toString());
            return error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // World
    // ------------------------------------------------------------------

    private static JsonObject getBlock(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        BlockPos pos = pos(p);
        BlockState state = level.getBlockState(pos);
        JsonObject result = ok(true);
        result.addProperty("block", id(state.getBlock()));
        result.addProperty("solid", !state.isAir());
        return result;
    }

    private static JsonObject setBlock(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        Block block = resolveBlock(string(p, "block", "minecraft:air"));
        if (block == null) {
            return error("unknown block: " + string(p, "block", ""));
        }
        BlockState state = block.defaultBlockState();
        boolean replace = !p.has("replace") || p.get("replace").getAsBoolean();
        level.setBlock(pos(p), state, replace ? 3 : 2);
        return ok(true);
    }

    private static JsonObject getTime(MinecraftServer server) {
        ServerLevel level = server.overworld();
        JsonObject result = ok(true);
        result.addProperty("time", level == null ? 0 : level.getDayTime());
        return result;
    }

    private static JsonObject setTime(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        level.setDayTime(p.has("time") ? p.get("time").getAsLong() : 0L);
        return ok(true);
    }

    private static JsonObject playSound(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        ResourceLocation id = parseId(string(p, "sound", ""));
        if (id == null) {
            return error("bad sound id");
        }
        var sound = BuiltInRegistries.SOUND_EVENT.get(id);
        float volume = p.has("volume") ? p.get("volume").getAsFloat() : 1.0f;
        float pitch = p.has("pitch") ? p.get("pitch").getAsFloat() : 1.0f;
        level.playSound(null, pos(p), sound, net.minecraft.sounds.SoundSource.PLAYERS, volume, pitch);
        return ok(true);
    }

    private static JsonObject spawn(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        ResourceLocation id = parseId(string(p, "entity", ""));
        if (id == null) {
            return error("bad entity id");
        }
        EntityType<?> type = EntityType.byString(id.toString()).orElse(null);
        if (type == null) {
            return error("unknown entity: " + id);
        }
        BlockPos pos = pos(p);
        Entity entity = type.create(level);
        if (entity == null) {
            return error("cannot create entity: " + id);
        }
        entity.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(entity);
        JsonObject result = ok();
        result.addProperty("uuid", entity.getUUID().toString());
        return result;
    }

    private static JsonObject particle(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        ResourceLocation id = parseId(string(p, "particle", "minecraft:flame"));
        if (id == null) {
            return error("bad particle id");
        }
        var type = BuiltInRegistries.PARTICLE_TYPE.get(id);
        if (!(type instanceof net.minecraft.core.particles.SimpleParticleType simple)) {
            return unsupported("world.particle (only simple particle types are supported)");
        }
        int count = p.has("count") ? p.get("count").getAsInt() : 1;
        BlockPos pos = pos(p);
        level.sendParticles(simple, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                Math.max(1, count), 0.0, 0.0, 0.0, 0.0);
        return ok();
    }

    private static JsonObject setWeather(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        String weather = string(p, "weather", "clear");
        int duration = p.has("duration") ? p.get("duration").getAsInt() : 6000;
        switch (weather) {
            case "rain" -> level.setWeatherParameters(0, duration, true, false);
            case "thunder" -> level.setWeatherParameters(0, duration, true, true);
            default -> level.setWeatherParameters(duration, 0, false, false);
        }
        return ok();
    }

    private static JsonObject blocksInBox(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        int radius = p.has("radius") ? p.get("radius").getAsInt() : 0;
        BlockPos center = pos(p);
        JsonArray out = new JsonArray();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos at = center.offset(x, y, z);
                    JsonObject entry = new JsonObject();
                    entry.addProperty("x", at.getX());
                    entry.addProperty("y", at.getY());
                    entry.addProperty("z", at.getZ());
                    entry.addProperty("block", id(level.getBlockState(at).getBlock()));
                    out.add(entry);
                }
            }
        }
        JsonObject result = ok(true);
        result.add("blocks", out);
        return result;
    }

    private static JsonObject entitiesInBox(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        double radius = p.has("radius") ? p.get("radius").getAsDouble() : 8;
        BlockPos center = pos(p);
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(center).inflate(radius);
        JsonArray out = new JsonArray();
        for (Entity entity : level.getEntities((Entity) null, box)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", id(entity.getType()));
            entry.addProperty("uuid", entity.getUUID().toString());
            entry.addProperty("x", entity.getX());
            entry.addProperty("y", entity.getY());
            entry.addProperty("z", entity.getZ());
            out.add(entry);
        }
        JsonObject result = ok(true);
        result.add("entities", out);
        return result;
    }

    // ------------------------------------------------------------------
    // Players / server
    // ------------------------------------------------------------------

    private static JsonObject playersAll(MinecraftServer server) {
        JsonArray names = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            names.add(player.getName().getString());
        }
        JsonObject result = ok(true);
        result.add("players", names);
        return result;
    }

    private static JsonObject playersGet(MinecraftServer server, JsonObject p) {
        ServerPlayer player = findPlayer(server, string(p, "name", ""));
        JsonObject result = ok();
        if (player != null) {
            result.add("player", playerInfo(player));
        }
        return result;
    }

    /** Sends a window tree (open / update) or a close request to one player. */
    private static JsonObject uiSend(MinecraftServer server, JsonObject p, String mode) {
        ServerPlayer player = findPlayer(server, string(p, "player", ""));
        if (player == null) {
            return error("player not found");
        }
        String json = "open".equals(mode) ? p.getAsJsonObject("window").toString() : string(p, "id", "");
        net.xmilon.craftpy.network.CraftPyNetwork.sendTo(player, net.xmilon.craftpy.network.ProjectAction.WINDOW,
                true, mode, json);
        return ok(true);
    }

    private static JsonObject sendMessage(MinecraftServer server, JsonObject p) {
        ServerPlayer player = findPlayer(server, string(p, "player", ""));
        if (player == null) {
            return error("player not found");
        }
        player.sendSystemMessage(Component.literal(string(p, "message", "")));
        return ok(true);
    }

    private static JsonObject teleport(MinecraftServer server, JsonObject p) {
        ServerPlayer player = findPlayer(server, string(p, "player", ""));
        if (player == null) {
            return error("player not found");
        }
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        double x = p.has("x") ? p.get("x").getAsDouble() : player.getX();
        double y = p.has("y") ? p.get("y").getAsDouble() : player.getY();
        double z = p.has("z") ? p.get("z").getAsDouble() : player.getZ();
        player.teleportTo(level, x, y, z, player.getYRot(), player.getXRot());
        return ok(true);
    }

    private static JsonObject give(MinecraftServer server, JsonObject p) {
        ServerPlayer player = findPlayer(server, string(p, "player", ""));
        if (player == null) {
            return error("player not found");
        }
        var item = resolveItem(string(p, "item", ""));
        if (item == null) {
            return error("unknown item: " + string(p, "item", ""));
        }
        int count = p.has("count") ? p.get("count").getAsInt() : 1;
        var stack = new net.minecraft.world.item.ItemStack(item, Math.max(1, Math.min(item.getDefaultMaxStackSize(), count)));
        player.getInventory().placeItemBackInInventory(stack);
        return ok(true);
    }

    private static JsonObject broadcast(MinecraftServer server, JsonObject p) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(string(p, "message", "")), false);
        return ok(true);
    }

    private static JsonObject command(MinecraftServer server, JsonObject p) {
        String command = string(p, "command", "");
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        return ok(true);
    }

    // ------------------------------------------------------------------
    // Expanded API: world, entities, players
    // ------------------------------------------------------------------

    private static final int FILL_LIMIT = 32768;

    private static JsonObject fill(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        Block block = resolveBlock(string(p, "block", "minecraft:air"));
        if (block == null) {
            return error("unknown block: " + string(p, "block", ""));
        }
        int x1 = (int) Math.floor(number(p, "x1", 0));
        int y1 = (int) Math.floor(number(p, "y1", 0));
        int z1 = (int) Math.floor(number(p, "z1", 0));
        int x2 = (int) Math.floor(number(p, "x2", 0));
        int y2 = (int) Math.floor(number(p, "y2", 0));
        int z2 = (int) Math.floor(number(p, "z2", 0));
        long volume = (long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
        if (volume > FILL_LIMIT) {
            return error("fill area too large (" + volume + " blocks, max " + FILL_LIMIT + ")");
        }
        BlockState state = block.defaultBlockState();
        for (BlockPos at : BlockPos.betweenClosed(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2))) {
            level.setBlock(at, state, 3);
        }
        JsonObject result = ok();
        result.addProperty("count", volume);
        return result;
    }

    private static JsonObject explode(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        float power = (float) Math.max(0, Math.min(10, number(p, "power", 3)));
        boolean fire = bool(p, "fire", false);
        boolean breakBlocks = bool(p, "break_blocks", true);
        level.explode(null, number(p, "x", 0), number(p, "y", 0), number(p, "z", 0), power, fire,
                breakBlocks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE);
        return ok();
    }

    private static JsonObject lightning(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        Entity bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return error("cannot create lightning");
        }
        bolt.moveTo(number(p, "x", 0), number(p, "y", 0), number(p, "z", 0));
        level.addFreshEntity(bolt);
        return ok();
    }

    private static JsonObject dropItem(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        var item = resolveItem(string(p, "item", ""));
        if (item == null) {
            return error("unknown item: " + string(p, "item", ""));
        }
        int count = (int) Math.max(1, Math.min(item.getDefaultMaxStackSize(), number(p, "count", 1)));
        var drop = new net.minecraft.world.entity.item.ItemEntity(level, number(p, "x", 0), number(p, "y", 0),
                number(p, "z", 0), new net.minecraft.world.item.ItemStack(item, count));
        level.addFreshEntity(drop);
        return ok();
    }

    private static JsonObject worldInfo(MinecraftServer server, JsonObject p) {
        ServerLevel level = level(server, p);
        if (level == null) {
            return error("unknown dimension");
        }
        BlockPos pos = pos(p);
        JsonObject result = ok();
        result.addProperty("biome", level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("unknown"));
        result.addProperty("highest_y", level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                pos.getX(), pos.getZ()));
        result.addProperty("light", level.getMaxLocalRawBrightness(pos));
        result.addProperty("is_day", level.isDay());
        result.addProperty("raining", level.isRaining());
        result.addProperty("thundering", level.isThundering());
        result.addProperty("time", level.getDayTime());
        return result;
    }

    private static Entity findEntity(MinecraftServer server, String uuid) {
        java.util.UUID id;
        try {
            id = java.util.UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static JsonObject entityGet(MinecraftServer server, JsonObject p) {
        Entity entity = findEntity(server, string(p, "uuid", ""));
        JsonObject result = ok();
        if (entity != null) {
            result.add("entity", net.xmilon.craftpy.lifecycle.EventBridge.wrapEntity(entity));
        }
        return result;
    }

    private static JsonObject entityKill(MinecraftServer server, JsonObject p) {
        Entity entity = findEntity(server, string(p, "uuid", ""));
        if (entity == null) {
            return error("entity not found");
        }
        entity.kill();
        return ok();
    }

    private static JsonObject entityTeleport(MinecraftServer server, JsonObject p) {
        Entity entity = findEntity(server, string(p, "uuid", ""));
        if (entity == null) {
            return error("entity not found");
        }
        entity.teleportTo(number(p, "x", entity.getX()), number(p, "y", entity.getY()), number(p, "z", entity.getZ()));
        return ok();
    }

    private static JsonObject entityName(MinecraftServer server, JsonObject p) {
        Entity entity = findEntity(server, string(p, "uuid", ""));
        if (entity == null) {
            return error("entity not found");
        }
        entity.setCustomName(Component.literal(string(p, "name", "")));
        entity.setCustomNameVisible(true);
        return ok();
    }

    private static JsonObject withPlayer(MinecraftServer server, JsonObject p,
                                         java.util.function.BiFunction<ServerPlayer, JsonObject, JsonObject> action) {
        ServerPlayer player = findPlayer(server, string(p, "player", ""));
        if (player == null) {
            return error("player not found: " + string(p, "player", ""));
        }
        return action.apply(player, p);
    }

    private static JsonObject heal(ServerPlayer player, JsonObject p) {
        player.heal((float) number(p, "amount", player.getMaxHealth()));
        return ok();
    }

    private static JsonObject setHealth(ServerPlayer player, JsonObject p) {
        player.setHealth((float) Math.max(0, Math.min(player.getMaxHealth(), number(p, "health", player.getMaxHealth()))));
        return ok();
    }

    private static JsonObject feed(ServerPlayer player, JsonObject p) {
        player.getFoodData().eat((int) number(p, "food", 20), (float) number(p, "saturation", 1.0));
        return ok();
    }

    private static JsonObject effect(ServerPlayer player, JsonObject p) {
        ResourceLocation id = parseId(string(p, "effect", ""));
        if (id == null) {
            return error("unknown effect: " + string(p, "effect", ""));
        }
        var holder = BuiltInRegistries.MOB_EFFECT.getHolder(id);
        if (holder.isEmpty()) {
            return error("unknown effect: " + string(p, "effect", ""));
        }
        int ticks = (int) Math.max(1, number(p, "seconds", 10) * 20);
        int amplifier = (int) Math.max(0, number(p, "level", 1) - 1);
        boolean particles = bool(p, "particles", true);
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(holder.get(), ticks, amplifier, false, particles));
        return ok();
    }

    private static JsonObject gamemode(ServerPlayer player, JsonObject p) {
        var mode = net.minecraft.world.level.GameType.byName(string(p, "mode", "survival"), null);
        if (mode == null) {
            return error("unknown game mode: " + string(p, "mode", ""));
        }
        player.setGameMode(mode);
        return ok();
    }

    private static JsonObject addXp(ServerPlayer player, JsonObject p) {
        if (p.has("levels")) {
            player.giveExperienceLevels((int) number(p, "levels", 0));
        }
        if (p.has("points")) {
            player.giveExperiencePoints((int) number(p, "points", 0));
        }
        return ok();
    }

    private static JsonObject title(ServerPlayer player, JsonObject p) {
        var connection = player.connection;
        connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(
                (int) number(p, "fade_in", 10), (int) number(p, "stay", 60), (int) number(p, "fade_out", 10)));
        if (p.has("subtitle")) {
            connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                    Component.literal(string(p, "subtitle", ""))));
        }
        connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                Component.literal(string(p, "title", ""))));
        return ok();
    }

    private static JsonObject playerSound(ServerPlayer player, JsonObject p) {
        ResourceLocation id = parseId(string(p, "sound", ""));
        if (id == null || !BuiltInRegistries.SOUND_EVENT.containsKey(id)) {
            return error("unknown sound: " + string(p, "sound", ""));
        }
        player.playNotifySound(BuiltInRegistries.SOUND_EVENT.get(id), net.minecraft.sounds.SoundSource.PLAYERS,
                (float) number(p, "volume", 1), (float) number(p, "pitch", 1));
        return ok();
    }

    private static JsonObject inventory(ServerPlayer player, JsonObject p) {
        JsonArray items = new JsonArray();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                JsonObject entry = net.xmilon.craftpy.lifecycle.EventBridge.wrapItem(stack);
                entry.addProperty("slot", slot);
                items.add(entry);
            }
        }
        JsonObject result = ok();
        result.add("items", items);
        return result;
    }

    private static JsonObject countItem(ServerPlayer player, JsonObject p) {
        var item = resolveItem(string(p, "item", ""));
        int count = 0;
        if (item != null) {
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                var stack = inventory.getItem(slot);
                if (stack.is(item)) {
                    count += stack.getCount();
                }
            }
        }
        JsonObject result = ok();
        result.addProperty("count", count);
        return result;
    }

    /** Removes up to {@code count} items; reports how many were actually taken. */
    private static JsonObject takeItem(ServerPlayer player, JsonObject p) {
        var item = resolveItem(string(p, "item", ""));
        if (item == null) {
            return error("unknown item: " + string(p, "item", ""));
        }
        int wanted = (int) Math.max(0, number(p, "count", 1));
        int taken = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && taken < wanted; slot++) {
            var stack = inventory.getItem(slot);
            if (stack.is(item)) {
                int n = Math.min(wanted - taken, stack.getCount());
                stack.shrink(n);
                taken += n;
            }
        }
        inventory.setChanged();
        JsonObject result = ok();
        result.addProperty("taken", taken);
        return result;
    }

    /** Vanilla/mod block id or a CraftPy {@code project:id} block; null when unknown. */
    private static Block resolveBlock(String raw) {
        var ref = net.xmilon.craftpy.registry.SlotRegistry.snapshotByHolder().get("BLOCK:" + raw);
        if (ref != null) {
            return net.xmilon.craftpy.registry.ModBlocks.SLOT_BLOCKS.get(ref.index()).get();
        }
        ResourceLocation id = parseId(raw);
        return id != null && BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.get(id) : null;
    }

    /** Vanilla/mod item id or a CraftPy {@code project:id} item (or block item); null when unknown. */
    private static net.minecraft.world.item.Item resolveItem(String raw) {
        var mapping = net.xmilon.craftpy.registry.SlotRegistry.snapshotByHolder();
        var ref = mapping.get("ITEM:" + raw);
        if (ref != null) {
            return net.xmilon.craftpy.registry.ModItems.SLOT_ITEMS.get(ref.index()).get();
        }
        ref = mapping.get("BLOCK:" + raw);
        if (ref != null) {
            return net.xmilon.craftpy.registry.ModItems.SLOT_BLOCK_ITEMS.get(ref.index()).get();
        }
        ResourceLocation id = parseId(raw);
        return id != null && BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id) : null;
    }

    private static double number(JsonObject p, String key, double fallback) {
        return p.has(key) && p.get(key).isJsonPrimitive() && p.get(key).getAsJsonPrimitive().isNumber()
                ? p.get(key).getAsDouble() : fallback;
    }

    private static boolean bool(JsonObject p, String key, boolean fallback) {
        return p.has(key) && p.get(key).isJsonPrimitive() && p.get(key).getAsJsonPrimitive().isBoolean()
                ? p.get(key).getAsBoolean() : fallback;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static JsonObject onServer(Function<MinecraftServer, JsonObject> work) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return error("no server is running");
        }
        if (server.isSameThread()) {
            return work.apply(server);
        }
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(work.apply(server));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return error("timed out on server thread: " + e.getMessage());
        }
    }

    private static ServerLevel level(MinecraftServer server, JsonObject p) {
        String dimension = string(p, "dimension", "minecraft:overworld");
        ResourceLocation id = parseId(dimension);
        if (id == null) {
            return server.overworld();
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        return server.getLevel(key);
    }

    private static ServerPlayer findPlayer(MinecraftServer server, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getName().getString().equalsIgnoreCase(name)
                    || player.getUUID().toString().equals(name)) {
                return player;
            }
        }
        return null;
    }

    private static JsonObject playerInfo(ServerPlayer player) {
        JsonObject info = new JsonObject();
        info.addProperty("name", player.getName().getString());
        info.addProperty("uuid", player.getUUID().toString());
        info.addProperty("x", player.getX());
        info.addProperty("y", player.getY());
        info.addProperty("z", player.getZ());
        info.addProperty("dimension", player.level().dimension().location().toString());
        info.addProperty("health", player.getHealth());
        info.addProperty("gamemode", player.gameMode.getGameModeForPlayer().getName());
        info.addProperty("max_health", player.getMaxHealth());
        info.addProperty("food", player.getFoodData().getFoodLevel());
        info.addProperty("level", player.experienceLevel);
        return info;
    }

    private static BlockPos pos(JsonObject p) {
        int x = p.has("x") ? (int) Math.floor(p.get("x").getAsDouble()) : 0;
        int y = p.has("y") ? (int) Math.floor(p.get("y").getAsDouble()) : 0;
        int z = p.has("z") ? (int) Math.floor(p.get("z").getAsDouble()) : 0;
        return new BlockPos(x, y, z);
    }

    private static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static String id(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    private static ResourceLocation parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null && !raw.contains(":")) {
            id = ResourceLocation.tryParse("minecraft:" + raw);
        }
        return id;
    }

    private static String storageKey(JsonObject p) {
        return string(p, "scope", "world") + "/" + string(p, "key", "");
    }

    private static String string(JsonObject p, String key, String fallback) {
        return p.has(key) && p.get(key).isJsonPrimitive() ? p.get(key).getAsString() : fallback;
    }

    private static JsonObject ok() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        return result;
    }

    private static JsonObject ok(boolean ignored) {
        return ok();
    }

    private static JsonObject unsupported(String method) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("reason", "unsupported host call: " + method);
        return result;
    }

    private static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return result;
    }
}
