package net.xmilon.craftpy.command;

import net.xmilon.craftpy.CraftPyLog;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import net.xmilon.craftpy.Config;

/**
 * NeoForge PermissionAPI nodes for the {@code /craftpy} command tree
 * ({@code craftpy.command.*}). The default resolver mirrors the operator-level
 * check used by the Brigadier {@code requires(...)} clauses, so permission mods
 * can override any single player. Node lookup is shared by the command handlers;
 * see {@link CraftPyCommand#allowed}.
 */
public final class CraftPyPermissions {

    private CraftPyPermissions() {
    }

    public static final PermissionNode<Boolean> IDE = node("ide", 2);
    public static final PermissionNode<Boolean> PROJECT = node("project", 2);
    public static final PermissionNode<Boolean> MODULE = node("module", 2);
    public static final PermissionNode<Boolean> GIVE = node("give", 2);
    public static final PermissionNode<Boolean> PY = node("py", 3);
    public static final PermissionNode<Boolean> DEBUG = node("debug", 3);

    private static PermissionNode<Boolean> node(String path, int fallbackLevel) {
        // The required level is resolved lazily so Config is read only when a
        // permission is actually queried (and so the node can be built in tests).
        return new PermissionNode<>(CraftPyLog.MODID, "command." + path, PermissionTypes.BOOLEAN,
                (player, uuid, context) -> {
                    int required = Math.max(fallbackLevel, Config.get(Config.PERMISSION_LEVEL));
                    return player == null ? required <= 2 : player.hasPermissions(required);
                })
                .setInformation(Component.literal("CraftPy /craftpy " + path),
                        Component.literal("Allows the /craftpy " + path + " commands (operator level "
                                + fallbackLevel + "+ or configured level, overridable by permission mods)."));
    }

    /** Every registered node, exposed for tests and for the /craftpy help tree. */
    public static List<PermissionNode<Boolean>> all() {
        return List.of(IDE, PROJECT, MODULE, GIVE, PY, DEBUG);
    }

    @SubscribeEvent
    public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(all().toArray(new PermissionNode<?>[0]));
    }
}