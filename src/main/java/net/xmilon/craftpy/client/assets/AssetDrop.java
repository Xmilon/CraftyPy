package net.xmilon.craftpy.client.assets;

import java.util.Collection;
import java.util.Locale;

import net.xmilon.craftpy.assets.AssetKind;

/**
 * What the asset browser does with an asset id when it is opened, copied or dropped into the editor. No Minecraft types,
 * so the naming rules are unit-tested.
 */
public final class AssetDrop {
    private AssetDrop() {
    }

    /** The folder inside a resource namespace that holds files of this kind, or null for registry-only kinds. */
    public static String folder(AssetKind kind) {
        return switch (kind) {
            case TEXTURE -> "textures";
            case MODEL -> "models";
            case SOUND -> "sounds";
            case ITEM, BLOCK -> null;
        };
    }

    public static String extension(AssetKind kind) {
        return switch (kind) {
            case TEXTURE -> "png";
            case SOUND -> "ogg";
            default -> "json";
        };
    }

    /** True when the asset is a file that can be copied into a project. */
    public static boolean isFile(AssetKind kind) {
        return folder(kind) != null;
    }

    /**
     * Where the project keeps its modified copy of {@code id} (a resource id such as {@code minecraft:block/stone}). It is
     * the module resource folder {@code assets/<project>/<kind folder>/}: the copy gets a name of its own and vanilla is
     * left alone. Overwriting the original is a different, explicit act: save to {@code assets/<namespace>/...} by typing
     * that path in the editor's name box. An id in the project's own namespace goes to the simple folder
     * ({@code assets/textures/...}) when the project already keeps the file there.
     */
    public static String projectPath(AssetKind kind, String id, String projectId, Collection<String> projectFiles) {
        String folder = folder(kind);
        if (folder == null) {
            return null;
        }
        String namespace = namespace(id);
        String relative = kind == AssetKind.SOUND ? path(id).replace('.', '/') : path(id); // block.bell.use -> block/bell/use
        String file = relative + "." + extension(kind);
        if (namespace.equals(projectId)) {
            String simple = "assets/" + folder + "/" + file;
            if (projectFiles.contains(simple)) {
                return simple;
            }
        } else if (!namespace.equals("minecraft")) {
            file = namespace + "/" + file; // two mods can both have block/gear
        }
        return clean("assets/" + projectId + "/" + folder + "/" + file);
    }

    /** {@code folder} (with or without a trailing slash) plus the asset's own file name. */
    public static String intoFolder(AssetKind kind, String id, String folder) {
        String name = kind == AssetKind.SOUND ? path(id).replace('.', '_') : path(id);
        name = name.substring(name.lastIndexOf('/') + 1);
        String base = folder.isEmpty() || folder.endsWith("/") ? folder : folder + "/";
        return clean(base + name + "." + extension(kind));
    }

    /** The code a drop on the editor inserts: the id as a string, or a sound call. */
    public static String snippet(AssetKind kind, String id) {
        String quoted = "\"" + id + "\"";
        return kind == AssetKind.SOUND ? "event.player.play_sound(" + quoted + ")" : quoted;
    }

    /** The namespace of an id, {@code minecraft} when it has none. */
    public static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    public static String path(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private static String clean(String path) {
        return path.toLowerCase(Locale.ROOT);
    }
}
