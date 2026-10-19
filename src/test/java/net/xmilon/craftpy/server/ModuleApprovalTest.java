package net.xmilon.craftpy.server;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import net.xmilon.craftpy.project.*;
import net.xmilon.craftpy.network.*;
import net.xmilon.craftpy.security.PermissionStore;

class ModuleApprovalTest {
    @TempDir Path root;
    @Test void changedBytesRejectAStaleApprovalEvenWithTheSameSizeAndTimestamp() throws Exception {
        String previous=System.getProperty("craftpy.home");
        try {
            System.setProperty("craftpy.home",root.toString()); PermissionStore.forget(); ModuleLoader.useGlobalState(); ProjectManager.init();
            Path source=root.resolve("source"); Files.createDirectories(source.resolve("code"));
            Files.writeString(source.resolve("project.json"),"{\"id\":\"approve_fixture\",\"name\":\"Fixture\",\"version\":\"1.0.0\",\"permissions\":{\"files\":true}}");
            Files.writeString(source.resolve("code/main.py"),"from craftpy import *\n");
            Path file=PyForgePaths.modules().resolve("approve_fixture.cpm"); ModuleBuilder.build(source,file);
            String hash=ModuleLoader.sha256(file); assertTrue(approve(hash)); assertTrue(PermissionStore.filesAllowed("module:approve_fixture"));
            var time=Files.getLastModifiedTime(file); byte[] changed=Files.readAllBytes(file); changed[10]^=1;
            Files.write(file,changed); Files.setLastModifiedTime(file,time);
            assertNotEquals(hash,ModuleLoader.sha256(file)); assertFalse(approve(hash));
            assertTrue(approve(ModuleLoader.sha256(file)));
        } finally {
            ProjectManager.shutdown(); PermissionStore.forget(); ModuleLoader.useGlobalState();
            if(previous==null) System.clearProperty("craftpy.home"); else System.setProperty("craftpy.home",previous);
        }
    }
    private boolean approve(String hash) throws Exception {
        var json=new com.google.gson.JsonObject(); json.addProperty("id","approve_fixture"); json.addProperty("approve",true); json.addProperty("sha256",hash);
        return ProjectRequestHandler.handle(ProjectRequestHandler.Caller.LOCAL,new RequestPayload(ProjectAction.MODULE_APPROVE,"",json.toString())).get().success();
    }
}
