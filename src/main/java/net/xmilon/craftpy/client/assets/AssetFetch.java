package net.xmilon.craftpy.client.assets;

import java.nio.charset.StandardCharsets;

import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.assets.AssetKind;

/**
 * Reads an asset by id from whatever the game has loaded (vanilla, mods, the packs CraftPy generates), for the asset
 * browser. Client thread only. The naming rules are in {@link AssetDrop}.
 */
public final class AssetFetch {
    private AssetFetch() {
    }

    /** The file behind the asset (PNG, OGG or model JSON), or null when it has none or cannot be found. */
    public static byte[] bytes(AssetKind kind, String id) {
        try {
            return switch (kind) {
                case TEXTURE -> AssetPreview.readResource(
                        ResourceLocation.parse(withNamespace(id)).withPrefix("textures/").withSuffix(".png"));
                case SOUND -> AssetPreview.readResource(AssetPreview.soundFile(id));
                case MODEL -> {
                    String json = AssetPreview.modelJson(id);
                    yield json.isEmpty() ? null : json.getBytes(StandardCharsets.UTF_8);
                }
                case ITEM, BLOCK -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The JSON text to draw for an asset: a model by its id, an item or block by its model file ({@code item/<name>},
     * then {@code block/<name>}). Empty when there is none (many blocks only have a block state).
     */
    public static String modelJson(AssetKind kind, String id) {
        String namespace = AssetDrop.namespace(id);
        String path = AssetDrop.path(id);
        return switch (kind) {
            case MODEL -> AssetPreview.modelJson(id);
            case ITEM -> first(namespace + ":item/" + path, namespace + ":block/" + path);
            case BLOCK -> {
                String json = first(namespace + ":block/" + path, namespace + ":item/" + path);
                yield json.isEmpty() ? blockState(namespace, path) : json;
            }
            default -> "";
        };
    }

    /** Many blocks only have a block state file; the preview follows it to a model. */
    private static String blockState(String namespace, String path) {
        try {
            byte[] data = AssetPreview.readResource(ResourceLocation.fromNamespaceAndPath(namespace, "blockstates/" + path + ".json"));
            return data == null ? "" : new String(data, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String first(String... ids) {
        for (String id : ids) {
            String json = AssetPreview.modelJson(id);
            if (!json.isEmpty()) {
                return json;
            }
        }
        return "";
    }

    private static String withNamespace(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }
}
