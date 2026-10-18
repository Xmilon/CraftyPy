package net.xmilon.craftpy.network;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.network.ConnectionProtocol;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.project.ModuleInfo;
import net.xmilon.craftpy.project.ModuleLoader;
import net.xmilon.craftpy.security.PermissionLabels;
import net.xmilon.craftpy.server.ServerPackZip;
import net.xmilon.craftpy.util.CraftPyExecutors;
import net.xmilon.craftpy.util.Json;

/**
 * Server side of joining from another computer. Before the player is in the world (a configuration task) the server
 * tells it which modules ask for permissions, with their READMEs; the player continues or leaves, and then receives the
 * server's resource pack when it does not have that version yet. Singleplayer and the LAN host share the server's
 * folders and skip all of this.
 */
public final class ServerModules {
    static final ConfigurationTask.Type TASK = new ConfigurationTask.Type(
            ResourceLocation.fromNamespaceAndPath(CraftPyLog.MODID, "server_modules"));

    private ServerModules() {
    }

    /** One task per joining player on another computer. */
    @SubscribeEvent
    public static void registerTasks(RegisterConfigurationTasksEvent event) {
        if (event.getListener().getConnection().isMemoryConnection()) {
            return;
        }
        event.register(new ICustomConfigurationTask() {
            @Override
            public void run(Consumer<CustomPacketPayload> sender) {
                sender.accept(new ServerModulesPayload("info", Json.COMPACT.toJson(info())));
            }

            @Override
            public Type type() {
                return TASK;
            }
        });
    }

    /** The running modules that ask for permissions, and the pack's hash and size (none: no "pack"). */
    static JsonObject info() {
        JsonObject out = new JsonObject();
        JsonArray modules = new JsonArray();
        for (ModuleInfo module : ModuleLoader.list()) {
            if (!module.loaded || !module.primary || module.permissions.isEmpty()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", module.id);
            entry.addProperty("name", module.name);
            entry.addProperty("version", module.version);
            entry.addProperty("sha256", String.valueOf(ModuleLoader.sha256(Path.of(module.path))));
            entry.addProperty("warning", PermissionLabels.warning(module.permissions));
            JsonArray can = new JsonArray();
            PermissionLabels.chips(module.permissions).forEach(can::add);
            entry.add("can", can);
            String readme = ModuleLoader.readmeFor(module.path);
            if (readme.length() > ServerModulesPayload.README_CHARS) {
                readme = readme.substring(0, ServerModulesPayload.README_CHARS) + "\n\n*(cut: the README is longer)*";
            }
            entry.addProperty("readme", readme);
            modules.add(entry);
        }
        out.add("modules", modules);
        ServerPackZip.Snapshot pack = ServerPackZip.current();
        if (pack != null) {
            JsonObject packInfo = new JsonObject();
            packInfo.addProperty("sha256", pack.sha256());
            packInfo.addProperty("size", pack.zip().length);
            out.add("pack", packInfo);
        }
        return out;
    }

    /** What the player sent (network thread). */
    static void receive(ServerModulesPayload payload, IPayloadContext context) {
        boolean joining = context.protocol() == ConnectionProtocol.CONFIGURATION;
        switch (payload.op()) {
            case "reply" -> {
                String choice = choiceOf(payload.json());
                switch (choice) {
                    case "leave" -> context.disconnect(Component.literal(
                            "You left: this server runs modules you did not want to continue with."));
                    case "need" -> {
                        if (!sendPack(context) && joining) {
                            context.finishCurrentTask(TASK);
                        }
                    }
                    default -> {
                        if (joining) {
                            context.finishCurrentTask(TASK);
                        }
                    }
                }
            }
            case "installed" -> {
                if (joining) {
                    context.finishCurrentTask(TASK);
                }
            }
            default -> {
            }
        }
    }

    private static String choiceOf(String json) {
        try {
            JsonObject object = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            return object.has("choice") ? object.get("choice").getAsString() : "";
        } catch (RuntimeException broken) {
            return "";
        }
    }

    /** Sends the pack in pieces; false when there is none. */
    private static boolean sendPack(IPayloadContext context) {
        ServerPackZip.Snapshot pack = ServerPackZip.current();
        if (pack == null) {
            return false;
        }
        byte[] zip = pack.zip();
        int total = Math.max(1, (zip.length + ServerModulesPayload.CHUNK - 1) / ServerModulesPayload.CHUNK);
        for (int i = 0; i < total; i++) {
            int from = i * ServerModulesPayload.CHUNK;
            byte[] piece = Arrays.copyOfRange(zip, from, Math.min(zip.length, from + ServerModulesPayload.CHUNK));
            JsonObject meta = new JsonObject();
            meta.addProperty("index", i);
            meta.addProperty("total", total);
            meta.addProperty("sha256", pack.sha256());
            context.reply(new ServerModulesPayload("chunk", Json.COMPACT.toJson(meta), piece));
        }
        return true;
    }

    /**
     * Projects or modules changed: zip the pack again, then tell the players on other computers (they fetch the new pack
     * and see modules that ask for something new). Any thread.
     */
    public static void modulesChanged(MinecraftServer server) {
        if (server == null) {
            return;
        }
        CraftPyExecutors.runAsync(() -> {
            ServerPackZip.rebuild();
            String json = Json.COMPACT.toJson(info());
            server.execute(() -> {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (!player.connection.getConnection().isMemoryConnection()) {
                        PacketDistributor.sendToPlayer(player, new ServerModulesPayload("info", json));
                    }
                }
            });
        }, CraftPyExecutors.io());
    }
}
