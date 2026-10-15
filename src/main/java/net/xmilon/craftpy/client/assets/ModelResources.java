package net.xmilon.craftpy.client.assets;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.client.PixelBuffer;

/**
 * Loads the models and textures a preview needs from the game's resource manager (vanilla, mods and the packs CraftPy
 * generates for loaded projects). Client thread only.
 */
public final class ModelResources {
    private final Map<String, PixelBuffer> textures = new HashMap<>();
    private final java.util.Set<String> absent = new java.util.HashSet<>();

    /** A model by id ({@code "minecraft:block/cube_all"}, {@code "block/stone"}); null when it does not exist or is not JSON. */
    public static JsonObject model(String id) {
        ResourceLocation location = location(id, "models/", ".json");
        if (location == null) {
            return null;
        }
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isEmpty()) {
                return null;
            }
            try (InputStream in = resource.get().open()) {
                return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** A texture by id; a pink and black checker when it cannot be loaded. A tall animation strip shows its first frame. */
    public PixelBuffer texture(String id) {
        return textures.computeIfAbsent(id, key -> {
            ResourceLocation location = location(key, "textures/", ".png");
            if (location != null) {
                try {
                    var resource = Minecraft.getInstance().getResourceManager().getResource(location);
                    if (resource.isPresent()) {
                        try (InputStream in = resource.get().open()) {
                            PixelBuffer full = TextureCodec.decode(in.readAllBytes());
                            if (full.height() > full.width() && full.height() % full.width() == 0) {
                                return full.resized(full.width(), full.width(), 0, 0);
                            }
                            return full;
                        }
                    }
                } catch (Exception e) {
                    // falls through to the placeholder
                }
            }
            absent.add(key);
            return missing();
        });
    }

    /** True when the texture was really loaded (call after {@link #texture}). */
    public boolean found(String id) {
        return !absent.contains(id);
    }

    private static PixelBuffer missing() {
        return new PixelBuffer(2, 2, new int[]{0xFFFF00FF, 0xFF000000, 0xFF000000, 0xFFFF00FF});
    }

    private static ResourceLocation location(String id, String folder, String extension) {
        try {
            String text = id.contains(":") ? id : "minecraft:" + id;
            ResourceLocation parsed = ResourceLocation.parse(text);
            return ResourceLocation.fromNamespaceAndPath(parsed.getNamespace(), folder + parsed.getPath() + extension);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
