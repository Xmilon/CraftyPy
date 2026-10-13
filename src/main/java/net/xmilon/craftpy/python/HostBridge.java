package net.xmilon.craftpy.python;

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
import net.xmilon.craftpy.CraftPy;

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
                    CraftPy.LOGGER.info("[module] {}", params.has("message") ? params.get("message").getAsString() : "");
                    yield ok();
                }
                case "capabilities" -> ok();
                case "ui.open", "ui.close" -> unsupported("ui (server-described windows are not wired yet)");
                default -> unsupported(method);
            };
        } catch (Exception e) {
            CraftPy.LOGGER.warn("Host call '{}' failed: {}", method, e.toString());
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
        ResourceLocation id = parseId(string(p, "block", "minecraft:air"));
        if (id == null) {
            return error("bad block id");
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
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
        return ok(true);
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
        ResourceLocation id = parseId(string(p, "item", ""));
        if (id == null) {
            return error("bad item id");
        }
        int count = p.has("count") ? p.get("count").getAsInt() : 1;
        var item = BuiltInRegistries.ITEM.get(id);
        var stack = new net.minecraft.world.item.ItemStack(item, Math.max(1, Math.min(64, count)));
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
