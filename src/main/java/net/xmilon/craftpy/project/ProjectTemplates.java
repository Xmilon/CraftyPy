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
    public record Template(String id, String label, String description) {
    }

    public static final String DEFAULT = "starter";

    public static final List<Template> ALL = List.of(
            new Template("starter", "Starter", "A block, an item, a recipe and two events. A good first project."),
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
