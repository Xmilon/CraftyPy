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
    void rememberedApprovalAndRevocationSurviveReloadButNotFileChanges(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        String previous=System.getProperty("craftpy.home");
        try {
            System.setProperty("craftpy.home",root.toString()); PermissionStore.forget(); PermissionStore.adminApproves=false;
            var asked=new ModulePermissions(); asked.files=true;
            PermissionStore.declare("module:remembered",asked,"a".repeat(64)); PermissionStore.approve("module:remembered",asked);
            PermissionStore.forget(); PermissionStore.declare("module:remembered",asked,"a".repeat(64)); assertTrue(PermissionStore.filesAllowed("module:remembered"));
            PermissionStore.declare("module:remembered",asked,"b".repeat(64)); assertFalse(PermissionStore.filesAllowed("module:remembered"));
            PermissionStore.approve("module:remembered",asked); PermissionStore.revoke("module:remembered");
            PermissionStore.forget(); PermissionStore.adminApproves=true; PermissionStore.declare("module:remembered",asked,"b".repeat(64));
            assertFalse(PermissionStore.filesAllowed("module:remembered"),"Dedicated defaults must not undo an explicit saved revocation");
        } finally { if(previous==null) System.clearProperty("craftpy.home"); else System.setProperty("craftpy.home",previous); }
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
