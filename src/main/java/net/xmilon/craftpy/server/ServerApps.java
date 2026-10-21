package net.xmilon.craftpy.server;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.network.ResponsePayload;
import net.xmilon.craftpy.project.ModuleApp;
import net.xmilon.craftpy.project.ModuleInfo;
import net.xmilon.craftpy.project.ModuleLoader;
import net.xmilon.craftpy.project.ModuleSide;
import net.xmilon.craftpy.project.ProjectManager;
import net.xmilon.craftpy.util.CraftPyExecutors;
import net.xmilon.craftpy.util.Json;

/**
 * The Hub apps of what runs on this server: loaded modules that are not client-only, and loaded projects of the world.
 * Every player gets the list (like the module keys), but opening one is checked again here against the same list.
 */
public final class ServerApps {
    private ServerApps() {
    }

    /** One owner with its apps: {@code [{owner, name, kind, icon, approved, apps: [...]}]}. Disk reads; not for the server thread. */
    public static JsonArray list() {
        JsonArray out = new JsonArray();
        for (ModuleInfo module : ModuleLoader.list()) {
            if (!module.loaded || !module.primary || module.error != null || module.apps.isEmpty()
                    || ModuleSide.of(module.side) == ModuleSide.CLIENT) {
                continue;
            }
            out.add(owner(module.id, module.name, "module", module.icon, module.permissionsApproved, module.apps));
        }
        ProjectManager manager = ProjectManager.get();
        if (manager != null) {
            for (var project : manager.loadedProjects()) {
                var meta = manager.metadataOf(project.id());
                if (meta == null || project.lastError() != null || meta.apps == null || meta.apps.isEmpty()
                        || ModuleSide.of(meta.side) == ModuleSide.CLIENT) {
                    continue;
                }
                out.add(owner(project.id(), meta.name, "project", ModuleApp.icon(meta.icon), true,
                        ModuleApp.clean(meta.apps).apps()));
            }
        }
        return out;
    }

    private static JsonObject owner(String id, String name, String kind, String icon, boolean approved, List<ModuleApp> apps) {
        JsonObject row = new JsonObject();
        row.addProperty("owner", id);
        row.addProperty("name", name == null || name.isBlank() ? id : name);
        row.addProperty("kind", kind);
        row.addProperty("icon", icon == null ? "" : icon);
        row.addProperty("approved", approved);
        row.add("apps", Json.COMPACT.toJsonTree(new ArrayList<>(apps)));
        return row;
    }

    /** The window of a declared app, or null when this owner has no such app running. */
    public static String windowOf(String owner, String app) {
        for (var row : list()) {
            JsonObject entry = row.getAsJsonObject();
            if (!entry.get("owner").getAsString().equals(owner)) {
                continue;
            }
            for (var raw : entry.getAsJsonArray("apps")) {
                JsonObject declared = raw.getAsJsonObject();
                if (declared.get("id").getAsString().equals(app)) {
                    return declared.get("window").getAsString();
                }
            }
        }
        return null;
    }

    /** Sends the current list to everyone (any thread; gathered off the server thread). */
    public static void broadcast(MinecraftServer server) {
        if (server == null) {
            return;
        }
        CraftPyExecutors.runAsync(() -> {
            ResponsePayload payload = payload();
            server.execute(() -> server.getPlayerList().getPlayers().forEach(p -> PacketDistributor.sendToPlayer(p, payload)));
        }, CraftPyExecutors.io());
    }

    public static void send(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        CraftPyExecutors.runAsync(() -> {
            ResponsePayload payload = payload();
            server.execute(() -> PacketDistributor.sendToPlayer(player, payload));
        }, CraftPyExecutors.io());
    }

    private static ResponsePayload payload() {
        return new ResponsePayload(ProjectAction.APPS, true, "", Json.COMPACT.toJson(list()));
    }
}
