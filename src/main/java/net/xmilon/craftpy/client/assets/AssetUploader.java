package net.xmilon.craftpy.client.assets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import net.minecraft.client.Minecraft;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.client.ClientProjectStore;
import net.xmilon.craftpy.util.CraftPyExecutors;
import net.xmilon.craftpy.assets.AssetKind;

/**
 * Client-side helper for importing asset files (via a native file dialog or by
 * dragging files onto the window). Files are read off the game thread and sent
 * to the server as base64 upload requests.
 */
public final class AssetUploader {
    private AssetUploader() {
    }

    /** Opens a native file chooser and uploads any selected files. */
    public static void openDialog(String projectId, AssetKind kind) {
        if (projectId == null) {
            ClientProjectStore.getInstance().append("Select a project before importing assets");
            return;
        }
        String result;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(5);
            filters.put(stack.UTF8("*.png"));
            filters.put(stack.UTF8("*.jpg"));
            filters.put(stack.UTF8("*.ogg"));
            filters.put(stack.UTF8("*.wav"));
            filters.put(stack.UTF8("*.json"));
            filters.flip();
            result = TinyFileDialogs.tinyfd_openFileDialog("Import asset into " + projectId, "", filters, "Assets",
                    true);
        }
        if (result == null) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        for (String line : result.split("\\|")) {
            if (!line.isBlank()) {
                paths.add(Path.of(line));
            }
        }
        uploadAll(projectId, paths, kind);
    }

    /** Uploads dropped files (from the GLFW drop callback) to the given project. */
    public static void uploadAll(String projectId, List<Path> paths, AssetKind kind) {
        if (projectId == null || paths.isEmpty()) {
            return;
        }
        ClientProjectStore store = ClientProjectStore.getInstance();
        for (Path path : paths) {
            CraftPyExecutors.runAsync(() -> {
                try {
                    if (!Files.isRegularFile(path)) {
                        return;
                    }
                    byte[] bytes = Files.readAllBytes(path);
                    String target = targetPath(path, kind);
                    net.xmilon.craftpy.client.IdeHost.get().execute(() -> store.uploadAsset(projectId, target, bytes));
                } catch (IOException e) {
                    CraftPyLog.LOGGER.warn("Failed to read dropped file {}", path, e);
                    net.xmilon.craftpy.client.IdeHost.get().execute(() -> store.append("Failed to read " + path.getFileName()));
                }
            }, CraftPyExecutors.forProject(projectId));
        }
    }

    private static String targetPath(Path file, AssetKind kind) {
        String name = sanitize(file.getFileName().toString());
        String ext = extension(name);
        String folder;
        if (kind == null) {
            folder = switch (ext) {
                case "ogg", "wav" -> "assets/sounds/";
                case "json" -> "assets/models/";
                default -> "assets/textures/";
            };
        } else {
            switch (kind) {
                case SOUND -> folder = "assets/sounds/";
                case MODEL -> folder = "assets/models/";
                case ITEM, BLOCK -> folder = "assets/textures/";
                default -> folder = switch (ext) {
                    case "ogg", "wav" -> "assets/sounds/";
                    case "json" -> "assets/models/";
                    default -> "assets/textures/";
                };
            }
        }
        return folder + name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String sanitize(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return cleaned.isBlank() ? "asset" : cleaned;
    }

    /** GLFW drop callback payload -> list of existing paths. */
    public static List<Path> fromDropArgs(int count, long names) {
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = GLFWDropCallback.getName(names, i);
            if (name != null && !name.isBlank()) {
                paths.add(Path.of(name));
            }
        }
        return paths;
    }
}
