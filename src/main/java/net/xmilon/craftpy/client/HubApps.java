package net.xmilon.craftpy.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.project.ModuleApp;
import net.xmilon.craftpy.project.ModuleInfo;
import net.xmilon.craftpy.project.ModuleSide;
import net.xmilon.craftpy.util.Json;

/**
 * What the Hub lists: the apps of this computer's client modules and the apps the server pushed. Client thread only,
 * except that the plain {@link App} values are immutable and may be handed to the UI thread.
 */
public final class HubApps {
    private HubApps() {
    }

    /** One tile. {@code reason} is empty when the app can be opened, otherwise why it cannot. */
    public record App(String owner, String ownerName, boolean server, ModuleApp def, String moduleIcon,
                      String reason, boolean permissionPending) {
        public boolean available() {
            return reason.isEmpty();
        }

        /** Stable identity across refreshes (pins, recents, selection). */
        public String key() {
            return (server ? "server:" : "client:") + owner + "/" + def.id;
        }

        public String icon() {
            return def.icon.isEmpty() ? moduleIcon : def.icon;
        }
    }

    /** What the server pushed: one row per running owner. */
    private record ServerOwner(String owner, String name, String icon, boolean approved, List<ModuleApp> apps) { }

    private static volatile List<ServerOwner> serverOwners = List.of();

    /** The server's list ({@code ProjectAction.APPS}); a broken packet changes nothing. */
    public static void receiveServer(String json) {
        try {
            List<ServerOwner> parsed = new ArrayList<>();
            int count = 0;
            for (JsonElement raw : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject row = raw.getAsJsonObject();
                List<ModuleApp> apps = new ArrayList<>();
                for (JsonElement app : row.getAsJsonArray("apps")) {
                    apps.add(Json.COMPACT.fromJson(app, ModuleApp.class));
                }
                // The client never trusts the server's text for sizes: the same limits as a local manifest.
                apps = ModuleApp.clean(apps).apps();
                count += apps.size();
                if (count > 1024 || parsed.size() >= 128) {
                    throw new IllegalArgumentException("Too many apps");
                }
                parsed.add(new ServerOwner(limit(row.get("owner").getAsString(), 64), limit(row.get("name").getAsString(), 64),
                        ModuleApp.icon(row.has("icon") ? row.get("icon").getAsString() : ""),
                        !row.has("approved") || row.get("approved").getAsBoolean(), apps));
            }
            serverOwners = List.copyOf(parsed);
        } catch (RuntimeException error) {
            CraftPyLog.LOGGER.warn("Ignoring a broken Hub app list from the server: {}", error.toString());
        }
    }

    /** Leaving the world. */
    public static void clearServer() {
        serverOwners = List.of();
    }

    /** All apps, sorted by category order then {@code order} then name. {@code modules} is the store's module list. */
    public static List<App> snapshot(List<ModuleInfo> modules) {
        List<App> out = new ArrayList<>();
        Set<String> pushed = new java.util.HashSet<>();
        for (ServerOwner owner : serverOwners) {
            pushed.add(owner.owner());
            for (ModuleApp app : owner.apps()) {
                out.add(new App(owner.owner(), owner.name(), true, app, owner.icon(), "", !owner.approved()));
            }
        }
        for (ModuleInfo module : modules) {
            if (!module.primary || !module.enabled || module.apps.isEmpty()) {
                continue;
            }
            boolean client = ModuleSide.of(module.side) == ModuleSide.CLIENT;
            if (!client && pushed.contains(module.id)) {
                continue; // the server said it runs
            }
            String reason = reasonNotRunning(module, client);
            boolean pending = !module.permissions.isEmpty() && !module.permissionsApproved;
            for (ModuleApp app : module.apps) {
                out.add(new App(module.id, module.name.isBlank() ? module.id : module.name, !client, app, module.icon,
                        reason, pending));
            }
        }
        out.sort(Comparator.comparingInt((App a) -> category(a.def().category)).thenComparingInt(a -> a.def().order)
                .thenComparing(a -> a.def().name.toLowerCase(Locale.ROOT)));
        return List.copyOf(out);
    }

    private static String reasonNotRunning(ModuleInfo module, boolean client) {
        if (module.error != null && !module.error.isBlank()) {
            return module.error;
        }
        if (module.loaded) {
            return "";
        }
        return client ? "Not loaded yet: press RELOAD in Modules" : "Not running on this server yet";
    }

    private static int category(String name) {
        int index = ModuleApp.CATEGORIES.indexOf(name);
        return index < 0 ? ModuleApp.CATEGORIES.size() : index;
    }

    private static String limit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** Opens the app's window: a client module's Python opens it here, a server app asks the server. */
    public static void open(App app) {
        if (!app.available()) {
            return;
        }
        returnWindow = app.def().window;
        returnAt = System.currentTimeMillis();
        if (app.server()) {
            JsonObject request = new JsonObject();
            request.addProperty("owner", app.owner());
            request.addProperty("app", app.def().id);
            ClientProjectStore.getInstance().request(ProjectAction.APP_OPEN, "", request.toString());
        } else {
            ClientPython.get().openApp(app.owner(), app.def().window);
        }
    }

    private static volatile String returnWindow = "";
    private static volatile long returnAt;

    /** True once for the window an app just asked to open: that window gets a Back button to the Hub. */
    public static boolean takeReturn(String windowId) {
        String expected = returnWindow;
        // a window that arrives long after the click was opened some other way
        if (!expected.isEmpty() && expected.equals(windowId) && System.currentTimeMillis() - returnAt < 8000) {
            returnWindow = "";
            return true;
        }
        return false;
    }
}
