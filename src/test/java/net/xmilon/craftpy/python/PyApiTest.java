package net.xmilon.craftpy.python;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyApiTest {

    @Test
    void theVersionComesFromWorkerPy() {
        assertTrue(PyApi.version().matches("\\d+\\.\\d+"), PyApi.version());
        assertEquals("2.1", PyApi.parse("x = 1\nAPI_VERSION = \"2.1\"\n"));
        assertEquals("0.0", PyApi.parse("API_VERSION = 2\n"));
    }

    @Test
    void newProjectsAreStampedWithIt() {
        assertEquals(PyApi.version(), new net.xmilon.craftpy.project.ProjectMetadata("a", "A").apiVersion);
    }
}
