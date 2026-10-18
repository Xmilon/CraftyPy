package net.xmilon.craftpy.security;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoApprovalTest {
    @AfterEach
    void reset() {
        PermissionStore.adminApproves = false;
        PermissionStore.forget();
    }

    @Test
    void dedicatedServerApprovesInstalledModules() {
        ModulePermissions asked = new ModulePermissions(List.of("api.example.com"), true);
        asked.commands = true;

        PermissionStore.forget();
        PermissionStore.adminApproves = false;
        PermissionStore.declare("mod_a", asked, "aa");
        assertFalse(PermissionStore.commandsAllowed("mod_a"), "singleplayer: the player approves");

        PermissionStore.forget();
        PermissionStore.adminApproves = true;
        PermissionStore.declare("mod_a", asked, "aa");
        assertTrue(PermissionStore.fullyApproved("mod_a"));
        assertTrue(PermissionStore.commandsAllowed("mod_a"));
        assertTrue(PermissionStore.filesAllowed("mod_a"));
        assertFalse(PermissionStore.javaAllowed("mod_a"), "only what is declared");
    }
}
