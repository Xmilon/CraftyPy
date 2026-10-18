package net.xmilon.craftpy.network;

import com.google.gson.JsonArray;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.server.ProjectRequestHandler;
import net.xmilon.craftpy.util.Json;

public final class CraftPyNetwork {
    public static final String PROTOCOL_VERSION = "4"; // 2: compressed text fields (PayloadText); 3: shared media; 4: server modules on join

    private CraftPyNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(RequestPayload.TYPE, RequestPayload.STREAM_CODEC, CraftPyNetwork::handleRequest);
        registrar.playToClient(ResponsePayload.TYPE, ResponsePayload.STREAM_CODEC, CraftPyNetwork::handleResponse);
        registrar.playBidirectional(MediaPayload.TYPE, MediaPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                context.enqueueWork(() -> net.xmilon.craftpy.server.MediaRelay.receive(player, payload));
            } else {
                context.enqueueWork(() -> net.xmilon.craftpy.client.media.MediaShare.receive(payload));
            }
        });
        // configuration (while joining) and play: the modules of the server and its resource pack
        registrar.commonBidirectional(ServerModulesPayload.TYPE, ServerModulesPayload.STREAM_CODEC, (payload, context) -> {
            if (context.flow().isServerbound()) {
                ServerModules.receive(payload, context);
            } else {
                context.enqueueWork(() -> net.xmilon.craftpy.client.ServerPack.receive(payload, context));
            }
        });
    }

    /** Tells the client which projects are currently loaded so it can reload resources. */
    public static void broadcastActive(MinecraftServer server) {
        if (server == null) {
            return;
        }
        JsonArray ids = new JsonArray();
        for (String id : SlotRegistry.activeProjectIds()) {
            ids.add(id);
        }
        ResponsePayload payload = new ResponsePayload(ProjectAction.SET_ACTIVE, true, "", Json.COMPACT.toJson(ids));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
        ServerModules.modulesChanged(server);
    }

    /**
     * A short action-bar line for every player allowed to use CraftPy ("ruby_rush loaded: 3 blocks, 4 items"), so a
     * reload is visible without opening the IDE. Any thread.
     */
    public static void notifyEditors(MinecraftServer server, String message, boolean error) {
        if (server == null || message == null || message.isBlank()) {
            return;
        }
        server.execute(() -> {
            var text = net.minecraft.network.chat.Component.literal("CraftPy: " + message)
                    .withStyle(error ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.GREEN);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (ProjectRequestHandler.Caller.of(player).allowed()) {
                    player.displayClientMessage(text, true);
                }
            }
        });
    }

    /** Pushes a console line (e.g. project print()/ctx.log output) to every player. */
    public static void broadcastConsole(MinecraftServer server, String message) {
        if (server == null || message == null || message.isBlank()) {
            return;
        }
        ResponsePayload payload = new ResponsePayload(ProjectAction.CONSOLE, true, message, "");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    public static void sendActive(ServerPlayer player) {
        JsonArray ids = new JsonArray();
        for (String id : SlotRegistry.activeProjectIds()) {
            ids.add(id);
        }
        PacketDistributor.sendToPlayer(player,
                new ResponsePayload(ProjectAction.SET_ACTIVE, true, "", Json.COMPACT.toJson(ids)));
    }

    /** Pushes a fresh project list to every player (async gather, then server thread). */
    public static void broadcastProjectList(MinecraftServer server) {
        if (server == null) {
            return;
        }
        net.xmilon.craftpy.project.ProjectManager manager = net.xmilon.craftpy.project.ProjectManager.get();
        if (manager == null) {
            return;
        }
        manager.listProjects().thenAccept(list -> server.execute(() -> {
            ResponsePayload payload = new ResponsePayload(ProjectAction.LIST, true, "", Json.COMPACT.toJson(list));
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }));
    }

    /** Sends an action to one player from any thread (CommandEvent handlers, worker callbacks). */
    public static void sendTo(ServerPlayer player, ProjectAction action, boolean success, String message, String json) {
        if (player != null) {
            PacketDistributor.sendToPlayer(player, new ResponsePayload(action, success, message, json));
        }
    }

    private static void handleRequest(RequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ProjectRequestHandler.handle(player, payload)
                    .thenAccept(response -> {
                        // null = intermediate upload chunk already handled; no reply.
                        if (response != null) {
                            player.server.execute(() -> context.reply(response));
                        }
                    })
                    .exceptionally(error -> {
                        CraftPyLog.LOGGER.error("Request {} failed", payload.action(), error);
                        player.server.execute(() -> context.reply(new ResponsePayload(payload.action(), false,
                                "Internal error: " + error.getMessage(), "")));
                        return null;
                    });
        });
    }

    private static void handleResponse(ResponsePayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // Must run on the client thread: SET_ACTIVE triggers a resource reload.
            context.enqueueWork(() -> net.xmilon.craftpy.client.ClientProjectStore.getInstance().onResponse(payload));
        }
    }
}
