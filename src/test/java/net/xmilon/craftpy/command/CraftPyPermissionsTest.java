package net.xmilon.craftpy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * The CraftPy PermissionAPI nodes must be namespace-scoped under
 * {@code craftpy.command.*}, boolean-typed and unique (unique names are also
 * required by {@link net.neoforged.neoforge.server.permission.events.PermissionGatherEvent.Nodes#addNodes}).
 * Minecraft-free by design, like the other unit tests.
 */
class CraftPyPermissionsTest {

    @Test
    void nodesAreUnixPathScopedBooleanAndUnique() {
        Set<String> names = new HashSet<>();
        for (PermissionNode<?> node : CraftPyPermissions.all()) {
            String name = node.getNodeName();
            assertTrue(name.startsWith("craftpy.command."), name);
            assertEquals(PermissionTypes.BOOLEAN, node.getType(), name);
            assertTrue(names.add(name), "duplicate node: " + name);
        }
        assertEquals(CraftPyPermissions.all().size(), names.size());
    }

    @Test
    void everyCommandGroupHasANode() {
        Set<String> registered = new HashSet<>();
        for (PermissionNode<?> node : CraftPyPermissions.all()) {
            registered.add(node.getNodeName().substring("craftpy.command.".length()));
        }
        for (String group : new String[] { "ide", "project", "module", "give", "py", "window", "debug" }) {
            assertTrue(registered.contains(group), "missing node for /craftpy " + group);
        }
    }
}