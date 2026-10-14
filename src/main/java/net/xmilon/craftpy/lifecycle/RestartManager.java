package net.xmilon.craftpy.lifecycle;

import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.network.CraftPyNetwork;

/**
 * Simple countdown-based restart helper.
 * <p>
 * Honest behavior: broadcasts a countdown to all players/console, then stops
 * the server. This does not automatically relaunch the game - the host must
 * start it again. This is sufficient for applying registry-level changes that
 * cannot be hot-reloaded.
 */
public final class RestartManager {
    private static final AtomicInteger ticksRemaining = new AtomicInteger(-1);
    private static MinecraftServer server;

    private RestartManager() {
    }

    public static void scheduleRestart(MinecraftServer s, int seconds) {
        if (s == null) {
            return;
        }
        server = s;
        int ticks = Math.max(0, seconds * 20);
        ticksRemaining.set(ticks);
        CraftPy.LOGGER.info("Scheduling game restart in {} seconds (honest: will stop server; manual relaunch needed).", seconds);
        broadcast("§c[CraftPy] §fRestarting in §e" + seconds + "§f seconds...");
    }

    public static boolean isScheduled() {
        return ticksRemaining.get() > 0;
    }

    public static void cancel() {
        if (ticksRemaining.get() > 0) {
            ticksRemaining.set(-1);
            broadcast("§c[CraftPy] §fRestart cancelled.");
        }
    }

    private static void broadcast(String msg) {
        if (server != null) {
            CraftPyNetwork.broadcastConsole(server, msg.replaceAll("§.", ""));
            for (var player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(Component.literal(msg));
            }
        } else {
            CraftPy.LOGGER.info(msg);
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (ticksRemaining.get() <= 0) {
            return;
        }
        int t = ticksRemaining.decrementAndGet();
        if (t == 0) {
            broadcast("§c[CraftPy] §fRestarting now... (server stopping; relaunch needed)");
            try {
                if (server != null) {
                    server.halt(false);
                } else {
                    event.getServer().halt(false);
                }
            } catch (Throwable e) {
                CraftPy.LOGGER.error("Failed to halt server for restart", e);
            }
            ticksRemaining.set(-1);
        } else if (t % 20 == 0) {
            int secs = t / 20;
            broadcast("§c[CraftPy] §fRestart in §e" + secs + "§f seconds...");
        }
    }
}