package net.xmilon.craftpy.project;

/**
 * Lightweight, serialization-friendly view of a project used by the explorer.
 * Shared between server and client (no game or client types).
 */
public class ProjectSummary {
    public String id = "";
    public String name = "";
    public String author = "";
    public String version = "1.0.0";
    public String description = "";
    public boolean requiresRestart = false;
    public int fileCount = 0;
    public long sizeBytes = 0L;
    public boolean loaded = false;
    public String error;

    public ProjectSummary() {
    }

    public static ProjectSummary of(ProjectMetadata meta) {
        ProjectSummary summary = new ProjectSummary();
        summary.id = meta.id;
        summary.name = meta.name;
        summary.author = meta.author;
        summary.version = meta.version;
        summary.description = meta.description;
        summary.requiresRestart = meta.requiresRestart;
        return summary;
    }
}
