package net.xmilon.craftpy.client.assets;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.client.ClientProjectStore;

/**
 * Best-effort metadata for an asset, used by the inspector. All lookups go
 * through the active resource manager so mod and dynamic project packs are
 * included.
 */
public final class AssetInfo {
    private AssetInfo() {
    }

    public record Result(String id, AssetKind kind, String namespace, String sourcePack, String dimensions,
            String format, String size, String detail) {
    }

    public static Result describe(AssetKind kind, String id) {
        String namespace = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
        ResourceLocation resource = resourceLocation(kind, id);
        if (resource != null) {
            ResourceManager rm = GameResources.manager();
            if (rm != null) {
                Optional<Resource> found = rm.getResource(resource);
                if (found.isPresent()) {
                    Resource res = found.get();
                    String pack = res.sourcePackId();
                    try (InputStream in = res.open()) {
                        byte[] bytes = in.readAllBytes();
                        String size = bytes.length + " bytes";
                        String format = kind == AssetKind.TEXTURE ? "PNG" : "JSON";
                        String dimensions = "-";
                        if (kind == AssetKind.TEXTURE && bytes.length >= 24
                                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P') {
                            int w = readInt(bytes, 16);
                            int h = readInt(bytes, 20);
                            dimensions = w + " x " + h + (isPowerOfTwo(w) && isPowerOfTwo(h) ? "" : "  (not power of two)");
                        }
                        return new Result(id, kind, namespace, pack, dimensions, format, size, resource.toString());
                    } catch (Exception e) {
                        return new Result(id, kind, namespace, pack, "-", "?", "-", "read error: " + e.getMessage());
                    }
                }
            }
        }
        // Registry-backed assets (items, blocks, sound events) have no file here.
        String detail = switch (kind) {
            case ITEM, BLOCK -> "registered object (no single file)";
            case SOUND -> soundDetail(id);
            default -> "not found in the active resource manager";
        };
        return new Result(id, kind, namespace, "registry", "-", "-", "-", detail);
    }

    private static String soundDetail(String id) {
        try {
            SoundManager sm = Minecraft.getInstance().getSoundManager();
            ResourceLocation rl = id.contains(":") ? ResourceLocation.parse(id) : ResourceLocation.parse(id);
            WeighedSoundEvents weighed = sm.getSoundEvent(rl);
            if (weighed != null) {
                return "sound event with variants (see Sounds tab)";
            }
        } catch (RuntimeException ignored) {
        }
        return "sound event";
    }

    /** Which currently-open project files mention this id (best effort). */
    public static List<String> usedBy(String id) {
        List<String> hits = new ArrayList<>();
        ClientProjectStore store = ClientProjectStore.getInstance();
        Map<String, String> open = store.openFileContents();
        for (Map.Entry<String, String> entry : open.entrySet()) {
            if (entry.getValue() != null && entry.getValue().contains(id)) {
                hits.add(entry.getKey());
            }
        }
        return hits;
    }

    private static ResourceLocation resourceLocation(AssetKind kind, String id) {
        try {
            String ns;
            String path;
            if (id.contains(":")) {
                ns = id.substring(0, id.indexOf(':'));
                path = id.substring(id.indexOf(':') + 1);
            } else {
                ns = "minecraft";
                path = id;
            }
            String full = switch (kind) {
                case TEXTURE -> "textures/" + path + ".png";
                case MODEL -> "models/" + path + ".json";
                default -> null;
            };
            if (full == null) {
                return null;
            }
            return ResourceLocation.fromNamespaceAndPath(ns, full);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    public static String prettify(AssetKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
