package net.xmilon.craftpy.project;

import java.nio.file.Path;

/**
 * Server-side representation of a project on disk. Runtime state (whether the
 * project has been loaded into the game) is tracked here.
 */
public class Project {
    private final String id;
    private final Path directory;
    private ProjectMetadata metadata;
    private boolean loaded;
    private boolean requiresRestart;
    private String lastError;

    public Project(String id, Path directory, ProjectMetadata metadata) {
        this.id = id;
        this.directory = directory;
        this.metadata = metadata;
        this.requiresRestart = metadata != null && metadata.requiresRestart;
    }

    public String id() {
        return id;
    }

    public Path directory() {
        return directory;
    }

    public Path codeDirectory() {
        return directory.resolve("code");
    }

    public Path assetsDirectory() {
        return directory.resolve("assets");
    }

    public Path dataDirectory() {
        return directory.resolve("data");
    }

    public Path buildDirectory() {
        return directory.resolve("build");
    }

    public Path projectJson() {
        return directory.resolve("project.json");
    }

    public ProjectMetadata metadata() {
        return metadata;
    }

    public void metadata(ProjectMetadata metadata) {
        this.metadata = metadata;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    public boolean requiresRestart() {
        return requiresRestart;
    }

    public void setRequiresRestart(boolean requiresRestart) {
        this.requiresRestart = requiresRestart;
    }

    public String lastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public ProjectSummary toSummary() {
        ProjectSummary summary = ProjectSummary.of(metadata);
        summary.loaded = loaded;
        summary.error = lastError;
        return summary;
    }
}
