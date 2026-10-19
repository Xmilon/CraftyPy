package net.xmilon.craftpy.keys;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.xmilon.craftpy.lifecycle.EventBridge;
import net.xmilon.craftpy.network.ModuleKeysPayload;
import net.xmilon.craftpy.python.PyRuntime;
import net.xmilon.craftpy.util.Json;

/** Authoritative active actions. A packet supplies no player identity, source or callback. */
public final class ServerKeyActions {
    private record Owner(long revision, List<KeyAction> actions) { }
    private static final Map<String, Owner> OWNERS = new HashMap<>();
    private static final Map<UUID, Long> LAST_PRESS = new HashMap<>();
    private static final java.util.Set<UUID> IN_FLIGHT = new java.util.HashSet<>();
    private static long sequence;
    private ServerKeyActions() { }

    public static synchronized void replace(String owner, JsonArray descriptions) {
        List<KeyAction> actions = KeyAction.parse(descriptions);
        Owner previous = OWNERS.get(owner);
        if (previous != null && previous.actions().equals(actions)) return;
        int total = OWNERS.entrySet().stream().filter(e -> !e.getKey().equals(owner)).mapToInt(e -> e.getValue().actions().size()).sum();
        if (total + actions.size() > 256) throw new IllegalArgumentException("At most 256 active server key actions");
        if (actions.isEmpty()) OWNERS.remove(owner);
        else {
            if (OWNERS.size() >= 128 && previous == null) throw new IllegalArgumentException("Too many key action owners");
            OWNERS.put(owner, new Owner(++sequence, actions));
        }
        broadcast();
    }

    public static synchronized void forget(String owner) {
        if (OWNERS.remove(owner) != null) broadcast();
    }

    public static synchronized void clear() { OWNERS.clear(); LAST_PRESS.clear(); IN_FLIGHT.clear(); sequence++; }
    public static synchronized void forgetPlayer(UUID player) { LAST_PRESS.remove(player); IN_FLIGHT.remove(player); }

    static synchronized String manifest() {
        JsonArray rows = new JsonArray();
        OWNERS.forEach((owner, value) -> {
            JsonObject row = new JsonObject(); row.addProperty("owner", owner); row.addProperty("revision", value.revision());
            JsonArray actions = new JsonArray(); value.actions().forEach(a -> actions.add(a.json())); row.add("actions", actions); rows.add(row);
        });
        return Json.COMPACT.toJson(rows);
    }

    private static void broadcast() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        // Build on the server thread so rapid loads cannot deliver older snapshots after newer ones.
        server.execute(() -> PacketDistributor.sendToAllPlayers(new ModuleKeysPayload(manifest())));
    }

    public static void send(ServerPlayer player) { PacketDistributor.sendToPlayer(player, new ModuleKeysPayload(manifest())); }

    public static synchronized boolean accept(UUID player, String owner, String action, long revision, long nowNanos) {
        if (!active(owner, action, revision) || IN_FLIGHT.contains(player)) return false;
        Long previous = LAST_PRESS.get(player);
        if (previous != null && nowNanos - previous < 100_000_000L) return false;
        LAST_PRESS.put(player, nowNanos);
        return true;
    }

    public static synchronized boolean active(String owner, String action, long revision) {
        Owner current = OWNERS.get(owner);
        return current != null && current.revision() == revision && current.actions().stream().anyMatch(a -> a.id().equals(action));
    }

    public static void press(ServerPlayer player, ModuleKeysPayload.Press packet) {
        if (!accept(player.getUUID(), packet.owner(), packet.action(), packet.revision(), System.nanoTime())) return;
        synchronized (ServerKeyActions.class) { IN_FLIGHT.add(player.getUUID()); }
        JsonObject context = new JsonObject();
        context.addProperty("owner", packet.owner()); context.addProperty("action", packet.action());
        context.addProperty("revision", packet.revision());
        context.add("player", EventBridge.wrapPlayer(player));
        PyRuntime.get().triggerEvent("key", context).whenComplete((result, error) -> {
            synchronized (ServerKeyActions.class) { IN_FLIGHT.remove(player.getUUID()); }
        });
    }
}
