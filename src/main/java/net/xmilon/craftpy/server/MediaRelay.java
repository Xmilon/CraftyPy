package net.xmilon.craftpy.server;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.xmilon.craftpy.Config;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.network.MediaPayload;

/**
 * Media a player lets nearby players hear: the file comes in pieces, is kept in memory while it plays (one per player,
 * at most {@code media.maxShareMb}) and goes to every player within {@code media.shareRange} blocks, also to the ones
 * who walk up later. The server never decodes it; each listener's game does, and may turn shared media off.
 */
public final class MediaRelay {
    private static final int MAX_PER_MINUTE = 6;

    /** What one player shares. Position is {@code seconds} at {@code since} (server time), moving unless paused. */
    private static final class Share {
        final String id;
        final String name;
        final int size;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean complete;
        double seconds;
        long since = System.currentTimeMillis();
        boolean paused;
        float volume;
        final Set<UUID> listeners = new HashSet<>();

        Share(String id, String name, int size, double seconds, float volume) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.seconds = seconds;
            this.volume = volume;
        }

        double now() {
            return paused ? seconds : seconds + (System.currentTimeMillis() - since) / 1000.0;
        }
    }

    private static final Map<UUID, Share> SHARES = new HashMap<>();
    private static final Map<UUID, long[]> STARTS = new HashMap<>();

    private MediaRelay() {
    }

    /** Server thread. */
    public static void receive(ServerPlayer player, MediaPayload payload) {
        JsonObject json;
        try {
            json = JsonParser.parseString(payload.json()).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        UUID owner = player.getUUID();
        switch (payload.op()) {
            case "begin" -> {
                stop(player.server, owner);
                if (!Config.get(Config.MEDIA_SHARE)) {
                    tell(player, "This server does not let players share media nearby");
                    return;
                }
                int size = json.has("size") ? json.get("size").getAsInt() : -1;
                int max = Config.get(Config.MEDIA_MAX_MB) * 1024 * 1024;
                if (size <= 0 || size > max) {
                    tell(player, "Too big to share here (the server allows " + Config.get(Config.MEDIA_MAX_MB) + " MiB)");
                    return;
                }
                if (!allowedStart(owner)) {
                    tell(player, "Shared too many tracks in the last minute; this one plays only for you");
                    return;
                }
                String name = json.has("name") ? json.get("name").getAsString() : "media";
                if (!net.xmilon.craftpy.client.media.MediaDecoder.supported(name)) {
                    return;
                }
                SHARES.put(owner, new Share(json.get("id").getAsString(), name, size, number(json, "seconds"),
                        (float) number(json, "volume")));
            }
            case "chunk" -> {
                Share share = SHARES.get(owner);
                if (share == null || share.complete || !share.id.equals(string(json, "id"))) {
                    return;
                }
                if (share.bytes.size() + payload.data().length > share.size) {
                    SHARES.remove(owner); // more than announced: drop it
                    return;
                }
                share.bytes.writeBytes(payload.data());
            }
            case "end" -> {
                Share share = SHARES.get(owner);
                if (share == null || !share.id.equals(string(json, "id")) || share.bytes.size() != share.size) {
                    SHARES.remove(owner);
                    return;
                }
                share.complete = true;
                share.since = System.currentTimeMillis();
                update(player.server);
            }
            case "control" -> {
                Share share = SHARES.get(owner);
                if (share == null) {
                    return;
                }
                String what = string(json, "what");
                if ("stop".equals(what)) {
                    stop(player.server, owner);
                    return;
                }
                share.seconds = number(json, "seconds");
                share.volume = (float) number(json, "volume");
                share.paused = "pause".equals(what);
                share.since = System.currentTimeMillis();
                if (share.complete) {
                    for (UUID listener : share.listeners) {
                        ServerPlayer target = player.server.getPlayerList().getPlayer(listener);
                        if (target != null) {
                            send(target, "control", control(owner, share));
                        }
                    }
                }
            }
            default -> {
            }
        }
    }

    private static boolean allowedStart(UUID owner) {
        long now = System.currentTimeMillis();
        long[] times = STARTS.computeIfAbsent(owner, k -> new long[MAX_PER_MINUTE]);
        int oldest = 0;
        for (int i = 1; i < times.length; i++) {
            if (times[i] < times[oldest]) {
                oldest = i;
            }
        }
        if (now - times[oldest] < 60_000) {
            return false;
        }
        times[oldest] = now;
        return true;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 40 == 0 && !SHARES.isEmpty()) {
            update(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity().getServer() != null) {
            stop(event.getEntity().getServer(), event.getEntity().getUUID());
        }
        SHARES.values().forEach(share -> share.listeners.remove(event.getEntity().getUUID()));
    }

    /** Sends the track to players who came in range and stops it for the ones who left. */
    private static void update(MinecraftServer server) {
        double range = Config.get(Config.MEDIA_RANGE);
        for (var entry : new HashMap<>(SHARES).entrySet()) {
            Share share = entry.getValue();
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
            if (owner == null) {
                stop(server, entry.getKey());
                continue;
            }
            if (!share.complete) {
                continue;
            }
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other == owner) {
                    continue;
                }
                boolean near = other.level() == owner.level() && other.distanceToSqr(owner) <= range * range;
                if (near && share.listeners.add(other.getUUID())) {
                    sendTrack(other, entry.getKey(), share);
                } else if (!near && share.listeners.remove(other.getUUID())) {
                    send(other, "stop", owner(entry.getKey()));
                }
            }
        }
    }

    private static void sendTrack(ServerPlayer target, UUID owner, Share share) {
        JsonObject head = control(owner, share);
        head.addProperty("id", share.id);
        head.addProperty("name", share.name);
        head.addProperty("size", share.size);
        send(target, "track", head);
        byte[] all = share.bytes.toByteArray();
        for (int at = 0; at < all.length; at += MediaPayload.CHUNK) {
            byte[] piece = java.util.Arrays.copyOfRange(all, at, Math.min(all.length, at + MediaPayload.CHUNK));
            PacketDistributor.sendToPlayer(target, new MediaPayload("chunk", owner(owner).toString(), piece));
        }
        send(target, "ready", control(owner, share));
    }

    private static void stop(MinecraftServer server, UUID owner) {
        Share share = SHARES.remove(owner);
        if (share == null) {
            return;
        }
        for (UUID listener : share.listeners) {
            ServerPlayer target = server.getPlayerList().getPlayer(listener);
            if (target != null) {
                send(target, "stop", owner(owner));
            }
        }
    }

    private static JsonObject owner(UUID owner) {
        JsonObject json = new JsonObject();
        json.addProperty("owner", owner.toString());
        return json;
    }

    private static JsonObject control(UUID owner, Share share) {
        JsonObject json = owner(owner);
        json.addProperty("seconds", share.now());
        json.addProperty("volume", share.volume);
        json.addProperty("paused", share.paused);
        return json;
    }

    private static void send(ServerPlayer target, String op, JsonObject json) {
        PacketDistributor.sendToPlayer(target, new MediaPayload(op, json.toString(), new byte[0]));
    }

    private static void tell(ServerPlayer player, String message) {
        player.displayClientMessage(net.minecraft.network.chat.Component.literal("Media: " + message), true);
        CraftPyLog.LOGGER.debug("media share of {}: {}", player.getName().getString(), message);
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsString() : "";
    }

    private static double number(JsonObject json, String key) {
        try {
            return json.has(key) ? Math.max(0, json.get(key).getAsDouble()) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
