package net.xmilon.craftpy.project;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contents of a project's project.json. Field names intentionally mirror the
 * on-disk format so users can edit the file by hand.
 */
public class ProjectMetadata {
    public String id = "";
    public String name = "";
    public String version = "1.0.0";
    public String author = "";
    public String description = "";
    public String mcVersion = "1.21.1";
    /** The Python API version the project was written for; new projects get the current one. */
    public String apiVersion = net.xmilon.craftpy.python.PyApi.version();
    public boolean requiresRestart = false;
    /** {@code both} (default) or {@code client}: a client project runs on the player's computer, not in the world. */
    public String side = "both";
    public Map<String, Integer> slotUsage = new LinkedHashMap<>();

    public ProjectMetadata() {
    }

    public ProjectMetadata(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
