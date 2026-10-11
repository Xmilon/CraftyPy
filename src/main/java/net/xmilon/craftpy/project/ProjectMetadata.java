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
    public String apiVersion = "1";
    public boolean requiresRestart = false;
    public Map<String, Integer> slotUsage = new LinkedHashMap<>();

    public ProjectMetadata() {
    }

    public ProjectMetadata(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
