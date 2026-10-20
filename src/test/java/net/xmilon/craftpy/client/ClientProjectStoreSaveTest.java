package net.xmilon.craftpy.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.xmilon.craftpy.network.ProjectAction;
import net.xmilon.craftpy.network.ResponsePayload;

/** A save reply clears the unsaved mark of the file it wrote, and only when nothing was typed while it travelled. */
class ClientProjectStoreSaveTest {

    @Test
    void theReplyClearsTheSavedFileNotTheActiveOne() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        store.select("save_test");
        store.markDirty("code/a.py", "a = 1");
        store.markDirty("code/b.py", "b = 1");
        store.saveFile("code/a.py");
        store.onResponse(new ResponsePayload(ProjectAction.WRITE_FILE, true, "", "code/a.py"));
        assertFalse(store.isDirty("code/a.py"));
        assertTrue(store.isDirty("code/b.py"));
    }

    @Test
    void editsTypedDuringTheSaveStayUnsaved() {
        ClientProjectStore store = ClientProjectStore.getInstance();
        store.select("save_test_2");
        store.markDirty("code/a.py", "a = 1");
        store.saveFile("code/a.py");
        store.markDirty("code/a.py", "a = 2");
        store.onResponse(new ResponsePayload(ProjectAction.WRITE_FILE, true, "", "code/a.py"));
        assertTrue(store.isDirty("code/a.py"));
    }
}
