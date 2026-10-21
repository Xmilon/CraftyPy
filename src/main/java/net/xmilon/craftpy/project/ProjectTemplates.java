package net.xmilon.craftpy.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Starter scripts offered when a project is created (50). The code lives in
 * {@code /craftpy_templates/<id>.py}; {@code {{id}}} is replaced with the project id. Shared by the
 * server (writes the file) and the client (the gallery), so it holds no game types.
 */
public final class ProjectTemplates {
    /** A {@code bundle} template is a whole project (several files, textures, windows) listed in its files.txt; the others are one script. */
    public record Template(String id, String label, String description, boolean bundle) {
        public Template(String id, String label, String description) {
            this(id, label, description, false);
        }
    }

    public static final String DEFAULT = "starter";

    public static final List<Template> ALL = List.of(
            new Template("starter", "Starter", "A block, an item, a recipe and two events. A good first project."),
            new Template("welcome", "Welcome tour", "A guided tour with every file commented: items, blocks, recipes, events, timers, commands and two windows. Needs no other mod.", true),
            new Template("block", "Block", "A custom ore: hardness, tool requirement, drops and a smelting recipe."),
            new Template("item", "Item", "A magic wand with a use effect and a food item, both craftable."),
            new Template("mob", "Mob", "Spawn a named guard with a command and reward the player when it dies."),
            new Template("command", "Command", "Slash commands with typed arguments."),
            new Template("window", "Window app", "A window with a click counter, described in Python and drawn in game."),
            new Template("hud", "HUD", "A status line in the action bar: health, food and coordinates."),
            new Template("empty", "Empty", "Only the import line."),
            new Template("client", "Client HUD", "Client side only: a HUD line and a key, on the player's own computer."));

    private ProjectTemplates() {
    }

    public static boolean exists(String id) {
        return ALL.stream().anyMatch(t -> t.id().equals(id));
    }

    public static boolean isBundle(String id) {
        return ALL.stream().anyMatch(t -> t.id().equals(id) && t.bundle());
    }

    /**
     * The files of a bundle template by path, with {{id}} filled in the text files. Empty for a one-script template.
     * A bundle may carry a {@code project.json} with only the fields it adds (description, icon, apps).
     */
    public static java.util.Map<String, byte[]> bundle(String templateId, String projectId) {
        java.util.Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        if (!isBundle(templateId)) {
            return files;
        }
        String base = "/craftpy_templates/" + templateId + "/";
        try (InputStream list = ProjectTemplates.class.getResourceAsStream(base + "files.txt")) {
            if (list == null) {
                throw new IllegalStateException("missing template manifest: " + templateId);
            }
            for (String line : new String(list.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String path = line.strip();
                if (path.isEmpty() || path.contains("..") || path.startsWith("/")) {
                    continue;
                }
                try (InputStream in = ProjectTemplates.class.getResourceAsStream(base + path)) {
                    if (in == null) {
                        throw new IllegalStateException("missing template file: " + templateId + "/" + path);
                    }
                    byte[] bytes = in.readAllBytes();
                    boolean text = path.endsWith(".py") || path.endsWith(".json") || path.endsWith(".md") || path.endsWith(".txt");
                    files.put(path, text ? new String(bytes, StandardCharsets.UTF_8).replace("{{id}}", projectId).getBytes(StandardCharsets.UTF_8) : bytes);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read template " + templateId, e);
        }
        return files;
    }

    /**
     * Writes a bundle template as the project {@code projectId} into {@code dir} (project.json included).
     * @return false when {@code templateId} is not a bundle (nothing is written)
     */
    public static boolean materialize(String templateId, String projectId, String name, String side, java.nio.file.Path dir)
            throws IOException {
        java.util.Map<String, byte[]> files = bundle(templateId, projectId);
        if (files.isEmpty()) {
            return false;
        }
        ProjectMetadata metadata = new ProjectMetadata(projectId, name);
        metadata.side = side;
        byte[] extra = files.remove("project.json");
        if (extra != null) {
            ProjectMetadata added = net.xmilon.craftpy.util.Json.COMPACT.fromJson(new String(extra, StandardCharsets.UTF_8), ProjectMetadata.class);
            if (added != null) {
                metadata.description = added.description;
                metadata.icon = added.icon;
                metadata.apps = added.apps == null ? new java.util.ArrayList<>() : added.apps;
            }
        }
        java.nio.file.Files.createDirectories(dir);
        net.xmilon.craftpy.util.Json.write(dir.resolve("project.json"), metadata);
        for (var file : files.entrySet()) {
            java.nio.file.Path target = dir.resolve(file.getKey()).normalize();
            if (!target.startsWith(dir.normalize())) {
                throw new IOException("unsafe template path " + file.getKey());
            }
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.write(target, file.getValue());
        }
        return true;
    }

    /** The script for {@code templateId} (the default when unknown) with the project id filled in. */
    public static String source(String templateId, String projectId) {
        String id = exists(templateId) ? templateId : DEFAULT;
        try (InputStream in = ProjectTemplates.class.getResourceAsStream("/craftpy_templates/" + id + ".py")) {
            if (in == null) {
                throw new IllegalStateException("missing template resource: " + id);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("{{id}}", projectId);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read template " + id, e);
        }
    }
}
