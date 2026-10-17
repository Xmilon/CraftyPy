package net.xmilon.craftpy.client.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.xmilon.craftpy.Config;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.network.MediaPayload;
import net.xmilon.craftpy.project.PyForgePaths;

/**
 * The client half of shared media: sends your file to the server (which passes it to players nearby) and plays what
 * others share, written to {@code pyforge/system/cache/media/} first. Client thread.
 */
public final class MediaShare {
    private static final Map<UUID, Incoming> INCOMING = new HashMap<>();

    private record Incoming(String id, String name, int size, ByteArrayOutputStream bytes) {
    }

    private MediaShare() {
    }

    static void startSharing(String name, Path file, double seconds, float volume) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        byte[] all;
        try {
            all = Files.readAllBytes(file);
        } catch (IOException e) {
            return;
        }
        String id = UUID.randomUUID().toString();
        JsonObject begin = new JsonObject();
        begin.addProperty("id", id);
        begin.addProperty("name", file.getFileName().toString());
        begin.addProperty("size", all.length);
        begin.addProperty("seconds", seconds);
        begin.addProperty("volume", volume);
        send("begin", begin, new byte[0]);
        JsonObject piece = new JsonObject();
        piece.addProperty("id", id);
        for (int at = 0; at < all.length; at += MediaPayload.CHUNK) {
            send("chunk", piece, java.util.Arrays.copyOfRange(all, at, Math.min(all.length, at + MediaPayload.CHUNK)));
        }
        send("end", piece, new byte[0]);
    }

    static void control(String what, double seconds, float volume) {
        JsonObject json = new JsonObject();
        json.addProperty("what", what);
        json.addProperty("seconds", seconds);
        json.addProperty("volume", volume);
        send("control", json, new byte[0]);
    }

    private static void send(String op, JsonObject json, byte[] data) {
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new MediaPayload(op, json.toString(), data));
        }
    }

    /** What the server sends about other players' media. */
    public static void receive(MediaPayload payload) {
        JsonObject json;
        UUID owner;
        try {
            json = JsonParser.parseString(payload.json()).getAsJsonObject();
            owner = UUID.fromString(json.get("owner").getAsString());
        } catch (RuntimeException e) {
            return;
        }
        MediaPlayer player = MediaPlayer.get();
        switch (payload.op()) {
            case "track" -> {
                int size = json.has("size") ? json.get("size").getAsInt() : 0;
                String name = json.has("name") ? json.get("name").getAsString() : "";
                if (size <= 0 || size > 64 * 1024 * 1024 || !MediaDecoder.supported(name)) {
                    return;
                }
                INCOMING.put(owner, new Incoming(json.get("id").getAsString(), name, size, new ByteArrayOutputStream()));
            }
            case "chunk" -> {
                Incoming in = INCOMING.get(owner);
                if (in != null && in.bytes.size() + payload.data().length <= in.size) {
                    in.bytes.writeBytes(payload.data());
                }
            }
            case "ready" -> {
                Incoming in = INCOMING.remove(owner);
                if (in == null || in.bytes.size() != in.size || !Config.get(Config.MEDIA_HEAR)) {
                    return;
                }
                try {
                    Path dir = PyForgePaths.cache().resolve("media");
                    Files.createDirectories(dir);
                    String ext = MediaDecoder.extension(in.name);
                    Path file = dir.resolve(owner + "." + ext);
                    player.stopOther(owner); // the old file may still be open
                    Files.write(file, in.bytes.toByteArray());
                    player.playOther(owner, in.name, file, number(json, "seconds"), (float) number(json, "volume"),
                            json.has("paused") && json.get("paused").getAsBoolean());
                } catch (IOException e) {
                    CraftPyLog.LOGGER.warn("Shared media could not be cached: {}", e.toString());
                }
            }
            case "control" -> {
                Path file = PyForgePaths.cache().resolve("media");
                try (var files = Files.list(file)) {
                    Path cached = files.filter(p -> p.getFileName().toString().startsWith(owner + ".")).findFirst().orElse(null);
                    if (cached != null && Config.get(Config.MEDIA_HEAR)) {
                        player.playOther(owner, cached.getFileName().toString(), cached, number(json, "seconds"),
                                (float) number(json, "volume"), json.has("paused") && json.get("paused").getAsBoolean());
                    }
                } catch (IOException ignored) {
                    // nothing cached for this player
                }
            }
            case "stop" -> {
                INCOMING.remove(owner);
                player.stopOther(owner);
            }
            default -> {
            }
        }
    }

    private static double number(JsonObject json, String key) {
        try {
            return json.has(key) ? Math.max(0, json.get(key).getAsDouble()) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
